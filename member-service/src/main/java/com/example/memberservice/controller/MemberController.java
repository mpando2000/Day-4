package com.example.memberservice.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/members")
public class MemberController {

    @GetMapping
    public List<Map<String, Object>> listMembers() {
        return List.of(
                Map.of("memberId", "MBR-101", "name", "Alice Amani", "tier", "GOLD"),
                Map.of("memberId", "MBR-102", "name", "Brian Mushi", "tier", "SILVER"));
    }

    @GetMapping("/{id}")
    public Map<String, Object> getMember(@PathVariable("id") String id) {
        return Map.of(
                "memberId", id,
                "name", "Demo Member",
                "tier", "GOLD",
                "updatedAt", Instant.now().toString());
    }
}
