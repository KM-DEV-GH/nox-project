# Quickstart: Validating API Authentication and Authorization

**Feature**: [spec.md](spec.md) | **Contract**: [contracts/openapi.yaml](contracts/openapi.yaml)

This guide shows how to prove the feature works end to end. Implementation details are in
`tasks.md` (created by `/speckit-tasks`).

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 25 | The machine default is currently Java 8; install JDK 25 and set `JAVA_HOME`. |
| Docker-compatible runtime | Current | Docker Desktop, Colima, or Podman. Required for Testcontainers and the image build. |
| Maven | — | Not needed; use `./mvnw` (pins Maven 3.10.0). |
| `curl`, `jq` | any | For the manual checks below. |

## 1. Automated validation (primary)

```bash
./mvnw verify                      # unit + integration tests (starts a Keycloak 26.8.1 container)
./mvnw verify -Dgroups=perf        # optional latency comparison (SC-004)
```

Expected: build succeeds. The integration suite covers every acceptance scenario in the spec:

| Spec item | Proven by |
|-----------|-----------|
| Constitution Principle X | `ArchitectureTest` (ArchUnit): layer dependencies, Spring-free domain and application, no field injection |
| US1 scenarios 1–6, FR-001–FR-004, FR-015 | Token matrix: none, expired, tampered, `alg=none`, foreign issuer, wrong audience, valid user, valid service client |
| Constitution Principle VI | `ErrorResponsesIT`: 404, 405, 406, 500 problem responses match the contract |
| SC-001 | Endpoint enumeration test: every non-probe endpoint returns 401 without a token |
| US2, FR-005–FR-007, SC-002 | Test-only role-restricted endpoint: `bob` gets 403, `alice` gets 200 |
| US3, FR-008–FR-010, FR-016, SC-003, SC-009 | `/api/v1/me` for `alice`, `bob`, and the `nox-test-client` service account; a spoofed identity header is ignored |
| FR-012, FR-014, SC-005 | Captured log output has security events with `reason` and no JWT-shaped strings; error `detail` is generic |
| FR-013, SC-007 | Key rotation via the Keycloak admin API, with no restart |
| FR-011, FR-017, SC-006, SC-010 | Paused Keycloak: unknown key ID gives 503; `/readyz` latches; a cold start without Keycloak gives `/readyz` 503 |

## 2. Manual validation (local)

```bash
docker compose up -d keycloak                                 # dev realm "nox-dev" on :8180
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run              # service on :8080
```

### Probes (no token)

```bash
curl -i localhost:8080/readyz      # 200, empty body (503 until Keycloak keys load)
curl -s localhost:8080/healthz | jq   # {"status":"UP","serviceId":"nox",...,"checks":[{"componentId":"keycloak",...}]}
```

### No token → 401

```bash
curl -i localhost:8080/api/v1/me
```

Expected: `401`, `WWW-Authenticate: Bearer`, `Content-Type: application/problem+json`, body
`type = urn:problem:unauthenticated` (see contract `Unauthenticated`).

### Valid user token → 200

Use the dev realm's test credentials from `compose`'s realm import (local-only test users;
never real accounts):

```bash
TOKEN=$(curl -s -X POST localhost:8180/realms/nox-dev/protocol/openid-connect/token \
  -d grant_type=password -d client_id=nox-dev-cli -d username=alice -d password="$ALICE_DEV_PASSWORD" \
  | jq -r .access_token)
curl -s localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN" | jq
```

Expected: `data.callerType = "USER"`, `data.username = "alice"`, and `meta.traceId` present.
The `password` grant is enabled **only** on the dev realm's `nox-dev-cli` client, for manual
testing.

### Service client token → 200

```bash
TOKEN=$(curl -s -X POST localhost:8180/realms/nox-dev/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=nox-dev-service -d client_secret="$NOX_DEV_SERVICE_SECRET" \
  | jq -r .access_token)
curl -s localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN" | jq
```

Expected: `data.callerType = "SERVICE"`, `data.clientId = "nox-dev-service"`,
`data.username = null`.

### Keycloak outage

```bash
docker compose stop keycloak
curl -i localhost:8080/readyz                                   # still 200 (latched)
curl -s localhost:8080/healthz | jq .checks                      # keycloak: DEGRADED
curl -s localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN" | jq .data.callerType   # still works
```

### Deviations recorded on 2026-10-25 (T075)

Run on macOS with Podman 5 (`docker-compose` against the Podman socket, `podman build`):

- **Compose needs four variables**, not three: `ALICE_DEV_PASSWORD`, `BOB_DEV_PASSWORD`,
  `NOX_DEV_SERVICE_SECRET` and `KC_BOOTSTRAP_ADMIN_PASSWORD`. Put them in an untracked `.env`
  file (ignored by `.gitignore`) or pass `--env-file`.
- **`/healthz` does not turn `DEGRADED` the moment Keycloak stops.** The keycloak check follows the
  signing key state, which changes only when a key fetch fails: on the next request with an
  unknown key ID, or on the scheduled refresh (about 4.5 minutes after the last load). Right
  after `docker compose stop keycloak` the check still says `UP`. After one failed fetch it says
  `DEGRADED`, as expected. `/readyz` stays 200 and known tokens keep working throughout.
- **Unknown key ID during the outage** returns `503 urn:problem:identity_provider_unavailable`
  (not 401), as FR-011 requires.
- All other results matched: probes without a token, 401 with `WWW-Authenticate: Bearer` and
  `application/problem+json`, `USER` and `SERVICE` identities from `/api/v1/me`, the response
  `traceId` equal to the log `trace.traceId`, and no token-shaped strings in the log.

## 3. Container image

```bash
docker build -t nox:local .
docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=dev \
  -e NOX_AUTH_ISSUER_URI=http://localhost:8180/realms/nox-dev \
  -e NOX_AUTH_JWKS_URI=http://host.docker.internal:8180/realms/nox-dev/protocol/openid-connect/certs \
  -e NOX_AUTH_AUDIENCE=nox-api nox:local
docker run --rm --entrypoint id nox:local                        # uid=10001, not root
```

The issuer stays `localhost` because that is the `iss` in tokens obtained from the host. Only
the key-fetch URL changes, which is why issuer and JWKS URI are configured separately.

Expected: the same results as section 2. Log lines on stdout are single-line JSON in the
knowledge-base schema.

## 4. Before merge

- Run the security expert review (constitution gate), e.g. `/security-review`, and resolve any
  high or critical findings.
