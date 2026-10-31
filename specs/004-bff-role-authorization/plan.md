# Implementation Plan: BFF Role-Based Access

**Branch**: `004-bff-role-authorization` | **Date**: 2026-10-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-bff-role-authorization/spec.md`

## Summary

Two front-end services call NOX, each a service client: `nox-bff` (partner-access) and `bff`
(internal-access). The link-request and link-query endpoints (today gated only on `nox-bff`)
become gated on the `partner-access` permission; the user-link endpoints (today open to any user
token) become gated on the `internal-access` permission and are closed to every person-held
token, including one that happens to carry a matching permission — only a service client may call
any of these three surfaces (clarify Q1, Q1′). `GET /api/v1/me` is unaffected (clarify Q2). The
internal BFF supplies the acting person per request as an internal user id (UUID) in the
`X-User-ID` header; this is the same `userId` NOX already stores on every link (distinct from the
Keycloak subject id), so user-link lookups move from `userKeycloakId` to `userId` with no schema
change. `MyPartnerLinkController` is renamed to `PartnerLinkController` and its surface moves
from `/api/v1/me/partner-links` to `/api/v1/user-partner-links`, since "me" no longer describes
the caller (clarify Q5). Every denial on any of
the three surfaces is recorded as an authorization failure event (clarify Q4), reusing the
existing centralized `AccessDeniedHandler`.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1.2 (unchanged)

**Primary Dependencies**: No new dependencies. Reuses `spring-boot-starter-security`,
`spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-webmvc`,
`spring-boot-starter-data-jpa` already in `pom.xml`.

**Storage**: PostgreSQL, unchanged schema. `partner_link.user_id` (the internal/Owl user id)
already exists alongside `user_keycloak_id` and is already used for write-path lookups
(`findActiveByUserAndPartner`); this feature only adds read-path queries on the same column — no
Liquibase changeset needed.

**Testing**: JUnit 5; `RestTestClient` integration tests (`*IT`) against the real security filter
chain and a Testcontainers Keycloak realm (existing `TestTokens`/`PartnerLinkAuthorizationIT`
pattern); ArchUnit for layering.

**Target Platform**: Linux container (existing Dockerfile/compose), unchanged.

**Project Type**: Single Spring Boot web service (existing).

**Performance Goals**: None beyond existing; this is an authorization-path change on endpoints
already in production with no added I/O (no new external calls, no new queries beyond swapping
one indexed column for another).

**Constraints**:

- Minimal API surface change: request/response payloads and error shapes are unchanged, and the
  link-request and link-query paths are unchanged (FR-015). The user-link surface changes prefix
  to `/api/v1/user-partner-links` (FR-018) with the old prefix retired outright (FR-019) — both
  callers are updated alongside, so no compatibility window is kept.
- Deny by default: a request reaching a controller without passing its surface's
  `AuthorizationManager` check never executes business logic (Principle II).
- `X-User-ID` is read only after the token-based authorization decision has already admitted the
  caller (FR-007, FR-011): the permission check never inspects the header, so the header can
  never substitute for, or widen, a token-based grant.

**Scale/Scope**: 3 endpoint groups (8 endpoints total: 2 link-request, 2 link-query, 6 user-link),
1 renamed controller, 2 roles, no new entities.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Technology Stack | Java 25 / Spring Boot 4.1.2 unchanged; no new datastore or cache | PASS |
| II. Security by Default | Deny-by-default retained; every surface now requires a service-client token carrying the matching permission (`partner-access` / `internal-access`), decided solely from the validated token (FR-001–FR-004, FR-009–FR-011); `X-User-ID` is a request parameter the already-authorized internal BFF supplies, not an input to the authorization decision itself (FR-007) — see research.md D2 for the explicit trust-boundary statement this principle requires | PASS |
| III. Test-First | tasks.md will order a failing `*IT`/unit test before each production change | PASS (to verify at tasks time) |
| IV. Integration Testing & Mocks | Extends the existing `PartnerLinkAuthorizationIT` real-Keycloak pattern; no external API is touched by this feature, so no new mock is needed | PASS |
| V. RESTful API Design | Verbs, payloads, and envelopes unchanged; the one path change replaces the misleading `/api/v1/me/partner-links` with the resource-named `/api/v1/user-partner-links` (FR-018), which better fits the principle now that the caller is never the resource owner; `X-User-ID` is a request header, consistent with existing `x-service-id` precedent | PASS |
| VI. Error Handling | Reuses existing `ProblemType.FORBIDDEN` (permission denied) and adds the existing `ProblemType.BAD_REQUEST` for an invalid `X-User-ID` (FR-006) — no new problem type | PASS |
| VII. Structured Logging | Reuses existing `SecurityEvents.accessDenied()` logged by the shared `ProblemAccessDeniedHandler`, which already fires for every 403 on every surface (FR-013); no new logging code needed | PASS |
| VIII. Current Dependencies | No dependency changes | PASS |
| IX. Liquibase | No schema change (see Storage above); N/A | PASS |
| X. Clean Architecture | New `X-User-ID` parsing lives in one adapter-layer `HandlerMethodArgumentResolver`, never in application/domain code; renamed controller stays a thin translator; repository method rename is adapter + port only | PASS |
| XI. Messaging | Not touched by this feature | N/A |
| XII. Standard Implementations | Reuses Spring Security's `AuthorizationManager`/`RequestAuthorizationContext` and Spring MVC's `HandlerMethodArgumentResolver`, the same mechanisms `PartnerCallerResolver` already uses | PASS |
| XIII. Resilient HTTP Calls | No outbound HTTP call is added | N/A |

**Post-Phase-1 re-check**: research.md (D1–D5), data-model.md, and contracts/access-control.md
were written against this table with no new technology, dependency, schema, or external call
introduced, and no duplicated validation/authorization logic — the table above still holds
without change.

## Project Structure

### Documentation (this feature)

```text
specs/004-bff-role-authorization/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── access-control.md   # Phase 1 output: permission/endpoint/caller matrix
└── tasks.md              # Phase 2 output (/speckit-tasks — not created here)
```

### Source Code (repository root)

Single Spring Boot project (existing layout; no new module). Changes land in:

```text
src/main/java/com/nox/
├── adapter/in/web/partnerlink/
│   ├── PartnerLinkController.java        # renamed from MyPartnerLinkController.java;
│   │                                     # @RequestMapping /api/v1/me/partner-links → /api/v1/user-partner-links
│   ├── PartnerCallerResolver.java        # role constant renamed nox-bff → partner-access
│   ├── InternalCallerResolver.java       # new: SERVICE + internal-access check (research.md D1)
│   └── ActingUserIdArgumentResolver.java # new: @ActingUser UUID parameter (research.md D3)
├── adapter/in/web/security/
│   └── CallerTypeAuthorization.java      # removed — superseded by InternalCallerResolver
├── adapter/out/persistence/partnerlink/
│   ├── PartnerLinkJpaRepository.java     # UserKeycloakId queries → UserId
│   └── JpaPartnerLinkRepository.java     # same rename, adapter side
├── application/port/out/partnerlink/PartnerLinkRepository.java  # port method rename
├── application/service/partnerlink/
│   ├── ListMyPartnerLinksService.java    # param userKeycloakId → userId
│   ├── GetMyPartnerLinkService.java      # same
│   ├── GetMyPartnerLinkHistoryService.java # same
│   └── PartnerLinkDecider.java           # same (approve/reject/revoke)
├── application/port/in/partnerlink/
│   ├── ListMyPartnerLinksUseCase.java, GetMyPartnerLinkUseCase.java,
│   │   GetMyPartnerLinkHistoryUseCase.java  # signatures renamed userKeycloakId → userId
│   │   ApprovePartnerLinkUseCase.java, RejectPartnerLinkUseCase.java,
│   │   RevokePartnerLinkUseCase.java        # same rename, javadoc updated
└── infrastructure/config/
    └── SecurityConfig.java               # USER_PARTNER_LINKS matcher → /api/v1/user-partner-links/**,
                                          # wires InternalCallerResolver on it

src/test/java/com/nox/
├── adapter/in/web/partnerlink/           # MyPartnerLinkController*Test.java → PartnerLinkController*Test.java
└── integration/
    └── PartnerLinkAuthorizationIT.java   # extended: internal-access caller, X-User-ID cases
```

**Structure Decision**: No new module or package family; all changes stay inside the existing
`adapter.in.web.partnerlink`, `adapter.out.persistence.partnerlink`, and
`application.*.partnerlink` packages, following the hexagonal layering already in place
(Principle X). No Option 2/3 structure (web app or mobile) applies — this is the existing single
Spring Boot service.

## Complexity Tracking

*No Constitution Check violations; table intentionally omitted.*
