# Consumed Contract: Payment Core

Payment-core is an internal OAuth2 resource server. NOX authenticates as a service client using
Keycloak client credentials (registration `payment-core`); do not send an API key or forward the
partner's access token.

Like Owl, payment-core can be reached through a SOCKS5 proxy (`NOX_PAYMENT_CORE_SOCKS5_ENABLED`,
`NOX_PAYMENT_CORE_SOCKS5_HOST`, `NOX_PAYMENT_CORE_SOCKS5_PORT`, default port 1080). When enabled,
both the payment calls and the client-credentials token requests use it; a missing host fails
startup.

**Approval record (2026-10-31, project owner)**: the payment-method field, the cancel endpoint, and
the idempotent confirm/cancel behavior below were confirmed before implementation began. Payment-core
and its token endpoint are stubbed with the shared WireMock 3.13.2 container in tests.

## Initialize Pay-In

```http
POST {{payment_core_base_url}}/internal-payments/as-provider/pay-in
Authorization: Bearer {{nox_service_access_token}}
Content-Type: application/json
Idempotency-Key: {{stable_nox_payment_id}}

{
  "provider": {
    "userId": "{{provider_user_id}}",
    "accountId": "{{account_id_snapshot}}"
  },
  "client": { "userId": "{{linked_user_id}}" },
  "money": { "amount": 123.45, "currencyCode": "EUR" },
  "paymentMethod": "{{configured_payin_method}}",
  "referenceNumber": "PAM-DEP-20260802-000001"
}
```

## Initialize Pay-Out

```http
POST {{payment_core_base_url}}/internal-payments/as-provider/pay-out
Authorization: Bearer {{nox_service_access_token}}
Content-Type: application/json
Idempotency-Key: {{stable_nox_payment_id}}

{
  "provider": {
    "userId": "{{provider_user_id}}",
    "accountId": "{{account_id_snapshot}}"
  },
  "client": { "userId": "{{linked_user_id}}" },
  "money": { "amount": 75.0, "currencyCode": "EUR" },
  "paymentMethod": "{{configured_payout_method}}",
  "referenceNumber": "PAM-WDR-20260802-000001"
}
```

`paymentMethod` is the NOX-configured value for the transaction type (`nox.payment.payin-method` /
`nox.payment.payout-method`); partners cannot supply it. `referenceNumber` maps from the partner's
`transactionReference`; `client.userId` is the linked user's Owl `user_id`. The `Idempotency-Key`
is the NOX payment UUID, so a retried initialization never creates a second upstream payment.

## Payment Response (initialize, confirm, cancel)

```json
{
  "data": {
    "payment_id": "string",
    "account_id": "string",
    "status": "INSERTED",
    "requestedPaymentMethod": "string",
    "paymentMethod": "string",
    "money": { "amount": 123.45, "currencyCode": "EUR" },
    "referenceNumber": "string",
    "fee": { "amount": 1.23, "currencyCode": "EUR" },
    "created_at": "2026-10-30T20:50:19.591Z",
    "expires_at": "2026-10-30T21:00:19.591Z",
    "confirmed_at": null,
    "counterparty_account_number": "string"
  },
  "meta": {
    "request_id": "string",
    "timestamp": "2026-10-30T20:50:19.591Z",
    "path": "string"
  }
}
```

NOX reads only `payment_id`, `status`, `money`, `fee`, `created_at`, and — when present —
`expires_at` and `confirmed_at`. `counterparty_account_number` and `account_id` are never mapped,
exposed, or logged. A returned `money` that differs from the request (amount or currency) is not
treated as success: the payment is kept as `INITIATION_UNKNOWN` for reconciliation.

### Status mapping

| payment-core `status` | NOX status |
| --- | --- |
| `INSERTED`, `PENDING`, `AWAITING_OTP` | `AWAITING_OTP` |
| `COMPLETED`, `CONFIRMED`, `SUCCESS`, `SUCCEEDED` | `CONFIRMED` |
| `CANCELLED`, `CANCELED` | `CANCELLED` |
| `EXPIRED` | `EXPIRED` |
| `FAILED`, `REJECTED`, `DECLINED` | `FAILED` |
| anything else | not trusted: treated as an ambiguous outcome (`*_UNKNOWN`) |

## Confirm Payment

```http
POST {{payment_core_base_url}}/internal-payments/as-provider/{{payment_core_payment_id}}/confirm
Authorization: Bearer {{nox_service_access_token}}
Content-Type: application/json

{
  "provider": {
    "userId": "{{provider_user_id_snapshot}}",
    "accountId": "{{account_id_snapshot}}"
  },
  "money": { "amount": 123.45, "currencyCode": "EUR" },
  "otp_code": "{{user_otp}}"
}
```

## Cancel Payment

```http
POST {{payment_core_base_url}}/internal-payments/as-provider/{{payment_core_payment_id}}/cancel
Authorization: Bearer {{nox_service_access_token}}
Content-Type: application/json

{
  "provider": {
    "userId": "{{provider_user_id_snapshot}}",
    "accountId": "{{account_id_snapshot}}"
  },
  "money": { "amount": 123.45, "currencyCode": "EUR" }
}
```

Both use the payment's original provider/account snapshot and money, never the partner's current
account.

## Failure handling

| Upstream outcome | Initialize | Confirm | Cancel |
| --- | --- | --- | --- |
| 2xx with a mapped status | stored; `AWAITING_OTP` (or `FAILED`) | stored status | stored status |
| 4xx | `FAILED` (`payment_rejected`) | stays `AWAITING_OTP` (`payment_otp_rejected`) | stays `AWAITING_OTP` (`payment_not_actionable`) |
| 5xx / timeout / unreadable, after bounded retries | `INITIATION_UNKNOWN` | `CONFIRMATION_UNKNOWN` | `CANCELLATION_UNKNOWN` |
| circuit open (no call made) | reservation released (`payment_processor_unavailable`) | stays `AWAITING_OTP` | stays `AWAITING_OTP` |

Retries are safe for all three operations: initialization is deduplicated by `Idempotency-Key`, and
payment-core deduplicates confirm and cancel per payment (owner-confirmed 2026-10-31). A `*_UNKNOWN`
payment is never retried automatically; it needs internal reconciliation. The OTP is never logged.
