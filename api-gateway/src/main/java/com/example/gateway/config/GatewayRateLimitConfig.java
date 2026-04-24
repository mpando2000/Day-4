package com.example.gateway.config;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Mono;

@Configuration
public class GatewayRateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    InetSocketAddress address = exchange.getRequest().getRemoteAddress();
                    return address != null ? address.getAddress().getHostAddress() : "anonymous";
                }));
    }
}
