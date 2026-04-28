package com.offlineupi.backend.controller;

import com.offlineupi.backend.model.AccountRepository;
import com.offlineupi.backend.model.TransactionRepository;
import com.offlineupi.backend.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository     accountRepository;
    private final TransactionRepository transactionRepository;
    private final WalletService         walletService;

    @GetMapping("/accounts")
    public List<Map<String, Object>> accounts() {
        return accountRepository.findAll().stream()
                .map(a -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("vpa", a.getVpa());
                    map.put("holderName", a.getHolderName());
                    map.put("balanceRupees", a.getBalancePaise() / 100.0);
                    return map;
                })
                .toList();
    }

    @GetMapping("/transactions")
    public Object transactions() {
        return transactionRepository.findTop20ByOrderBySettledAtDesc().stream().map(t -> Map.of(
            "id",            t.getId(),
            "senderVpa",     t.getSenderVpa(),
            "receiverVpa",   t.getReceiverVpa(),
            "amountRupees",  t.getAmountPaise() / 100.0,
            "outcome",       t.getOutcome(),
            "edgeNodeId",    t.getEdgeNodeId() != null ? t.getEdgeNodeId() : "",
            "settledAt",     t.getSettledAt().toString()
        )).toList();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        long settled    = transactionRepository.countByOutcome("SETTLED");
        long duplicates = transactionRepository.countByOutcome("DUPLICATE_DROPPED");
        long invalid    = transactionRepository.countByOutcome("INVALID");
        long insufficient = transactionRepository.countByOutcome("INSUFFICIENT_FUNDS");
        return Map.of(
            "settled",           settled,
            "duplicateDropped",  duplicates,
            "invalid",           invalid,
            "insufficientFunds", insufficient,
            "total",             settled + duplicates + invalid + insufficient
        );
    }

    /** Top up an edge node's offline wallet */
    @PostMapping("/wallet/topup")
    public ResponseEntity<Map<String, Object>> topUp(@RequestBody Map<String, Object> body) {
        String edgeNodeId  = (String) body.get("edgeNodeId");
        String vpa         = (String) body.get("vpa");
        long   amountPaise = ((Number) body.get("amountPaise")).longValue();

        boolean ok = walletService.topUp(edgeNodeId, vpa, amountPaise);
        if (ok) {
            return ResponseEntity.ok(Map.of(
                "status",  "OK",
                "message", "Wallet topped up with " + amountPaise + " paise"
            ));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                "status",  "FAILED",
                "message", "Top-up failed — check balance and limits"
            ));
        }
    }

    /** Query an edge node's current wallet balance */
    @GetMapping("/wallet/{edgeNodeId}/{vpa}")
    public Map<String, Object> walletBalance(
            @PathVariable String edgeNodeId,
            @PathVariable String vpa
    ) {
        long balance = walletService.getWalletBalance(edgeNodeId, vpa);
        return Map.of(
            "edgeNodeId",     edgeNodeId,
            "vpa",            vpa,
            "walletBalancePaise",  balance,
            "walletBalanceRupees", balance >= 0 ? balance / 100.0 : -1
        );
    }
}
