---

description: "Task list for Partner Link Management API"
---

# Tasks: Partner Link Management API

**Input**: Design documents from `/specs/002-pam-account-linking/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi.yaml, contracts/events.md, quickstart.md

**Tests**: Included, and every production task is preceded by the failing test that drives it —
constitution Principle III (Test-First) is non-negotiable, and Principle IV requires integration
tests against real PostgreSQL and RabbitMQ with only Owl mocked (WireMock).

**Organization**: Grouped by user story (spec.md priorities). Regenerated 2026-10-26 after the
`/speckit-analyze` remediation.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US5
- Paths are relative to the repository root; Java sources under `src/main/java/com/nox/`, tests under `src/test/java/com/nox/`

---

## Phase 1: Setup (Shared Infrastructure)

- [X] T001 Add production dependencies to `pom.xml`: `spring-boot-starter-data-jpa`, `org.postgresql:postgresql`, `org.liquibase:liquibase-core`, `spring-boot-starter-amqp`, `spring-boot-starter-restclient`, `spring-boot-starter-oauth2-client` (Owl client-credentials token, research.md decision 10), `org.springframework.retry:spring-retry`, and a `spring-modulith-bom` import (`2.1.2`; re-verify it is the latest stable at implementation time, Principle VIII) with `spring-modulith-starter-jpa` and `spring-modulith-events-amqp`
- [X] T002 [P] Add test dependencies to `pom.xml`: `org.testcontainers:postgresql`, `org.testcontainers:rabbitmq` (managed by the existing `testcontainers-bom`), a WireMock Testcontainers module (latest stable), `org.springframework.modulith:spring-modulith-starter-test`
- [X] T003 [P] Add `postgres` and `rabbitmq` services to `compose.yaml` (credentials required from environment variables, like the existing `keycloak` service) and `depends_on` entries for `app` in `compose.dev.yaml`
- [X] T004 [P] Add configuration to `src/main/resources/application.yaml` and the `dev`/`stage`/`prod` profiles: `nox.partner-link.approval-window`, `nox.partner-link.owl.base-url`, `nox.partner-link.owl.connect-timeout`, `nox.partner-link.owl.read-timeout`, `nox.partner-link.owl.max-attempts`, `nox.partner-link.owl.backoff`, `nox.partner-link.owl.api-key` (bound only from the secret `NOX_OWL_API_KEY`), `nox.partner-link.stuck-publication-age`; the OAuth2 client registration `spring.security.oauth2.client.registration.owl` (`client_credentials`, client id per environment, client secret only from `NOX_OWL_CLIENT_SECRET`) and its provider `token-uri`; `spring.datasource.*`, `spring.rabbitmq.*` (publisher confirms on), `spring.liquibase.*`, `spring.modulith.events.republish-outstanding-events-on-restart=true` — all environment-specific values externalized
- [X] T005 [P] Create `src/main/resources/db/changelog/db.changelog-master.yaml`, set `spring.liquibase.change-log` to it and `spring.jpa.hibernate.ddl-auto=validate` (Principle IX)
- [X] T006 [P] Add the Keycloak client role `nox-bff` (on the `nox-api` audience) and a client-credentials client that holds it to `docker/keycloak/nox-dev-realm.json` and to the test realm under `src/test/resources/keycloak/` (research.md decision 6)

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests for the foundation (write first, confirm they fail) ⚠️

- [X] T007 [P] Amend `src/test/java/com/nox/architecture/ArchitectureTest.java`: the application layer may depend on `java..`, `com.nox.domain..`, `com.nox.application..` and **only** `org.springframework.transaction.annotation..` (plan.md Complexity Tracking); add a rule that `com.nox.domain..` and `com.nox.application.event..` depend on no `org.springframework..` package
- [X] T008 [P] Write `src/test/java/com/nox/integration/LiquibaseSchemaIT.java` (Testcontainers PostgreSQL): the application context starts with `ddl-auto=validate`; a second `PENDING` or `CONNECTED` row for the same `(user_id, partner_id)` is rejected by the partial unique index; a duplicate `(partner_id, idempotency_key)` is rejected
- [X] T009 [P] Write `src/test/java/com/nox/domain/partnerlink/PartnerLinkTest.java`: `approve(now)` on `PENDING` before `expires_at` → `CONNECTED`/`Approved`; at or after `expires_at` → `EXPIRED`/`Expired`; `reject(now)` likewise → `REJECTED`/`Rejected` or `Expired`; `revoke()` only from `CONNECTED`, otherwise `NotActionable`; approve/reject on a decided link → `NotActionable`; `effectiveStatus(now)` returns `EXPIRED` for `PENDING` at or after `expires_at` without mutating
- [X] T010 [P] Write `src/test/java/com/nox/domain/partnerlink/PartnerLinkRequestTest.java` covering every row of the "Reported status" table in data-model.md (`PROCESSING`/`EXPIRED` with and without a link; terminal link statuses passed through)
- [X] T011 [P] Write `src/test/java/com/nox/domain/partner/PartnerTest.java`: `isCallableAt(now)` true only for `ACTIVE` within `[valid_from, valid_to)`
- [X] T012 [P] Write `src/test/java/com/nox/integration/PartnerLinkPersistenceIT.java` (Testcontainers PostgreSQL): round-trip of every entity; concurrent update of the same `PartnerLink` fails one writer with an optimistic-lock conflict; "most recent link by `(partner_id, partner_external_id)`" returns the latest `created_at`
- [X] T013 [P] Write `src/test/java/com/nox/application/service/PartnerLinkTransitionTest.java` (plain JUnit, fake ports): a transition saves the link, appends exactly one `PartnerLinkHistoryEntry` with the given actor and reason, and publishes exactly the events passed to it — none for revoke or expiry
- [X] T014 [P] Write `src/test/java/com/nox/integration/PartnerLinkEventPublicationIT.java` (Testcontainers PostgreSQL + RabbitMQ; test-only queues bound to `partnerlink.events` and `partnerlink.webhooks`): a rolled-back transition leaves no `event_publication` row; a committed one is delivered once to the right exchange and routing key with `message_id`, `correlation_id`, `type` properties and a `traceId` header, and a body matching contracts/events.md; with the broker stopped then restarted the message is delivered after recovery
- [X] T015 [P] Write `src/test/java/com/nox/integration/PartnerLinkSecurityRulesIT.java`: on `/api/v1/partner-link-requests/**` and `/api/v1/partner-links/**`, a SERVICE token without `nox-bff` → 403 and a USER token → 403; on `/api/v1/me/partner-links/**`, a SERVICE token → 403; a `nox-bff` call whose `x-service-id` is missing, unknown, `SUSPENDED`, or outside its validity window → 403
- [X] T016 [P] Write `src/test/java/com/nox/adapter/in/web/partnerlink/PartnerCallerResolverTest.java`: resolves `x-service-id` to a callable `Partner` only for a caller holding `nox-bff`; never reads the header otherwise

### Implementation for the foundation

- [X] T017 Create changesets (each with a rollback) under `src/main/resources/db/changelog/` for `partner` and `partner_history` exactly as in data-model.md: `partner.client_id` unique NOT NULL; `partner.status` constrained to `ACTIVE`/`SUSPENDED`/`DEREGISTERED`; `valid_to` nullable; `partner_history` append-only
- [X] T018 Create the `partner_link` changeset: `id` UUID PK; `user_id` VARCHAR NOT NULL; `user_keycloak_id` VARCHAR NOT NULL; `partner_id` FK NOT NULL; `partner_external_id` VARCHAR(128) NOT NULL; `licensee_id` VARCHAR(64) NOT NULL; `status` constrained to `PENDING`/`CONNECTED`/`REJECTED`/`EXPIRED`/`REVOKED`/`SUSPENDED`; `expires_at` NOT NULL; `version` BIGINT NOT NULL; timestamps; partial unique index on `(user_id, partner_id)` WHERE `status IN ('PENDING','CONNECTED')`; indexes on `(partner_id, partner_external_id, created_at DESC)` and `(user_keycloak_id)`; rollback
- [X] T019 Create the `partner_link_request` changeset: `id` UUID PK; `partner_id` FK NOT NULL; `idempotency_key` VARCHAR(128) NOT NULL; `partner_external_id` NOT NULL; `licensee_id` NOT NULL; `email` VARCHAR(254) NULL; `lookup_status` constrained to `PENDING`/`RESOLVED`/`NOT_FOUND`/`FAILED`; `link_id` FK → `partner_link(id)` NULL; `correlation_id` UUID NOT NULL; `submitted_at`, `expires_at` NOT NULL; unique `(partner_id, idempotency_key)`; rollback
- [X] T020 Create the `partner_link_history` changeset (`from_status` NULL, `to_status`, `actor_type` constrained to `USER`/`PARTNER`/`NOX`, `actor_reference` NULL, `occurred_at`, `reason` constrained to the six values in data-model.md) and the `partner_link_stuck_publication` changeset (`event_publication_id` unique NOT NULL, `event_type`, `flagged_at`); rollbacks
- [X] T021 Generate Spring Modulith's `event_publication` schema for PostgreSQL as a changeset (with rollback) and include it in the master changelog (depends on T001)
- [X] T022 [P] Add a changeset with Liquibase context `dev` seeding partner `demo-partner` (status `ACTIVE`), and a test fixture `src/test/java/com/nox/integration/support/PartnerFixtures.java` that inserts partners in each status for ITs
- [X] T023 [P] Implement `domain/partner/Partner.java`, `PartnerStatus.java`, `PartnerHistoryEntry.java` (makes T011 pass)
- [X] T024 [P] Implement `domain/partnerlink/PartnerLinkStatus.java`, `ActorType.java`, `LookupStatus.java`, `LinkDecisionOutcome.java` (`Approved`, `Rejected`, `Revoked`, `Expired`, `NotActionable`), `PartnerLinkHistoryEntry.java`, `PartnerLink.java`, `PartnerLinkRequest.java` — framework-free, `Instant now` passed in (makes T009, T010 pass)
- [X] T025 [P] Create outbound ports in `application/port/out/`: `PartnerRepository` (`findByClientId`), `PartnerLinkRequestRepository`, `PartnerLinkRepository` (`findActiveByUserAndPartner`, `findLatestByPartnerAndExternalId`, `findAllByUserKeycloakId`, `findByIdAndUserKeycloakId`), `PartnerLinkHistoryRepository`, `UserLookupPort` (email → `Found(userId, keycloakId)`, `NotFound`, or `Failed`, per contracts/owl-user-lookup.md), `DomainEventPublisherPort` (`publish(Object)`)
- [X] T026 [P] Create event records in `application/event/`: `PartnerLinkRequestSubmitted`, `PartnerLinkApprovalRequested`, `PartnerLinkApprovedForUser`, `PartnerLinkApprovedForPartner`, `PartnerLinkRejectedForUser`, `PartnerLinkRejectedForPartner`, with the fields in contracts/events.md plus `traceId` and `correlationId`; no Spring imports
- [X] T027 Implement JPA entities and repositories in `adapter/out/persistence/partnerlink/` implementing the T025 repository ports, with `@Version` on the link entity (makes T008, T012 pass; depends on T017–T021, T023–T025)
- [X] T028 Implement `adapter/out/messaging/partnerlink/SpringDomainEventPublisherAdapter.java` implementing `DomainEventPublisherPort` with Spring's `ApplicationEventPublisher`
- [X] T029 Implement `infrastructure/config/RabbitMqConfig.java`: declare only the durable topic exchanges `partnerlink.events` and `partnerlink.webhooks` — no queues, bindings or DLXs (research.md decision 3)
- [X] T030 Implement `infrastructure/config/PartnerLinkEventExternalizationConfig.java`: programmatic `EventExternalizationConfiguration` mapping each externalized event in T026 to its exchange and routing key (data-model.md table), the `{ data, meta }` envelope, and the `message_id`, `correlation_id`, `type` properties and `traceId` header; `PartnerLinkRequestSubmitted` is not externalized (makes T014 pass with T031)
- [X] T031 Implement `application/service/PartnerLinkTransition.java` (`@Transactional`): persist the link, append the history entry, publish the given events through `DomainEventPublisherPort` (makes T013 pass)
- [X] T032 Implement `infrastructure/config/PartnerLinkProperties.java` (`@ConfigurationProperties("nox.partner-link")`) and extend `infrastructure/config/UseCaseConfig.java` to construct the foundational adapters and `PartnerLinkTransition`, passing plain values — no adapter or service depends on `PartnerLinkProperties` (research.md decision 8)
- [X] T033 Implement `adapter/in/web/partnerlink/PartnerCallerResolver.java` and extend `infrastructure/config/SecurityConfig.java` with request matchers: Partner-facing prefixes require a SERVICE caller with `ROLE_nox-bff`; `/api/v1/me/partner-links/**` requires a USER caller; an unresolvable or non-callable `x-service-id` → 403 through the existing problem format (makes T015, T016 pass)

**Checkpoint**: `ArchitectureTest`, `LiquibaseSchemaIT`, `PartnerLinkPersistenceIT`, `PartnerLinkEventPublicationIT`, `PartnerLinkSecurityRulesIT` and the domain tests are green.

---

## Phase 3: User Story 1 - Partner requests a link (Priority: P1) 🎯 MVP

**Goal**: A partner submits a request and always gets the same `202`; NOX resolves the user asynchronously with bounded retry, creates a `PENDING` link only for a real user without an active link, and asks the user to decide.

**Independent Test**: Submit for a known user and for an unknown email; both responses are identical; only the known user gets a `PENDING` link and an `approval-requested` message.

### Tests for User Story 1 ⚠️

- [X] T034 [P] [US1] Create `src/test/java/com/nox/integration/support/OwlWireMockSupport.java`, following contracts/owl-user-lookup.md: an OAuth2 token endpoint stub returning a client-credentials token; `GET /api/v1/service/user` stubs that match only when both `Authorization: Bearer …` and `api-key: test-api-key` are present, returning one `APPROVED` item, zero items, one non-`APPROVED` item, two items, `success: false`, 401, 5xx, and a delayed response for timeouts. Placeholder credentials only — never real keys
- [X] T035 [P] [US1] Write `src/test/java/com/nox/adapter/in/web/partnerlink/PartnerLinkRequestControllerCreateTest.java`: `202` body matches `LinkRequestAcceptedResponse`; missing/invalid `externalId`, `email`, `licenseeId`, or unknown properties → `400`; missing `Idempotency-Key` → `400`
- [X] T036 [P] [US1] Write `src/test/java/com/nox/application/service/SubmitPartnerLinkRequestServiceTest.java`: persists the request with `lookup_status=PENDING` and `expires_at = submitted_at + window`; publishes `PartnerLinkRequestSubmitted`; never calls `UserLookupPort`; a repeated `Idempotency-Key` returns the original `requestId` without a new row
- [X] T037 [P] [US1] Write `src/test/java/com/nox/application/service/ResolvePartnerLinkRequestServiceTest.java`: user found, no active link → `PENDING` link with the request's `expires_at`, `user_id` and `user_keycloak_id` from the lookup, history `link_created` (actor PARTNER), `PartnerLinkApprovalRequested`, request `RESOLVED` with `link_id`, email cleared; user found with an active link → `link_id` points at it, no new link or event; user found with a `PENDING` link past its deadline → that link written `EXPIRED` (`expired_on_new_request`, actor NOX) then a new link; not found → `NOT_FOUND`, email cleared, nothing else; lookup failed → `FAILED`, email cleared
- [X] T038 [P] [US1] Write `src/test/java/com/nox/adapter/out/userlookup/OwlUserLookupAdapterTest.java` (WireMock, every row of the mapping table in contracts/owl-user-lookup.md): the request sends the email URL-encoded with `page=1&page_size=2&direction=DESC` and both the bearer token and `api-key` headers; one `APPROVED` item → `Found(userId, keycloakId)`; zero items, one non-`APPROVED` item, or two items → `NotFound`; `success: false`, 5xx and timeouts retried up to `max-attempts` with backoff, then `Failed`; 401/403 and other 4xx → `Failed` without retry; the email, query string, api-key and token never appear in captured logs
- [X] T039 [P] [US1] Write `src/test/java/com/nox/integration/PartnerLinkRequestCreationIT.java`: known user, unknown email, existing active link, idempotency replay — responses identical in status and body shape; link and `partner-link.approval-requested.v1` message only for the known user (FR-001–FR-006, SC-002, SC-007)
- [X] T040 [P] [US1] Write `src/test/java/com/nox/integration/PartnerLinkRequestOwlOutageIT.java`: Owl times out then recovers → response unchanged, link created after recovery; Owl down past `max-attempts` → request `FAILED`, no link (FR-002)

### Implementation for User Story 1

- [X] T041 [US1] Create `application/port/in/SubmitPartnerLinkRequestUseCase.java` and `ResolvePartnerLinkRequestUseCase.java`
- [X] T042 [US1] Implement `adapter/out/userlookup/OwlUserLookupAdapter.java`: `RestClient` with constructor-supplied base URL, timeouts and api-key (default `api-key` header), Spring Security's `OAuth2ClientHttpRequestInterceptor` for the `owl` client registration, response mapping exactly as contracts/owl-user-lookup.md, `@Retryable` on transient failures only with constructor-supplied attempts/backoff, `@Recover` returning `Failed`; never logs the request URL (makes T038 pass)
- [X] T043 [US1] Implement `application/service/SubmitPartnerLinkRequestService.java` (`@Transactional`) (makes T036 pass)
- [X] T044 [US1] Implement `application/service/ResolvePartnerLinkRequestService.java` (`@Transactional`), using `PartnerLinkTransition` for both the superseding `EXPIRED` and the new link (makes T037 pass)
- [X] T045 [US1] Implement `adapter/in/event/PartnerLinkRequestSubmittedListener.java` with `@ApplicationModuleListener`, calling `ResolvePartnerLinkRequestUseCase`
- [X] T046 [US1] Implement the `POST /api/v1/partner-link-requests` handler in `adapter/in/web/partnerlink/PartnerLinkRequestController.java`, using `PartnerCallerResolver` for the partner (makes T035 pass)
- [X] T047 [US1] Wire `SubmitPartnerLinkRequestService`, `ResolvePartnerLinkRequestService`, `OwlUserLookupAdapter` and the listener in `infrastructure/config/UseCaseConfig.java` (makes T039, T040 pass)

**Checkpoint**: Partners can submit requests; enumeration-safe; Owl outages tolerated.

---

## Phase 4: User Story 2 - User approves or rejects (Priority: P1)

**Goal**: The user approves (`CONNECTED`) or rejects (`REJECTED`) a `PENDING` link before its deadline, with history and both messages; late decisions commit `EXPIRED` and return `409 link_expired`.

**Independent Test**: Approve one pending link and reject another; check status, history and messages; repeat with the clock past the deadline and check `409` plus a committed `EXPIRED`.

### Tests for User Story 2 ⚠️

- [X] T048 [P] [US2] Write `src/test/java/com/nox/adapter/in/web/partnerlink/MyPartnerLinkControllerDecisionTest.java`: approve/reject → `200` `UserLinkResponse`; outcome `Expired` → `409 urn:problem:link_expired`; `NotActionable` → `409 urn:problem:link_not_actionable`; not owned or unknown → `404`
- [X] T049 [P] [US2] Write `src/test/java/com/nox/application/service/ApprovePartnerLinkServiceTest.java` and `RejectPartnerLinkServiceTest.java`: correct outcome per state and deadline; `Expired` path writes `EXPIRED` (`expired_on_late_action`, actor NOX) with no events and returns normally (no exception); approve publishes `PartnerLinkApprovedForUser` + `PartnerLinkApprovedForPartner` (with `linkId`); reject publishes the two rejected events; link whose `user_keycloak_id` is not the caller's token subject → not found; an optimistic-lock conflict is retried once against the re-read link
- [X] T050 [P] [US2] Write `src/test/java/com/nox/integration/PartnerLinkApprovalIT.java` and `PartnerLinkRejectionIT.java`: status, one history entry, both messages on the right exchanges with the payloads in contracts/events.md, broker-confirmed within 5 s (SC-006)
- [X] T051 [P] [US2] Write `src/test/java/com/nox/integration/PartnerLinkExpiryIT.java` (fixed `Clock`): late approve and late reject → `409 link_expired`, link stored `EXPIRED` after the response, exactly one NOX history entry, no messages; an undecided link past its deadline reads as `EXPIRED` with no history entry
- [X] T052 [P] [US2] Write `src/test/java/com/nox/integration/PartnerLinkConcurrencyIT.java`: a late approve racing a superseding request for the same pair yields one `EXPIRED` transition and one history entry; two concurrent approvals yield one `CONNECTED` and one `NotActionable`

### Implementation for User Story 2

- [X] T053 [US2] Create `application/port/in/ApprovePartnerLinkUseCase.java` and `RejectPartnerLinkUseCase.java` returning `LinkDecisionOutcome`
- [X] T054 [US2] Implement `application/service/ApprovePartnerLinkService.java` (`@Transactional`): load by `linkId` and `user_keycloak_id` (the caller's token subject), apply `PartnerLink.approve(now)`, record through `PartnerLinkTransition`, return the outcome without throwing; one retry on optimistic-lock conflict
- [X] T055 [US2] Implement `application/service/RejectPartnerLinkService.java` the same way with `PartnerLink.reject(now)` (makes T049 pass)
- [X] T056 [US2] Implement the approve/reject handlers in `adapter/in/web/partnerlink/MyPartnerLinkController.java`, passing the token subject as the user's Keycloak id, and add problem types `link_expired` and `link_not_actionable` to the existing problem catalogue (`adapter/in/web/error/ProblemType.java`) (makes T048 pass)
- [X] T057 [US2] Wire both services in `infrastructure/config/UseCaseConfig.java` (makes T050–T052 pass)

**Checkpoint**: Links reach `CONNECTED`/`REJECTED`; late decisions are refused and recorded.

---

## Phase 5: User Story 3 - User and partner check status (Priority: P1)

**Goal**: The partner polls requests and reads established links; the user lists and reads their links — all enumeration-safe.

**Independent Test**: Poll a request for a known and an unknown email across the deadline (identical responses); read an established link by id and by external id; list a user's links in every status.

### Tests for User Story 3 ⚠️

- [X] T058 [P] [US3] Write `src/test/java/com/nox/adapter/in/web/partnerlink/PartnerLinkQueryControllersTest.java`: `GET /api/v1/partner-link-requests/{requestId}`, `GET /api/v1/partner-links/{linkId}`, `GET /api/v1/partner-links/by-external/{externalId}`, `GET /api/v1/me/partner-links`, `GET /api/v1/me/partner-links/{linkId}` match their schemas in contracts/openapi.yaml; another partner's request → `404`
- [X] T059 [P] [US3] Write `src/test/java/com/nox/application/service/PartnerLinkQueryServicesTest.java`: request status follows data-model.md's reported-status table; partner reads return only `CONNECTED`/`REVOKED` links of the calling partner (everything else not found); by external id returns the most recently created link; the user's list and detail use `effectiveStatus(now)` and only links whose `user_keycloak_id` equals the caller's token subject
- [X] T060 [P] [US3] Write `src/test/java/com/nox/integration/PartnerLinkStatusQueryIT.java` (fixed `Clock`): known vs unknown email produce identical status responses before and after the deadline; outcome reported after approve/reject/revoke; partner reads of a `PENDING`/`REJECTED`/`EXPIRED` link → `404`; latest link by external id; the user list shows all statuses (FR-012–FR-014, SC-002, SC-003)

### Implementation for User Story 3

- [X] T061 [US3] Create `application/port/in/GetPartnerLinkRequestStatusUseCase.java`, `GetPartnerLinkForPartnerUseCase.java`, `ListMyPartnerLinksUseCase.java`, `GetMyPartnerLinkUseCase.java`
- [X] T062 [US3] Implement `application/service/GetPartnerLinkRequestStatusService.java` using `PartnerLinkRequest.reportedStatus(link, now)`
- [X] T063 [US3] Implement `application/service/GetPartnerLinkForPartnerService.java` (established links only; latest by external id)
- [X] T064 [US3] Implement `application/service/ListMyPartnerLinksService.java` and `GetMyPartnerLinkService.java` (makes T059 pass)
- [X] T065 [US3] Add the `GET` handler to `adapter/in/web/partnerlink/PartnerLinkRequestController.java` and create `adapter/in/web/partnerlink/PartnerLinkQueryController.java`
- [X] T066 [US3] Add the list and detail handlers to `adapter/in/web/partnerlink/MyPartnerLinkController.java` (makes T058 pass)
- [X] T067 [US3] Wire the four services in `infrastructure/config/UseCaseConfig.java` (makes T060 pass)

**Checkpoint**: Full read surface for partner and user.

---

## Phase 6: User Story 4 - User revokes (Priority: P2)

**Goal**: The user revokes a `CONNECTED` link; nothing else can be revoked; no messages are published.

**Independent Test**: Revoke a `CONNECTED` link → `REVOKED` with history and no messages; revoke again → `409 link_not_actionable`.

### Tests for User Story 4 ⚠️

- [X] T068 [P] [US4] Write `src/test/java/com/nox/adapter/in/web/partnerlink/MyPartnerLinkControllerRevokeTest.java`: `200` on success, `409 link_not_actionable` otherwise, `404` when not owned
- [X] T069 [P] [US4] Write `src/test/java/com/nox/application/service/RevokePartnerLinkServiceTest.java`: `CONNECTED` → `Revoked` with history `user_revoked` and **no** events; `PENDING`/`REJECTED`/`EXPIRED`/`REVOKED` → `NotActionable` (FR-016)
- [X] T070 [P] [US4] Write `src/test/java/com/nox/integration/PartnerLinkRevocationIT.java`: revoke → `REVOKED`, history entry, no message on either exchange; partner read afterwards shows `REVOKED` (FR-015, FR-016, SC-004)

### Implementation for User Story 4

- [X] T071 [US4] Create `application/port/in/RevokePartnerLinkUseCase.java`
- [X] T072 [US4] Implement `application/service/RevokePartnerLinkService.java` (`@Transactional`) (makes T069 pass)
- [X] T073 [US4] Add the revoke handler to `adapter/in/web/partnerlink/MyPartnerLinkController.java` (makes T068 pass)
- [X] T074 [US4] Wire the service in `infrastructure/config/UseCaseConfig.java` (makes T070 pass)

---

## Phase 7: User Story 5 - Auditing link history (Priority: P3)

**Goal**: The user retrieves the ordered history of written transitions for their link.

**Independent Test**: Create → approve → revoke, then read three ordered entries with actor and timestamp.

### Tests for User Story 5 ⚠️

- [X] T075 [P] [US5] Write `src/test/java/com/nox/adapter/in/web/partnerlink/MyPartnerLinkControllerHistoryTest.java`: response matches `PartnerLinkHistoryResponse`; not owned → `404`
- [X] T076 [P] [US5] Write `src/test/java/com/nox/application/service/GetMyPartnerLinkHistoryServiceTest.java`: entries ordered by `occurred_at`, scoped to the user's own link
- [X] T077 [P] [US5] Write `src/test/java/com/nox/integration/PartnerLinkHistoryIT.java`: create → approve → revoke gives `link_created` (PARTNER), `user_approved` (USER), `user_revoked` (USER) in order; a late approve adds `expired_on_late_action` (NOX) timestamped at the refusal (FR-009, FR-010, SC-005)

### Implementation for User Story 5

- [X] T078 [US5] Create `application/port/in/GetMyPartnerLinkHistoryUseCase.java`
- [X] T079 [US5] Implement `application/service/GetMyPartnerLinkHistoryService.java` (makes T076 pass)
- [X] T080 [US5] Add the history handler to `adapter/in/web/partnerlink/MyPartnerLinkController.java` (makes T075 pass)
- [X] T081 [US5] Wire the service in `infrastructure/config/UseCaseConfig.java` (makes T077 pass)

---

## Final Phase: Polish & Cross-Cutting Concerns

- [X] T082 [P] Write `src/test/java/com/nox/adapter/out/messaging/partnerlink/PartnerLinkStuckPublicationSweeperTest.java`: a publication incomplete for longer than the configured age is recorded once in `partner_link_stuck_publication`; younger or completed ones are not
- [X] T083 Implement `adapter/out/messaging/partnerlink/PartnerLinkStuckPublicationSweeper.java` (`@Scheduled`, using Modulith's `IncompleteEventPublications`; age passed in from `UseCaseConfig`) and wire it (makes T082 pass)
- [X] T084 [P] Write `src/test/java/com/nox/integration/PartnerLinkLoggingIT.java` (pattern of the existing `LogHygieneIT`): publications and the lookup listener log the `messaging` fields (`operation`, `exchange`, `routingKey`, `messageId`) with the request `traceId`; no email address, Owl request URL or query string, Owl api-key, or token appears in logs; then add the logging to `PartnerLinkEventExternalizationConfig` and `PartnerLinkRequestSubmittedListener` (Principles VII, XI)
- [X] T085 [P] Write `src/test/java/com/nox/integration/PartnerLinkAuthorizationIT.java`: the full caller matrix of quickstart.md (FR-019) against every endpoint of this feature
- [X] T086 [P] Write `src/test/java/com/nox/integration/PartnerLinkErrorResponsesIT.java` (pattern of `ErrorResponsesIT`): `400`, `401`, `403`, `404`, `409 link_expired`, `409 link_not_actionable` shapes (Principle VI)
- [X] T087 Write `src/test/java/com/nox/integration/PartnerLinkLatencyPerfIT.java` tagged `perf` (pattern of `SecurityLatencyPerfIT`): link-request acknowledgment < 2 s (SC-001) and approval messages broker-confirmed within 5 s p95 (SC-006)
- [ ] T088 Run the manual walkthrough in quickstart.md and confirm every response matches the contracts

---

## Dependencies & Execution Order

### Phase dependencies

- Setup → Foundational → user stories → Polish.
- **Code**: every story depends only on Foundational.
- **Demonstration data**: US2–US5 need a link, which US1 creates; their tests seed links directly through the repositories so they do not wait on US1.
- `UseCaseConfig.java`, `MyPartnerLinkController.java` and `PartnerLinkRequestController.java` are touched by several stories — those tasks are sequential, never `[P]` with each other.

### Within each story

Failing tests → ports → services → controllers/listeners → wiring (the wiring task turns the ITs green).

### Parallel opportunities

- Setup: T002–T006 after T001.
- Foundational: all tests T007–T016 together; then T022–T026 together; changesets T017–T020 together.
- Each story's test tasks together.
- After Foundational, US1, US2 and US3 can be developed in parallel by different people (their shared files are listed above).

## Parallel Example: User Story 1

```bash
Task: "OwlWireMockSupport in src/test/java/com/nox/integration/support/OwlWireMockSupport.java"
Task: "PartnerLinkRequestControllerCreateTest in src/test/java/com/nox/adapter/in/web/partnerlink/"
Task: "SubmitPartnerLinkRequestServiceTest in src/test/java/com/nox/application/service/"
Task: "ResolvePartnerLinkRequestServiceTest in src/test/java/com/nox/application/service/"
Task: "OwlUserLookupAdapterTest in src/test/java/com/nox/adapter/out/userlookup/"
```

## Implementation Strategy

### MVP (User Story 1 only)

1. Setup and Foundational.
2. US1 — stop and validate `PartnerLinkRequestCreationIT` and `PartnerLinkRequestOwlOutageIT`.

### Incremental delivery

US1 (requests) → US2 (decisions and deadline) → US3 (status reads) → US4 (revoke) → US5 (history) → Polish.
