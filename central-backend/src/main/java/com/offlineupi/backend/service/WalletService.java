package com.offlineupi.backend.service;

import com.offlineupi.backend.model.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Tracks the pre-funded offline wallet balance for each edge node.
 *
 * HOW PRE-FUNDED WALLETS WORK:
 * Before going offline, an edge node "tops up" — the backend locks Rs X
 * from the user's main account and records that the edge node is allowed
 * to spend up to Rs X offline. Each packet the edge node submits debits
 * from this allowance. This prevents double-spending: the sender can't
 * spend more than what was pre-funded, even offline.
 *
 * This is exactly the mechanism behind UPI Lite (offline payments up to Rs 500).
 *
 * Key format: "wallet:<edgeNodeId>:<userVpa>"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final String WALLET_KEY_PREFIX = "wallet:";

    private final StringRedisTemplate redis;
    private final AccountRepository accountRepository;

    @Value("${wallet.max.balance.paise:500000}")
    private long maxBalancePaise;

    /**
     * Top-up: reserve funds from the user's main account into the edge node wallet.
     * This is called when an edge node comes online and requests an offline allowance.
     */
    public boolean topUp(String edgeNodeId, String vpa, long amountPaise) {
        if (amountPaise > maxBalancePaise) {
            log.warn("Top-up rejected: amount {} exceeds max {}", amountPaise, maxBalancePaise);
            return false;
        }

        // Deduct from main account first
        var accountOpt = accountRepository.findByVpa(vpa);
        if (accountOpt.isEmpty()) return false;

        var account = accountOpt.get();
        if (account.getBalancePaise() < amountPaise) {
            log.warn("Top-up rejected: insufficient main balance for vpa={}", vpa);
            return false;
        }

        account.setBalancePaise(account.getBalancePaise() - amountPaise);
        accountRepository.save(account);

        // Credit the edge wallet
        String key = walletKey(edgeNodeId, vpa);
        redis.opsForValue().set(key, String.valueOf(amountPaise));
        log.info("Wallet topped up: edgeNode={} vpa={} amount={}p", edgeNodeId, vpa, amountPaise);
        return true;
    }

    /**
     * Check if the edge node wallet has enough funds for this payment.
     * Called during ingestion BEFORE settlement to validate offline spend limit.
     */
    public boolean hasSufficientWalletBalance(String edgeNodeId, String vpa, long amountPaise) {
        String key = walletKey(edgeNodeId, vpa);
        String val = redis.opsForValue().get(key);
        if (val == null) {
            // No wallet topped up — fall through to main account balance check
            return true;
        }
        long walletBalance = Long.parseLong(val);
        return walletBalance >= amountPaise;
    }

    /**
     * Deduct from the edge wallet after successful settlement.
     */
    public void deductFromWallet(String edgeNodeId, String vpa, long amountPaise) {
        String key = walletKey(edgeNodeId, vpa);
        String val = redis.opsForValue().get(key);
        if (val == null) return;
        long walletBalance = Long.parseLong(val);
        long newBalance = Math.max(0, walletBalance - amountPaise);
        redis.opsForValue().set(key, String.valueOf(newBalance));
    }

    public long getWalletBalance(String edgeNodeId, String vpa) {
        String val = redis.opsForValue().get(walletKey(edgeNodeId, vpa));
        return val == null ? -1L : Long.parseLong(val);
    }

    private String walletKey(String edgeNodeId, String vpa) {
        return WALLET_KEY_PREFIX + edgeNodeId + ":" + vpa;
    }
}
