package com.example.cardservice.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cards")
public class CardController {

    @GetMapping
    public List<Map<String, Object>> listCards() {
        return List.of(
                Map.of("cardId", "CRD-501", "type", "VISA", "status", "ACTIVE"),
                Map.of("cardId", "CRD-502", "type", "MASTERCARD", "status", "BLOCKED"));
    }

    @GetMapping("/{id}")
    public Map<String, Object> getCard(@PathVariable("id") String id) {
        return Map.of(
                "cardId", id,
                "type", "VISA",
                "status", "ACTIVE",
                "updatedAt", Instant.now().toString());
    }
}
