# Phase 0 Research: Partner Link Management API

Revised 2026-10-26 after `/speckit-analyze` and the project owner's decisions on C1–C6, H1–H7,
M1–M8, L1–L4 and X1–X9. Each decision states what was chosen, why, and what was rejected.

## 1. Owl lookup: asynchronous, after the request is acknowledged

**Decision**: `POST /api/v1/partner-link-requests` only validates, persists the `PartnerLinkRequest`
(including the email), and publishes an internal, non-externalized event
`PartnerLinkRequestSubmitted` in the same transaction, then returns `202`. A Spring Modulith
`@ApplicationModuleListener` (asynchronous, runs after commit in its own transaction) receives the
event and calls the `ResolvePartnerLinkRequestUseCase`, which calls Owl through `UserLookupPort`.
The Owl adapter uses Spring's `RestClient` with Spring Retry `@Retryable` (bounded attempts and
exponential backoff from configuration). When retries are exhausted, a `@Recover` method records
the request's lookup as `FAILED` and completes normally.

The exact Owl call (`GET /api/v1/service/user?email=…&page=1&page_size=2&direction=DESC`) and how
each response maps to `Found(userId, keycloakId)`, `NotFound` or `Failed` are fixed in
[contracts/owl-user-lookup.md](contracts/owl-user-lookup.md). In short: only exactly one item with
`verification_status = APPROVED` is `Found` (project owner, 2026-10-26); zero items, more than one
item, or any other verification status is `NotFound`, which the rest of the flow treats exactly
like an unknown email. Only transient failures (5xx, timeouts, `success: false`, token endpoint
errors) are retried; 401/403 and other 4xx fail at once, since retrying a configuration error
cannot help.

**Rationale**: A synchronous lookup made response time depend on whether the user exists and on
Owl's health — a timing side channel (analysis H2) that contradicts FR-001 and SC-002. Running the
lookup after the acknowledgement makes every `POST` take the same path. Using a Modulith listener
means the lookup is itself recorded in the event publication registry, so a crash between commit
and lookup does not lose it: incomplete publications are resubmitted on restart.

**Alternatives considered**:
- Synchronous `RestClient` call with in-thread retries (the earlier draft): rejected for the
  timing side channel.
- `@Async` method call without the registry: rejected — a crash after commit loses the lookup.
- `WebClient`: rejected — pulls in the reactive stack for one call.

## 2. Mock-server approach for Owl (Principle IV gate)

**Decision** (confirmed with the project owner, 2026-10-26): WireMock running as a Testcontainer
stands in for Owl's lookup-by-email endpoint in every integration test. RabbitMQ is not mocked:
integration tests use a real broker via `org.testcontainers:rabbitmq` and assert on messages
actually published to the exchanges.

**Rationale**: WireMock is a declarative, maintained stub with Testcontainers support, mirroring
the existing `testcontainers-keycloak` pattern.

**Alternatives considered**: hand-rolled stub controller (duplicates WireMock); Prism from an
OpenAPI contract (Owl has published none).

## 3. Messaging contract (Principles V, XI)

**Decision**:
- Exchange `partnerlink.events` (topic, durable) for user notifications; exchange
  `partnerlink.webhooks` (topic, durable) for partner webhook events.
- Routing keys (analysis H7 — only approval and rejection notify both audiences):
  - `partner-link.approval-requested.v1` on `partnerlink.events` — asks the user to decide (FR-006)
  - `partner-link.approved.v1` on both exchanges (the partner payload includes `linkId`)
  - `partner-link.rejected.v1` on both exchanges
- Revocation and expiry publish nothing.
- Payloads are JSON with lowerCamelCase keys, versioned by routing key, documented in
  `contracts/events.md`. Every message carries `messageId`, `traceId`, and `correlationId` as AMQP
  message properties/headers.
- NOX declares **only its two exchanges**. Queues, bindings, and dead-letter exchanges belong to
  the consuming services (Principle XI: "Consumers own and bind their queues"); NOX's integration
  tests bind a test-only queue to assert delivery.

**Rationale**: Matches the constitution's `<domain>.<purpose>` and `<entity>.<event>.v<version>`
conventions; separate exchanges keep the user and partner failure domains independent.

**Alternatives considered**: one shared exchange with a prefix per audience (blurs the two
consumers' unrelated failure handling); NOX declaring consumer queues and DLXs (the earlier
draft — violates queue ownership).

## 3a. Transactional outbox: Spring Modulith Event Publication Registry

**Decision**: Use `spring-modulith-starter-jpa` and `spring-modulith-events-amqp`, pinned via
`spring-modulith-bom:2.1.2` (latest stable on 2026-10-26; `2.2.0-RC1` is pre-release and excluded
by Principle VIII). Use cases publish plain, framework-free event records through
`DomainEventPublisherPort`, in the same transaction as the history write. The registry stores one
`event_publication` row per event in that transaction; the AMQP externalization delivers it and
marks it complete after the broker confirms. Exchange/routing-key mapping is configured
programmatically with `EventExternalizationConfiguration` in `infrastructure/config`, not with
`@Externalized` on the event classes, so the event records stay free of Spring imports.

Modulith retries incomplete publications indefinitely, so `PartnerLinkStuckPublicationSweeper` flags
publications still incomplete after a configured age into `partner_link_stuck_publication` for
alerting.

**Rationale**: Atomic "state changed + messages will be sent" with no bespoke relay code
(Principle XII).

**Alternatives considered**: hand-rolled outbox table and `SKIP LOCKED` relay (duplicates
Modulith); publishing to RabbitMQ inside the transaction (couples commit to broker health and
still loses messages on a crash after commit); Debezium/CDC (new infrastructure for low volume).

## 4. Persistence, concurrency, and dependency versions

**Decision**: Spring Data JPA over PostgreSQL, `ddl-auto=validate`, schema owned by Liquibase
changesets (each with a rollback). `partner_link` has a `version` column mapped with JPA `@Version`
(optimistic locking). A concurrent write to the same link fails with an optimistic-lock conflict
in one of the two transactions; that transaction is rolled back and its use case retries once by
re-reading the link and re-applying its rule (which then refuses, because the other transition
already won). Dependency versions come from the Spring Boot `4.1.2` BOM: PostgreSQL JDBC
`42.7.14`, Liquibase `5.0.4`.

**Rationale**: Gives the "single consistent final state, exactly one history entry" edge case a
concrete mechanism (analysis H6, X4) with standard JPA support.

**Alternatives considered**: pessimistic `SELECT … FOR UPDATE` (holds locks across the user's
request; more contention for no benefit at this volume); jOOQ or plain JDBC.

## 5. Expiry: deadline check, no scheduler

**Decision** (project owner, 2026-10-26):
- The approval deadline is fixed **when the request is submitted**: `PartnerLinkRequest.expires_at =
  submitted_at + approval window`, and the link created for that request copies the same
  `expires_at`. Fixing it at submission (rather than when the user is notified, as the analysis
  first suggested) keeps a known and an unknown email on exactly the same timeline, so the request
  status endpoint flips from `PROCESSING` to `EXPIRED` at the same moment for both (analysis X5).
- Approve/reject compare the injected `Clock` with `expires_at`; at or after the deadline the link
  is transitioned to `EXPIRED` (NOX actor, history entry, no messages).
- The late approve/reject use case **returns an outcome** (`Approved`, `Rejected`, `Expired`,
  `NotActionable`) instead of throwing, so the `EXPIRED` transition commits; the controller maps
  `Expired` to `409 urn:problem:link_expired` (analysis X3, X8).
- Reads derive `EXPIRED` for an undecided link past its deadline without writing it.
- A new request for a pair whose undecided link is past its deadline first records that link as
  `EXPIRED` (NOX actor) in the lookup transaction, then creates the new link, keeping the partial
  unique index correct without a time-dependent predicate.

**Trade-off accepted**: a link that runs out with no user action has no history entry until it
is touched, and no message is ever published for expiry; the partner learns of it by polling.

**Alternatives considered**: a `@Scheduled` sweep (rejected by the project owner).

## 6. Authorization and identity (Principle II; analysis C6, H4)

**Decision**:
- **Partner-facing endpoints** (`/api/v1/partner-link-requests/**`, `/api/v1/partner-links/**`) require a
  SERVICE caller holding the Keycloak client role `nox-bff` on the `nox-api` audience — the BFF's
  application-to-application (client-credentials) token. Only on such calls is the `x-service-id`
  header read and resolved to a `Partner`; a SERVICE caller without the role gets `403`, and the
  header is never read from any other caller.
- **User-facing endpoints** (`/api/v1/me/partner-links/**`) require a USER caller (the user's own
  Keycloak token). Owl's `user_id` is **not** the token subject; the Owl lookup also returns the
  user's `keycloak_id`, which is the subject. NOX stores it on the link as `user_keycloak_id`
  when the link is created, and every user-facing use case filters by
  `user_keycloak_id = token subject`, so another user's link is reported as not found — with no
  Owl call on the user-facing path.
- Enforced in `SecurityConfig` with request matchers on caller type and role, reusing the existing
  `CallerAuthenticationConverter` (roles become `ROLE_<name>` authorities).

**Rationale**: "Authorization MUST be enforced on the server using token scopes/roles, never on
client assertions alone" — `x-service-id` is a client assertion, so it is trusted only behind the
BFF role.

**Alternatives considered**: trusting `x-service-id` from any SERVICE token (spoofable); one partner
client per Keycloak client calling NOX directly (contradicts "partners never call NOX directly").

## 7. Transaction boundaries in the application layer (Principle X exception; analysis C1)

**Decision** (project owner, 2026-10-26): application services and `PartnerLinkTransition` use
Spring's `@Transactional` directly. `ArchitectureTest` is amended to allow the application layer
to depend on `org.springframework.transaction.annotation..` only — no other Spring package.
Recorded as a justified exception in plan.md's Complexity Tracking.

**Rationale**: A transaction port (`TransactionRunner`) would add a callback layer to every use
case for no behavioural gain. The annotation is inert without a Spring proxy, so services remain
unit-testable without starting Spring.

**Alternatives considered**: a `TransactionRunner` port implemented with `TransactionTemplate` in
infrastructure — rejected by the project owner as more complex.

## 8. Configuration reaches adapters through the composition root (Principle X; analysis C2)

**Decision**: `PartnerLinkProperties` (`@ConfigurationProperties("nox.partner-link")`) lives in
`infrastructure/config` and is read only by `UseCaseConfig`, which passes plain values (durations,
retry counts, base URL) to adapter and service constructors — the same pattern the existing
`SigningKeySource` uses with `AuthProperties`.

**Rationale**: Keeps the existing ArchUnit rule `onlyTheCompositionRootDependsOnInfrastructure`
intact.

**Amendment (2026-10-30)**: adapter settings are now `@ConfigurationProperties` records owned by
the adapter that uses them — `OwlProperties` (`nox.partner-link.owl.*`) and
`PartnerLinkPublicationSweepProperties` (sweep interval, stuck age) — injected as one object, so
the adapters are plain `@Component`s. Keys are unchanged. `PartnerLinkProperties` keeps only the
approval window, which still reaches the application service as a plain value through the
composition root (now `ConfiguredBeansConfig`), because the application layer may not depend on
configuration types.

## 9. Enumeration safety on partner-facing reads

**Decision**: Partner-facing link reads (`GET /api/v1/partner-links/{linkId}`,
`GET /api/v1/partner-links/by-external/{externalId}`) return only established links (`CONNECTED`,
`REVOKED`) belonging to the calling partner; every other case — no link, a `PENDING`, `REJECTED` or
`EXPIRED` link, another partner's link — returns the same `404`. Rejection and expiry are reported
through the request status endpoint, which treats a known and an unknown email identically.
By external identifier, the most recently created link is returned (spec FR-013).

**Rationale**: A read by external identifier that returned `PENDING` or `EXPIRED` would reveal
that the email matched an account.

## 10. Authenticating to Owl (Principle II)

**Decision** (project owner, 2026-10-26): every Owl call carries **both** an OAuth2
client-credentials access token and Owl's `api-key` header.

- Token: `spring-boot-starter-oauth2-client` with a client registration `owl`
  (`authorization-grant-type: client_credentials`), attached to the Owl `RestClient` through
  Spring Security's `OAuth2ClientHttpRequestInterceptor`. Spring caches the token and refreshes it
  on expiry — no custom token code (Principle XII).
- `api-key`: added by a `RestClient` default header whose value is passed in from `UseCaseConfig`.
- Secrets: the client secret and the api-key come only from runtime secret sources
  (`NOX_OWL_CLIENT_SECRET`, `NOX_OWL_API_KEY`); they never appear in the repository, in test
  fixtures (tests use placeholders), or in logs. The token URI, client id, and Owl base URL are
  per-environment configuration.
- Because Owl takes the email in the query string, NOX never logs the Owl request URL or query.

**Rationale**: Owl requires both credentials; using Spring's OAuth2 client keeps token handling
standard and testable (the token endpoint is stubbed by the same WireMock container as Owl).

**Alternatives considered**: hand-written token fetching and caching (duplicates Spring Security);
api-key only (rejected — Owl expects both).

**Update (2026-10-31)**: the client, token interceptor and resilience are shared with payment-core in
`com.nox.adapter.out.http` (`HttpClients`, `ResilientCall`). Owl lookups now run under Resilience4j
retry (bounded by `max-attempts`, exponential backoff) and a circuit breaker with the same defaults
as payment-core (`nox.partner-link.owl.circuit-breaker.*`). While the circuit is open a lookup fails
fast as `UserLookupResult.Failed` instead of retrying against an unavailable Owl.
