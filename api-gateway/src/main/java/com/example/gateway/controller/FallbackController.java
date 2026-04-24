package com.example.gateway.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/claims")
    public ResponseEntity<Map<String, Object>> claimFallback() {
        Map<String, Object> body = Map.of(
                "message", "Claim service is temporarily unavailable. Please try again shortly.",
                "service", "claim-service",
                "timestamp", Instant.now().toString());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
