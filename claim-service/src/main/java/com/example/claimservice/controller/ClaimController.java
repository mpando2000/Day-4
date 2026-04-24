package com.example.claimservice.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/claims")
public class ClaimController {

    @GetMapping
    public List<Map<String, Object>> listClaims() {
        return List.of(
                Map.of("claimId", "CLM-1001", "status", "APPROVED", "amount", 120.50),
                Map.of("claimId", "CLM-1002", "status", "PENDING", "amount", 75.00));
    }

    @GetMapping("/{id}")
    public Map<String, Object> getClaim(@PathVariable("id") String id) {
        return Map.of(
                "claimId", id,
                "status", "PENDING",
                "amount", 200.00,
                "updatedAt", Instant.now().toString());
    }

    @GetMapping("/slow")
    public Map<String, Object> slowClaim(@RequestParam(name = "delayMs", defaultValue = "5000") long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
        return Map.of(
                "message", "Slow claim response",
                "delayMs", delayMs,
                "timestamp", Instant.now().toString());
    }

    @GetMapping("/by-member/{memberId}")
    public List<Map<String, Object>> getClaimsByMember(@PathVariable("memberId") String memberId) {
        return List.of(
                Map.of("claimId", "CLM-" + memberId + "-001", "memberId", memberId, "status", "APPROVED", "amount", 120.50),
                Map.of("claimId", "CLM-" + memberId + "-002", "memberId", memberId, "status", "PENDING",  "amount", 75.00),
                Map.of("claimId", "CLM-" + memberId + "-003", "memberId", memberId, "status", "PAID",     "amount", 340.00));
    }

    @GetMapping("/paid")
    public List<Map<String, Object>> getPaidClaimsWithItems() {
        // Intentionally returns item-level rows — one claim appears multiple times.
        // Demonstrates join multiplication (Task 10 row-count mismatch scenario).
        return List.of(
                Map.of("claimId", "CLM-1001", "status", "PAID", "itemId", "ITEM-001", "serviceCode", "SVC-10"),
                Map.of("claimId", "CLM-1001", "status", "PAID", "itemId", "ITEM-002", "serviceCode", "SVC-11"),
                Map.of("claimId", "CLM-1001", "status", "PAID", "itemId", "ITEM-003", "serviceCode", "SVC-12"),
                Map.of("claimId", "CLM-1002", "status", "PAID", "itemId", "ITEM-004", "serviceCode", "SVC-20"),
                Map.of("claimId", "CLM-1002", "status", "PAID", "itemId", "ITEM-005", "serviceCode", "SVC-21"));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createClaim(@RequestBody Map<String, Object> claim) {
        Map<String, Object> response = Map.of(
                "claimId", "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "status", "RECEIVED",
                "request", claim,
                "createdAt", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
