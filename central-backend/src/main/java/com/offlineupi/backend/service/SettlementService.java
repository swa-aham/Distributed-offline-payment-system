package com.offlineupi.backend.service;

import com.offlineupi.backend.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public enum SettleResult { SETTLED, INSUFFICIENT_FUNDS, ACCOUNT_NOT_FOUND }

    /**
     * Atomically debits sender and credits receiver.
     *
     * @Transactional ensures either BOTH writes happen or NEITHER.
     * PESSIMISTIC_WRITE lock (in AccountRepository) prevents another
     * thread from reading the balance between our SELECT and UPDATE.
     */
    @Transactional
    public SettleResult settle(
            String senderVpa,
            String receiverVpa,
            long amountPaise,
            String packetHash,
            String edgeNodeId,
            Instant createdAtEdge
    ) {
        // Lock both accounts in a consistent order (alphabetical) to prevent deadlock
        String firstVpa  = senderVpa.compareTo(receiverVpa) < 0 ? senderVpa : receiverVpa;
        String secondVpa = senderVpa.compareTo(receiverVpa) < 0 ? receiverVpa : senderVpa;

        Account first = accountRepository.findByVpaForUpdate(firstVpa)
            .orElse(null);
        Account second = accountRepository.findByVpaForUpdate(secondVpa)
            .orElse(null);

        if (first == null || second == null) {
            log.error("Account not found: sender={} receiver={}", senderVpa, receiverVpa);
            return SettleResult.ACCOUNT_NOT_FOUND;
        }

        Account sender   = senderVpa.equals(first.getVpa()) ? first : second;
        Account receiver = senderVpa.equals(first.getVpa()) ? second : first;

        if (sender.getBalancePaise() < amountPaise) {
            log.warn("INSUFFICIENT_FUNDS sender={} balance={} required={}",
                senderVpa, sender.getBalancePaise(), amountPaise);
            // Still write to ledger so we have an audit trail
            writeTransaction(packetHash, senderVpa, receiverVpa, amountPaise,
                edgeNodeId, "INSUFFICIENT_FUNDS", createdAtEdge);
            return SettleResult.INSUFFICIENT_FUNDS;
        }

        // Debit sender, credit receiver
        sender.setBalancePaise(sender.getBalancePaise() - amountPaise);
        receiver.setBalancePaise(receiver.getBalancePaise() + amountPaise);
        accountRepository.save(sender);
        accountRepository.save(receiver);

        writeTransaction(packetHash, senderVpa, receiverVpa, amountPaise,
            edgeNodeId, "SETTLED", createdAtEdge);

        log.info("SETTLED sender={} receiver={} amount={}p edgeNode={}",
            senderVpa, receiverVpa, amountPaise, edgeNodeId);
        return SettleResult.SETTLED;
    }

    private void writeTransaction(String packetHash, String senderVpa, String receiverVpa,
            long amountPaise, String edgeNodeId, String outcome, Instant createdAtEdge) {
        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(senderVpa);
        tx.setReceiverVpa(receiverVpa);
        tx.setAmountPaise(amountPaise);
        tx.setEdgeNodeId(edgeNodeId);
        tx.setOutcome(outcome);
        tx.setCreatedAtEdge(createdAtEdge);
        transactionRepository.save(tx);
    }
}
