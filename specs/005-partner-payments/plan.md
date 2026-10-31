# Implementation Plan: Partner Payments

**Branch**: `005-partner-payments` | **Date**: 2026-10-31 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-partner-payments/spec.md`

## Summary

Add partner-facing pay-in/pay-out initiation by either `userLinkId` or partner-scoped
`externalLinkId`, OTP confirmation, and cancellation of payments awaiting OTP. Persist
partner-scoped idempotency and payment state in PostgreSQL so retries return the same payment and
confirmation/cancellation use the account captured at initiation. Call payment-core through an
OAuth2 client-credentials `RestClient` adapter, mapping its provider payload to NOX's versioned,
enveloped, lowerCamelCase partner API. Retry initialization only with the same stable payment-core
idempotency key; do not automatically retry an ambiguous confirmation or cancellation.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1.2 (existing)

**Primary Dependencies**:

- Existing Spring MVC, Security OAuth2 resource-server/client, Validation, `RestClient`, Spring Data
  JPA, Liquibase, PostgreSQL driver (42.7.14, Spring Boot BOM), JUnit 6, Testcontainers 2.0.5,
  Spring Boot Test, and WireMock 3.13.2.
- Add `io.github.resilience4j:resilience4j-circuitbreaker:2.4.0` for the outbound payment-core
  circuit breaker. Use bounded HTTP timeouts and Spring's retry support only for initialization,
  whose payment-core contract accepts an idempotency key. No retry for OTP confirmation.

**Storage**: PostgreSQL 18.6 (existing compose image), Liquibase-managed schema. Add payment,
partner payment account, and partner/type/currency configuration records. Redis: N/A; no cache is
needed. PostgreSQL JDBC driver target: 42.7.14.

**Testing**: JUnit 6; domain and application unit tests; full HTTP integration tests using
Testcontainers PostgreSQL 18.6 and Keycloak, plus WireMock 3.13.2 payment-core and OAuth token
stubs. Use existing `RestTestClient`, `InfrastructureContainers`, and `ProblemAssertions` patterns.
Add contract checks for both NOX's OpenAPI and the consumed payment-core contract, and ArchUnit
coverage for layer boundaries.

**Target Platform**: Linux container, following the existing Docker and Development/Stage/Production
configuration.

**Project Type**: Single Spring Boot web service.

**Performance Goals**: Meet the spec's 99% successful initiation outcome when payment-core is
available. Use bounded outbound connect/read timeouts (initial targets 2s/5s, configurable by
environment) and a circuit breaker. The feature adds no independent throughput or availability SLA.

**Constraints**:

- Require existing partner service authentication and `partner-access` authorization, resolving
  the calling partner through the established `x-service-id` mechanism. Never accept partner,
  account, or user identities from an untrusted body field.
- Add initiation, confirmation, and cancellation route variants keyed by `externalLinkId`. Resolve
  that value as the authenticated partner's stored external link reference and require the most
  recent matching established link to be `CONNECTED`. Both cancellation routes require the link
  identifier and verify that the payment belongs to that link and authenticated partner.
- Keep public paths versioned and public JSON lowerCamelCase, inside the standard `{data, meta}`
  envelope. Map payment-core's supplied mixed-case fields in the outbound adapter.
- Enforce enabled currencies and amount limits by partner + transaction type + currency.
- Apply a configured pay-in or pay-out payment method inside NOX; it is not partner-selectable.
- Store the selected provider user/account identity on each payment at initiation; later account
  rotation applies only to new payments.
- Scope `Idempotency-Key` to partner; persist the request fingerprint and payment so same-key,
  same-request retries return the original outcome and changed payloads are rejected.
- Payment initiation may be retried only with the same durable upstream key. Confirmation has no
  idempotency key in the provided upstream contract: serialize per payment, never auto-retry an
  ambiguous timeout, record `CONFIRMATION_UNKNOWN`, and require internal reconciliation before any
  new upstream attempt. Before implementation, confirm the payment-core duplicate-confirm and
  confirmation-response behavior with its owner; the supplied example does not define those cases.
- Cancellation is allowed only from the pending OTP state and competes atomically with
  confirmation. Do not report cancellation unless payment-core confirms it. The cancel endpoint,
  request/response contract, and idempotency behavior are not supplied; confirm them with the
  payment-core owner before implementation. Never blindly retry an ambiguous cancel request.
- The payment-core initialization example omits a payment-method request field despite returning
  `requestedPaymentMethod` and `paymentMethod`; confirm how its configured value is selected before
  implementation.
- Use WireMock 3.13.2 in a container for payment-core and OAuth token stubs. This is the proposed
  mock approach; project owner confirmation is required before implementation that depends on it
  (Constitution IV).
- Never log access/refresh tokens, OTPs, account identifiers, or payment amounts. Apply the existing
  structured JSON log and problem-details conventions.

**Scale/Scope**: Two transaction types; six public operations (initiate, confirm, and cancel by
userLinkId or externalLinkId); one new payment domain slice;
payment/account/config persistence; no messaging or user OTP delivery changes.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Technology Stack | Java 25 / Spring Boot 4.1.2; PostgreSQL 18.6 / JDBC 42.7.14; Redis N/A (no cache need) | PASS |
| II. Security by Default | Reuse deny-by-default Keycloak resource-server security and partner-access role; resolve partner only from authorized service context; payment-core client credentials are runtime secrets | PASS |
| III. Test-First | Tasks must place failing unit/integration/contract tests before each production change | PASS (verify in tasks phase) |
| IV. Integration Testing & Mocks | Real PostgreSQL and Keycloak; propose WireMock 3.13.2 container for payment-core and OAuth; owner confirmation is a pre-implementation gate | CONDITIONAL (no implementation before confirmation) |
| V. RESTful API Design | Versioned resource paths, lowerCamelCase, `{data, meta}` responses, standard error media type; exact contract in `contracts/openapi.yaml` | PASS |
| VI. Error Handling | Central problem-details handling; document and integration-test validation, limits, idempotency, payment-core availability, OTP, cancel, and state-conflict problems | PASS |
| VII. Structured JSON Logging | Use existing formatter/tracing; omit credentials, OTP, account IDs, and amounts from logs | PASS |
| VIII. Current Dependencies | Reuse existing stack and add Resilience4j circuit breaker 2.4.0, current stable at plan time | PASS |
| IX. Liquibase | Add immutable changesets with rollback for payment/config tables; run integration tests on Liquibase schema | PASS |
| X. Clean Architecture | Domain rules and use cases stay framework-independent; HTTP, payment-core, persistence, and config are adapters/infrastructure | PASS |
| XI. Messaging | No asynchronous messages introduced | N/A |
| XII. Standard Implementations | Use Spring `RestClient`, OAuth2 client, JPA, Liquibase, and Resilience4j; no custom HTTP or resilience framework | PASS |
| XIII. Resilient HTTP Calls | Bounded timeouts and circuit breaker for payment-core; bounded safe retry only for idempotent initiation; no retry of ambiguous confirmation or cancellation | PASS |

**Post-Phase-1 re-check**: The design preserves the existing stack and architecture, adds only
Liquibase-managed persistence and a circuit-breaker dependency, and follows the current API,
security, error, and logging standards. Principle IV remains conditional on owner approval of the
WireMock approach. Payment-core method selection, duplicate-confirm/response behavior, and
cancellation endpoint/response semantics are documented pre-implementation contract gates.

## Project Structure

### Documentation (this feature)

```text
specs/005-partner-payments/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── openapi.yaml
│   └── payment-core.md
└── tasks.md                 # generated by $speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/com/nox/
├── domain/payment/                         # payment lifecycle and money/limit rules
├── application/port/in/payment/            # initiate and confirm use cases
├── application/port/out/payment/           # payment, account, config, and processor ports
├── application/service/payment/             # use cases and idempotency orchestration
├── adapter/in/web/payment/                  # partner payment endpoints and DTO mapping
├── adapter/out/paymentcore/                 # component-scanned adapter, its own PaymentCoreProperties,
│                                            #   OAuth2 RestClient, upstream DTOs, resilience
├── adapter/out/persistence/payment/         # JPA entities and repository adapters
└── infrastructure/config/                   # payment-method properties; InitiatePaymentService @Bean

src/main/resources/db/changelog/changes/     # Liquibase changesets
src/test/java/com/nox/
├── domain/payment/                          # unit tests
├── application/service/payment/             # use-case tests
├── adapter/out/paymentcore/                 # mapped request/response and resilience tests
└── integration/                             # security, API, persistence, idempotency, WireMock ITs
```

**Structure Decision**: Extend the existing single Spring Boot Clean Architecture project with a
`payment` domain slice. Persist payment state and partner-scoped idempotency with Liquibase. Keep
payment-core OAuth2 and wire details inside the outbound adapter. No new application module or
RabbitMQ flow is required.

## Complexity Tracking

No constitution exceptions are proposed. The mock-server approval and confirmation-contract checks
are pre-implementation gates, not architecture exceptions.
