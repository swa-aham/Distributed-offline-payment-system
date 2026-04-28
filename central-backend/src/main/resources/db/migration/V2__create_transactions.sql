CREATE TABLE transactions (
    id              BIGSERIAL PRIMARY KEY,
    packet_hash     VARCHAR(64) NOT NULL UNIQUE,
    sender_vpa      VARCHAR(100) NOT NULL,
    receiver_vpa    VARCHAR(100) NOT NULL,
    amount_paise    BIGINT NOT NULL,
    edge_node_id    VARCHAR(100),
    outcome         VARCHAR(30) NOT NULL,
    settled_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at_edge TIMESTAMP
);

CREATE INDEX idx_transactions_sender  ON transactions(sender_vpa);
CREATE INDEX idx_transactions_settled ON transactions(settled_at);
