# Quickstart: Validating Partner Payments

**Feature**: [spec.md](spec.md) | **NOX API**: [openapi.yaml](contracts/openapi.yaml) |
**Payment-core**: [payment-core.md](contracts/payment-core.md) | **Data model**: [data-model.md](data-model.md)

## Prerequisites

- Java 25 and the repository Maven wrapper.
- Docker or Podman available to Testcontainers.
- A Keycloak service token carrying `partner-access`, with a partner configured with an active
  payment-core account and enabled limits for the requested type/currency.
- A connected user link for that partner.
- Payment-core's mock approach: approved by the project owner on 2026-10-31 (shared WireMock 3.13.2).
- Mocked payment-core and OAuth token endpoint; integration scenarios must never call live
  payment-core.

## Automated Validation

Run the full verification suite after implementation:

```bash
./mvnw verify
```

This must execute unit tests and full HTTP integration tests against Testcontainers PostgreSQL and
Keycloak, with WireMock stubbing payment-core and its OAuth token endpoint. Tests must cover:

| Scenario | Expected result |
|---|---|
| Valid PAYIN or PAYOUT by `userLinkId` or partner-owned `externalLinkId`, enabled currency, in-range amount | One initialized payment; required payment fields and fee returned; OTP stage entered |
| Same partner, idempotency key, and request body repeated | Original payment returned; no second payment-core initialization |
| Same key reused with changed request data | `409` problem response; no second initialization |
| Wrong partner link, no active account, missing method, disabled currency, or out-of-range amount | Appropriate 4xx problem response; payment-core not called |
| Active account changes after initiation | Confirmation sends the original account snapshot |
| Correct OTP | Payment-core confirmation result is persisted and returned |
| Cancel while awaiting OTP | Payment-core cancellation result is persisted; payment cannot later be confirmed |
| Cancel after confirmation/expiry or race cancel against confirmation | Non-pending action is rejected; at most one terminal outcome wins |
| Cancel request is repeated after cancellation | Existing canceled outcome returned without another processor action |
| Invalid OTP or non-confirmable payment | No successful completion is reported |
| Confirmation called concurrently or repeated after completion | At most one upstream confirmation; existing outcome returned |
| Payment-core timeout before/after an ambiguous operation | Bounded response, circuit breaker behavior, no unsafe confirmation retry; unresolved state retained for reconciliation |
| Missing/invalid bearer token or wrong role | Existing `401`/`403` problem response; use case and payment-core are not called |

## Manual API Walkthrough

The partner calls NOX using the established service token and `x-service-id`. Replace placeholders
with test-only values; never use production credentials in local validation.

```bash
curl -i -X POST "$NOX_BASE_URL/api/v1/user-partner-links/$USER_LINK_ID/payments" \
  -H "Authorization: Bearer $PARTNER_SERVICE_TOKEN" \
  -H "x-service-id: $PARTNER_CLIENT_ID" \
  -H "Idempotency-Key: $PAYMENT_REQUEST_KEY" \
  -H "Content-Type: application/json" \
  -d '{"type":"PAYIN","money":{"amount":123.45,"currencyCode":"EUR"},"externalId":"76490253-9de4-4988-b757-ad0f13a5c7fd","transactionReference":"PAM-DEP-20260802-000001","description":"Deposit to PAM wallet"}'
```

Submit the OTP received by the user for the returned NOX `paymentId`:

```bash
curl -i -X POST "$NOX_BASE_URL/api/v1/user-partner-links/$USER_LINK_ID/payments/$PAYMENT_ID/confirmation" \
  -H "Authorization: Bearer $PARTNER_SERVICE_TOKEN" \
  -H "x-service-id: $PARTNER_CLIENT_ID" \
  -H "Content-Type: application/json" \
  -d '{"code":"123456"}'
```

Expected: successful bodies match the `{data, meta}` envelope and public schema in `openapi.yaml`;
payment-core sees a NOX OAuth2 bearer token, no API key, and the account captured at initiation.
Errors use `application/problem+json`. The OTP and financial details must not appear in logs.

To cancel through the NOX link ID, use:

```bash
curl -i -X POST "$NOX_BASE_URL/api/v1/user-partner-links/$USER_LINK_ID/payments/$PAYMENT_ID/cancellation" \
  -H "Authorization: Bearer $PARTNER_SERVICE_TOKEN" \
  -H "x-service-id: $PARTNER_CLIENT_ID"
```

Integrations that address links by the partner's external reference use the parallel
`/user-partner-links/by-external-id/$EXTERNAL_LINK_ID/payments` initiation, confirmation, and
cancellation routes defined in `openapi.yaml`. `EXTERNAL_LINK_ID` is the stored
`partner_external_id`, resolved only within the authenticated partner. Its cancellation route is:

```bash
curl -i -X POST "$NOX_BASE_URL/api/v1/user-partner-links/by-external-id/$EXTERNAL_LINK_ID/payments/$PAYMENT_ID/cancellation" \
  -H "Authorization: Bearer $PARTNER_SERVICE_TOKEN" \
  -H "x-service-id: $PARTNER_CLIENT_ID"
```

Both cancellation routes verify that the payment belongs to the resolved connected link. The
payment-core cancel contract was confirmed on 2026-10-31 (`contracts/payment-core.md`).

## Validation Record (2026-10-31)

`./mvnw verify` on branch `002-pam-account-linking` with Podman-backed Testcontainers:

| Suite | Result |
| --- | --- |
| Unit tests (surefire) | 450 run, 0 failures |
| Integration tests (failsafe) | 166 run, 165 passed, 1 failure (see below) |

Feature coverage by scenario table row: `PartnerPaymentPayinIT` (11), `PartnerPaymentPayoutIT` (4),
`PartnerPaymentConfirmationIT` (10), `PartnerPaymentCancellationIT` (7), `PaymentPersistenceIT`
(10), plus `InitiatePaymentServiceTest`, `InitiatePaymentServicePayoutTest`,
`ConfirmPaymentServiceTest`, `CancelPaymentServiceTest`, `PaymentCoreAdapterTest`, `PaymentTest`,
`PartnerPaymentPropertiesTest`, the payment problem mapping in `GlobalExceptionHandlerTest`, and
the payment rules in `ArchitectureTest`. Every scenario in the table above is covered.

Known non-feature failure: `PartnerLinkRequestCreationIT.aRepeatedIdempotencyKeyReturnsTheOriginalRequest`
failed once on a transport-level `Connection` response header present in one of two otherwise
identical responses. It passed in two isolated reruns; the partner-link code it covers is unchanged
by this feature. Its header comparison should ignore hop-by-hop headers.

Environment note: Keycloak tokens were rejected as `token_expired` until the Podman VM clock,
74 minutes behind after host sleep, was resynchronized.

The manual walkthrough was not run: it needs a live payment-core and Keycloak service client,
which are not available locally. The automated tests cover the same calls against WireMock.
