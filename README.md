# Finance Harness Backend

## Purpose

Finance Harness Backend owns the Finance product runtime and its `finance_db` logical database. This S1 slice establishes the Spring MVC, PostgreSQL, Flyway, health, and test foundation for the later Journal slice.

## Runtime stack

- Kotlin on Java 21
- Spring Boot 4.1.0 with Gradle Kotlin DSL
- Spring MVC, Spring Data JPA, and Hibernate
- PostgreSQL with Flyway migrations
- Spring Boot Actuator

## Requirements

- Java 21
- PostgreSQL for local runtime
- Docker for PostgreSQL Testcontainers integration tests

The repository wrapper supplies Gradle. The service does not require a local PostgreSQL instance when running the integration test suite; Testcontainers supplies an isolated PostgreSQL instance.

## Configuration

Runtime database settings are environment-backed:

| Environment variable | Purpose |
| --- | --- |
| `FINANCE_DB_URL` | JDBC URL for the Finance-owned `finance_db` logical database |
| `FINANCE_DB_USERNAME` | Finance database username |
| `FINANCE_DB_PASSWORD` | Finance database password; never commit it |
| `FINANCE_DB_APPLICATION_NAME` | Optional PostgreSQL connection application name |
| `FINANCE_SERVICE_NAME` | Optional service identity; defaults to `finance-harness-be` |
| `SPRING_PROFILES_ACTIVE` | Optional active profile value used in structured log service metadata |

No database secret, token, cookie, trusted auth header, or financial request body is logged by this foundation.

## Build and test

```bash
./gradlew clean build
./gradlew test
```

The integration tests require a running Docker daemon and use a pinned `postgres:16-alpine` Testcontainers image.

## Migration ownership

Flyway is the schema source of truth. S1 intentionally has no product migration; the first real Journal migration is owned by S2. Hibernate is configured with `ddl-auto=validate` and never creates or updates schema. Open Session in View is disabled.

## Scope boundaries

S1 is backend foundation only. S2 adds Journal persistence, Journal API, UUIDv7 ownership, current-user consumption, and Journal time/DST handling.

Finance owns `finance_db` and must not access the Shared Identity or Carelog logical databases. Shared Identity remains the authentication source; its concrete consumer integration is a later platform slice.

AI calls are handled by a separate AI Server. NATS JetStream, ReviewExecution, RAG, embeddings, vector storage, LLM integration, evidence processing, and transactional outbox work belong to later review/runtime slices.
