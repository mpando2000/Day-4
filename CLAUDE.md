# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot API Gateway lab demonstrating microservices architecture with Spring Cloud Gateway (Java 17, Spring Boot 3.3.4). Features JWT authentication, Redis rate limiting, Resilience4j circuit breaker/timeout/fallback patterns, and Spring Actuator observability. All backend services use hardcoded in-memory mock data — no database.

## Build & Run

**All services via Docker (recommended):**
```bash
docker compose up --build
```
First run builds all four images (~2 min). Subsequent runs skip the build unless `--build` is passed.

**Local development (4 terminals):**
```bash
docker compose up -d redis               # Redis only
mvn -pl claim-service spring-boot:run    # port 8081
mvn -pl member-service spring-boot:run   # port 8082
mvn -pl card-service spring-boot:run     # port 8083
mvn -pl api-gateway spring-boot:run      # port 8080
```

**Run a single module's tests:**
```bash
mvn -pl api-gateway test
mvn -pl claim-service test -Dtest=ClaimControllerTest
```

## Testing the API

Get a JWT token, then use it for all requests:
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"student","roles":["ROLE_USER"]}' | jq -r '.token')

curl http://localhost:8080/api/claims/CLM-777 -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/members -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/cards -H "Authorization: Bearer $TOKEN"

# Trigger circuit breaker via slow endpoint (>2s timeout)
curl http://localhost:8080/api/claims/slow -H "Authorization: Bearer $TOKEN"
```

**Actuator endpoints** (no auth required):
```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/gateway/routes
curl http://localhost:8080/actuator/circuitbreakers
```

## Architecture

```
Client → api-gateway (8080) → claim-service (8081)
                             → member-service (8082)
                             → card-service (8083)
         Redis (6379) ← rate limiter state
```

The gateway strips the `/api` prefix before forwarding. Path-based routing:
- `/api/claims/**` → claim-service, with circuit breaker + 2s timeout
- `/api/members/**` → member-service
- `/api/cards/**` → card-service

**Request pipeline:** JWT validation → rate limiting (Redis, 5 req/s burst 10 per user) → routing → optional circuit breaker → backend service.

## Key Files

| File | Purpose |
|------|---------|
| [api-gateway/src/main/resources/application.yml](api-gateway/src/main/resources/application.yml) | All routing rules, rate limit config, circuit breaker settings |
| [api-gateway/src/main/java/com/example/gateway/config/SecurityConfig.java](api-gateway/src/main/java/com/example/gateway/config/SecurityConfig.java) | JWT validation filter, path authorization |
| [api-gateway/src/main/java/com/example/gateway/security/JwtService.java](api-gateway/src/main/java/com/example/gateway/security/JwtService.java) | Token generation/validation (HS256, 3600s expiry) |
| [api-gateway/src/main/java/com/example/gateway/config/GatewayRateLimitConfig.java](api-gateway/src/main/java/com/example/gateway/config/GatewayRateLimitConfig.java) | Redis key resolver (per-user or IP fallback) |
| [api-gateway/src/main/java/com/example/gateway/controller/FallbackController.java](api-gateway/src/main/java/com/example/gateway/controller/FallbackController.java) | Fallback responses when circuit breaker is open |
| [claim-service/src/main/java/com/example/claimservice/controller/ClaimController.java](claim-service/src/main/java/com/example/claimservice/controller/ClaimController.java) | Mock endpoints including `/claims/slow` (simulates latency) |

## Module Structure

All four modules share the same pattern: a single Spring Boot application class + one mock controller returning hardcoded JSON. The `api-gateway` module is the only complex one — the three backend services exist solely as routing targets.

The root `pom.xml` declares the multi-module project. Key dependency versions: Java 17, Spring Boot 3.3.4, Spring Cloud 2023.0.3, JJWT 0.12.6.
