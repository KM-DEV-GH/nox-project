# Phase 0 Research: BFF Role-Based Access

No `NEEDS CLARIFICATION` markers remain in the spec (all resolved in `$speckit-clarify`); the
items below are implementation-approach decisions needed before Phase 1 design, each with
alternatives considered.

## D1: How is "service client + a specific permission" checked for each surface?

**Decision**: Extract the existing `PartnerCallerResolver.isBff(Authentication)` check into a
small, reusable predicate `ServiceCallerAuthorization.requiresRole(String role)` returning an
`AuthorizationManager<RequestAuthorizationContext>` that grants only when
`authentication.getPrincipal() instanceof CallerIdentity caller && caller.callerType() ==
CallerType.SERVICE` and the authorities contain `ROLE_<role>`. `PartnerCallerResolver` keeps its
own class (it still has partner-resolution behavior beyond the role check: reading
`x-service-id` and calling `FindCallablePartnerUseCase`) but delegates the role+caller-type test
to this shared predicate with `role = "partner-access"`. A new `InternalCallerResolver` wraps the
same predicate with `role = "internal-access"` for the user-link surface; it has no extra
resolution step (FR-003) and sets no request attribute — the acting person comes from the header,
not from authorization (see D2).

**Rationale**: Both surfaces need the identical "service client + named permission" test; only
the partner surface layers an extra partner-existence lookup on top. Extracting the shared test
avoids duplicating the `CallerType.SERVICE` + authority-matching logic a second time, and keeps
each resolver's unique part (partner resolution vs. none) visible.

**Alternatives considered**:

- Keep `CallerTypeAuthorization.usersOnly()` and simply add a role check to it. Rejected: that
  class's purpose (any authenticated user) is being removed from this feature entirely (clarify
  Q1/Q1′ — no person token reaches this surface), so repurposing it would leave a misleadingly
  named class; it is deleted instead (FR-004).
- One `AuthorizationManager` implementation per surface with the check inlined twice. Rejected:
  duplicates the authority-matching logic that must stay identical in meaning for both surfaces.

## D2: Authorization vs. acting-person resolution — the trust boundary (constitution Principle II)

**Decision**: The `AuthorizationManager` (D1) decides access **solely** from the validated
token's caller type and roles; it never reads `X-User-ID`. Only *after* that check admits the
request does a separate component (D3) read and validate `X-User-ID`. This plan records the
trust boundary explicitly, as the constitution's Security-by-Default principle requires: a
missing or malformed `X-User-ID` can only ever narrow an already-granted request (to a 400), and
a caller that lacks `internal-access` is refused (403) before the header is inspected at all
(FR-007) — the header can never be an alternative path to authorization.

**Rationale**: Keeps "who may call" (token-only) and "whom the call is about" (header, once
already admitted) as two separable, independently testable decisions, matching how
`PartnerCallerResolver` already separates "is this the BFF" from "which partner" today.

**Alternatives considered**:

- Fold header validation into the same `AuthorizationManager` (as `PartnerCallerResolver` folds
  partner resolution in). Rejected: that path only ever produces a boolean, which forces every
  header problem to surface as 403; FR-006 requires a 400 for a missing/invalid `X-User-ID`,
  which needs a mechanism capable of raising a distinct client error, not just granting or
  denying.

## D3: Where `X-User-ID` is parsed and validated

**Decision**: A `HandlerMethodArgumentResolver` (`ActingUserIdArgumentResolver`) bound to a new
`@ActingUser` parameter annotation, resolving to `UUID`. It reads the `X-User-ID` header, and
throws `ProblemException(ProblemType.BAD_REQUEST)` when the header is absent, blank, or not a
valid UUID (FR-006); `GlobalExceptionHandler` already turns that into the standard problem body,
so no new error-handling code is needed. Every renamed `PartnerLinkController` method takes
`@ActingUser UUID userId` instead of `@AuthenticationPrincipal CallerIdentity caller`.

**Rationale**: One parser used by all six endpoints, instead of six copies of
`@RequestHeader` + manual `UUID.fromString` + try/catch. Matches the existing idiom
(`@AuthenticationPrincipal`) so the controller stays a pure translator (Principle X): it never
sees the raw header.

**Alternatives considered**:

- `@RequestHeader("X-User-ID") String` plus inline validation in each controller method.
  Rejected: duplicates validation logic six times and risks inconsistent error behavior between
  methods.
- A `Filter`/`OncePerRequestFilter` that validates the header and stores it as a request
  attribute. Rejected: argument resolvers are the standard Spring MVC mechanism for "this
  parameter needs cross-cutting resolution logic" and need no new filter-chain wiring or request
  attribute contract.

## D4: Keying user-link lookups by `userId` instead of `userKeycloakId`

**Decision**: `PartnerLinkRepository`/`PartnerLinkJpaRepository` gain `findAllByUserId` and
`findByIdAndUserId`, replacing `findAllByUserKeycloakId` and `findByIdAndUserKeycloakId`.
`PartnerLink.userId()` (the internal/Owl id) is already persisted on every link and already used
on the write path (`findActiveByUserAndPartner(userId, partnerId)` in
`ResolvePartnerLinkRequestService`), so this is a derived-query rename on an existing, already
populated and already indexed column — no Liquibase changeset. All four user-link use cases
(`ListMyPartnerLinksUseCase`, `GetMyPartnerLinkUseCase`, `GetMyPartnerLinkHistoryUseCase`,
`ApprovePartnerLinkUseCase`/`RejectPartnerLinkUseCase`/`RevokePartnerLinkUseCase` via
`PartnerLinkDecider`) rename their `userKeycloakId` parameter to `userId` and take the value
`@ActingUser` resolved (D3), not the caller's own `subjectId()`.

**Rationale**: `X-User-ID` was clarified to carry the internal user id, not the Keycloak subject
id (spec Assumptions); the domain already stores both, so no identifier translation or schema
change is needed — only the query key and the parameter source change.

**Alternatives considered**:

- Look up the Keycloak id for the internal user id via a new Owl call before querying. Rejected:
  unnecessary network dependency and latency for data NOX already has locally on every link.
- Add a new column or index specifically for this feature. Rejected: `user_id` already exists and
  is already indexed for the active-link uniqueness check.

## D5: Permission naming — `nox-bff`'s role literal renamed, not its client id

**Decision**: `PartnerCallerResolver.BFF_ROLE` changes from the literal `"nox-bff"` to
`"partner-access"`; the client id the partner-facing BFF authenticates as is unaffected (still
`nox-bff` in Keycloak) — only the *role* it must hold changes name. A parallel constant
`INTERNAL_ACCESS_ROLE = "internal-access"` is added for `InternalCallerResolver`. Both remain
Java literals (no new externalized configuration), consistent with how `BFF_ROLE` is defined
today.

**Rationale**: The spec's clarified model separates "which client" (`nox-bff`, `bff`) from
"which permission the client's token carries" (`partner-access`, `internal-access`); conflating
them (as today's single `nox-bff`-named role does) is exactly the ambiguity this feature removes
(FR-017: the two permissions are independent).

**Alternatives considered**: Make the role names configurable via `AuthProperties`. Rejected: no
stated need (the constitution requires configuration only for values that vary per environment;
these role names are fixed contract strings, like the existing `nox-bff` literal).

## D6: Moving the user-link surface off the `/api/v1/me` prefix

**Decision**: The six user-link endpoints move from `/api/v1/me/partner-links...` to
`/api/v1/user-partner-links...` (FR-018), keeping every suffix, verb, and payload. The acting
person stays in the `X-User-ID` header rather than becoming a path variable. `SecurityConfig`'s
`USER_PARTNER_LINKS` matcher becomes `{"/api/v1/user-partner-links/**"}` — note Spring's
`PathPattern` `/**` matches zero or more trailing segments, so the bare collection path
`/api/v1/user-partner-links` is still covered by the single matcher, exactly as
`/api/v1/me/partner-links/**` covers it today. The old prefix is removed outright with no alias
(FR-019): once the controller's mapping moves, `/api/v1/me/partner-links...` falls through to
`anyRequest().authenticated()` and yields `404` for an authenticated caller — no route, no
handler, no data. `GET /api/v1/me` (the caller-identity endpoint) keeps its path and is
untouched (clarify Q2).

**Rationale**: `me` asserts "the authenticated caller owns this resource", which this feature
makes permanently false — the caller is always a front-end service acting for someone else, so
the prefix would actively mislead. A distinct top-level resource name also keeps the two
authorization regimes on cleanly separate path roots, so each `SecurityConfig` matcher stays a
single prefix with no overlap or ordering subtlety between them.

**Alternatives considered**:

- `/api/v1/users/{userId}/partner-links`, dropping `X-User-ID` entirely. Rejected by the user:
  it would reverse the clarified header decision and would put an internal user id into request
  URLs, and therefore into access logs, proxy traces, and metrics path labels.
- A sub-path under the existing `/api/v1/partner-links` prefix (e.g.
  `/api/v1/partner-links/user/...`). Rejected: `PARTNER_FACING` already matches
  `/api/v1/partner-links/**` with the partner resolver, so a user-link sub-path would sit inside
  a prefix governed by a different permission, making correctness depend on matcher ordering —
  exactly the kind of implicit precedence the deny-by-default principle discourages.
- Keeping the old prefix as a deprecated alias for a release. Rejected (spec Assumptions): both
  front-end services are updated alongside this change and no other caller exists, so an alias
  would only keep a misleading, person-implying path reachable.
