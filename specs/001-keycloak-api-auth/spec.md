# Feature Specification: API Authentication and Authorization with Keycloak

**Feature Branch**: `001-keycloak-api-auth`

**Created**: 2026-10-25

**Status**: Draft

**Input**: User description: "Authentication and Authorization — Keycloak (self-hosted) for
authentication and authorization; OAuth 2.0 Bearer tokens secure every API endpoint. Clients
obtain an access token from Keycloak and send it in the `Authorization` header; this service
validates the token with Keycloak before processing the request. Access is restricted by the
roles and permissions defined in Keycloak. The service must also identify exactly which user
(or client) made each request, from the validated token's claims (ID, username, email, roles),
so later request handling and authorization decisions can use it."

## Clarifications

### Session 2026-10-25

- Q: Must every request be checked live against Keycloak, or may the service verify tokens
  locally with Keycloak's published signing keys? → A: Verify locally with published signing
  keys; revoked tokens stay usable until expiry, bounded by short (~5 min) token lifetimes.
- Q: Should this phase expose a "current caller" endpoint? → A: Yes — a "current caller"
  endpoint (e.g. `GET /api/v1/me`) returning ID, username, email, roles, and caller type.
- Q: Should the service trust one Keycloak realm per environment or several realms? → A: Exactly
  one realm per environment; multi-realm / multi-tenant trust is out of scope.
- Q: Must a token name this service as its intended audience, or is any valid realm token
  enough? → A: The token's audience MUST include this service, in every environment; tokens
  issued for other apps in the realm are rejected.
- Q: Should the service report "not ready" if it cannot fetch Keycloak's signing keys? → A: Not
  ready until signing keys are loaded at least once; stays ready afterwards even if Keycloak
  becomes unreachable.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Only authenticated callers can reach the API (Priority: P1)

A client application (acting for a person, or for itself as a service) obtains an access token
from the organization's Keycloak identity server and presents it with every request. The
service accepts the request only if the token is genuine, unexpired, and issued for this
service. Any request without a valid token is rejected before any business logic runs.

**Why this priority**: Without this, nothing else in the service can be exposed safely. It is
the minimum viable security boundary and the foundation for every later feature.

**Independent Test**: Call any protected endpoint with (a) no token, (b) an expired token,
(c) a token from a different issuer, (d) a tampered token, and (e) a valid token. Only (e)
succeeds; (a)–(d) are rejected as unauthenticated using the standard error format.

**Acceptance Scenarios**:

1. **Given** a request with no `Authorization` header, **When** it reaches any protected
   endpoint, **Then** it is rejected as unauthenticated (401) with the standard error body and
   no business logic runs.
2. **Given** a request with an expired, malformed, tampered, or wrongly signed token,
   **When** it reaches a protected endpoint, **Then** it is rejected as unauthenticated (401).
3. **Given** a token issued by a different identity server or for a different audience,
   **When** it reaches a protected endpoint, **Then** it is rejected as unauthenticated (401).
4. **Given** a valid token from the trusted realm that was issued for another application
   (its audience does not include this service), **When** it reaches a protected endpoint,
   **Then** it is rejected as unauthenticated (401).
5. **Given** a valid, unexpired token issued by the configured Keycloak realm for this service,
   **When** it reaches a protected endpoint, **Then** the request proceeds to processing.
6. **Given** a token obtained through the client credentials flow (service-to-service),
   **When** it is valid, **Then** it is accepted in the same way as a user token.

---

### User Story 2 - Access is limited by Keycloak roles (Priority: P2)

Administrators assign roles to users and service clients in Keycloak. Each endpoint of this
service declares which roles may use it. An authenticated caller without a required role is
refused, while one with the role is allowed. Role changes are made only in Keycloak, never in
this service.

**Why this priority**: Authentication alone lets every valid account do everything. Role
restrictions are required before any sensitive operation is exposed, but they depend on
Story 1.

**Independent Test**: With an endpoint that requires a test role, call it with a valid token
that lacks the role (refused as forbidden) and with a valid token that has the role
(allowed).

**Acceptance Scenarios**:

1. **Given** an authenticated caller whose token lacks the role an endpoint requires,
   **When** they call that endpoint, **Then** the request is refused as forbidden (403) with
   the standard error body.
2. **Given** an authenticated caller whose token includes the required role, **When** they
   call that endpoint, **Then** the request is processed.
3. **Given** an endpoint that declares no role requirement, **When** any authenticated caller
   calls it, **Then** it is processed (authentication is still required).
4. **Given** an administrator removes a role from a user in Keycloak, **When** that user
   presents a token issued after the change, **Then** access to endpoints needing that role
   is refused.

---

### User Story 3 - The service knows exactly who is calling (Priority: P3)

For every authenticated request, the service builds a single, trusted description of the
caller from the validated token — unique ID, username, email, roles, and whether the caller is
a person or a service client. Any later request handling (personalizing a response, checking
that a caller only touches their own data, recording who did what) uses this description
instead of re-reading the token or trusting values sent by the client.

**Why this priority**: Required for fine-grained access control and auditing in later
features, but the API is already secured without it.

**Independent Test**: Send requests with tokens for two different test users and one service
client to the "current caller" endpoint and confirm each response describes the correct
caller (ID, username or client ID, email, roles, caller type).

**Acceptance Scenarios**:

1. **Given** a valid user token, **When** the request is processed, **Then** the caller's
   unique ID, username, email, and roles are available to request handling and match the
   token.
2. **Given** a valid service-client token (no human user), **When** the request is processed,
   **Then** the caller is identified as a service client with its client ID and roles, and
   person-only fields (such as email) are reported as absent rather than invented.
3. **Given** a request body or header that claims a different user ID than the token,
   **When** the request is processed, **Then** the identity from the token is used and the
   client-supplied value is not trusted for identity.
4. **Given** an authenticated request, **When** the service writes log entries for it,
   **Then** the entries record the caller's ID and never contain the token itself.
5. **Given** any authenticated caller, **When** they request the "current caller" endpoint,
   **Then** they receive their own ID, caller type, username or client ID, email (if present),
   and roles in the standard success envelope — and never another caller's data.
6. **Given** a request to the "current caller" endpoint without a valid token, **When** it is
   processed, **Then** it is rejected as unauthenticated (401) like every other endpoint.

---

### Edge Cases

- A token expires while a long-running request is in progress: the request that was accepted
  completes; the next request with that token is rejected.
- The Keycloak server is unreachable or slow: tokens whose signing key the service already
  holds continue to be verified locally and accepted if valid. If a token needs a signing key
  the service cannot obtain, the service fails closed — it never accepts a token it could not
  verify — and reports the problem as a service-side failure, not as the caller's fault.
- A user is disabled or their session is revoked in Keycloak: tokens already issued remain
  usable until they expire (accepted trade-off of local verification); new tokens cannot be
  obtained, so access ends within one token lifetime.
- Keycloak rotates its signing keys: tokens signed with the new key are accepted without a
  service restart; tokens signed with retired keys are rejected.
- The `Authorization` header uses a scheme other than Bearer, contains several tokens, or has
  an empty value: rejected as unauthenticated.
- A token is missing an expected claim (e.g. no email, no roles): authentication still
  succeeds if the token is valid; the missing attribute is reported as absent; role checks
  treat missing roles as "no roles".
- A request presents a token over an unencrypted connection in a non-local environment: such
  connections are not served (transport security is enforced by the deployment).
- The service starts while Keycloak is unreachable: it reports "not ready" and receives no
  traffic until it loads the signing keys; it becomes ready as soon as they load, with no
  restart needed.
- Health and readiness probes are called without a token: they respond, because the
  orchestrator cannot obtain tokens; they expose no business or user data.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST require a valid OAuth 2.0 Bearer access token on every endpoint
  except the explicitly listed health and readiness probes.
- **FR-002**: The system MUST validate each presented token locally, before any business
  processing, using the signing keys published by the configured Keycloak realm: genuine
  signature from Keycloak, expected issuer, an audience that includes this service's
  configured identifier (in every environment), and not expired or not-yet-valid. It MUST NOT
  call Keycloak per request to check a token.
- **FR-003**: The system MUST reject requests with a missing, malformed, expired, tampered, or
  foreign token as unauthenticated (HTTP 401) using the project's standard error format.
- **FR-004**: The system MUST accept tokens issued through both user-facing flows (e.g.
  authorization code) and the service-to-service client credentials flow.
- **FR-005**: The system MUST let each endpoint declare the Keycloak roles required to use it,
  and MUST refuse authenticated callers lacking those roles as forbidden (HTTP 403) using the
  standard error format.
- **FR-006**: The system MUST treat Keycloak as the only source of roles and permissions; it
  MUST NOT store or edit role assignments itself.
- **FR-007**: The system MUST recognize both realm-level roles and roles specific to this
  service's client registration in Keycloak.
- **FR-008**: For every authenticated request, the system MUST produce one caller identity
  containing: unique subject ID, caller type (user or service client), username or client ID,
  email (if present), and roles.
- **FR-009**: The caller identity MUST be derived only from the validated token and MUST be
  available to all subsequent request handling and authorization decisions within that
  request.
- **FR-010**: The system MUST NOT accept caller identity from request bodies, query parameters,
  or custom headers in place of the validated token.
- **FR-011**: The system MUST fail closed: if a token cannot be validated (including because
  Keycloak is unavailable), the request MUST NOT be processed. Such failures caused by the
  service's side MUST NOT be reported as the caller's error.
- **FR-012**: The system MUST record authentication and authorization failures as structured
  log events containing the reason, request path, and trace ID — and the caller ID when known —
  without ever logging the token or other credentials.
- **FR-013**: The system MUST continue accepting valid tokens across Keycloak signing-key
  rotation without a restart or redeployment.
- **FR-014**: Error responses for authentication and authorization failures MUST NOT reveal
  why a token was judged invalid in a way that helps an attacker (e.g. which check failed);
  details go to logs only.
- **FR-015**: Each environment (Development, Stage, Production) MUST trust exactly one Keycloak
  realm. The realm, issuer, and expected audience MUST be configurable per environment; tokens
  from any other realm MUST be rejected as unauthenticated.
- **FR-016**: The system MUST provide a "current caller" endpoint, available to any
  authenticated caller with no role requirement, that returns the caller identity from FR-008
  (subject ID, caller type, username or client ID, email if present, roles) in the standard
  success envelope. It MUST only ever return the requesting caller's own identity and MUST NOT
  return the token or any other claims beyond those listed.
- **FR-017**: The readiness probe MUST report "not ready" until the service has successfully
  loaded the realm's signing keys at least once. After that, loss of contact with Keycloak MUST
  NOT by itself make the service report "not ready".

### Key Entities

- **Access Token**: A credential issued by Keycloak and presented by the client. Carries the
  issuer, audience, expiry, subject, and claims such as username, email, and roles. Never
  stored by this service.
- **Caller Identity**: The service's trusted, per-request view of who is calling, built from a
  validated token. Attributes: subject ID, caller type (user / service client), username or
  client ID, email (optional), roles. Lives only for the duration of the request.
- **Role**: A named permission assigned in Keycloak, either realm-wide or specific to this
  service's client. Endpoints reference roles by name to decide access.
- **Endpoint Access Rule**: The declaration, per endpoint, of whether authentication alone is
  enough or which roles are required.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of non-probe endpoints reject requests without a valid token, verified by an
  automated test that enumerates every endpoint.
- **SC-002**: 100% of endpoints with a role requirement refuse callers lacking that role and
  admit callers holding it, verified by automated tests per endpoint.
- **SC-003**: For 100% of authenticated requests in tests, the caller identity available to
  request handling matches the subject, username, email, and roles in the token used.
- **SC-004**: Security checks add no delay that callers notice: 95% of authenticated requests
  complete within 50 ms of the time the same request takes with security checks disabled in a
  test environment.
- **SC-005**: 0 occurrences of access tokens or credentials in application logs, verified by an
  automated log scan in integration tests.
- **SC-006**: When the identity server is unavailable, 0 requests carrying unverifiable tokens
  are processed.
- **SC-007**: After a Keycloak signing-key rotation, valid tokens are accepted with no service
  restart and no failed requests caused by the rotation.
- **SC-008**: A user disabled in Keycloak loses all API access within one access-token lifetime
  (target: 5 minutes or less).
- **SC-009**: In tests, 100% of "current caller" responses describe exactly the caller whose
  token was presented, for both user and service-client tokens.
- **SC-010**: An instance that has not loaded the signing keys receives no production traffic
  (it reports "not ready" 100% of the time until keys load), and an outage of the identity
  server after startup causes 0 already-ready instances to report "not ready".

## Assumptions

- A self-hosted Keycloak instance and realm exist (or will be provisioned) for each
  environment; registering clients, users, and roles in Keycloak is an administrative task
  outside this feature.
- This service is an API only: it validates tokens but never issues them, never shows a login
  page, and does not handle login, logout, refresh tokens, or password flows — clients do that
  with Keycloak directly.
- Health (`/healthz`) and readiness (`/readyz`) probes stay unauthenticated, as permitted by
  the constitution, and return no user or business data.
- The project's standard error format (constitution Principle VI) is used for 401 and 403
  responses.
- No business endpoints exist yet; role-restricted behavior is verified with test endpoints or
  test roles until real endpoints arrive in later features.
- Fine-grained, resource-level checks (e.g. "a user may only see their own orders") are built
  by later features using the caller identity delivered here; this feature does not define
  them.
- Multi-tenancy (trusting several realms, or choosing a realm per request) is out of scope.
- Keycloak's fine-grained "Authorization Services" (policies/permissions evaluated by
  Keycloak) are out of scope; authorization in this phase is role-based.
- Every client that calls this service is configured in Keycloak so its tokens include this
  service in the audience (an audience mapper); this is realm configuration, not part of this
  service.
- Access tokens are configured in Keycloak to be short-lived (about 5 minutes) to bound how long
  a revoked token stays usable; this is realm configuration, not part of this service.
- Integration tests run against a real Keycloak instance in a test container or an equivalent
  stand-in, never a shared live environment.
