# Feature Specification: Partner Payments

**Feature Branch**: `005-partner-payments`

**Created**: 2026-10-30

**Status**: Draft

**Input**: User description: "A partner can perform pay-in and pay-out for a user that has a connected link, in enabled currencies and within configured limits. The partner initiates a payment with the transaction type, currency, amount, payment method, external ID, transaction reference, and description. Nox processes it through payment-core, which sends an OTP to the user. The partner submits the user's OTP to complete the transaction. Each partner has one internally managed active account ID, and payment method values can be configured separately for pay-in and pay-out."

## Clarifications

### Session 2026-10-31

- Q: What does `externalLinkId` identify for payment requests? → A: The existing `partner_external_id` stored on the link, resolved within the authenticated partner's records.

### Session 2026-10-30

- Q: Are enabled currencies and minimum/maximum amounts configured globally, per partner, or per partner and transaction type? → A: Per partner and transaction type, with limits for each currency.
- Q: If a partner's active account changes while a payment is awaiting OTP, which account should be used to confirm it? → A: Use the account recorded when the payment was initiated.
- Q: Should an initiation idempotency key be unique across all partners, or only within the partner that submits it? → A: Unique within the submitting partner.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Initiate a pay-in (Priority: P1)

A partner initiates a deposit for a user with a connected link, identifying the link either by its NOX `userLinkId` or by the partner's `externalLinkId`. The partner chooses an enabled currency, an amount within that currency's configured limits, and supplies a unique request key and transaction identifiers. The user receives an OTP to authorize the transaction.

**Why this priority**: Deposits are a core way for linked users to fund their account and establish the end-to-end payment flow.

**Independent Test**: Submit a valid pay-in for a linked user and verify that it is accepted once, records the payment details and fee, and enters the OTP confirmation stage.

**Acceptance Scenarios**:

1. **Given** a linked user, enabled currency, amount within limits, configured pay-in method, and active partner account, **When** the partner initiates a pay-in, **Then** the system returns a payment record with user link ID, external ID, payment ID, creation date, type, money, fee, transaction reference, status, and expiration date.
2. **Given** the same initiation request is submitted again with the same idempotency key, **When** it is received, **Then** the system does not create a second payment.
3. **Given** an unlinked user, disabled currency, or amount outside the configured limits, **When** the partner initiates a pay-in, **Then** the request is rejected and no payment is created.
4. **Given** the partner supplies an `externalLinkId`, **When** it initiates a pay-in, **Then** NOX resolves the most recent established link for that external reference belonging to the authenticated partner and proceeds only if that link is connected.

---

### User Story 2 - Initiate a pay-out (Priority: P1)

A partner initiates a withdrawal for a user with a connected link using an enabled currency and an amount within that currency's configured limits. The user receives an OTP to authorize the transaction.

**Why this priority**: Withdrawals are the other required transaction type and must be available to partners under the same eligibility and limit controls.

**Independent Test**: Submit a valid pay-out and verify it enters OTP confirmation with the submitted amount, currency, and transaction identifiers recorded.

**Acceptance Scenarios**:

1. **Given** a linked user, enabled currency, amount within limits, configured pay-out method, and active partner account, **When** the partner initiates a pay-out, **Then** the system returns the required payment record and starts user OTP authorization.
2. **Given** the partner account changes after a payment was initiated, **When** the partner confirms that payment, **Then** confirmation uses the account associated with that payment initiation.
3. **Given** a disabled currency or amount outside the configured limits, **When** the partner initiates a pay-out, **Then** the request is rejected and no payment is created.
4. **Given** the partner supplies an `externalLinkId`, **When** it initiates a pay-out, **Then** NOX resolves the most recent established link for that external reference belonging to the authenticated partner and proceeds only if that link is connected.

---

### User Story 3 - Confirm a payment with the user's OTP (Priority: P1)

After the user receives an OTP, the partner collects it and submits it for the corresponding payment. The system completes the payment through the payment service and returns the resulting payment status and dates.

**Why this priority**: Initiation alone does not complete the user-authorized transaction; confirmation is required for both pay-in and pay-out.

**Independent Test**: Initiate a payment, submit the correct OTP for that payment, and verify the resulting payment status is returned and the confirmation cannot create a duplicate transaction.

**Acceptance Scenarios**:

1. **Given** a pending payment and valid OTP, **When** the partner confirms the payment, **Then** the system finalizes that payment and reports its resulting status and confirmation date when supplied.
2. **Given** an invalid or expired OTP, **When** the partner submits it, **Then** the payment is not reported as successfully confirmed.
3. **Given** a payment has already been confirmed, **When** the partner retries confirmation, **Then** no second transaction is created and the response reflects the existing payment outcome.
4. **Given** a payment belongs to a different user link or is not in a confirmable state, **When** a partner attempts to confirm it, **Then** the request is rejected without completing the payment.

5. **Given** the partner initiated the payment using `externalLinkId`, **When** it submits the OTP using the matching external-link confirmation endpoint, **Then** NOX confirms only a payment associated with that partner's resolved link.

### User Story 4 - Cancel a pending payment (Priority: P1)

When the user chooses to cancel a payment awaiting OTP, the partner submits the cancellation request. NOX asks the payment processing service to cancel the payment and reports the resulting status.

**Why this priority**: A user must be able to stop an unconfirmed payment instead of being forced to complete it.

**Independent Test**: Initiate a payment, cancel it while it awaits OTP, and verify that it reaches a canceled state and can no longer be confirmed.

**Acceptance Scenarios**:

1. **Given** a payment awaiting OTP, **When** the partner submits a cancellation request, **Then** NOX requests cancellation from payment-core and returns the resulting payment status.
2. **Given** a payment is confirmed, expired, or otherwise not cancellable, **When** the partner requests cancellation, **Then** NOX rejects the request without changing the payment outcome.
3. **Given** confirmation and cancellation race for the same pending payment, **When** both requests arrive concurrently, **Then** at most one outcome is accepted and the other action is rejected as no longer available.
4. **Given** the payment is already canceled, **When** the partner repeats the cancellation request, **Then** NOX returns the existing canceled outcome without creating another transaction.
5. **Given** the partner supplies `userLinkId` or `externalLinkId` with a cancellation request, **When** NOX processes it, **Then** cancellation is allowed only when the payment belongs to that connected link and authenticated partner.

### Edge Cases

- The partner has no active account when a payment is initiated.
- The active partner account changes between payment initiation and OTP confirmation.
- A payment method is missing for the requested transaction type.
- The same idempotency key is reused with different payment details.
- The same external ID is reused with a different idempotency key.
- Payment processing is unavailable or returns an unsuccessful result during initiation or confirmation.
- The OTP is incorrect, expired, or submitted after the payment has expired.
- The payment service returns a fee or money currency different from the requested currency.
- A confirmation request is repeated after a timeout where the partner did not receive the first response.
- An external link identifier is unknown to the authenticated partner, maps to a non-connected link, or is paired with a payment from a different link.
- Cancellation is attempted after payment confirmation, expiry, or another terminal outcome.
- Confirmation and cancellation race, or the cancellation result is ambiguous after a timeout.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST allow an authenticated partner to initiate pay-in and pay-out for a user with a valid connected user link.
- **FR-002**: Each initiation MUST specify the user link, transaction type, amount, currency, external ID, transaction reference, and optional description. NOX supplies the configured payment method for the transaction type.
- **FR-003**: The system MUST accept only currencies enabled for the partner and transaction type and MUST enforce that configuration's currency-specific minimum and maximum amounts.
- **FR-004**: The system MUST use an application-configured payment method for each transaction type; pay-in and pay-out values MAY differ, and partners MUST NOT choose or override these values.
- **FR-005**: The system MUST associate each initiated payment with the partner's active account at initiation time. Only one partner account is active at a time; account addition and activation are managed internally, not by partners.
- **FR-006**: The system MUST use the payment processing service to initialize pay-ins and pay-outs and MUST return the payment identifier, user link identifier, external identifier, creation date, transaction type, amount and currency, fee, transaction reference, status, and expiration date when available.
- **FR-007**: The system MUST preserve the association between an initiated payment and its account for subsequent confirmation, even if the partner's active account later changes; account changes apply to new payments.
- **FR-008**: The system MUST scope payment initiation idempotency keys to the submitting partner and MUST prevent duplicate payment creation when a request is retried with the same key and same details.
- **FR-009**: The system MUST reject reuse of an idempotency key with different payment details.
- **FR-010**: After successful initiation, the system MUST allow the partner to submit the user's OTP for that payment.
- **FR-011**: The system MUST submit a confirmation request to the payment processing service using the OTP and the payment's associated account and amount details.
- **FR-012**: The system MUST report the payment's resulting status and confirmation date when available, and MUST NOT represent a payment as completed unless the payment processing service confirms completion.
- **FR-013**: The system MUST prevent a repeated confirmation request from creating a second transaction.
- **FR-014**: The system MUST reject confirmation for a payment that does not belong to the specified user link or is not eligible for confirmation.
- **FR-015**: The system MUST communicate authentication, validation, processing, OTP, expiry, and duplicate-request failures without exposing credentials or internal service details.
- **FR-016**: The system MUST provide a separate pay-in/pay-out initiation endpoint and confirmation endpoint that accept `externalLinkId` instead of `userLinkId`.
- **FR-017**: The system MUST resolve `externalLinkId` only within the authenticated partner's links, select the most recent established matching link, and permit payment operations only when that link is connected.
- **FR-018**: The system MUST allow the partner to request cancellation of a payment awaiting OTP through link-scoped endpoints using either `userLinkId` or `externalLinkId`, and MUST report cancellation only when payment-core confirms it.
- **FR-019**: The system MUST prevent confirmation and cancellation from both succeeding for the same payment; only one terminal outcome may be accepted.
- **FR-020**: The system MUST reject cancellation for a confirmed, expired, or otherwise non-cancellable payment and MUST return the existing result for a repeated cancellation of an already-canceled payment.
- **FR-021**: The system MUST reject cancellation if the payment does not belong to the connected link identified by the request or to the authenticated partner.
- **FR-022**: A payment's external ID MUST be unique within the submitting partner across all of its payments, whatever their status; an initiation reusing it under a different idempotency key MUST be rejected without creating a payment.

### Key Entities *(include if feature involves data)*

- **Partner**: An authorized organization that initiates payments for linked users and has one active processing account at a time.
- **User Link**: The partner-facing connection that identifies the user for whom a transaction is requested.
- **Partner Account**: An internally managed account used to process a partner's payments; a payment retains the account associated at initiation.
- **Payment Configuration**: Each partner's enabled currencies and currency-specific amount limits for pay-in and pay-out, plus configured payment method values for each transaction type.
- **Payment**: A pay-in or pay-out with amount, currency, fee, external ID, transaction reference, status, creation, expiry, and optional confirmation date, associated with a user link and partner account.
- **Idempotency Key**: A request identifier that allows safe retries of payment initiation without creating duplicate payments.
- **OTP Confirmation**: The user's one-time code submitted by the partner to authorize a pending payment.
- **External Link ID**: The `partner_external_id` for a user link, scoped to the authenticated partner.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Partners can initiate a valid pay-in or pay-out and receive the required payment details in one request-response cycle in at least 99% of requests when the payment service is available.
- **SC-002**: 100% of payment initiation requests using disabled currencies or amounts outside configured limits are rejected before a payment is created.
- **SC-003**: 100% of repeated initiation requests using the same idempotency key and same details resolve to one payment only.
- **SC-004**: 100% of valid OTP confirmations for eligible payments produce a status consistent with the payment service's outcome; invalid or expired OTPs never produce a successful completion result.
- **SC-005**: Partners can complete the initiation and OTP confirmation steps for both transaction types without partner-managed account identifiers.
- **SC-006**: 100% of payment operations addressed by `externalLinkId` resolve only to a link owned by the authenticated partner, and proceed only when that link is connected.
- **SC-007**: For each pending payment, confirmation and cancellation never both succeed; a confirmed payment cannot later be canceled and a canceled payment cannot later be confirmed.

## Assumptions

- Connected user links already exist and identify the user account understood by the payment processing service.
- Currency enablement and currency-specific minimum and maximum limits are configured separately for each partner and transaction type.
- Payment method configuration provides separate values for pay-in and pay-out and is maintained internally.
- The payment processing service sends OTPs to the user and is the authority for OTP validity, transaction processing state, fee, and final outcome.
- Account changes affect new payment initiations; an already initiated payment retains its original account for confirmation.
- When payment service fields such as expiration or confirmation date are not yet available, the response may represent them as absent or null according to the established API contract.
- Authentication and authorization follow the existing partner API access model and the payment processing service's established service-to-service authentication.
- This feature covers partner initiation and OTP submission; internal partner account management and user OTP delivery are outside its scope.
- `externalLinkId` maps to the existing `partner_external_id` stored on a link; it is not a new identifier namespace.
- The user communicates the choice to confirm or cancel through the partner platform. Cancellation itself does not require an OTP; the payment processing service remains authoritative for whether the pending payment can be canceled.
