# Data Model: Partner Payments

## Entities

### Partner Payment Account

The one currently active payment-core account identity for a partner, managed internally.

| Field | Type | Rules |
|---|---|---|
| `partnerId` | UUID | Primary key and foreign key to `partner.id`; one active record per partner |
| `providerUserId` | String | Required payment-core provider identity |
| `accountId` | String | Required current active account ID |
| `updatedAt` | Instant | Required; set when the active identity is replaced |

Account rotation replaces this current record. A payment stores an immutable snapshot of the
provider user and account IDs it used, so an in-flight payment can still be confirmed with its
original account. No partner API writes this entity.

### Partner Payment Limit

Currency enablement and bounds for one partner and transaction type.

| Field | Type | Rules |
|---|---|---|
| `partnerId` | UUID | Foreign key to `partner.id` |
| `type` | Enum | `PAYIN` or `PAYOUT` |
| `currencyCode` | String | Required currency code |
| `enabled` | Boolean | Only enabled rows allow initiation |
| `minimumAmount` | Decimal | Required, greater than zero, inclusive lower bound |
| `maximumAmount` | Decimal | Required, greater than or equal to minimum, inclusive upper bound |

The composite primary key is `(partnerId, type, currencyCode)`. Amounts use decimal arithmetic;
request precision and range must fit the configured currency and payment-core contract.

### Payment

The durable NOX record for one initiated pay-in or pay-out.

| Field | Type | Rules |
|---|---|---|
| `id` | UUID | NOX payment ID and stable upstream idempotency key |
| `paymentCoreId` | String | Required once payment-core returns a payment ID; unique when present |
| `partnerId` | UUID | Foreign key to the submitting partner |
| `userLinkId` | UUID | Foreign key to the connected partner link |
| `providerUserIdSnapshot` | String | Provider user identity selected at initiation |
| `accountIdSnapshot` | String | Active account selected at initiation |
| `idempotencyKey` | String | Partner-supplied key, scoped unique to `partnerId` |
| `requestFingerprint` | String | Stable digest of normalized request fields; detects same-key payload changes |
| `type` | Enum | `PAYIN` or `PAYOUT` |
| `paymentMethod` | String | Configured method value used for this type |
| `amount` | Decimal | Positive; within configured bounds |
| `currencyCode` | String | Enabled for this partner and type at initiation |
| `externalId` | String | Partner-supplied external transaction identity; unique per partner across all payments |
| `transactionReference` | String | Partner-supplied reference |
| `description` | String, nullable | Partner-supplied optional description |
| `feeAmount` | Decimal, nullable | Returned by payment-core |
| `feeCurrencyCode` | String, nullable | Returned by payment-core |
| `status` | Enum | `INITIATING`, `AWAITING_OTP`, `CONFIRMING`, `CANCELLING`, `CONFIRMED`, `CANCELLED`, `FAILED`, `EXPIRED`, `INITIATION_UNKNOWN`, `CONFIRMATION_UNKNOWN`, `CANCELLATION_UNKNOWN` |
| `createdAt` | Instant | NOX record creation time |
| `paymentCoreCreatedAt` | Instant, nullable | Upstream `created_at` |
| `expirationDate` | Instant, nullable | Upstream value when present |
| `confirmedDate` | Instant, nullable | Upstream value after successful confirmation |
| `updatedAt` | Instant | Last state update |

Unique constraints: `(partnerId, idempotencyKey)`, `(partnerId, externalId)` (every stored payment,
any status; a released reservation is deleted and frees it), and `paymentCoreId` when non-null. A version
column is recommended for optimistic locking of state transitions. Preserve the original request
fingerprint and account snapshot for the full payment record lifetime.

### Configured Payment Methods

The pay-in and pay-out payment-core method strings are application configuration, exposed only to
the outbound adapter and injected separately per environment. Partners cannot override them.

## Relationships

- A partner has one active `Partner Payment Account` and many `Partner Payment Limit` rows.
- A partner has many `Payment` records; each payment references the partner and the connected
  `PartnerLink` used for the user's authorization.
- A payment snapshots its account and payment method; later configuration changes affect only new
  payment initiations.
- A partner idempotency key resolves to at most one payment.
- A link may be addressed by its NOX UUID or by the partner-scoped external reference stored on
  that link; both resolve to the same `userLinkId` relationship on the payment.

## Payment State Transitions

```text
INITIATING ──accepted by payment-core──> AWAITING_OTP ──valid OTP accepted──> CONFIRMED
    │                                      ├──cancel accepted──────────────> CANCELLED
    ├──definitive rejection──> FAILED      ├──expired/rejected────────────> FAILED or EXPIRED
    └──ambiguous timeout──────> INITIATION_UNKNOWN
                                           ├──ambiguous confirm result───> CONFIRMATION_UNKNOWN
                                           └──ambiguous cancel result────> CANCELLATION_UNKNOWN
```

`INITIATION_UNKNOWN`, `CONFIRMATION_UNKNOWN`, and `CANCELLATION_UNKNOWN` are not success states.
They are recorded only after the adapter's bounded retries (safe: initialization is keyed by the
payment UUID, and payment-core deduplicates confirm and cancel) end without an authoritative
answer. They require internal reconciliation; a later partner request never repeats the action. Only payment-core can establish a completed or canceled payment.
Concurrent confirmation and cancellation for the same payment must be serialized so only one
operation leaves NOX at a time and at most one terminal outcome is recorded.

## Validation and Persistence Rules

- The linked user relationship must belong to the authenticated partner and be `CONNECTED` when
  initiation begins.
- `externalLinkId` resolves only within the authenticated partner's links; select the most recent
  established matching link and then require that it is `CONNECTED`.
- The requested type, currency, and amount must match an enabled partner limit row.
- The configured payment method must exist for that type, and the partner must have an active
  payment account before initiation.
- Persist the idempotency reservation before calling payment-core. A same-key, same-fingerprint
  retry resolves to the original payment; a different fingerprint is a conflict.
- Keep payment initialization and external service calls outside a long-running database
  transaction; persist state transitions in short transactions.
- Persist processor responses before returning them. Do not mark complete on `INSERTED` or on
  receipt of an OTP alone.
- Accept cancellation only from `AWAITING_OTP`; confirmation and cancellation race through one
  serialized state transition. Do not claim `CANCELLED` unless payment-core confirms cancellation.
- A mismatch in returned amount/currency must not be reported as a successful payment; preserve an
  unresolved state for reconciliation if the upstream transaction may have been created.
- Implement schema and reference-data changes with immutable Liquibase changesets and rollback.

## Operating the Configuration

Partners cannot read or change accounts, limits, or methods. Operators manage them as follows.

- **Payment methods**: set `NOX_PAYMENT_PAYIN_METHOD` and `NOX_PAYMENT_PAYOUT_METHOD` per
  environment. A blank value refuses that type with `payment_not_configured`; a change applies to
  new payments only (each payment stores the method it used).
- **Active account**: one `partner_payment_account` row per partner (`partner_id` is the primary key
  and references `partner.id`). Rotate by replacing `provider_user_id` / `account_id` and setting
  `updated_at`. Payments already awaiting the OTP keep their snapshot and are confirmed or cancelled
  with it. Without a row, initiation returns `payment_not_configured`.
- **Limits**: one `partner_payment_limit` row per `(partner_id, type, currency_code)`. Initiation is
  allowed only when the row exists, `enabled` is true, and `minimum_amount <= amount <=
  maximum_amount` (both inclusive); otherwise `payment_limit_exceeded`. The database enforces
  `minimum_amount > 0` and `maximum_amount >= minimum_amount`.
- **Delivery**: under constitution Principle IX, account and limit data are reference data and
  ship as Liquibase changesets (environment-specific `context`), never as manual DML.
- **Reconciliation**: payments in a `*_UNKNOWN` state need an operator to check payment-core and
  record its outcome. Until then, partner requests for them answer `payment_unresolved`.
