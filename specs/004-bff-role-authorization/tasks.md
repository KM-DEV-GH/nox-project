---
description: "Task list for BFF Role-Based Access Control"
---

# Tasks: BFF Role-Based Access Control

**Input**: Design documents from `/specs/004-bff-role-authorization/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/access-control.md, quickstart.md

**Tests**: Included — every behavior change is covered by a test at the narrowest level that proves it
(unit test for a resolver/argument-resolver, integration test for the caller matrix), matching this
repo's existing `PartnerCallerResolverTest` / `PartnerLinkAuthorizationIT` conventions.

**Organization**: Tasks are grouped by user story (spec.md priorities P1/P2/P2/P3) so each story can be
implemented and verified independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Every task names the exact file(s) it touches

## Path Conventions

Single Maven project: production code under `src/main/java/com/nox/...`, tests under
`src/test/java/com/nox/...`, Keycloak realm fixtures under `src/test/resources/keycloak/` and
`docker/keycloak/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Give every story's tests a `bff` service client holding `internal-access`, alongside the
existing `nox-bff` client (renamed to hold `partner-access`), before any production code changes.

- [X] T001 In `src/test/resources/keycloak/nox-test-realm.json`: rename the `nox-api` client role
  `nox-bff` to `partner-access`; add a new `nox-api` client role `internal-access`; add a new
  client-credentials client `bff` (`serviceAccountsEnabled: true`, secret placeholder
  `${INTERNAL_BFF_SECRET}`, same `protocolMappers`/`defaultClientScopes` as the existing `nox-bff`
  client) with its own service-account user `service-account-bff` whose `clientRoles.nox-api` is
  `["internal-access"]`; change the existing `service-account-nox-bff` user's `clientRoles.nox-api`
  entry from `["nox-bff"]` to `["partner-access"]`
- [X] T002 [P] Add an `internalBffSecret` field (and its random-UUID generation) to
  `src/test/java/com/nox/integration/support/TestCredentials.java`
- [X] T003 Wire `.withEnv("INTERNAL_BFF_SECRET", credentials.internalBffSecret())` into
  `src/test/java/com/nox/integration/support/NoxKeycloakContainer.java` (depends on T002)
- [X] T004 Add an `internalBff()` client-credentials token method (client id `bff`) to
  `src/test/java/com/nox/integration/support/TestTokens.java`, mirroring the existing `bff()` method
  (depends on T001, T003)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Build the shared authorization mechanism — the two caller resolvers, the `@ActingUser`
header binding, and the `userId`-keyed repository/service layer — that every user story's behavior
depends on. No path or controller naming changes happen here (see US4); the old
`/api/v1/me/partner-links` mapping and its `SecurityConfig` matcher stay as-is until US4, so no window
exists where a path is unprotected while a different task renames it.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests for the foundation (write first, confirm they fail)

- [X] T005 [P] Write `src/test/java/com/nox/adapter/in/web/partnerlink/InternalCallerResolverTest.java`,
  mirroring `PartnerCallerResolverTest`: grants only a `CallerIdentity` with `callerType() == SERVICE`
  and authority `ROLE_internal-access`; denies a SERVICE caller without that authority; denies a USER
  caller even when its authorities include `ROLE_internal-access` (the Story 3 invariant); never reads
  `X-User-ID` and sets no request attribute
- [X] T006 [P] Write
  `src/test/java/com/nox/adapter/in/web/partnerlink/ActingUserIdArgumentResolverTest.java`:
  `supportsParameter` is true only for a `UUID` parameter annotated `@ActingUser`; `resolveArgument`
  returns `UUID.fromString(...)` for a well-formed `X-User-ID` header; throws
  `ProblemException(ProblemType.BAD_REQUEST)` when the header is missing, blank, or not a valid UUID
  (FR-005, FR-006)
- [X] T007 Update
  `src/test/java/com/nox/application/fake/InMemoryPartnerLinkRepository.java` to implement
  `findAllByUserId(String)` / `findByIdAndUserId(UUID, String)` (filtering on `link.userId()`) in place
  of `findAllByUserKeycloakId` / `findByIdAndUserKeycloakId` — every existing service unit test using
  this fake needs it to compile against the renamed port, so do this before the port rename below

### Implementation for the foundation

- [X] T008 Rename `PartnerCallerResolver.BFF_ROLE` from `"nox-bff"` to `"partner-access"` (and update
  its class javadoc) in
  `src/main/java/com/nox/adapter/in/web/partnerlink/PartnerCallerResolver.java`
- [X] T009 [P] Create
  `src/main/java/com/nox/adapter/in/web/partnerlink/InternalCallerResolver.java` implementing
  `AuthorizationManager<RequestAuthorizationContext>`: grants only for a SERVICE caller holding
  `ROLE_internal-access`; mirrors `PartnerCallerResolver`'s structure but never resolves a partner and
  never reads `X-User-ID` (research.md D1, D2) (implements T005)
- [X] T010 [P] Create the `@ActingUser` parameter annotation at
  `src/main/java/com/nox/adapter/in/web/partnerlink/ActingUser.java`
- [X] T011 Create
  `src/main/java/com/nox/adapter/in/web/partnerlink/ActingUserIdArgumentResolver.java` implementing
  `HandlerMethodArgumentResolver` per T006's cases, throwing
  `ProblemException(ProblemType.BAD_REQUEST)` on a missing/blank/invalid `X-User-ID` (research.md D3)
  (implements T006, depends on T010)
- [X] T012 Register `ActingUserIdArgumentResolver` via a new `WebMvcConfigurer` bean in
  `src/main/java/com/nox/infrastructure/config/WebMvcConfig.java` (depends on T011)
- [X] T013 Rename the two user-link query methods on the port —
  `findAllByUserKeycloakId(String)` → `findAllByUserId(String)`,
  `findByIdAndUserKeycloakId(UUID, String)` → `findByIdAndUserId(UUID, String)` — in
  `src/main/java/com/nox/application/port/out/partnerlink/PartnerLinkRepository.java` (depends on T007)
- [X] T014 Rename the matching Spring Data derived-query methods in
  `src/main/java/com/nox/adapter/out/persistence/partnerlink/PartnerLinkJpaRepository.java` (depends on
  T013)
- [X] T015 Update
  `src/main/java/com/nox/adapter/out/persistence/partnerlink/JpaPartnerLinkRepository.java` to
  implement the renamed port methods by delegating to the renamed JPA repository methods (depends on
  T013, T014)
- [X] T016 [P] Rename the `userKeycloakId` parameter to `userId` (and its javadoc, from "the subject of
  the caller's token" to "the internal user id supplied via X-User-ID") in
  `src/main/java/com/nox/application/port/in/partnerlink/ListMyPartnerLinksUseCase.java`,
  `GetMyPartnerLinkUseCase.java`, `GetMyPartnerLinkHistoryUseCase.java`, `ApprovePartnerLinkUseCase.java`,
  `RejectPartnerLinkUseCase.java`, `RevokePartnerLinkUseCase.java` (depends on T013)
- [X] T017 Update
  `src/main/java/com/nox/application/service/partnerlink/ListMyPartnerLinksService.java`,
  `GetMyPartnerLinkService.java`, `GetMyPartnerLinkHistoryService.java` to rename their parameter to
  `userId` and call `findAllByUserId` / `findByIdAndUserId` (depends on T015, T016)
- [X] T018 Update
  `src/main/java/com/nox/application/service/partnerlink/PartnerLinkDecider.java`'s `decide(...)` to
  rename its parameter to `userId` and call `findByIdAndUserId`; leave `record(...)`'s use of
  `link.userId()` for the history entry unchanged — it is already correct per research.md D4 (depends
  on T015, T016)
- [X] T019 [P] Update
  `src/main/java/com/nox/application/service/partnerlink/ApprovePartnerLinkService.java`,
  `RejectPartnerLinkService.java`, `RevokePartnerLinkService.java` to rename their parameter to `userId`
  (depends on T016, T018)
- [X] T020 In `src/main/java/com/nox/infrastructure/config/SecurityConfig.java`: add an
  `InternalCallerResolver` bean and constructor parameter, and replace
  `.access(CallerTypeAuthorization.usersOnly())` with `.access(internalCallerResolver)` on the
  **existing** `USER_PARTNER_LINKS = {"/api/v1/me/partner-links/**"}` matcher — the matcher's path
  string does not change in this phase, only the authorization rule (depends on T009)
- [X] T021 Delete
  `src/main/java/com/nox/adapter/in/web/security/CallerTypeAuthorization.java` (depends on T020)
- [X] T022 Update the round-trip assertions in
  `src/test/java/com/nox/integration/PartnerLinkPersistenceIT.java` to call
  `findAllByUserId(link.userId())` / `findByIdAndUserId(link.id(), link.userId())` (depends on T015)

**Checkpoint**: Foundation ready — both caller resolvers exist and are wired, the user-link surface is
served under `internal-access` + `X-User-ID` (still at its old path/class name), and all persistence
layers are `userId`-keyed. User story work can now begin.

---

## Phase 3: User Story 1 - Partner BFF keeps its own access (Priority: P1) 🎯 MVP

**Goal**: `nox-bff` continues to reach only the partner-facing surfaces, now under the renamed
permission `partner-access` instead of the role name `nox-bff` (FR-001–FR-004).

**Independent Test**: With only Phase 1+2 complete, a `nox-bff` client-credentials token (now carrying
`partner-access`) still succeeds on `partner-link-request-controller` and
`partner-link-query-controller` endpoints and still gets 403 on the user-link surface; a token without
`partner-access` is refused on the partner surfaces.

- [X] T023 [US1] In `src/test/java/com/nox/integration/PartnerLinkAuthorizationIT.java`, confirm (and
  update any comment/wording) that the existing test proving the BFF reaches only partner-facing
  endpoints passes unchanged now that `nox-bff`'s token carries `partner-access` (T001, T008) — this is
  a renamed permission, not a behavior change
- [X] T024 [P] [US1] Run
  `src/test/java/com/nox/adapter/in/web/partnerlink/PartnerCallerResolverTest.java` and confirm it
  passes unmodified — it already references `PartnerCallerResolver.BFF_ROLE` symbolically, never the
  literal string `"nox-bff"`, so T008's rename alone satisfies it

**Checkpoint**: User Story 1 is independently verified — the partner surface's behavior is unchanged
under its new permission name, with no dependency on US2/US3/US4.

---

## Phase 4: User Story 2 - Internal BFF drives decisions on behalf of a person (Priority: P2)

**Goal**: `bff` (holding `internal-access`) can approve/reject/revoke/list/view a person's partner
links by sending `X-User-ID`, with no person token ever accepted (FR-005–FR-011, FR-014).

**Independent Test**: A `bff` client-credentials token with a valid `X-User-ID` header succeeds on
every `MyPartnerLinkController` endpoint for that user id only; the same token without, with a blank,
or with a malformed `X-User-ID` gets 400; any person (user) token gets 403 regardless of header.

### Tests for User Story 2

- [X] T025 [P] [US2] Rewrite
  `src/test/java/com/nox/adapter/in/web/partnerlink/MyPartnerLinkControllerDecisionTest.java` to stop
  setting `SecurityContextHolder`'s `CallerAuthentication`/`ALICE` and instead `@Import` the
  `ActingUserIdArgumentResolver` (or `WebMvcConfig`) and send an `X-User-ID: <uuid>` header on every
  request, stubbing the use-case mocks with that same UUID string
- [X] T026 [P] [US2] Apply the same header-based rewrite to
  `src/test/java/com/nox/adapter/in/web/partnerlink/MyPartnerLinkControllerHistoryTest.java`
- [X] T027 [P] [US2] Apply the same header-based rewrite to
  `src/test/java/com/nox/adapter/in/web/partnerlink/MyPartnerLinkControllerRevokeTest.java`
- [X] T028 [US2] Add `getAsInternalBff(String userId, String path)` / `postAsInternalBff(...)` helpers
  to `src/test/java/com/nox/integration/support/PartnerLinkApi.java` that send
  `Authorization: Bearer <internalBff token>` plus `X-User-ID: <userId>` against the still-current
  `/api/v1/me/partner-links` path, replacing the old `getAsUser`/`postAsUser` helpers (depends on T004)
- [X] T029 [US2] Rewrite
  `src/test/java/com/nox/integration/PartnerLinkAuthorizationIT.java`'s
  `usersReachOnlyUserFacingEndpointsAndOnlyTheirOwnLinks()`: replace direct user tokens with
  `TOKENS.internalBff()` + `X-User-ID: <Link.userId()>`; assert the link owner's own id succeeds, an
  unrelated id is reported not-found, and — inverting the current assertion — a person's own token
  (e.g. `alice`) is now refused with 403 on every user-link endpoint, with or without `X-User-ID`
  (depends on T020, T028)
- [X] T030 [P] [US2] Add cases to `PartnerLinkAuthorizationIT` for FR-006: an otherwise-valid
  `internal-access` caller gets 400 when `X-User-ID` is missing, blank, or not a valid UUID (depends on
  T028)

### Implementation for User Story 2

- [X] T031 [US2] In
  `src/main/java/com/nox/adapter/in/web/partnerlink/MyPartnerLinkController.java`, change all six
  handler methods from `@AuthenticationPrincipal CallerIdentity caller` + `caller.subjectId()` to
  `@ActingUser UUID userId` + `userId.toString()` when calling the use-case ports, removing the
  now-unused `CallerIdentity`/`@AuthenticationPrincipal` imports (depends on T011, T017, T018, T019)

**Checkpoint**: User Story 2 is independently verified — the internal BFF can act for any person via
`X-User-ID`, still under the controller's current name and path (the rename is US4's job), independent
of US1/US3.

---

## Phase 5: User Story 3 - Only front-end services ever reach these surfaces (Priority: P2)

**Goal**: Prove the service-only invariant holds even for a person who happens to hold `partner-access`
or `internal-access` directly, and that every denial is still audited (FR-012, FR-013, FR-015–FR-017).

**Independent Test**: A person (`CallerType.USER`) token carrying `partner-access` and/or
`internal-access` is refused with 403 on every surface; one `access denied` log line (no token
contents) is recorded per refusal.

- [X] T032 [P] [US3] Confirm `InternalCallerResolverTest` (T005) already asserts a USER-type caller
  holding `internal-access` is denied; if the case is missing, add it here —
  `src/test/java/com/nox/adapter/in/web/partnerlink/InternalCallerResolverTest.java`
- [X] T033 [US3] In `src/test/resources/keycloak/nox-test-realm.json`, add `partner-access` and
  `internal-access` to the existing `alice` user's `clientRoles.nox-api` list (she already has a
  password-grant flow available via `TOKENS.user(...)`), then in
  `src/test/java/com/nox/integration/PartnerLinkAuthorizationIT.java` add a case proving alice's own
  (person) token is refused with 403 on every one of the three surfaces despite holding both
  permissions (Story 3 Acceptance Scenario 2)
- [X] T034 [P] [US3] In `PartnerLinkAuthorizationIT`, using `CapturedLogs.of(SecurityEvents.class)` (as
  `ProblemAccessDeniedHandlerTest` already does), assert exactly one `access denied` log event is
  recorded for a 403 on each of the three surfaces (`partner-link-request-controller`,
  `partner-link-query-controller`, user-link surface) — note that
  `ProblemAccessDeniedHandlerTest` already proves this generically (FR-013) and needs no change; this
  task only ties that proof to the concrete endpoints

**Checkpoint**: User Story 3 is independently verified — holding a permission is not enough without a
service-typed caller, on any surface, and denials are always audited.

---

## Phase 6: User Story 4 - Consistent, intention-revealing names (Priority: P3)

**Goal**: Rename `MyPartnerLinkController` to `PartnerLinkController` and move its surface from
`/api/v1/me/partner-links` to `/api/v1/user-partner-links`, with the old prefix returning 404/401 and
no alias (FR-018, FR-019).

**Independent Test**: `/api/v1/user-partner-links/**` behaves exactly as the old
`/api/v1/me/partner-links/**` did after US2; the old prefix no longer resolves to any controller.

### Tests for User Story 4

- [X] T035 [P] [US4] Rename
  `src/test/java/com/nox/adapter/in/web/partnerlink/MyPartnerLinkControllerDecisionTest.java` to
  `PartnerLinkControllerDecisionTest.java`, updating `@WebMvcTest(controllers = ...)` to
  `PartnerLinkController.class`
- [X] T036 [P] [US4] Rename
  `src/test/java/com/nox/adapter/in/web/partnerlink/MyPartnerLinkControllerHistoryTest.java` to
  `PartnerLinkControllerHistoryTest.java`, with the same `@WebMvcTest` update
- [X] T037 [P] [US4] Rename
  `src/test/java/com/nox/adapter/in/web/partnerlink/MyPartnerLinkControllerRevokeTest.java` to
  `PartnerLinkControllerRevokeTest.java`, with the same `@WebMvcTest` update
- [X] T038 [US4] Add cases to `PartnerLinkAuthorizationIT` for FR-019: every old
  `/api/v1/me/partner-links...` path now returns 404 for an authenticated `internal-access` caller
  (and 401 with no token, per the existing security-filter ordering) (depends on T039, T040)

### Implementation for User Story 4

- [X] T039 [US4] Rename `MyPartnerLinkController.java` (class + file) to
  `src/main/java/com/nox/adapter/in/web/partnerlink/PartnerLinkController.java`, changing
  `@RequestMapping("/api/v1/me/partner-links")` to `@RequestMapping("/api/v1/user-partner-links")`
  (depends on T031)
- [X] T040 [US4] In `src/main/java/com/nox/infrastructure/config/SecurityConfig.java`, move the
  `USER_PARTNER_LINKS` matcher from `{"/api/v1/me/partner-links/**"}` to
  `{"/api/v1/user-partner-links/**"}`, done in lockstep with T039 so the two are never out of sync
  (depends on T039)
- [X] T041 [P] [US4] Rename `PartnerLinkApi.MY_LINKS` to
  `USER_LINKS = "/api/v1/user-partner-links"` and repoint `getAsInternalBff`/`postAsInternalBff` at it
  — `src/test/java/com/nox/integration/support/PartnerLinkApi.java` (depends on T028)
- [X] T042 [P] [US4] Update `Link.path()` to build from `PartnerLinkApi.USER_LINKS` —
  `src/test/java/com/nox/integration/support/LinkScenarios.java`

**Checkpoint**: User Story 4 is independently verified — the surface has its final name and path, and
the old prefix is fully retired. All four user stories are now complete.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T043 [P] Apply the same role-rename and new-client changes as T001 to
  `docker/keycloak/nox-dev-realm.json` (its `nox-dev-bff` client currently holds `nox-bff`), so the
  manual `quickstart.md` walkthrough works against the dev compose stack
- [X] T044 Run `./mvnw verify` for the full suite and fix any regressions surfaced by the renames above
- [ ] T045 Walk through `specs/004-bff-role-authorization/quickstart.md`'s manual curl steps against the
  local dev stack (after T043) and confirm each step's expected status code
- [X] T046 [P] Run `src/test/java/com/nox/architecture/ArchitectureTest.java` and confirm it passes
  unmodified — the new `InternalCallerResolver`/`ActingUser*`/`WebMvcConfig` classes already sit in
  packages the existing rules allow
- [X] T047 [P] Grep the repo for any remaining `/api/v1/me/partner-links` or `nox-bff`-as-role-name
  references outside of intentional retirement notes (`spec.md`, `contracts/access-control.md`,
  `quickstart.md`) and update anything stale

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup (needs the `bff`/`internal-access` fixtures for its
  tests) — BLOCKS all user stories
- **User Stories (Phase 3-6)**: All depend on Foundational completion
  - US1 has no dependency on US2/US3/US4
  - US2 has no dependency on US1/US3/US4 (US4 depends on US2's controller existing in its final form)
  - US3 has no dependency on US1/US2/US4 beyond the resolvers Foundational already built
  - US4 depends on US2 (T039 renames the controller T031 modified) but not on US1/US3
- **Polish (Phase 7)**: Depends on all four user stories being complete

### User Story Dependencies

- **US1 (P1)**: Foundational only
- **US2 (P2)**: Foundational only
- **US3 (P2)**: Foundational only
- **US4 (P3)**: Foundational + US2 (renames the controller US2 modified; everything else is independent)

### Within Each User Story

- Tests are written and confirmed to fail before their implementation task
- Port/service renames before controller changes that call them
- Story complete before its checkpoint is declared

### Parallel Opportunities

- T002 (Setup) can run alongside T001
- T005, T006 (Foundational tests) can run in parallel; T009, T010 (Foundational implementation) can run
  in parallel once their tests exist
- T016, T019 (parameter renames across sibling files) can run in parallel
- Once Foundational completes, US1 and US3 can be fully executed in parallel with US2; US4 must wait for
  US2's T031
- T025, T026, T027 (the three `@WebMvcTest` rewrites in US2) can run in parallel
- T035, T036, T037, T041, T042 (US4 renames across independent files) can run in parallel

---

## Parallel Example: Foundational Phase

```bash
# Tests first, in parallel:
Task: "Write InternalCallerResolverTest in src/test/java/com/nox/adapter/in/web/partnerlink/InternalCallerResolverTest.java"
Task: "Write ActingUserIdArgumentResolverTest in src/test/java/com/nox/adapter/in/web/partnerlink/ActingUserIdArgumentResolverTest.java"

# Then implementation, in parallel:
Task: "Create InternalCallerResolver in src/main/java/com/nox/adapter/in/web/partnerlink/InternalCallerResolver.java"
Task: "Create @ActingUser annotation in src/main/java/com/nox/adapter/in/web/partnerlink/ActingUser.java"
```

## Parallel Example: User Story 2

```bash
Task: "Rewrite MyPartnerLinkControllerDecisionTest for X-User-ID"
Task: "Rewrite MyPartnerLinkControllerHistoryTest for X-User-ID"
Task: "Rewrite MyPartnerLinkControllerRevokeTest for X-User-ID"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: `nox-bff` still works end to end under `partner-access`
5. Deploy/demo if ready — the partner surface's contract hasn't changed for its caller

### Incremental Delivery

1. Setup + Foundational → both caller resolvers exist, nothing regresses
2. Add US1 → partner-facing permission rename verified independently
3. Add US2 → internal BFF can act on behalf of any person via `X-User-ID`
4. Add US3 → service-only invariant and audit coverage proven even against a permission-holding person
5. Add US4 → final naming/path cutover, old prefix retired
6. Each story adds value without breaking the previous ones
