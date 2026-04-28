package com.offlineupi.edge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the edge node's local offline wallet balance.
 *
 * The wallet is pre-funded from the central backend before going offline.
 * Every payment deducts from this local balance. If balance is 0,
 * no more offline payments can be created — preventing double-spend.
 *
 * In production: this balance would be cryptographically signed by the bank
 * so it can't be tampered with on the device.
 */
@Slf4j
@Component
public class WalletManager {

    private final AtomicLong walletBalancePaise = new AtomicLong(0);

    @Value("${edge.node.owner.vpa:alice@upi}")
    private String ownerVpa;

    /** Called after a successful top-up from the backend */
    public void setBalance(long amountPaise) {
        walletBalancePaise.set(amountPaise);
        log.info("Wallet balance set to {} paise (Rs {})", amountPaise, amountPaise / 100.0);
    }

    /**
     * Try to deduct amountPaise from the local wallet.
     * Uses compareAndSet in a loop for thread safety.
     *
     * @return true if deducted successfully, false if insufficient balance
     */
    public boolean deduct(long amountPaise) {
        while (true) {
            long current = walletBalancePaise.get();
            if (current < amountPaise) {
                log.warn("Insufficient wallet balance: have {}p, need {}p", current, amountPaise);
                return false;
            }
            if (walletBalancePaise.compareAndSet(current, current - amountPaise)) {
                log.info("Wallet deducted {}p, remaining: {}p", amountPaise, current - amountPaise);
                return true;
            }
            // Another thread changed it — retry
        }
    }

    /** Refund — called if backend rejects the payment */
    public void refund(long amountPaise) {
        walletBalancePaise.addAndGet(amountPaise);
        log.info("Wallet refunded {}p", amountPaise);
    }

    public long getBalance() {
        return walletBalancePaise.get();
    }

    public String getOwnerVpa() {
        return ownerVpa;
    }
}
