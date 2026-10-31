<!--
Sync Impact Report
==================
Version change: 1.6.0 → 1.7.0
Bump rationale: MINOR — Principle X guidance materially expanded with an explicit, enforced rule
for wiring adapters and the composition root.
Modified principles: X. Clean Code & Clean Architecture (title unchanged) — adapters own their
settings records and are component-scanned; `@Bean` in infrastructure/config limited to three
listed reasons; outbound adapters built there must be on the architecture test's allow-list; the
rule is enforced by ArchUnit.
Added sections: none.
Removed sections: none.
Trigger: feature 005 put the payment-core adapter's settings in infrastructure/config and built
the adapter with `@Bean`; fixed alongside this amendment and now guarded by
`ArchitectureTest.infrastructureDoesNotConstructOutboundAdapters`.
Follow-up:
- none
Deferred TODOs: none
Dependent templates: not modified; they read the constitution at runtime.
This report is review material and must be removed before committing the amendment.
-->

# Nox Constitution

## Core Principles

### I. Mandated Technology Stack

- Application code MUST be written in Java 25 on the latest GA release of Spring Boot.
- Persistent data MUST be stored in PostgreSQL. No other primary datastore is permitted
  without a constitution amendment.
- When caching is required, Redis MUST be the caching server. Caching MUST NOT be added
  without a stated need (latency, load, or cost) recorded in the feature plan.
- Every plan MUST list the exact Java, Spring Boot, PostgreSQL, and Redis versions it targets.

**Rationale**: A single, fixed stack keeps services interoperable, operable by one team, and
reviewable against one set of security and performance baselines.

### II. Security by Default (OAuth 2.0 / Keycloak)

- All APIs MUST be protected with OAuth 2.0, using Keycloak as the authorization server.
  Services act as OAuth 2.0 resource servers validating JWT access tokens.
- Endpoints MUST deny by default; any unauthenticated endpoint (e.g. health probes) MUST be
  explicitly listed and justified in the feature plan.
- Authorization MUST be enforced on the server using token scopes/roles, never on client
  assertions alone.
- Secrets, keys, and credentials MUST NOT be committed to the repository or written to logs;
  they MUST be injected at runtime from environment-specific secret sources.

**Rationale**: Centralized identity and deny-by-default access remove a whole class of
authentication defects and keep credentials out of source control.

### III. Test-First Development (NON-NEGOTIABLE)

- TDD is mandatory: write a failing test, confirm it fails for the expected reason, implement
  the minimum code to pass, then refactor (Red-Green-Refactor).
- No production code may be merged without tests that were written before it and that cover
  its behavior.
- Task lists MUST order test tasks before the implementation tasks they verify.

**Rationale**: Tests written first define behavior unambiguously and prevent untested code
from entering the codebase.

### IV. Integration Testing & Mocked External APIs

- Every API endpoint MUST have integration tests that exercise the full HTTP path, including
  security, validation, persistence, and the success and error response formats.
- Integration tests MUST run against real PostgreSQL and Redis instances (e.g. Testcontainers),
  not in-memory substitutes.
- Calls to external (third-party or other-team) APIs MUST be served by a mock server during
  development and testing; tests MUST NOT depend on live external systems.
- The mock server approach (tool and hosting model) MUST be proposed and confirmed with the
  project owner before any implementation that depends on it begins, and recorded in the plan.

**Rationale**: Integration tests catch wiring and contract defects unit tests miss; mocks make
those tests deterministic and independent of external availability.

### V. RESTful API Design

- All APIs MUST follow RESTful principles: resource-oriented nouns in paths, correct HTTP
  methods and status codes, statelessness, and idempotent PUT/DELETE.
- APIs MUST be versioned in the path (e.g. `/api/v1/...`). Breaking changes MUST ship as a new
  version or as a two-stage migration (add new key, then remove old key).
- Request and response keys MUST use lowerCamelCase (`firstName`, `traceId`).
- Successful responses MUST use the standard envelope:
  `{ "data": <payload>, "meta": { "traceId", "timestamp", "instance" } }`, where `timestamp` is
  ISO-8601 UTC and `instance` is the request path.
- Webhooks MUST use the same envelopes. For webhooks called by external parties: internal
  errors return `500`; authentication/authorization failures return `401`/`403`; already
  processed (idempotent) and intentionally ignored calls return `2XX` with a descriptive `data`
  message.
- Health (`GET /healthz`) and readiness (`GET /readyz`) probes are exempt from the envelope and
  follow the knowledge-base health-check contract.

**Rationale**: A uniform contract lets consumers, gateways, and tooling handle every service the
same way. Source: Obsidian knowledge base, *API Standards*.

### VI. Consistent Error Handling

- Every error response MUST use a single problem-details format, served as
  `application/problem+json`, with the top-level fields `type` (`urn:problem:<code>`), `title`
  (the error code), `detail`, `traceId`, `timestamp`, and `instance`. Error-specific fields MAY
  be added at the top level.
- Errors MUST be produced by one centralized handler (e.g. `@RestControllerAdvice`); controllers
  MUST NOT build ad-hoc error bodies.
- Error responses MUST NOT expose stack traces, SQL, internal class names, or secrets.
- Each error `type` MUST be documented in the API contract and covered by an integration test.

**Rationale**: Consumers can handle failures programmatically only when every error has the same
shape. Source: Obsidian knowledge base, *API Standards* (failure structure).

### VII. Structured JSON Logging

- All application logs MUST be structured JSON, serialized as one line per entry outside local
  development.
- Every entry MUST include `timestamp` (UTC), `level` (exactly one of `DEBUG`, `INFO`, `WARN`,
  `ERROR`, `CRITICAL`), `service` (`name`, `version`, `environment`), and `message`.
- When applicable, entries MUST include `trace` (`traceId`, `spanId`, `parentSpanId`,
  `correlationId`), `request` (`method`, `url`, `userAgent`, `clientIp`), `messaging`, `user`
  (`id`, `platform`), and `exception` (`type`, `message`, `stacktrace` as a string array).
- Log keys MUST be camelCase and spelled identically across services. The `traceId` in logs
  MUST match the `traceId` returned in API responses for the same request.
- Logs MUST NOT contain secrets, tokens, passwords, or payment/card data.

**Rationale**: Consistent machine-readable logs enable cross-service search, alerting, and
request tracing. Source: Obsidian knowledge base, *Structured Logging*.

### VIII. Current Dependencies

- Every dependency (libraries, plugins, base images, build tools) MUST use its latest stable
  release at the time it is added or upgraded.
- Pre-release (milestone, RC, snapshot) versions MUST NOT be used.
- Production and test code MUST NOT call APIs marked deprecated by their maintainers. When a
  dependency deprecates an API in use, the code MUST migrate to a supported replacement before
  that dependency is upgraded to a version that removes the deprecated API.
- Pinning to an older version is permitted only with a documented reason (e.g. a known defect
  or incompatibility) recorded in the plan, and MUST be revisited on the next upgrade.

**Rationale**: Current releases carry the latest security fixes and avoid accumulated upgrade
debt.

### IX. Versioned Database Migrations (Liquibase)

- Every database schema or reference-data change MUST be delivered as a Liquibase changeset
  committed alongside the code that depends on it.
- Migrations MUST be applied automatically by Liquibase when the application starts (or by an
  automated deployment step that runs before the application). Manual DDL/DML against any
  environment is prohibited.
- Hibernate/JPA schema generation (`ddl-auto` other than `validate` or `none`) MUST NOT be used
  in any environment.
- Changesets MUST be immutable once merged; fixes are made with a new changeset. Each changeset
  MUST have a unique `id` and `author`, and SHOULD define a rollback.
- Integration tests MUST run against a schema built by the same Liquibase changelog.

**Rationale**: Versioned, automatically applied migrations keep every environment's schema
reproducible, auditable, and in sync with the code.

### X. Clean Code & Clean Architecture

- Code MUST be organized in Clean Architecture layers, with dependencies pointing inward only:
  - **domain**: entities, value objects, domain rules. MUST NOT depend on Spring, persistence,
    messaging, web, or any other framework or outer layer.
  - **application**: use cases and the ports (interfaces) they need. Depends only on domain,
    except that it MAY use Spring stereotype annotations (`@Service`, `@Component`) and
    `@Transactional`; no other framework API is allowed.
  - **adapters**: inbound (REST controllers, message listeners) and outbound (persistence,
    messaging publishers, external API clients) implementations of ports. Adapters MUST be
    registered by component scanning (`@Component`, `@Repository`, `@RestController`, etc.). An
    adapter that needs settings MUST own a `@ConfigurationProperties` record in its own package
    and receive it by constructor injection; settings for an adapter MUST NOT live in
    `infrastructure/config`.
  - **infrastructure/config**: framework configuration, plus `@Bean` definitions only for
    (a) application services that need configuration values, because the application layer may
    not depend on configuration types; (b) beans with lifecycle hooks; and (c) framework
    configuration. Constructing an outbound adapter there requires one of these reasons and MUST
    be listed, with that reason, in the architecture test's allow-list. Everything else is
    component-scanned.
- Controllers and listeners MUST only translate input, call a use case, and translate output;
  business rules MUST NOT live in adapters.
- Domain and application code MUST be unit-testable without starting Spring or any container.
- Layer rules, including the adapter and composition-root wiring rule above, MUST be enforced
  by automated architecture tests (e.g. ArchUnit) that fail the build on violation.
- Code MUST follow Clean Code practices: intention-revealing names, small single-purpose
  methods and classes, no duplicated logic, no dead or commented-out code, no magic values,
  constructor injection only, and immutable data where practical.

**Rationale**: Keeping business rules independent of frameworks and I/O makes them easy to test,
change, and reason about, and lets infrastructure be replaced without rewriting the core.
Stereotype and transaction annotations are inert metadata outside a Spring context, so allowing
them keeps that guarantee while avoiding a hand-wired composition root that adds boilerplate
without protecting the dependency rule; ArchUnit still enforces the dependency directions. An
adapter that owns its settings stays self-contained and is wired one way only, so the
composition root holds just what genuinely needs it.

### XI. Asynchronous Messaging (RabbitMQ)

- All asynchronous communication between services MUST use RabbitMQ.
- Producers MUST publish to a named exchange with an explicit routing key. Publishing to the
  default exchange or directly to a queue is prohibited. Consumers own and bind their queues.
- Exchanges and queues MUST be durable, and messages MUST be persistent. Producers MUST use
  publisher confirms, and consumers MUST use manual acknowledgement.
- Every consumer queue MUST have a dead-letter exchange; messages that fail after bounded
  retries MUST be dead-lettered, never silently dropped.
- Consumers MUST be idempotent, because a message may be delivered more than once.
- Message payloads MUST be JSON with lowerCamelCase keys and a documented, versioned contract.
  Breaking changes MUST use a new routing key or version. Messages MUST carry `messageId`,
  `traceId`, and `correlationId` (as headers or properties).
- Messaging log entries MUST populate the `messaging` fields of the logging standard
  (`operation`, `exchange`, `routingKey`, `queue`, `messageId`) (Principle VII).
- Exchange names SHOULD follow `<domain>.<purpose>` (e.g. `payments.events`) and routing keys
  `<entity>.<event>.v<version>` (e.g. `payment.completed.v1`).
- Messaging adapters MUST be covered by integration tests against a real RabbitMQ instance
  (e.g. Testcontainers).

**Rationale**: Exchange and routing-key based publishing decouples producers from consumers,
while durability, confirms, dead-lettering, and idempotency together prevent message loss and
duplicate side effects.

### XII. Standard Implementations First

- Before implementing functionality, developers MUST check the Java standard library and the
  Spring ecosystem for an existing implementation.
- If Java or Spring provides functionality that meets the requirements, developers MUST use
  that standard implementation rather than write or maintain a custom equivalent.
- Custom code MUST be limited to project-specific behavior and gaps not covered by standard
  implementations. The feature plan or change description MUST identify the alternatives
  considered and the unmet requirements that justify any custom implementation.
- Reuse MUST respect Principle X: Java APIs may be used in the core; Spring-dependent
  implementations MUST remain in adapters or infrastructure/config behind application ports.
- Reviews MUST verify that custom code does not duplicate available Java or Spring functionality
  that meets the requirements.

**Rationale**: Reusing maintained standard implementations reduces defects, unnecessary code,
and long-term maintenance effort.

### XIII. Resilient HTTP Calls

- Every outbound HTTP call MUST pass through a configured resilience mechanism; call sites MUST
  NOT invoke remote services without the applicable resilience policy.
- The policy MUST define bounded timeouts and MUST use retries, circuit breaking, or other
  controls as appropriate to the remote service and operation. Retries MUST be bounded and MUST
  NOT repeat non-idempotent operations unless the remote contract provides safe deduplication.
- The implementation SHOULD use a maintained standard library such as Resilience4j when it meets
  the requirements. The chosen mechanisms and their limits MUST be documented in the feature plan.

**Rationale**: Explicit resilience controls limit the impact of slow or unavailable remote
services and prevent retry behavior from creating duplicate side effects.

## Deployment & Environment Configuration

- A `Dockerfile` MUST be provided for the application. It MUST use a multi-stage build, run as
  a non-root user, and use the latest stable Java 25 runtime base image.
- Environment configuration MUST be provided for exactly three environments: Development,
  Stage, and Production (e.g. Spring profiles `dev`, `stage`, `prod`).
- Environment-specific values (URLs, credentials, Keycloak realms, pool sizes, log formats)
  MUST be externalized; the same container image MUST run unchanged in every environment.
- Production configuration MUST NOT enable debug endpoints, verbose error details, or mock
  servers.

## Development Workflow & Review Gates

- Every change MUST be reviewed by a security expert agent before it is merged. Findings rated
  high or critical MUST be resolved, or explicitly accepted by the project owner with a recorded
  justification, before merge.
- A change is mergeable only when: tests were written first (Principle III), all unit and
  integration tests pass (Principle IV), API and error contracts match Principles V–VI, logging
  matches Principle VII, any schema change ships as a Liquibase changeset (Principle IX),
  architecture tests pass (Principle X), messaging follows Principle XI, standard implementations
  are reused (Principle XII), outbound HTTP calls follow Principle XIII, and the security review
  is complete.
- Feature plans MUST include a Constitution Check that confirms compliance with each principle
  or records a justified exception.

## Governance

- This constitution supersedes all other development practices for this project. Where it
  conflicts with another guideline, the constitution wins until amended.
- Amendments MUST be made through `/speckit-constitution`, include a Sync Impact Report, and be
  approved by the project owner before taking effect.
- Versioning follows semantic versioning: MAJOR for removing or redefining a principle, MINOR
  for adding a principle or materially expanding guidance, PATCH for clarifications and wording.
- Compliance MUST be verified at plan time (Constitution Check) and at review time (every
  change). Unjustified violations block merge.
- The Obsidian knowledge base guidelines on logging, REST APIs, and error handling are
  normative references; when they change, this constitution MUST be reviewed for alignment.

**Version**: 1.7.0 | **Ratified**: 2026-10-25 | **Last Amended**: 2026-10-31
