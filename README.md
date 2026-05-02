# 📴 Offline UPI — Payments Without Internet

> A distributed payment system where payments work even with **zero internet connectivity** — queued locally on edge nodes, synced to a central backend when online, with cryptographic tamper-detection and exactly-once settlement.

---

## 🎯 The Problem

UPI requires internet. In rural India, basement offices, metro tunnels, or during network outages — payments simply fail. This project explores: **what if they didn't have to?**

---

## 💡 The Solution

Edge nodes (think: payment terminals, kiosks, or phones) can **accept payments fully offline**. Each payment is encrypted and stored locally in a queue. The moment internet returns, the queue flushes to the central backend — which settles every payment **exactly once**, no matter how many times the same packet arrives.

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Edge Node 1   │     │   Edge Node 2   │     │   Edge Node 3   │
│                 │     │                 │     │                 │
│  Local Queue    │     │  Local Queue    │     │  Local Queue    │
│  (encrypted)    │     │  (encrypted)    │     │  (encrypted)    │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         │    Sync when online (HTTPS)                   │
         └───────────────────────┼───────────────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────┐
                  │     Central Backend      │
                  │                          │
                  │  Redis  →  Idempotency   │
                  │  PostgreSQL  →  Ledger   │
                  │  Prometheus  →  Metrics  │
                  └──────────────────────────┘
```

---

## ✨ Key Features

- **Truly offline payments** — edge nodes work with zero internet, queue payments in local encrypted storage
- **Exactly-once settlement** — the same payment packet delivered 10 times still settles exactly once
- **Tamper-proof packets** — AES-256-GCM authentication detects any modification in transit
- **Double-spend prevention** — pre-funded offline wallets cap how much can be spent without connectivity
- **Automatic retry with backoff** — edge nodes retry failed syncs with exponential backoff (1s → 2s → 4s → ...)
- **Multi-node safe** — multiple edge nodes sync concurrently with no race conditions
- **Production observability** — Prometheus metrics, structured logs, live dashboard

---

## 🏗️ Architecture

### Two Services

**Central Backend** (`central-backend/`) — Spring Boot, PostgreSQL, Redis
- Receives encrypted payment packets from edge nodes
- Runs the full ingestion pipeline: hash → idempotency check → decrypt → validate → settle
- Exposes a live dashboard and REST API

**Edge Node** (`edge-node/`) — Spring Boot, H2 (local)
- Accepts payment requests locally via REST
- Encrypts each payment with the server's RSA public key
- Stores in a local outbox queue (H2 database)
- Background scheduler flushes the queue to the backend when online
- Can be toggled offline/online to simulate real network conditions

---

## 🔐 How Encryption Works

Each payment goes through **hybrid RSA-OAEP + AES-256-GCM** encryption:

```
Payment JSON
     │
     ▼
Fresh AES-256 key generated per packet
     │
     ├──► AES-GCM encrypts the payment data
     │         (includes 16-byte auth tag — detects any tampering)
     │
     └──► RSA-OAEP encrypts the AES key with server's public key
               (only the server's private key can unwrap it)

Final packet = [RSA-encrypted AES key] + [IV] + [AES ciphertext + auth tag]
```

**Why hybrid?** RSA alone can't encrypt large payloads. AES alone can't safely share keys. Hybrid gives you both: the speed of AES and the key security of RSA. This is exactly how TLS works.

**Why GCM specifically?** GCM is *authenticated* encryption — it produces an auth tag that covers every byte of the ciphertext. If even one bit changes in transit, decryption throws an exception. No silent corruption.

---

## 🛡️ How Exactly-Once Settlement Works

This is the most critical guarantee in any payment system.

```
Packet arrives at backend
        │
        ▼
SHA-256(ciphertext) → packet hash
        │
        ▼
Redis SET NX EX 86400
"Has this hash been seen before?"
        │
   ┌────┴────┐
  YES        NO (first time)
   │          │
   ▼          ▼
DUPLICATE   Decrypt → Validate → Settle
DROPPED     Write to PostgreSQL ledger
```

**`SET NX EX`** (Set if Not eXists, with TTL) is an atomic Redis operation. Even if 10 edge nodes deliver the same packet simultaneously, exactly one thread wins the `SET NX` and proceeds to settle. All others get `DUPLICATE_DROPPED` instantly.

The PostgreSQL `transactions` table also has a **unique index on `packet_hash`** as a belt-and-suspenders second layer — if Redis ever fails, the DB constraint catches it.

---

## 💳 How Offline Wallets Prevent Double-Spending

Without internet, an edge node could theoretically issue the same Rs 500 to two different people if its balance is only Rs 500.

**The solution — pre-funded wallets:**

1. Before going offline, the edge node requests a spending allowance (e.g. Rs 1,000)
2. The backend locks Rs 1,000 from the user's main account into a wallet tied to that edge node
3. Every offline payment deducts from this local wallet balance using an **atomic compare-and-set** loop
4. If the wallet hits zero, no more offline payments — the device refuses locally before any network call
5. When syncing, the backend validates payments against the pre-funded amount

This is the same mechanism behind **UPI Lite** (RBI's offline payment feature, capped at Rs 500).

---

## 📦 The Outbox Pattern

Edge nodes use the **transactional outbox pattern** for reliable delivery:

```
createPayment()
      │
      ├─► Deduct from local wallet  ──┐  (same local DB transaction)
      └─► Write to outbox table    ──┘

Background scheduler (every 10s):
      │
      ├─► Read PENDING packets from outbox
      ├─► POST to backend
      ├─► On success → mark DELIVERED
      └─► On failure → schedule retry with exponential backoff
```

**Why this matters:** If the backend is unreachable when a payment is created, the payment isn't lost — it sits safely in the local database until delivery succeeds. The outbox is the source of truth for delivery state.

---

## 🔁 Retry with Exponential Backoff

When delivery fails, the edge node doesn't hammer the backend repeatedly. It backs off:

| Attempt | Wait before retry |
|---------|-------------------|
| 1st failure | 1 second |
| 2nd failure | 2 seconds |
| 3rd failure | 4 seconds |
| 4th failure | 8 seconds |
| 5th failure | Give up, mark FAILED |

This prevents the **thundering herd problem** — if the backend comes back after downtime, 100 edge nodes backing off at different intervals won't all hit it at the same second.

---

## 🧪 Test Results

All tests passing on local setup with 2 concurrent edge nodes:

| Test | Result |
|------|--------|
| Offline payment queue + sync | ✅ 9/9 settled |
| Multi-node concurrent settlement | ✅ No conflicts, exact balances |
| Idempotency (same packet 3x) | ✅ `DUPLICATE_DROPPED`, balance unchanged |
| Tamper detection | ✅ `INVALID` on modified ciphertext |
| Wallet overflow prevention | ✅ Rejected at device, never hits network |
| Rate limiting (60 req/min) | ✅ `429` after limit exceeded |
| Balance accounting | ✅ Every paisa accounted for |

---

## 🗄️ Database Schema

```sql
-- Accounts with optimistic locking
accounts (id, vpa, holder_name, balance_paise, version, created_at)

-- Settled transactions — unique index on packet_hash (idempotency layer 2)
transactions (id, packet_hash, sender_vpa, receiver_vpa, amount_paise,
              edge_node_id, outcome, settled_at, created_at_edge)

-- Edge node local outbox queue (H2, per node)
outbox_packets (id, sender_vpa, receiver_vpa, amount_paise, ciphertext,
                nonce, status, attempt_count, next_retry_at, backend_outcome)
```

---

## 🛠️ Tech Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| Backend framework | Spring Boot 3.2 | Production-grade, familiar |
| Database | PostgreSQL (Neon) | ACID transactions, optimistic locking |
| Cache / Idempotency | Redis | Atomic SET NX, distributed-safe |
| DB Migrations | Flyway | Version-controlled schema |
| Encryption | Java JCE (RSA + AES) | Standard library, no external crypto deps |
| Metrics | Micrometer + Prometheus | Industry standard observability |
| Rate limiting | Bucket4j | Token bucket, per-node |
| Local queue | H2 (in-memory) | Zero-config, embedded |
| Dashboard | Thymeleaf + vanilla JS | Simple, no frontend build needed |

---

## 🚀 Running Locally

### Prerequisites
- Java 17+
- Maven
- Docker (for Redis)
- A free [Neon](https://neon.tech) PostgreSQL database

### 1. Start Redis
```bash
docker-compose up -d redis
```

### 2. Configure database
Edit `central-backend/src/main/resources/application.properties`:
```properties
spring.datasource.url=jdbc:postgresql://YOUR_NEON_HOST/offlineupi?sslmode=require
spring.datasource.username=YOUR_USERNAME
spring.datasource.password=YOUR_PASSWORD
spring.flyway.url=jdbc:postgresql://YOUR_NEON_HOST/offlineupi?sslmode=require
spring.flyway.user=YOUR_USERNAME
spring.flyway.password=YOUR_PASSWORD
```

### 3. Start the backend
```bash
cd central-backend
mvn spring-boot:run
```
Dashboard → http://localhost:8080

### 4. Start an edge node
```bash
cd edge-node
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --edge.node.id=edge-node-1 --edge.node.owner.vpa=alice@upi"
```

### 5. Run the demo
```powershell
# Fund wallet (Rs 1000)
Invoke-RestMethod -Uri "http://localhost:8081/api/wallet/set" -Method POST -ContentType "application/json" -Body '{"amountPaise": 100000}'

# Go offline
Invoke-RestMethod -Uri "http://localhost:8081/api/offline" -Method POST -ContentType "application/json" -Body '{"offline": true}'

# Make payments
Invoke-RestMethod -Uri "http://localhost:8081/api/pay" -Method POST -ContentType "application/json" -Body '{"senderVpa":"alice@upi","receiverVpa":"bob@upi","amountPaise":50000}'

# Come back online — watch dashboard update
Invoke-RestMethod -Uri "http://localhost:8081/api/offline" -Method POST -ContentType "application/json" -Body '{"offline": false}'
Invoke-RestMethod -Uri "http://localhost:8081/api/sync"
```

---

## 📡 API Reference

### Central Backend (`:8080`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/` | Live dashboard |
| `POST` | `/api/ingest` | Receive encrypted packet from edge node |
| `GET` | `/api/accounts` | All account balances |
| `GET` | `/api/transactions` | Last 20 transactions |
| `GET` | `/api/stats` | Settlement statistics |
| `GET` | `/api/public-key` | Server RSA public key |
| `POST` | `/api/wallet/topup` | Pre-fund an edge node wallet |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

### Edge Node (`:8081`, `:8082`, ...)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/pay` | Create a new payment |
| `GET` | `/api/status` | Node status, wallet balance, queue counts |
| `POST` | `/api/offline` | Toggle offline mode `{"offline": true/false}` |
| `POST` | `/api/sync` | Trigger immediate sync to backend |
| `GET` | `/api/outbox` | View local payment queue |
| `POST` | `/api/wallet/set` | Set local wallet balance |

---

## 🧠 Design Decisions

**Why hash the ciphertext for idempotency (not a UUID)?**
A UUID can be forged or replicated. The ciphertext is authenticated by AES-GCM — two legitimate copies of the same payment are byte-for-byte identical. Their SHA-256 hash is stable and unforgeable.

**Why pessimistic DB lock AND Redis idempotency?**
Defence in depth. Redis is the fast first gate — catches duplicates in microseconds without touching the DB. The pessimistic lock on `Account` prevents concurrent debits from reading a stale balance during settlement. The unique DB index on `packet_hash` is the last resort if Redis ever has an outage.

**Why store balance in paise (integer) not rupees (float)?**
Floating-point arithmetic loses precision. `0.1 + 0.2 = 0.30000000000000004` in IEEE 754. Payment systems always store money as integers in the smallest unit (paise, cents, etc.) and only convert to decimal for display.

**Why outbox pattern instead of direct API calls?**
If the edge node called the backend directly when creating a payment, a network failure at that exact moment would lose the payment permanently. The outbox writes to local DB first — an operation that can't fail silently — and retries delivery separately. This is at-least-once delivery; idempotency at the backend makes it exactly-once.

---

## 👤 Author

Built by **Soham Mandaviya**
