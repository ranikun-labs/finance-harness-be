#!/usr/bin/env bash
set -euo pipefail

finance_source="${1:?Finance source checkout is required}"
platform_source="${2:?Platform source checkout is required}"
: "${RUNNER_TEMP:?Hosted runner temp directory is required}"
: "${JAVA_HOME_17_X64:?Java 17 is required for Gateway}"
: "${JAVA_HOME_21_X64:?Java 21 is required for Core and Finance}"
# Public Core E2E fixture only; it is masked before commands can echo it.
FINANCE_REFERENCE_E2E_USER=issue24-e2e-user
FINANCE_REFERENCE_E2E_PASSWORD=Password123!
export FINANCE_REFERENCE_E2E_USER FINANCE_REFERENCE_E2E_PASSWORD

expected_finance_sha=5cab16ab7ba04d0bf65b77b1ebca976d6d29e1dc
expected_finance_tree=d598dadb2677b2ac73488b6ab34f6e278aab009c
expected_platform_sha=5a0b0dc38f1d53655cc21613f9b202a317d7c37d
expected_platform_tree=9057914850b9e0026fa8851cbb062c692a51b4b3
fixture_account_id=00000000-0000-4000-8000-000000000024
core_port=18081
core_management_port=19092
gateway_port=18080
gateway_management_port=19091
finance_port=18082
finance_management_port=18083
log_dir="$RUNNER_TEMP/finance-investment-composite-api-e2e"
mkdir -m 700 -p "$log_dir"

printf '::add-mask::%s\n' "$FINANCE_REFERENCE_E2E_PASSWORD"

check_source() {
  local path="$1" expected_sha="$2" expected_tree="$3"
  local actual_sha actual_tree
  actual_sha="$(git -C "$path" rev-parse HEAD)"
  actual_tree="$(git -C "$path" rev-parse 'HEAD^{tree}')"
  [[ "$actual_sha" == "$expected_sha" && "$actual_tree" == "$expected_tree" ]]
}
check_source "$finance_source" "$expected_finance_sha" "$expected_finance_tree"
check_source "$platform_source" "$expected_platform_sha" "$expected_platform_tree"

# The hosted service container uses trust auth and is destroyed with this single job.
export PGHOST=127.0.0.1 PGPORT=5432 PGUSER=postgres PGDATABASE=postgres
for _ in $(seq 1 60); do
  if pg_isready -q; then break; fi
  sleep 1
done
pg_isready -q
identity_db_password="$(openssl rand -hex 32)"
finance_db_password="$(openssl rand -hex 32)"
printf '::add-mask::%s\n' "$identity_db_password"
printf '::add-mask::%s\n' "$finance_db_password"
psql -v ON_ERROR_STOP=1 -v identity_password="$identity_db_password" -v finance_password="$finance_db_password" <<'SQL'
CREATE ROLE identity_e2e LOGIN PASSWORD :'identity_password';
CREATE ROLE finance_e2e LOGIN PASSWORD :'finance_password';
CREATE DATABASE platform_e2e OWNER identity_e2e;
CREATE DATABASE finance_db OWNER finance_e2e;
SQL
psql -v ON_ERROR_STOP=1 -d platform_e2e -c 'CREATE SCHEMA identity AUTHORIZATION identity_e2e;'

# New, per-run reference keys and caller proof; they stay in process environments only.
reference_jwt_key="$(openssl rand -hex 64)"
trusted_context_proof="$(openssl rand -hex 32)"
gateway_legacy_jwt_key="$(openssl rand -hex 32)"
gateway_internal_secret="$(openssl rand -hex 32)"
printf '::add-mask::%s\n' "$reference_jwt_key"
printf '::add-mask::%s\n' "$trusted_context_proof"
printf '::add-mask::%s\n' "$gateway_legacy_jwt_key"
printf '::add-mask::%s\n' "$gateway_internal_secret"

# Build the pinned source trees. Gateway toolchain is Java 17; Core and Finance use Java 21.
"$platform_source/gradlew" --no-daemon --console=plain -p "$platform_source" bootJar
(
  cd "$finance_source"
  ./gradlew --no-daemon --console=plain bootJar
)

find_boot_jar() {
  local dir="$1" result
  result="$(find "$dir" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)"
  [[ -n "$result" && -f "$result" ]]
  printf '%s' "$result"
}
core_jar="$(find_boot_jar "$platform_source/platform-core/app/build/libs")"
gateway_jar="$(find_boot_jar "$platform_source/gateway-app/build/libs")"
finance_jar="$(find_boot_jar "$finance_source/build/libs")"

nohup env \
  SPRING_PROFILES_ACTIVE=finance-reference \
  PLATFORM_ENVIRONMENT=finance-composite-e2e \
  PLATFORM_CORE_PORT="$core_port" \
  PLATFORM_CORE_MANAGEMENT_PORT="$core_management_port" \
  PLATFORM_IDENTITY_AUTH_ENABLED=false \
  PLATFORM_IDENTITY_PERSISTENCE_ENABLED=true \
  PLATFORM_IDENTITY_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/platform_e2e?currentSchema=identity&connectTimeout=3&socketTimeout=10' \
  PLATFORM_IDENTITY_DATASOURCE_USERNAME=identity_e2e \
  PLATFORM_IDENTITY_DATASOURCE_PASSWORD="$identity_db_password" \
  PLATFORM_IDENTITY_FLYWAY_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/platform_e2e?currentSchema=identity&connectTimeout=3&socketTimeout=10' \
  PLATFORM_IDENTITY_FLYWAY_DATASOURCE_USERNAME=identity_e2e \
  PLATFORM_IDENTITY_FLYWAY_DATASOURCE_PASSWORD="$identity_db_password" \
  PLATFORM_IDENTITY_REDIS_HOST=127.0.0.1 \
  PLATFORM_IDENTITY_REDIS_PORT=6379 \
  PLATFORM_IDENTITY_REDIS_KEY_PREFIX=finance-e2e:identity: \
  SERVER_ADDRESS=127.0.0.1 \
  MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_ENABLED=true \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_SELECTED_ACCOUNT_IDS="$fixture_account_id" \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_JWT_SIGNING_KEY="$reference_jwt_key" \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_JWT_ISSUER=urn:ranikunlabs:identity:finance:reference \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_JWT_AUDIENCE=finance-journal-reference \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_JWT_ACCESS_TOKEN_TTL_SECONDS=300 \
  "$JAVA_HOME_21_X64/bin/java" -jar "$core_jar" >"$log_dir/core.log" 2>&1 &
core_pid=$!

wait_ready() {
  local url="$1" label="$2"
  for _ in $(seq 1 150); do
    if curl --fail --silent --output /dev/null "$url"; then return 0; fi
    sleep 2
  done
  printf '%s readiness failed; process logs remain on the ephemeral runner only.\n' "$label" >&2
  return 1
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  for pid in "${gateway_pid:-}" "${finance_pid:-}" "${core_pid:-}"; do
    if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
  done
  wait 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT INT TERM
wait_ready "http://127.0.0.1:$core_management_port/actuator/health/readiness" Core
psql -v ON_ERROR_STOP=1 -d platform_e2e -f "$platform_source/scripts/e2e/seed.sql" >/dev/null

nohup env \
  SPRING_PROFILES_ACTIVE=finance-trusted-context-reference \
  PLATFORM_ENVIRONMENT=finance-composite-e2e \
  PLATFORM_GATEWAY_PORT="$gateway_port" \
  SERVER_ADDRESS=127.0.0.1 \
  MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
  PLATFORM_GATEWAY_MANAGEMENT_PORT="$gateway_management_port" \
  PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1 \
  PLATFORM_GATEWAY_REDIS_PORT=6379 \
  PLATFORM_GATEWAY_REDIS_KEY_PREFIX=finance-e2e:gateway: \
  SPRING_DATA_REDIS_TIMEOUT=2s \
  PLATFORM_GATEWAY_AUTH_URI="http://127.0.0.1:$core_port" \
  PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:9 \
  PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:9 \
  PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET="$gateway_legacy_jwt_key" \
  PLATFORM_GATEWAY_INTERNAL_SECRET="$gateway_internal_secret" \
  PLATFORM_GATEWAY_FINANCE_TRUSTED_CONTEXT_ENABLED=false \
  PLATFORM_GATEWAY_FINANCE_TRUSTED_CONTEXT_SERVICE_SECRET="$trusted_context_proof" \
  PLATFORM_GATEWAY_FINANCE_REFERENCE_ENABLED=true \
  PLATFORM_GATEWAY_FINANCE_REFERENCE_JWT_VERIFICATION_KEY="$reference_jwt_key" \
  PLATFORM_GATEWAY_FINANCE_REFERENCE_ISSUER=urn:ranikunlabs:identity:finance:reference \
  PLATFORM_GATEWAY_FINANCE_REFERENCE_AUDIENCE=finance-journal-reference \
  PLATFORM_GATEWAY_FINANCE_REFERENCE_PURPOSE=access \
  PLATFORM_GATEWAY_FINANCE_REFERENCE_CLOCK_SKEW_SECONDS=30 \
  PLATFORM_GATEWAY_FINANCE_REFERENCE_MAX_TOKEN_LIFETIME_SECONDS=300 \
  SPRING_APPLICATION_JSON="{\"spring\":{\"cloud\":{\"gateway\":{\"routes\":[{\"id\":\"core-finance-reference-login\",\"uri\":\"http://127.0.0.1:$core_port\",\"order\":-10,\"predicates\":[\"Path=/api/v1/auth/finance-reference/login\",\"Method=POST\"]},{\"id\":\"finance-reference-e2e\",\"uri\":\"http://127.0.0.1:$finance_port\",\"order\":20,\"predicates\":[\"Path=/finance/**\"]}]}}}}" \
  "$JAVA_HOME_17_X64/bin/java" -jar "$gateway_jar" >"$log_dir/gateway.log" 2>&1 &
gateway_pid=$!

nohup env \
  SPRING_PROFILES_ACTIVE=finance-trusted-context-reference \
  FINANCE_METRICS_ENVIRONMENT=finance-composite-e2e \
  SERVER_PORT="$finance_port" \
  FINANCE_MANAGEMENT_PORT="$finance_management_port" \
  SERVER_ADDRESS=127.0.0.1 \
  MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
  FINANCE_HTTP_BIND_ADDRESS=127.0.0.1 \
  FINANCE_MANAGEMENT_BIND_ADDRESS=127.0.0.1 \
  FINANCE_DB_URL='jdbc:postgresql://127.0.0.1:5432/finance_db?connectTimeout=3&socketTimeout=10' \
  FINANCE_DB_USERNAME=finance_e2e \
  FINANCE_DB_PASSWORD="$finance_db_password" \
  FINANCE_DB_APPLICATION_NAME=finance-composite-e2e \
  FINANCE_AUTH_MODE=trusted-context \
  FINANCE_AUTH_TRUSTED_CONTEXT_SERVICE_SECRET="$trusted_context_proof" \
  "$JAVA_HOME_21_X64/bin/java" -jar "$finance_jar" >"$log_dir/finance.log" 2>&1 &
finance_pid=$!

wait_ready "http://127.0.0.1:$finance_management_port/actuator/health/readiness" Finance
wait_ready "http://127.0.0.1:$gateway_management_port/actuator/health/readiness" Gateway

E2E_GATEWAY_URL="http://127.0.0.1:$gateway_port" \
E2E_EXPECTED_SUBJECT="$fixture_account_id" \
node "$PWD/scripts/e2e/finance-investment-composite-api-e2e.mjs"
