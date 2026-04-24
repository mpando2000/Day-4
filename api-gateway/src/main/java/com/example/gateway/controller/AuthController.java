package com.example.gateway.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.gateway.security.JwtService;

import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/auth")
@Validated
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> createToken(@RequestBody(required = false) TokenRequest request) {
        String username = (request != null && request.username() != null && !request.username().isBlank())
                ? request.username()
                : "student";
        List<String> roles = (request != null && request.roles() != null && !request.roles().isEmpty())
                ? request.roles()
                : List.of("ROLE_USER");

        String token = jwtService.generateToken(username, roles);
        TokenResponse response = new TokenResponse(token, "Bearer", jwtService.getExpirationSeconds());
        return ResponseEntity.ok(response);
    }

    public record TokenRequest(@NotBlank String username, List<String> roles) {
    }

    public record TokenResponse(String token, String tokenType, long expiresInSeconds) {
    }
}
