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

For local development, activate the `local` profile and provide `FINANCE_LOCAL_IDENTITY_USER_ID`. The local adapter is available only when an active profile is `local` or `test`, no active profile is `production`, and `finance.auth.mode=local`; it is not a production authentication integration or fallback.

## Scope boundaries

S2 does not implement Journal detail/list reads, production Shared Identity integration, Spring Security/JWT/JWKS, idempotency, FE production activation, or AI/NATS/outbox/review processing. Those require later reliability, platform, and review slices.

Finance owns `finance_db` and must not access the Shared Identity or Carelog logical databases. Shared Identity remains the authentication source; its concrete consumer integration is a later platform slice.

AI calls are handled by a separate AI Server. NATS JetStream, ReviewExecution, RAG, embeddings, vector storage, LLM integration, evidence processing, and transactional outbox work belong to later review/runtime slices.

## Container deployment (EC2 + external RDS)

The production image is built in two stages: Gradle compiles the application with Java 21, then a Java 21 JRE image runs only the executable JAR as an unprivileged user. The image contains no database credentials. Compose supplies `FINANCE_DB_URL`, `FINANCE_DB_USERNAME`, and `FINANCE_DB_PASSWORD` at runtime. The production Compose file does not start or own a database; Finance continues to own the `finance_db` schema through Flyway, with Hibernate set to `validate`.

### Build and run

Install Docker Engine and Docker Compose v2. Create a runtime env file outside the repository (for example, `/secure/finance.env`) through the deployment secret injection process, with mode `0600`. Set the RDS JDBC URL with TLS and certificate/hostname verification, such as `jdbc:postgresql://<rds-host>:5432/finance_db?sslmode=verify-full&sslrootcert=/etc/finance/rds-ca-bundle.pem`, and supply the database username and password. Do not commit this file, add values as Docker build arguments, or paste its contents into logs or support output.

The PostgreSQL JDBC driver's `sslmode=verify-full` validates the certificate chain and the server hostname. The RDS CA bundle must be made available inside the container at the `sslrootcert` path. The checked-in `compose.yaml` forwards `FINANCE_DB_URL` but does not mount or provision the CA bundle; the production deployment contract must supply a read-only CA mount and the matching JDBC URL. The sample URL therefore describes the required runtime configuration, not a CA injection feature already present in Compose.

```bash
docker compose --env-file /secure/finance.env build
docker compose --env-file /secure/finance.env up -d --wait
docker compose ps
curl --noproxy '*' --fail --silent http://127.0.0.1:8080/actuator/health/readiness
```

If a proxy-managed build requires a session CA, pass it as a BuildKit secret rather than copying it into the context or image: `docker build --secret id=proxy_ca,src="$CODEX_PROXY_CERT" -t finance-harness-be:local .`, then start the Compose service without `--build`.

The image's readiness health check includes the database connection and reports no component details. The service listens on port `8080`; limit inbound EC2 security-group access to the intended load balancer or client networks. `FINANCE_HTTP_BIND_ADDRESS` can change the host bind address, and `FINANCE_IMAGE_TAG` selects the built image tag.

### Local container smoke test

This disposable Compose overlay starts a local PostgreSQL container only for smoke verification. Its credentials and fixed user ID are test-only and must never be reused for deployment.

In a proxy-managed build environment, use the BuildKit-secret command above first and omit `--build` in the smoke command below.

```bash
FINANCE_HTTP_BIND_ADDRESS=127.0.0.1 docker compose -f compose.yaml -f compose.smoke.yaml --profile smoke up --build --wait
curl --noproxy '*' --fail --silent http://127.0.0.1:8080/actuator/health/readiness
FINANCE_HTTP_BIND_ADDRESS=127.0.0.1 docker compose -f compose.yaml -f compose.smoke.yaml --profile smoke down --remove-orphans
```

### Runtime behavior and production blocker

Compose caps the service at one CPU and 768 MiB. The JVM uses container memory detection, caps heap at 55% of the container limit, and bounds metaspace, code cache, and direct memory. Spring graceful shutdown allows 30 seconds per phase; Compose forwards termination signals and waits up to 40 seconds.

The local adapter is permitted only when `!production & (local | test)` matches and `finance.auth.mode=local`; any active profile list containing `production` excludes it, including mixed `production,local` and `production,test` lists. The checked-in Compose default profile is `production`, and this repository declares no `prod` alias. Regression tests cover these combinations even when a local user ID is supplied. A real Shared Identity `CurrentUserPort` integration and the production authentication/security boundary are not present in this repository slice. Without that integration, the application cannot wire the journal services under the production profile; do not work around this by enabling the `local` profile. Production activation is blocked until the Shared Identity integration is delivered. An RDS endpoint, credentials, and EC2-to-RDS network rules must also be supplied by the deployment environment; no RDS resource is created here.
