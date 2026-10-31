# Implementation Plan: Partner Link Management API

**Branch**: `002-pam-account-linking` | **Date**: 2026-10-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-pam-account-linking/spec.md`

## Summary

A partner, through the BFF, submits a link request for a user by email. NOX persists the request and
answers `202` immediately, then resolves the user's Owl `user_id` asynchronously (with bounded
retry) and, if the user exists and has no active link with that partner, creates a `PENDING` link and
asks the user to decide. The user approves (→ `CONNECTED`), rejects (→ `REJECTED`), or later
revokes (→ `REVOKED`). Each link has an approval deadline fixed at submission; a late decision is
refused and recorded as `EXPIRED`, with no scheduler. Every written transition is appended to an
audit history; approvals and rejections also publish a user notification and a partner webhook event
through Spring Modulith's transactional outbox to RabbitMQ. Partner-facing endpoints accept only the
BFF's application token; user-facing endpoints accept only the user's own Keycloak token.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1.2 (unchanged)

**Primary Dependencies**:

- Existing: `spring-boot-starter-webmvc`, `spring-boot-starter-security`,
  `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-validation`, tracing/logging.
- New: `spring-boot-starter-data-jpa`, `org.postgresql:postgresql` (BOM: `42.7.14`),
  `org.liquibase:liquibase-core` (BOM: `5.0.4`), `spring-boot-starter-amqp`,
  `spring-boot-starter-restclient`, `spring-boot-starter-oauth2-client` (Owl client-credentials token), `spring-retry` (Owl lookup retry), and Spring Modulith via
  `spring-modulith-bom:2.1.2`: `spring-modulith-starter-jpa`, `spring-modulith-events-amqp`.
- Test: `org.testcontainers:postgresql`, `org.testcontainers:rabbitmq`, a WireMock Testcontainers
  module, `spring-modulith-starter-test`.

**Storage**: PostgreSQL — `partner`, `partner_history`, `partner_link_request`, `partner_link`,
`partner_link_history`, `partner_link_stuck_publication`, plus Modulith's `event_publication`; all via
Liquibase changesets with rollbacks. No caching (no stated need).

**Testing**: JUnit 5; Testcontainers PostgreSQL and RabbitMQ (real); WireMock Testcontainer for Owl
(confirmed with the project owner); ArchUnit; Failsafe `*IT` tests over the full HTTP, security,
persistence and messaging path; a fixed `Clock` for deadline tests.

**Target Platform**: Linux container (existing Dockerfile/compose)

**Project Type**: Single Spring Boot web service

**Performance Goals**: link-request acknowledgment < 2 s (SC-001); approval/rejection messages
broker-confirmed within 5 s p95 of commit (SC-006).

**Constraints**:

- Enumeration safety: `POST` does no lookup before responding; the request status endpoint and
  partner-facing reads behave identically for known and unknown emails (research.md decisions 1, 9).
- Idempotency per `(partner_id, Idempotency-Key)`.
- At most one active (`PENDING`/`CONNECTED`) link per user per partner, enforced by a partial unique
  index; concurrent writes resolved by optimistic locking (`@Version`).
- Deadline-based expiry, no scheduler; late decisions commit `EXPIRED` and return `409`
  (research.md decision 5).
- Only approval and rejection publish messages (spec FR-011).

**Scale/Scope**: 5 user stories, 10 endpoints, 1 asynchronous listener; normal per-user volume.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design and after the
2026-10-26 analysis remediation.*

| Principle | Check | Status |
|---|---|---|
| I. Technology Stack | Java 25 / Spring Boot 4.1.2; PostgreSQL; no caching | PASS |
| II. Security by Default | Deny-by-default retained; partner-facing endpoints require SERVICE + `nox-bff` role and read `x-service-id` only then; user-facing endpoints require a USER token and filter by its subject (research.md decision 6); outbound Owl calls carry an OAuth2 client-credentials token and the api-key, both secrets injected at runtime and never logged | PASS |
| III. Test-First | Every production task in tasks.md is preceded by a failing test task, including the foundational phase | PASS |
| IV. Integration Testing & Mocks | Real PostgreSQL and RabbitMQ; WireMock for Owl, confirmed with the project owner | PASS |
| V. RESTful API Design | Versioned paths, `{ data, meta }` envelope, lowerCamelCase keys in REST and messages; webhook messages use the same envelope (contracts/events.md) | PASS |
| VI. Error Handling | Existing `GlobalExceptionHandler`; new documented types `link_expired`, `link_not_actionable`, each with a test | PASS |
| VII. Structured Logging | `NoxStructuredLogFormatter`; messaging log fields populated for publications and the lookup listener | PASS |
| VIII. Current Dependencies | Versions above are latest stable as of 2026-10-26; re-verified when added | PASS |
| IX. Liquibase | Seven changesets (six feature tables + Modulith's), each with a rollback; `ddl-auto=validate` | PASS |
| X. Clean Architecture | Domain stays framework-free; application uses only `@Transactional` and Spring stereotypes (sanctioned by constitution 1.4.0); adapters and use cases are component-scanned; adapters bind their own settings records, and application services get plain values only through `ConfiguredBeansConfig` | PASS |
| XI. Messaging | Named topic exchanges, routing keys `partner-link.<event>.v1`, persistent + publisher confirms, `messageId`/`traceId`/`correlationId` on every message, documented contract; NOX declares only its exchanges — consumers own queues and DLXs | PASS |
| XII. Standard Implementations | Spring Data JPA, Spring AMQP, `RestClient`, Spring Security OAuth2 client (Owl token), Spring Retry, Spring Modulith registry and listener; custom code limited to the stuck-publication sweeper | PASS |

## Project Structure

### Documentation (this feature)

```text
specs/002-pam-account-linking/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── openapi.yaml     # REST contract
│   ├── events.md        # RabbitMQ message contract (published)
│   └── owl-user-lookup.md  # Owl lookup contract (consumed)
└── tasks.md
```

### Source Code (repository root)

```text
src/main/java/com/nox/
├── domain/
│   ├── partner/
│   │   ├── Partner.java, PartnerStatus.java, PartnerHistoryEntry.java
│   └── partnerlink/
│       ├── PartnerLinkRequest.java        # reportedStatus(link, now); LookupStatus
│       ├── LookupStatus.java          # PENDING, RESOLVED, NOT_FOUND, FAILED (internal)
│       ├── PartnerLink.java               # approve/reject/revoke rules, effectiveStatus(now), deadline check
│       ├── PartnerLinkStatus.java         # PENDING, CONNECTED, REJECTED, EXPIRED, REVOKED, SUSPENDED (reserved)
│       ├── PartnerLinkHistoryEntry.java
│       ├── ActorType.java             # USER, PARTNER, NOX
│       └── LinkDecisionOutcome.java   # Approved | Rejected | Revoked | Expired | NotActionable
├── application/
│   ├── port/in/                       # one use case interface per operation (below)
│   ├── port/out/
│   │   ├── PartnerRepository.java, PartnerLinkRequestRepository.java,
│   │   ├── PartnerLinkRepository.java, PartnerLinkHistoryRepository.java,
│   │   ├── UserLookupPort.java      # email → Found(userId, keycloakId) | NotFound | Failed
│   │   └── DomainEventPublisherPort.java
│   ├── event/                         # plain records: PartnerLinkRequestSubmitted, PartnerLinkApprovalRequested,
│   │                                  # PartnerLinkApprovedForUser/ForPartner, PartnerLinkRejectedForUser/ForPartner
│   └── service/                       # @Service/@Component + @Transactional allowed (constitution X)
│       ├── SubmitPartnerLinkRequestService.java     # persist request + publish PartnerLinkRequestSubmitted
│       ├── ResolvePartnerLinkRequestService.java    # Owl result → create PENDING link / duplicate / not found
│       ├── GetPartnerLinkRequestStatusService.java
│       ├── ApprovePartnerLinkService.java, RejectPartnerLinkService.java, RevokePartnerLinkService.java
│       ├── GetPartnerLinkForPartnerService.java         # established links only, by id or latest by externalId
│       ├── ListMyPartnerLinksService.java, GetMyPartnerLinkService.java, GetMyPartnerLinkHistoryService.java
│       └── PartnerLinkTransition.java               # write status + history (+ events) in one transaction
├── adapter/in/
│   ├── web/partnerlink/
│   │   ├── PartnerLinkRequestController.java        # POST + GET /api/v1/partner-link-requests
│   │   ├── PartnerLinkQueryController.java       # GET /api/v1/partner-links/**
│   │   ├── MyPartnerLinkController.java             # /api/v1/me/partner-links/** (reads + approve/reject/revoke)
│   │   └── PartnerCallerResolver.java               # x-service-id → Partner, only for nox-bff callers
│   └── event/
│       └── PartnerLinkRequestSubmittedListener.java # @ApplicationModuleListener → ResolvePartnerLinkRequestUseCase
├── adapter/out/
│   ├── persistence/partnerlink/                     # JPA entities + repositories (@Version on PartnerLink)
│   ├── userlookup/OwlUserLookupAdapter.java # RestClient + OAuth2 token interceptor + api-key header, @Retryable/@Recover; contracts/owl-user-lookup.md
│   ├── userlookup/OwlProperties.java        # nox.partner-link.owl.*, owned by the adapter
│   └── messaging/partnerlink/
│       ├── SpringDomainEventPublisherAdapter.java
│       ├── PartnerLinkStuckPublicationSweeper.java
│       └── PartnerLinkPublicationSweepProperties.java # sweep interval + stuck age, owned by the sweeper
└── infrastructure/config/
    ├── PartnerLinkProperties.java                   # approval window only; read only by ConfiguredBeansConfig
    ├── ConfiguredBeansConfig.java               # @Bean only for config-valued / lifecycle beans
    ├── SecurityConfig.java                      # extended: caller-type and nox-bff role matchers
    ├── RabbitMqConfig.java                      # declares the two exchanges only
    └── PartnerLinkEventExternalizationConfig.java   # event type → exchange::routing-key, headers

src/main/resources/db/changelog/                 # master changelog + seven changesets (+ dev-only partner seed)
src/test/java/com/nox/
├── architecture/ArchitectureTest.java           # amended for org.springframework.transaction.annotation
├── domain/partnerlink/, domain/partner/
├── application/service/
├── adapter/in/web/partnerlink/, adapter/in/event/, adapter/out/**
└── integration/                                 # PartnerLink*IT + support fixtures (Owl WireMock, partner seed)
```

**Structure Decision**: Single Spring Boot project, extending the existing `com.nox` Clean
Architecture tree with `partner` and `partnerlink` slices. Services are wired in `UseCaseConfig`, as the
existing slices are.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| _(none — the `@Transactional` exception previously tracked here was folded into constitution 1.4.0, Principle X, on 2026-10-30, together with Spring stereotypes)_ | | |
