# Finance Harness Backend

## Purpose

Finance Harness Backend owns the Finance product runtime and its `finance_db` logical database. S2 adds the first durable Journal record and the protected `POST /finance/journals` create path on top of the S1 Spring MVC, PostgreSQL, Flyway, health, and test foundation.

## Runtime stack

- Kotlin on Java 21
- Spring Boot 4.1.0 with Gradle Kotlin DSL
- Spring MVC, Spring Data JPA, and Hibernate
- PostgreSQL 18.x with Flyway migrations
- Spring Boot Actuator
- Jackson 3 with strict request-shape handling
- Application-owned UUIDv7 Journal IDs

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

The integration tests require a reachable Docker daemon and start `postgres:18.4-alpine3.24` through Testcontainers. The test container creates the `finance_db` logical database and applies Flyway V1 before Hibernate validation.

## Migration ownership

Flyway is the schema source of truth. `V1__create_journal_schema.sql` owns `journals`, `investment_journals`, `study_journals`, and `study_open_questions`, including relational constraints, discriminator checks, ordered-question keys, and owner chronology indexing. Hibernate is configured with `ddl-auto=validate` and never creates or updates schema. Open Session in View is disabled.

## Journal Create

`POST /finance/journals` accepts the frozen `investment` and `study` request shapes. A successful request returns `201 Created`, a `/finance/journals/{journalId}` `Location` header, and a non-blank `journalId` body. The owner is obtained from `CurrentUserPort`; identity fields in the client payload are rejected.

`occurredAt` is a strict local wall-clock value paired with a registered IANA `timeZone`. DST gaps and overlaps are rejected, while the resolved instant, original local value, and original zone are persisted together. Study `openQuestions` is required, accepts an empty list, preserves order and duplicates, and is limited to 10 items of 500 characters each.

For local development, activate the `local` profile and provide `FINANCE_LOCAL_IDENTITY_USER_ID`. The local adapter is available only when the profile is `local` or `test` and `finance.auth.mode=local`; it is not a production authentication integration or fallback.

## Scope boundaries

S2 does not implement Journal detail/list reads, production Shared Identity integration, Spring Security/JWT/JWKS, idempotency, FE production activation, or AI/NATS/outbox/review processing. Those require later reliability, platform, and review slices.

Finance owns `finance_db` and must not access the Shared Identity or Carelog logical databases. Shared Identity remains the authentication source; its concrete consumer integration is a later platform slice.

AI calls are handled by a separate AI Server. NATS JetStream, ReviewExecution, RAG, embeddings, vector storage, LLM integration, evidence processing, and transactional outbox work belong to later review/runtime slices.
