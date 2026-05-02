# Offline UPI Payment System

A production-grade distributed payment system where edge nodes (offline payment
terminals) queue payments locally and sync to a central backend with guaranteed
exactly-once settlement.

## Architecture

```
Edge Node 1 (port 8081)  ─┐
Edge Node 2 (port 8082)  ─┼──► Central Backend (port 8080) ◄── Dashboard
Edge Node 3 (port 8083)  ─┘         │
                                     ├── PostgreSQL (idempotent ledger)
                                     └── Redis (SETNX idempotency gate)
```

## Setup (Step by Step)

### Prerequisites
- Java 17+
- Maven
- Docker Desktop (running)

---

### Step 1: Start PostgreSQL and Redis

```bash
cd offline-upi-system
docker-compose up -d
```

Verify:
```bash
docker ps
# Should show: upi_postgres and upi_redis both running
```

---

### Step 2: Start the Central Backend

```bash
cd central-backend
mvn spring-boot:run
```

Wait for: `Started BackendApplication in X.XXX seconds`

**Important:** Copy the RSA public key printed in the logs:
```
========================================================
RSA key pair GENERATED (not loaded from env).
Public key (copy to edge node config):
MIIBIjANBgkq... (long base64 string)
========================================================
```

Open dashboard: http://localhost:8080
You should see 5 accounts each with Rs 10,000.

---

### Step 3: Start Edge Node 1

Open a NEW terminal:

```bash
cd edge-node
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --edge.node.id=edge-node-1 --edge.node.owner.vpa=alice@upi"
```

---

### Step 4: Start Edge Node 2 (optional, for duplicate demo)

Open ANOTHER terminal:

```bash
cd edge-node
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8082 --edge.node.id=edge-node-2 --edge.node.owner.vpa=bob@upi"
```

---

## Demo Walkthrough

### Demo 1: Basic Offline Payment

```bash
# 1. Set edge node 1's wallet (Rs 1000 = 100000 paise)
curl -X POST http://localhost:8081/api/wallet/set \
  -H "Content-Type: application/json" \
  -d '{"amountPaise": 100000}'

# 2. Take edge node 1 OFFLINE
curl -X POST http://localhost:8081/api/offline \
  -H "Content-Type: application/json" \
  -d '{"offline": true}'

# 3. Create 3 payments while offline
curl -X POST http://localhost:8081/api/pay \
  -H "Content-Type: application/json" \
  -d '{"senderVpa":"alice@upi","receiverVpa":"bob@upi","amountPaise":50000}'

curl -X POST http://localhost:8081/api/pay \
  -H "Content-Type: application/json" \
  -d '{"senderVpa":"alice@upi","receiverVpa":"carol@upi","amountPaise":25000}'

curl -X POST http://localhost:8081/api/pay \
  -H "Content-Type: application/json" \
  -d '{"senderVpa":"alice@upi","receiverVpa":"dave@upi","amountPaise":10000}'

# 4. Check outbox - 3 PENDING packets
curl http://localhost:8081/api/outbox

# 5. Bring back ONLINE
curl -X POST http://localhost:8081/api/offline \
  -H "Content-Type: application/json" \
  -d '{"offline": false}'

# 6. Trigger immediate sync (or wait 10 seconds)
curl -X POST http://localhost:8081/api/sync

# 7. Check dashboard - balances should have changed
open http://localhost:8080
```

### Demo 2: Idempotency (Exactly-Once Settlement)

This proves that even if the same packet reaches the backend multiple times,
it settles exactly once.

```bash
# Get a ciphertext from the outbox
curl http://localhost:8081/api/outbox

# Copy the ciphertext from a DELIVERED packet and POST it 3 times
# All 3 will return — 1st: SETTLED, 2nd+3rd: DUPLICATE_DROPPED
# Balance changes exactly once.

curl -X POST http://localhost:8080/api/ingest \
  -H "Content-Type: application/json" \
  -H "X-Edge-Node-Id: manual-test" \
  -d '{"ciphertext":"<paste ciphertext here>"}'
```

### Demo 3: Tamper Detection

```bash
# Take any ciphertext, change one character, POST it
# The AES-GCM auth tag will fail → INVALID
curl -X POST http://localhost:8080/api/ingest \
  -H "Content-Type: application/json" \
  -H "X-Edge-Node-Id: attacker" \
  -d '{"ciphertext":"AAAA<rest of valid ciphertext>"}'
# Response: {"outcome":"INVALID","reason":"Decryption failed — possible tampering"}
```

### Demo 4: Wallet Overflow Prevention

```bash
# Set wallet to Rs 100
curl -X POST http://localhost:8081/api/wallet/set \
  -H "Content-Type: application/json" \
  -d '{"amountPaise": 10000}'

# Try to pay Rs 150 — should be REJECTED by edge node immediately
curl -X POST http://localhost:8081/api/pay \
  -H "Content-Type: application/json" \
  -d '{"senderVpa":"alice@upi","receiverVpa":"bob@upi","amountPaise":15000}'
# Response: {"success":false,"message":"Insufficient offline wallet balance..."}
```

---

## API Reference

### Central Backend (port 8080)

| Method | Path | Description |
|--------|------|-------------|
| GET  | `/` | Live dashboard |
| GET  | `/api/accounts` | All account balances |
| GET  | `/api/transactions` | Last 20 transactions |
| GET  | `/api/stats` | Settlement stats |
| GET  | `/api/public-key` | Server RSA public key |
| POST | `/api/ingest` | Receive packet from edge node |
| POST | `/api/wallet/topup` | Pre-fund an edge node wallet |
| GET  | `/api/wallet/{edgeNodeId}/{vpa}` | Check wallet balance |
| GET  | `/actuator/prometheus` | Prometheus metrics |
| GET  | `/actuator/health` | Health check |

### Edge Node (port 8081/8082/8083)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/pay` | Create a new payment |
| GET  | `/api/status` | Node status + wallet + queue counts |
| POST | `/api/offline` | Toggle offline mode `{"offline": true/false}` |
| POST | `/api/sync` | Trigger immediate sync |
| POST | `/api/wallet/set` | Set local wallet balance |
| GET  | `/api/outbox` | View outbox queue |
| GET  | `/h2-console` | Browse local H2 DB |

---

## Key Design Decisions (for interviews)

1. **Why Redis SETNX for idempotency?**
   ConcurrentHashMap would break across multiple backend instances.
   Redis SET NX EX is atomic and distributed — exactly one caller wins.

2. **Why AES-GCM specifically?**
   Authenticated encryption. Any bit flip in transit → AEADBadTagException.
   No decryption of tampered data. Same scheme TLS uses.

3. **Why hash the ciphertext (not packetId)?**
   PacketId can be reforged. Ciphertext is authenticated by GCM — two
   legitimate copies of the same payment are byte-identical. Hash is stable.

4. **Why Outbox Pattern on the edge node?**
   Write to local DB first, deliver later. Guarantees at-least-once delivery
   even if the backend is temporarily down. Idempotency at the backend turns
   at-least-once into exactly-once.

5. **Why pessimistic locking on Account + @Version?**
   Two layers: pessimistic lock prevents concurrent reads during settlement.
   @Version optimistic lock is the last-resort catch if locks ever race.

6. **Why exponential backoff?**
   Prevents a fleet of edge nodes from hammering the backend simultaneously
   when it comes back online after downtime (thundering herd problem).
