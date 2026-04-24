# Spring Boot API Gateway Lab

A complete teaching lab for:
- Java 17
- Spring Boot 3.x
- Spring Cloud Gateway
- Spring Security JWT (HS256)
- Redis-based rate limiting
- Resilience4j circuit breaker + timeout + fallback
- Actuator observability
- Three mock backend services (`claim-service`, `member-service`, `card-service`)

## Project Structure

```text
spring-gateway-lab/
├── pom.xml
├── docker-compose.yml
├── README.md
├── api-gateway/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/gateway/
│       │   ├── ApiGatewayApplication.java
│       │   ├── config/
│       │   │   ├── GatewayRateLimitConfig.java
│       │   │   └── SecurityConfig.java
│       │   ├── controller/
│       │   │   ├── AuthController.java
│       │   │   └── FallbackController.java
│       │   └── security/
│       │       └── JwtService.java
│       └── resources/application.yml
├── claim-service/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/claimservice/
│       │   ├── ClaimServiceApplication.java
│       │   └── controller/ClaimController.java
│       └── resources/application.yml
├── member-service/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/memberservice/
│       │   ├── MemberServiceApplication.java
│       │   └── controller/MemberController.java
│       └── resources/application.yml
└── card-service/
    ├── pom.xml
    └── src/main/
        ├── java/com/example/cardservice/
        │   ├── CardServiceApplication.java
        │   └── controller/CardController.java
        └── resources/application.yml
```

## Option A) Run Everything with Docker (recommended)

Builds all four service images and starts Redis in one command:

```bash
docker-compose up --build
```

First run takes ~2 minutes while Maven downloads dependencies inside the containers. Subsequent runs are faster unless `--build` is passed.

## Option B) Run Locally (4 terminals)

### 1) Start Redis

```bash
docker-compose up -d redis
```

### 2) Build Everything

```bash
mvn clean package
```

### 3) Run the Services (4 terminals)

Terminal 1:
```bash
mvn -pl claim-service spring-boot:run
```

Terminal 2:
```bash
mvn -pl member-service spring-boot:run
```

Terminal 3:
```bash
mvn -pl card-service spring-boot:run
```

Terminal 4:
```bash
mvn -pl api-gateway spring-boot:run
```

## 4) Get JWT Token

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"student","roles":["ROLE_USER"]}' | jq -r '.token')

echo "$TOKEN"
```

## 5) Test Gateway Routes

```bash
curl -s http://localhost:8080/api/claims/CLM-777 \
  -H "Authorization: Bearer $TOKEN" | jq

curl -s http://localhost:8080/api/members/MBR-101 \
  -H "Authorization: Bearer $TOKEN" | jq

curl -s http://localhost:8080/api/cards/CRD-501 \
  -H "Authorization: Bearer $TOKEN" | jq
```

Additional claim endpoints:

```bash
# Claims filtered by member ID
curl -s http://localhost:8080/api/claims/by-member/42 \
  -H "Authorization: Bearer $TOKEN" | jq

# Paid claims — returns item-level rows (demonstrates join multiplication)
curl -s http://localhost:8080/api/claims/paid \
  -H "Authorization: Bearer $TOKEN" | jq
```

## 6) Verify Auth Protection

No token (should be `401`):
```bash
curl -i http://localhost:8080/api/members/MBR-101
```

## 7) Rate Limiting Test (Redis)

```bash
for i in {1..15}; do
  curl -s -o /dev/null -w "Request $i => HTTP %{http_code}\n" \
    http://localhost:8080/api/cards/CRD-501 \
    -H "Authorization: Bearer $TOKEN"
done
```

You should eventually see `429` responses after burst capacity is exceeded.

## 8) Circuit Breaker + Fallback (claim-service)

1. Stop `claim-service`.
2. Call claim route through gateway:

```bash
curl -i http://localhost:8080/api/claims/CLM-777 \
  -H "Authorization: Bearer $TOKEN"
```

Expected: `503 Service Unavailable` with fallback JSON from `/fallback/claims`.

### Timeout demo (while claim-service is running)

```bash
curl -i "http://localhost:8080/api/claims/slow?delayMs=5000" \
  -H "Authorization: Bearer $TOKEN"
```

`claimServiceCB` time limiter is set to `2s`, so this should trigger fallback.

## 9) Actuator Endpoints

```bash
curl -s http://localhost:8080/actuator/health | jq
curl -s http://localhost:8080/actuator/gateway/routes | jq
curl -s http://localhost:8080/actuator/circuitbreakers | jq
```

## Default Ports

- API Gateway: `8080`
- Claim Service: `8081`
- Member Service: `8082`
- Card Service: `8083`
- Redis: `6379`

## Notes for Teaching

- Gateway strips `/api` before forwarding, so `/api/claims/**` maps to backend `/claims/**`.
- JWT is validated in the gateway using a shared HS256 secret.
- Rate limiting is per authenticated user (or IP fallback) using Redis.
- Claim route has circuit breaker + timeout + fallback; member/card routes only have rate limiting.
