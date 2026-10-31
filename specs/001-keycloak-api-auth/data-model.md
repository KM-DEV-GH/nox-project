# Data Model: API Authentication and Authorization with Keycloak

**Feature**: [spec.md](spec.md) | **Research**: [research.md](research.md)

Nothing in this feature is persisted. All entities are in-memory: they live for one request,
or for the process lifetime in the case of the signing key set. No database schema or Liquibase
changeset is required.

Each section names its Clean Architecture layer (constitution Principle X, research R16).

## CallerIdentity

**Layer**: `domain.caller` (plain Java record; the constructor enforces the invariants below).
Claims are mapped to it by `adapter.in.web.security`.

The trusted, per-request description of who is calling (FR-008, FR-009). It is built only from
a validated access token and is immutable.

| Field | Type | Required | Source claim | Rules |
|-------|------|----------|--------------|-------|
| `subjectId` | string | yes | `sub` | Non-blank. Stable unique ID of the user or service account. |
| `callerType` | enum `USER` \| `SERVICE` | yes | derived | `SERVICE` if `client_id` claim present or `preferred_username` starts with `service-account-`; else `USER`. |
| `username` | string | USER only | `preferred_username` | `null` when `callerType = SERVICE`. |
| `clientId` | string | SERVICE only | `client_id`, else `azp` | `null` when `callerType = USER`. |
| `email` | string | no | `email` | `null` if absent; never invented. |
| `roles` | set of string | yes (may be empty) | `realm_access.roles` ∪ `resource_access.<audience>.roles` | Deduplicated, sorted. Missing claims mean an empty set. Roles under other clients are ignored. |

**Invariants**

- Exactly one of `username` / `clientId` is non-null, matching `callerType`.
- Never built from request bodies, query parameters, or custom headers (FR-010).
- Never holds the raw token or any claims outside this table.

## AccessToken (external, not stored)

**Layer**: Handled only in `adapter.in.web.security` and `infrastructure.config`; never visible to
domain or application code.

A JWT issued by the configured Keycloak realm. Validation rules (all must pass, see research R4):

| Check | Rule | Failure |
|-------|------|---------|
| Format | `Authorization: Bearer <one JWT>` | 401 |
| Algorithm | `RS256` only | 401 |
| Signature | Verifies against a key in the realm's key set (matched by `kid`) | 401 |
| Issuer | `iss` = configured issuer exactly | 401 |
| Audience | `aud` contains configured audience | 401 |
| Time | `exp` in the future and `nbf` (if present) in the past, ±30 s skew | 401 |
| Key availability | Unknown `kid` and key set cannot be refreshed | 503 |

## Role

A role name string assigned in Keycloak. It is exposed to Spring Security as the authority
`ROLE_<name>`. Keycloak is the only source of roles (FR-006); this service never stores or
edits role assignments.

## EndpointAccessRule

A per-endpoint declaration in code:

- **authenticated** (default for every endpoint under `/api/**`): any valid token.
- **role-restricted**: `@PreAuthorize("hasRole('<name>')")`; missing role → 403.
- **public**: only `GET /healthz` and `GET /readyz`.

Default-deny: any request not matching an explicit rule requires authentication.

## SigningKeySet (process lifetime)

**Layer**: State tracked in `adapter.out.identityprovider`, exposed to the application through the
`ReadinessCondition` and `DependencyHealthProbe` outbound ports.

The realm's JWKS held in memory by the key source (research R3).

| State | Meaning | `/readyz` | `/healthz` keycloak check |
|-------|---------|-----------|---------------------------|
| `NOT_LOADED` | Never fetched successfully since startup | 503 | `DOWN` |
| `LOADED` | Fetched at least once; latest refresh succeeded | 200 | `UP` |
| `LOADED_STALE` | Fetched at least once; latest refresh failed (Keycloak unreachable) | 200 | `DEGRADED` |

Transitions:

- `NOT_LOADED → LOADED` on the first successful fetch (background retry with backoff).
- `LOADED ↔ LOADED_STALE` as refreshes fail or succeed.
- There is no transition back to `NOT_LOADED` (the latch required by FR-017).

## ServiceHealth (domain rule)

**Layer**: `domain.health`.

- `ComponentHealth`: `componentId`, `componentType` (`redis` | `postgresql` | `mongodb` |
  `rabbitmq` | `external-service`), `status` (`UP` | `DOWN` | `DEGRADED`), `critical`
  (boolean, not serialized), `time`.
- `ServiceHealth.aggregate(checks)`: `DOWN` if any critical check is `DOWN`; else `DEGRADED` if
  any check is not `UP`; else `UP`. No checks means `UP`.
- Keycloak check: `critical = true` while keys are `NOT_LOADED`, `false` once loaded.

## Response envelopes (from constitution Principles V and VI)

**Layer**: `adapter.in.web.response` and `adapter.in.web.error`.

- **Success**: `{ "data": <payload>, "meta": { "traceId", "timestamp", "instance" } }`
- **Error** (`application/problem+json`): `{ "type", "title", "detail", "traceId",
  "timestamp", "instance" }`

Full schemas are in [contracts/openapi.yaml](contracts/openapi.yaml).

## Error catalogue (this feature)

| `type` | `title` | HTTP | When |
|--------|---------|------|------|
| `urn:problem:unauthenticated` | `unauthenticated` | 401 | Missing or invalid token (any validation failure) |
| `urn:problem:forbidden` | `forbidden` | 403 | Valid token, missing required role |
| `urn:problem:identity_provider_unavailable` | `identity_provider_unavailable` | 503 | Signing key needed but unobtainable |
| `urn:problem:not_found` | `not_found` | 404 | No such resource (authenticated callers only) |
| `urn:problem:method_not_allowed` | `method_not_allowed` | 405 | HTTP method not supported (`Allow` header set) |
| `urn:problem:not_acceptable` | `not_acceptable` | 406 | `Accept` header cannot be satisfied (body is still `application/problem+json`) |
| `urn:problem:internal_error` | `internal_error` | 500 | Unexpected server error |

Every type is documented in [contracts/openapi.yaml](contracts/openapi.yaml) and covered by an
integration test (constitution Principle VI): 401 `AuthenticationIT`, 403 `RoleAuthorizationIT`,
404/405/406/500 `ErrorResponsesIT`, 503 `KeycloakOutageIT`.

`detail` is a fixed, generic sentence per type. It never states which token check failed
(FR-014); the specific reason is logged instead.

## Security log event (FR-012)

Emitted at `WARN` for 401, 403, and 503 outcomes, using the standard log schema
(constitution Principle VII). The handler passes **only** the message and the event-specific
`reason` (an SLF4J key-value). All general context is resolved by `NoxStructuredLogFormatter`
from the current thread (research R9):

- `message`: `"authentication failed"` / `"access denied"` / `"identity provider unavailable"`
- resolved by the formatter: `trace{traceId, spanId, parentSpanId?, correlationId?}`,
  `request{method, url (path only), userAgent, clientIp}`, and `user{id, clientId?}` when an
  authenticated `CallerIdentity` exists (403 only)
- `reason` (key-value): an internal code such as `token_expired`, `invalid_audience`, `invalid_issuer`,
  `invalid_signature`, `malformed_token`, `missing_token`, `missing_role`, `jwks_unavailable`

Never includes: the token, the `Authorization` header, or any other credential.
