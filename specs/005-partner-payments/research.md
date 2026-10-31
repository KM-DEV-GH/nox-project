# Research: Partner Payments

**Date**: 2026-10-31

## Decisions

### D1. Keep the partner API inside NOX's existing REST conventions

- **Decision**: Expose versioned resource paths under `/api/v1`, use lowerCamelCase JSON, the
  standard `{ data, meta }` success envelope, and the existing problem-details error format. Use
  `/api/v1/user-partner-links/{userLinkId}/payments` for initiation and append
  `/{paymentId}/confirmation` for OTP confirmation.
- **Rationale**: These are the established NOX API and constitution conventions. The caller is
  authenticated as a partner service using Keycloak and the existing `partner-access` role and
  `x-service-id` partner resolution.
- **Alternatives considered**: Expose the supplied unversioned singular `/user/{id}/payment` path
  and mixed snake_case keys. Rejected because it would diverge from existing API compatibility,
  naming, and envelope rules. The outbound adapter preserves payment-core's own field names.

### D2. Persist payment records and partner-scoped idempotency

- **Decision**: Add a payment record with a unique `(partner_id, idempotency_key)` constraint,
  canonical request fingerprint, processor payment ID, transaction data, state, and account
  snapshot. A retry with the same key and same fingerprint returns the original payment; the same
  key with different details returns a conflict.
- **Rationale**: The request key must survive process restarts and concurrent requests. Existing
  link-request persistence uses this partner-scoped uniqueness pattern. Durable payment state also
  binds confirmation to the account used at initiation.
- **Alternatives considered**: In-memory idempotency and forwarding the partner's key directly to
  payment-core. Rejected because process restart and partner namespace collisions could permit
  duplicate transactions. Generate one durable payment UUID before the external call and use that
  stable value as payment-core's idempotency key.

### D3. Keep account and limit configuration internally managed

- **Decision**: Store one active payment-core provider-user/account pair per partner in PostgreSQL;
  copy both values into each payment at initiation. Store currency enablement and min/max amounts
  for each partner, transaction type, and currency in PostgreSQL. Keep the pay-in and pay-out
  payment-method values in environment-specific application configuration, as requested.
- **Rationale**: Partner accounts and partner-specific limit rows are operationally managed and
  need durable changes. The payment-method values are shared application settings rather than
  partner-editable data.
- **Alternatives considered**: Put account and all limit values in global application properties.
  Rejected because partner-specific limits and account activation need independent managed values.

### D4. Use Spring's synchronous REST and OAuth2 client capabilities

- **Decision**: Implement an outbound port and adapter using Spring `RestClient` plus OAuth2
  client-credentials bearer authentication. Configure payment-core base URL, token endpoint,
  client ID/secret, and bounded timeouts per environment. Do not send an API key. As for Owl, the
  adapter is component-scanned and binds its own `PaymentCoreProperties`
  (`nox.payment.payment-core.*`); only the payment methods, which the application service needs,
  stay in `infrastructure/config` (constitution 1.7.0, Principle X).
- **Rationale**: The payment-core examples specify OAuth2/Keycloak and explicitly have no API-key
  authentication; NOX already uses Spring REST and OAuth2 client support for Owl. Keep all upstream
  casing and response-envelope translation in the adapter.
- **Alternatives considered**: Add a new HTTP client or pass partner bearer tokens through to
  payment-core. Rejected because the service contract calls for NOX service authentication, and the
  existing Spring client is already established.

### D5. Bound remote-call failures without duplicating money movement

- **Decision**: Apply 2s connect and 5s read timeouts and a Resilience4j circuit breaker (2.4.0).
  Allow bounded retry for initiation, reusing the same durable upstream idempotency key, and for
  confirm/cancel, which payment-core deduplicates per payment (owner-confirmed 2026-10-31).
  Serialize confirmations per payment; when retries are exhausted without an answer, record
  `CONFIRMATION_UNKNOWN` for internal reconciliation.
- **Rationale**: Initiation accepts an idempotency key in the supplied payment-core contract.
  Confirmation does not. Retrying an ambiguous confirmation without an upstream deduplication
  guarantee could complete a payment twice. Resilience4j documents circuit breaker behavior and
  Spring's `RestClient` supports synchronous intercepted requests.
- **Alternatives considered**: Retry all 5xx/timeouts, including confirmation. Rejected because
  timeout does not prove that the remote transaction did not complete.
- **Resolved 2026-10-31 (project owner)**: payment-core deduplicates repeated confirm and cancel
  calls for the same payment. A bounded retry of confirm/cancel with the same payment and body is
  therefore safe. NOX still serializes confirm/cancel per payment, and records
  `CONFIRMATION_UNKNOWN` / `CANCELLATION_UNKNOWN` only when the bounded retries are exhausted
  without an authoritative answer. No status lookup endpoint is used.

### D6. Supply transaction payment methods from NOX configuration

- **Decision**: Do not accept a partner-selected `paymentMethod` in the public request. Resolve the
  configured pay-in or pay-out value inside NOX and pass it through the supported payment-core
  mechanism.
- **Rationale**: The spec says the value is application configuration and partners cannot override
  it. This prevents an arbitrary method from bypassing the agreed partner setup.
- **Alternatives considered**: Require the partner to send the method from its example request.
  Rejected because the partner could submit a different value and the payment-core request example
  does not show a payment-method field.
- **Resolved 2026-10-31 (project owner)**: NOX sends the configured value as `paymentMethod` in
  the pay-in/pay-out request body.

### D7. Mock payment-core and its token endpoint with the existing WireMock container pattern

- **Decision**: Extend the shared WireMock 3.13.2 container setup to stub the payment-core token
  endpoint and pay-in, pay-out, and confirmation endpoints. Use placeholder credentials only.
- **Rationale**: Existing integration tests already use a WireMock container and real PostgreSQL
  and Keycloak containers. This keeps tests deterministic without calling the internal service.
- **Alternatives considered**: Live payment-core calls or a bespoke fake client. Rejected because
  they make tests environment-dependent or fail to cover HTTP serialization and OAuth headers.
- **Approved 2026-10-31 (project owner)**: payment-core and its token endpoint are stubbed on the
  shared WireMock 3.13.2 container (Constitution IV).

### D8. Treat payment-core as authoritative for fee and transaction outcome

- **Decision**: Persist its payment ID, returned fee, status and timestamps; validate echoed amount
  and currency against the request. Do not report completion unless payment-core confirms it.
  Return unavailable dates as nullable fields in the NOX response.
- **Rationale**: Initialization returns `INSERTED`, fee, and creation time, while the examples do
  not show expiration or confirmation dates at every phase. NOX should retain the service result and
  avoid inventing a settlement result.
- **Alternatives considered**: Calculate fees or mark a payment complete from the OTP submission
  alone. Rejected because fee and final processing belong to payment-core.
- **Resolved 2026-10-31**: confirm and cancel answer with the same `{data, meta}` envelope as
  initialization. The status mapping NOX applies is recorded in `contracts/payment-core.md`.

### D9. Resolve external link identifiers within the authenticated partner

- **Decision**: Add separate initiation, confirmation, and cancellation routes using
  `externalLinkId`, which maps to the existing `partner_external_id` field. Resolve it
  through the existing partner-scoped external-link lookup, then require the most recent
  established matching link to be `CONNECTED`. Cancellation routes also require the link
  identifier and verify the payment belongs to that link and the authenticated partner.
- **Rationale**: Existing link records already store the partner's external reference, and the
  lookup is scoped by internal partner ID. This supports integrations that do not retain NOX's
  link UUID without allowing cross-partner lookup.
- **Alternatives considered**: Accept either identifier value in one opaque path parameter or
  resolve external IDs globally. Rejected because it obscures validation and risks cross-partner
  data access.

### D10. Make cancellation a payment-core-owned terminal transition

- **Decision**: Allow cancel only while a payment awaits OTP. Serialize cancel and confirm by
  payment, and let only one terminal outcome win. Return the persisted canceled outcome on an
  identical repeated cancellation. Treat ambiguous cancel timeouts as `CANCELLATION_UNKNOWN` and
  do not retry automatically.
- **Rationale**: Confirmation and cancellation must never both succeed, and NOX cannot claim a
  payment is canceled until payment-core confirms it.
- **Alternatives considered**: Mark the payment canceled locally without a processor response or
  cancel after OTP confirmation. Rejected because either could disagree with the actual money
  movement.
- **Resolved 2026-10-31 (project owner)**: `POST /internal-payments/as-provider/{paymentCoreId}/cancel`
  with the confirm body minus the OTP; idempotent upstream, so a bounded retry is safe.

## Sources

- Project constitution, Principles I–XIII: `.specify/memory/constitution.md`.
- Existing partner-link API, persistence and mock patterns: `specs/002-pam-account-linking/` and
  `src/test/java/com/nox/integration/support/InfrastructureContainers.java`.
- Existing partner-scoped external-link lookup: `GetPartnerLinkForPartnerService` and
  `PartnerLinkRepository.findLatestByPartnerAndExternalId`.
- Spring Framework REST client reference: <https://docs.spring.io/spring-framework/reference/integration/rest-clients.html>.
- Spring Security OAuth2 client reference: <https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html>.
- Resilience4j circuit breaker reference: <https://resilience4j.readme.io/docs/circuitbreaker>.
- Resilience4j 2.4.0 release: <https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0>.
