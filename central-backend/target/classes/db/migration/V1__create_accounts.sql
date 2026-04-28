CREATE TABLE accounts (
    id          BIGSERIAL PRIMARY KEY,
    vpa         VARCHAR(100) NOT NULL UNIQUE,
    holder_name VARCHAR(100) NOT NULL,
    balance_paise BIGINT NOT NULL DEFAULT 0,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
