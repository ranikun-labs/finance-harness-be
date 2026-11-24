#!/usr/bin/env bash
set -euo pipefail

# Runs one platform-core with Carelog password auth AND Finance reference signup/login enabled together,
# behind one gateway, with no seed fixture. The Carelog enrollment target is a synthetic stub embedded
# below (not carelog-be): it only answers the PUT enrollment and the product-claims GET that Core calls.

finance_source="${1:?Finance source checkout is required}"
platform_source="${2:?Platform source checkout is required}"
: "${RUNNER_TEMP:?Hosted runner temp directory is required}"
: "${JAVA_HOME_17_X64:?Java 17 is required for Gateway}"
: "${JAVA_HOME_21_X64:?Java 21 is required for Core and Finance}"
# Machine-checkable disclosure: the Carelog enrollment target is the embedded stub below, never carelog-be.
test "${CARELOG_ENROLLMENT_TARGET:-}" = "stub" || {
  echo "CARELOG_ENROLLMENT_TARGET must be 'stub' (the only supported target for this job)." >&2
  exit 1
}
echo "CARELOG_ENROLLMENT_TARGET=stub (real carelog-be enrollment NOT verified)"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verifier="$script_dir/carelog-finance-dual-auth-e2e.mjs"

core_port=18081
core_management_port=19092
gateway_port=18080
gateway_management_port=19091
finance_port=18082
finance_management_port=18083
stub_port=18091

log_dir="$RUNNER_TEMP/carelog-finance-dual-auth-e2e"
mkdir -m 700 -p "$log_dir"

# Gradle builds for Core and Finance use Java 21; the Gateway jar is run explicitly with Java 17 below.
export JAVA_HOME="$JAVA_HOME_21_X64"
export PATH="$JAVA_HOME/bin:$PATH"

export PGHOST=127.0.0.1 PGPORT=5432 PGUSER=postgres PGDATABASE=postgres
for _ in $(seq 1 60); do
  if pg_isready -q; then break; fi
  sleep 1
done
pg_isready -q

# Per-run secrets. They live only in process environments and are masked before any command can echo them.
identity_db_password="$(openssl rand -hex 32)"
finance_db_password="$(openssl rand -hex 32)"
reference_jwt_key="$(openssl rand -hex 64)"
identity_jwt_key="$(openssl rand -hex 64)"
product_claims_secret="$(openssl rand -hex 32)"
carelog_enrollment_credential="$(openssl rand -hex 32)"
trusted_context_proof="$(openssl rand -hex 32)"
gateway_legacy_jwt_key="$(openssl rand -hex 32)"
gateway_internal_secret="$(openssl rand -hex 32)"
for secret in "$identity_db_password" "$finance_db_password" "$reference_jwt_key" "$identity_jwt_key" \
  "$product_claims_secret" "$carelog_enrollment_credential" "$trusted_context_proof" \
  "$gateway_legacy_jwt_key" "$gateway_internal_secret"; do
  printf '::add-mask::%s\n' "$secret"
done

psql -v ON_ERROR_STOP=1 -v identity_password="$identity_db_password" -v finance_password="$finance_db_password" <<'SQL'
CREATE ROLE identity_e2e LOGIN PASSWORD :'identity_password';
CREATE ROLE finance_e2e LOGIN PASSWORD :'finance_password';
CREATE DATABASE platform_e2e OWNER identity_e2e;
CREATE DATABASE finance_db OWNER finance_e2e;
SQL
psql -v ON_ERROR_STOP=1 -d platform_e2e -c 'CREATE SCHEMA identity AUTHORIZATION identity_e2e;'

# Build the three boot jars from their checkouts. Gateway runs on Java 17; Core and Finance on Java 21.
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

wait_ready() {
  local url="$1" label="$2"
  for _ in $(seq 1 150); do
    if curl --fail --silent --output /dev/null "$url"; then return 0; fi
    sleep 2
  done
  printf '%s readiness failed; process logs remain on the ephemeral runner only.\n' "$label" >&2
  return 1
}

# Synthetic Carelog enrollment and product-claims stub. Written beside the logs, never into the repository.
stub_script="$log_dir/carelog-enrollment-stub.py"
cat >"$stub_script" <<'PY'
import json
import os
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ENROLL_PREFIX = "/api/v1/internal/v1/identity/carelog/enrollments/"
CLAIMS_PREFIX = "/api/v1/internal/identity/claims/"
ENROLL_BEARER = "Bearer " + os.environ["CARELOG_STUB_ENROLL_TOKEN"]
CLAIMS_SECRET = os.environ["CARELOG_STUB_CLAIMS_SECRET"]
NAMESPACE = uuid.UUID("6f1c0d2e-4b7a-4c1e-9a55-0c1a2b3c4d5e")
LOCK = threading.Lock()
ENROLLED = {}
COUNTS = {"enrollments": 0, "claimsServed": 0}


class Handler(BaseHTTPRequestHandler):
    server_version = "CarelogFinanceE2EStub/1.0"

    def log_message(self, _format, *_args):
        return

    def _send(self, status, body=None):
        encoded = b"" if body is None else json.dumps(body).encode("utf-8")
        self.send_response(status)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        if encoded:
            self.wfile.write(encoded)

    def do_GET(self):
        if self.path == "/__e2e/health":
            return self._send(200, {"status": "ok"})
        if self.path == "/__e2e/counters":
            with LOCK:
                return self._send(200, dict(COUNTS))
        if self.path.startswith(CLAIMS_PREFIX):
            if self.headers.get("X-Service-Id") != "platform-identity" or \
                    self.headers.get("X-Service-Secret") != CLAIMS_SECRET:
                return self._send(403, {"error": "forbidden"})
            account = self.path[len(CLAIMS_PREFIX):]
            with LOCK:
                if account not in ENROLLED:
                    return self._send(404, {"error": "not_found"})
                COUNTS["claimsServed"] += 1
            return self._send(200, {
                "organizationId": str(uuid.uuid5(NAMESPACE, "org:" + account)),
                "role": "MANAGER",
                "publicId": str(uuid.uuid5(NAMESPACE, "public:" + account)),
            })
        return self._send(404, {"error": "not_found"})

    def do_PUT(self):
        if not self.path.startswith(ENROLL_PREFIX):
            return self._send(404, {"error": "not_found"})
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        if self.headers.get("Authorization") != ENROLL_BEARER:
            return self._send(403)
        account = self.path[len(ENROLL_PREFIX):]
        try:
            json.loads(raw.decode("utf-8"))
        except ValueError:
            return self._send(400, {"error": "invalid_body"})
        with LOCK:
            if account in ENROLLED:
                return self._send(409)
            ENROLLED[account] = True
            COUNTS["enrollments"] += 1
        return self._send(201)


ThreadingHTTPServer(("127.0.0.1", int(os.environ["CARELOG_STUB_PORT"])), Handler).serve_forever()
PY

nohup env \
  CARELOG_STUB_PORT="$stub_port" \
  CARELOG_STUB_ENROLL_TOKEN="$carelog_enrollment_credential" \
  CARELOG_STUB_CLAIMS_SECRET="$product_claims_secret" \
  python3 -I "$stub_script" >"$log_dir/carelog-stub.log" 2>&1 &
stub_pid=$!

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  for pid in "${gateway_pid:-}" "${finance_pid:-}" "${core_pid:-}" "${stub_pid:-}"; do
    if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
  done
  wait 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT INT TERM

wait_ready "http://127.0.0.1:$stub_port/__e2e/health" "Carelog enrollment stub"

# One Core, both compositions: Carelog password auth (+ DEV registration/enrollment) and Finance reference
# with public signup. No selected account IDs: Finance admission is via signup enrollment only.
nohup env \
  SPRING_PROFILES_ACTIVE=finance-reference,dev \
  PLATFORM_ENVIRONMENT=carelog-finance-dual-e2e \
  PLATFORM_CORE_PORT="$core_port" \
  PLATFORM_CORE_MANAGEMENT_PORT="$core_management_port" \
  SERVER_ADDRESS=127.0.0.1 \
  MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
  PLATFORM_IDENTITY_AUTH_ENABLED=true \
  PLATFORM_IDENTITY_PERSISTENCE_ENABLED=true \
  PLATFORM_IDENTITY_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/platform_e2e?currentSchema=identity&connectTimeout=3&socketTimeout=10' \
  PLATFORM_IDENTITY_DATASOURCE_USERNAME=identity_e2e \
  PLATFORM_IDENTITY_DATASOURCE_PASSWORD="$identity_db_password" \
  PLATFORM_IDENTITY_FLYWAY_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/platform_e2e?currentSchema=identity&connectTimeout=3&socketTimeout=10' \
  PLATFORM_IDENTITY_FLYWAY_DATASOURCE_USERNAME=identity_e2e \
  PLATFORM_IDENTITY_FLYWAY_DATASOURCE_PASSWORD="$identity_db_password" \
  PLATFORM_IDENTITY_REDIS_HOST=127.0.0.1 \
  PLATFORM_IDENTITY_REDIS_PORT=6379 \
  PLATFORM_IDENTITY_REDIS_KEY_PREFIX=carelog-finance-e2e:identity: \
  PLATFORM_IDENTITY_JWT_SECRET_KEY="$identity_jwt_key" \
  PLATFORM_IDENTITY_JWT_ACCESS_TOKEN_VALIDITY=900000 \
  PLATFORM_IDENTITY_JWT_REFRESH_TOKEN_VALIDITY=3600000 \
  PLATFORM_IDENTITY_PRODUCT_CLAIMS_ENABLED=true \
  PLATFORM_IDENTITY_PRODUCT_CLAIMS_BASE_URL="http://127.0.0.1:$stub_port" \
  PLATFORM_IDENTITY_PRODUCT_CLAIMS_SERVICE_SECRET="$product_claims_secret" \
  PLATFORM_IDENTITY_KAKAO_CLIENT_ID=carelog-finance-e2e-unused \
  PLATFORM_IDENTITY_KAKAO_CLIENT_SECRET=carelog-finance-e2e-unused-secret \
  PLATFORM_IDENTITY_KAKAO_AUTHORIZATION_URI="http://127.0.0.1:$stub_port/oauth/authorize" \
  PLATFORM_IDENTITY_KAKAO_TOKEN_URI="http://127.0.0.1:$stub_port/oauth/token" \
  PLATFORM_IDENTITY_KAKAO_USER_INFO_URI="http://127.0.0.1:$stub_port/v2/user/me" \
  PLATFORM_IDENTITY_OAUTH_KAKAO_WEB_REDIRECT_URI="http://carelog-finance-e2e.invalid/oauth/kakao/callback" \
  PLATFORM_IDENTITY_OAUTH_KAKAO_MOBILE_REDIRECT_URI="http://carelog-finance-e2e.invalid/oauth/kakao/callback" \
  PLATFORM_IDENTITY_OAUTH_ALLOWED_RETURN_ORIGINS="http://carelog-finance-e2e.invalid" \
  PLATFORM_IDENTITY_CARELOG_REGISTRATION_ENABLED=true \
  PLATFORM_IDENTITY_CARELOG_ENROLLMENT_ENABLED=true \
  PLATFORM_IDENTITY_CARELOG_ENROLLMENT_BASE_URL="http://127.0.0.1:$stub_port" \
  PLATFORM_IDENTITY_CARELOG_ENROLLMENT_CREDENTIAL="$carelog_enrollment_credential" \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_ENABLED=true \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_SIGNUP_ENABLED=true \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_DEV_DUAL_AUTH_ENABLED=true \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_JWT_SIGNING_KEY="$reference_jwt_key" \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_JWT_ISSUER=urn:ranikunlabs:identity:finance:reference \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_JWT_AUDIENCE=finance-journal-reference \
  PLATFORM_IDENTITY_FINANCE_REFERENCE_JWT_ACCESS_TOKEN_TTL_SECONDS=300 \
  "$JAVA_HOME_21_X64/bin/java" -jar "$core_jar" >"$log_dir/core.log" 2>&1 &
core_pid=$!

wait_ready "http://127.0.0.1:$core_management_port/actuator/health/readiness" Core

nohup env \
  SPRING_PROFILES_ACTIVE=finance-trusted-context-reference \
  PLATFORM_ENVIRONMENT=carelog-finance-dual-e2e \
  PLATFORM_GATEWAY_PORT="$gateway_port" \
  SERVER_ADDRESS=127.0.0.1 \
  MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
  PLATFORM_GATEWAY_MANAGEMENT_PORT="$gateway_management_port" \
  PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1 \
  PLATFORM_GATEWAY_REDIS_PORT=6379 \
  PLATFORM_GATEWAY_REDIS_KEY_PREFIX=carelog-finance-e2e:gateway: \
  SPRING_DATA_REDIS_TIMEOUT=2s \
  PLATFORM_GATEWAY_AUTH_URI="http://127.0.0.1:$core_port" \
  PLATFORM_GATEWAY_FINANCE_BE_URI="http://127.0.0.1:$finance_port" \
  PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:9 \
  PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:9 \
  PLATFORM_GATEWAY_CARELOG_REGISTRATION_ENABLED=true \
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
  SPRING_APPLICATION_JSON="{\"spring\":{\"cloud\":{\"gateway\":{\"routes\":[{\"id\":\"finance-reference-e2e\",\"uri\":\"http://127.0.0.1:$finance_port\",\"order\":20,\"predicates\":[\"Path=/finance/**\"]}]}}}}" \
  "$JAVA_HOME_17_X64/bin/java" -jar "$gateway_jar" >"$log_dir/gateway.log" 2>&1 &
gateway_pid=$!

nohup env \
  SPRING_PROFILES_ACTIVE=finance-trusted-context-reference \
  FINANCE_METRICS_ENVIRONMENT=carelog-finance-dual-e2e \
  SERVER_PORT="$finance_port" \
  FINANCE_MANAGEMENT_PORT="$finance_management_port" \
  SERVER_ADDRESS=127.0.0.1 \
  MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
  FINANCE_HTTP_BIND_ADDRESS=127.0.0.1 \
  FINANCE_MANAGEMENT_BIND_ADDRESS=127.0.0.1 \
  FINANCE_DB_URL='jdbc:postgresql://127.0.0.1:5432/finance_db?connectTimeout=3&socketTimeout=10' \
  FINANCE_DB_USERNAME=finance_e2e \
  FINANCE_DB_PASSWORD="$finance_db_password" \
  FINANCE_DB_APPLICATION_NAME=carelog-finance-dual-e2e \
  FINANCE_AUTH_MODE=trusted-context \
  FINANCE_AUTH_TRUSTED_CONTEXT_SERVICE_SECRET="$trusted_context_proof" \
  "$JAVA_HOME_21_X64/bin/java" -jar "$finance_jar" >"$log_dir/finance.log" 2>&1 &
finance_pid=$!

wait_ready "http://127.0.0.1:$finance_management_port/actuator/health/readiness" Finance
wait_ready "http://127.0.0.1:$gateway_management_port/actuator/health/readiness" Gateway

E2E_GATEWAY_URL="http://127.0.0.1:$gateway_port" \
E2E_CARELOG_STUB_URL="http://127.0.0.1:$stub_port" \
node "$verifier"
