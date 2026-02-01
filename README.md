# PayCore

A fintech payment platform built with Spring Boot microservices architecture.

## Services

| Service | Port | Status |
|---|---|---|
| `account-service` | 8081 | ✅ In Progress |
| `ledger-service` | 8082 | 🔲 Planned |
| `transaction-service` | 8083 | 🔲 Planned |
| `api-gateway` | 8080 | 🔲 Planned |
| `eureka-server` | 8761 | 🔲 Planned |

## Tech Stack

- Java 21, Spring Boot 3.4.x
- PostgreSQL, Flyway, Spring Data JPA
- Kafka (event-driven Saga), Redis (idempotency)
- JWT RS256, Spring Security, mTLS (internal)
- Docker + Docker Compose
- Springdoc OpenAPI, Prometheus + Grafana

## Quick Start (account-service)

```bash
cd account-service

# 1. Generate RS256 keypair (first time only)
openssl genrsa -out src/main/resources/keys/private.pem 4096
openssl rsa -in src/main/resources/keys/private.pem -pubout -out src/main/resources/keys/public.pem

# 2. Start Postgres
docker compose up account-db -d

# 3. Run service
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Swagger UI: http://localhost:8081/swagger-ui.html

## Architecture Principles

- Strong consistency at Ledger — no floating-point for money (BigDecimal only)
- Idempotency-Key required on all write operations
- Double-entry bookkeeping — immutable ledger entries, reversals only
- Saga pattern (Kafka choreography) for cross-service transactions
- mTLS for internal service-to-service communication
