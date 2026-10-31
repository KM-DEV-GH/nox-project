# Phase 1 Data Model: BFF Role-Based Access

This feature changes **access control**, not persisted data: no table, column, or Liquibase
changeset is added or altered. The conceptual model below is the access-control vocabulary used
by the plan and tasks; the one storage change is a query-key swap on an already-existing column
(research.md D4).

## Concept: Calling Front-End Service

A service client that calls NOX for someone else. Never a person's own token (FR-004).

| Field | Notes |
|---|---|
| `clientId` | `nox-bff` (partner surface) or `bff` (user-link surface); the client identity that authenticated, unrelated to the permission name it must hold |
| `callerType` | Always `CallerType.SERVICE` for these three surfaces (FR-001–FR-004) |
| `roles` | Set of Keycloak role names on the token; checked for the one permission the target surface requires (D1, D5) |

## Concept: Access Permission

A named role carried in the access token, checked by `ServiceCallerAuthorization.requiresRole`
(research.md D1).

| Permission | Opens | Checked by |
|---|---|---|
| `partner-access` | Link requests (FR-001), link queries (FR-002) | `PartnerCallerResolver` |
| `internal-access` | User links (FR-003) | `InternalCallerResolver` |

A caller holding one permission gets no access to the other surface (FR-009); holding both grants
exactly the union, nothing more (spec Edge Cases).

## Concept: Acted-For Partner (unchanged)

Resolved exactly as today by `PartnerCallerResolver` from `x-service-id`, scoped to
`Partner.id`/`clientId`/`status`/validity window (FR-012; see 002's `data-model.md` for the
`Partner` entity, untouched here).

## Concept: Acted-For Person

| Field | Type | Notes |
|---|---|---|
| `userId` (`X-User-ID`) | UUID, required | Internal user id, already stored as `PartnerLink.userId` on every link; resolved by `ActingUserIdArgumentResolver` (research.md D3) only once `internal-access` has already admitted the request (D2) |

**Validation rules** (FR-006): absent, blank, or not a syntactically valid UUID → `400
bad_request` before any use case runs. Not found / belongs to someone else → the existing
per-endpoint behavior (empty list, or `404 not_found`) — unchanged from today's
`userKeycloakId`-keyed behavior, just re-keyed on `userId` (FR-008).

This is **distinct** from `CallerIdentity.subjectId()` (the calling BFF's own Keycloak subject),
which is never used to scope a user-link query for this surface (FR-005).

## Changed query keys (`PartnerLink`, existing entity/table — no schema change)

| Before (this feature) | After |
|---|---|
| `findAllByUserKeycloakId(String userKeycloakId)` | `findAllByUserId(String userId)` |
| `findByIdAndUserKeycloakId(UUID id, String userKeycloakId)` | `findByIdAndUserId(UUID id, String userId)` |

Both target `PartnerLink.userId` (and the already-indexed `partner_link.user_id` column), the
same column `findActiveByUserAndPartner` already queries on the write path.

## Audit/observability entity (existing, reused)

No new audit record type. FR-013's "authorization failure event" is the existing structured log
line emitted once, centrally, by `ProblemAccessDeniedHandler` → `SecurityEvents.accessDenied()`
for every `403` on any secured path — already covers all three surfaces without change, since the
handler is wired once for the whole filter chain. FR-014's audit attribution (user link history
entries recording the acting person) already uses `link.userId()` via
`PartnerLinkHistoryEntry.of(link, from, ActorType.USER, link.userId(), reason)`
(`PartnerLinkDecider`); after D4 that value is the same `userId` resolved from `X-User-ID`, so no
change is needed there either.
