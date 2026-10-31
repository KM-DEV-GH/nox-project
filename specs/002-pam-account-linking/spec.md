# Feature Specification: Partner Link Management API

**Feature Branch**: `002-pam-account-linking`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "Partner Link Management API — Partners (external platforms that offer services to NOX users) integrate with NOX to gain payment capabilities for a user. A partner initiates a secure link request for a user by email; NOX verifies the user exists (via Owl), avoids duplicate links, and sends the user a secure approval notification. The user approves, rejects, or revokes the link at any time. Status changes are recorded in an auditable history; approvals and rejections are published as notifications (to the user) and webhook events (to the partner). Users and partners can both query current link status. Payment operations (PAYIN/PAYOUT) and the BFF component are out of scope."

## Clarifications

### Session 2026-10-26

- Q: When a user "inactivates" a link rather than revoking it, what status does the link end up in, and can it be reactivated later without a brand-new partner link request? → A: There is no separate "inactivate" action — only `revoke` exists, which is terminal (`REVOKED`). Reconnecting requires a brand-new partner link request.
- Q: What should happen if the Owl lookup (checking whether the user's email matches an existing NOX account) fails or times out while NOX is processing a new link request? → A: The link request still returns `ACCEPTED` immediately; NOX retries the Owl lookup in the background until it succeeds or a retry limit is hit. Enumeration-safety and the "PROCESSING until reportable outcome" contract are preserved regardless of the Owl outcome.
- Q: How does a link expire — a background job, or a deadline check? → A: No scheduler. Each link carries an approval deadline (date-time). A user approval or rejection at or after that deadline is refused and the link is recorded as `EXPIRED` at that moment; reads report a past-deadline, undecided link as `EXPIRED`.
- Q: What does `PENDING` mean? → A: Any link that has not been approved, rejected, or revoked is `PENDING` (awaiting the user's decision). There is no intermediate post-approval state: approval moves a link straight to `CONNECTED`.
- Q: Which tokens call this API, and how is the user identified? → A: User-facing endpoints are called with the user's own Keycloak token; partner-facing endpoints are called by the BFF with an application-to-application token. Owl identifies a person by `user_id`.
- Q: How is a user-facing call matched to a user, given that Owl's `user_id` differs from the Keycloak subject? → A: Owl's lookup returns both the Owl `user_id` and the user's `keycloak_id`. NOX stores both on the link and matches user-facing calls on the Keycloak subject — no extra Owl call.
- Q: Which users may be linked? → A: Only users whose Owl `verification_status` is `APPROVED`. Any other status is treated exactly like "no user found".
- Q: How does NOX authenticate to Owl? → A: With both an OAuth2 client-credentials token and Owl's api-key, sent on every call.
- Q: Does every link status change notify the user and the partner? → A: No — only a user's approval or rejection publishes the user notification and the partner webhook event. Revocation and expiry publish nothing. (The approval-request notification that asks the user to decide is separate and still sent, FR-006.)
- Q: When a partner reads a link by its own external identifier and more than one link exists over time, which one is returned? → A: A user can have only one active link per partner, and the most recently created link is always the one returned.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Partner requests a link to a user's account (Priority: P1)

A partner, acting on behalf of a payment flow it wants to offer a user, submits a link request identifying the user by email and its own external reference for that user. The request is accepted for processing without confirming anything about whether the user exists, so the partner cannot use this call to discover account existence.

**Why this priority**: This is the entry point of the entire feature — no link, history, or later user action is possible without it. It must also be safe to call repeatedly without creating duplicate links.

**Independent Test**: Can be fully tested by submitting a link request for a known user and for an unknown email and confirming both return an identical `202 Accepted` response with a request identifier, with no indication of the user's account state.

**Acceptance Scenarios**:

1. **Given** a user with a NOX account and no active link to this partner, **When** the partner submits a link request with the user's email and its external identifier, **Then** the request is accepted (`ACCEPTED`), a `PENDING` link is created once the user is resolved, and a secure approval notification is sent to the user's registered email.
2. **Given** a user who already has a `CONNECTED` link, or a `PENDING` link whose deadline has not passed, with this same partner, **When** the partner submits another link request for the same user, **Then** no new link is created and the caller still receives the same generic `ACCEPTED` response.
3. **Given** an email address that does not correspond to any NOX user, **When** the partner submits a link request, **Then** the response is identical in shape, status, and timing characteristics to a request for a real user, revealing nothing about account existence.
4. **Given** a link request already submitted once, **When** the same request is resubmitted with the same idempotency key, **Then** NOX returns the original outcome rather than creating a duplicate link request.

---

### User Story 2 - User approves or rejects a pending link (Priority: P1)

A user receives a secure notification prompting them to approve or reject a partner's link request. Approving establishes the connection; rejecting permanently declines it. Either action is recorded with who did it and when, and both the user and the partner are informed.

**Why this priority**: The user's decision is the crux of the consent model this whole feature exists to enforce — without it, a link can never legitimately reach `CONNECTED`.

**Independent Test**: Can be fully tested by having a user approve a pending link and confirming it becomes `CONNECTED`, with the decision recorded in history and both a user notification and a partner webhook event published.

**Acceptance Scenarios**:

1. **Given** a `PENDING` link whose deadline has not passed, **When** the user approves it, **Then** the link becomes `CONNECTED`, the decision and actor are appended to the link's history, a notification is sent to the user, and a webhook event containing the link identifier is published to the partner.
2. **Given** a `PENDING` link whose deadline has not passed, **When** the user rejects it, **Then** the link becomes `REJECTED`, the decision and actor are appended to history, and both the user notification and the partner webhook event are published (without a link identifier, since no connection was established).
3. **Given** a link whose deadline has passed, **When** the user attempts to approve or reject it, **Then** the action is refused, the link is recorded as `EXPIRED`, and the user is told the request has expired.
4. **Given** a link that was already approved or rejected, **When** the user repeats the action, **Then** the repeat is refused without creating a duplicate history entry or duplicate notifications.

---

### User Story 3 - User and partner check current link status (Priority: P1)

Both the user and the partner need to know, at any time, the current state of a link — the user to manage their connected services, and the partner to decide whether it may offer payment features.

**Why this priority**: Status visibility is required for every other interaction (deciding whether to allow payment, whether to prompt the user again, whether to show the link as active) and is needed from day one alongside request creation.

**Independent Test**: Can be fully tested by querying a request that is still processing, a request that has reached a final outcome, and an established link by its identifier or by the partner's own external identifier, and confirming each returns the correct, enumeration-safe status.

**Acceptance Scenarios**:

1. **Given** a link request with no reportable outcome yet (including an unknown email, an unresolved lookup, or a link still `PENDING`), **When** the partner queries the request's status, **Then** the response reports `PROCESSING` and reveals nothing about account existence, eligibility, or notification delivery.
2. **Given** a link request that has reached a reportable outcome (`CONNECTED`, `REJECTED`, `EXPIRED`, `REVOKED`), **When** the partner queries the request's status, **Then** that outcome is returned.
3. **Given** an established link (`CONNECTED` or `REVOKED`), **When** the partner retrieves it by the link identifier NOX previously supplied, or by its own external identifier, **Then** the current status is returned; for any other link, or when no link exists, the partner receives the same not-found response.
4. **Given** a user who has one or more links, **When** the user requests their list of links, **Then** every link they have (regardless of status) is returned with its current status, and the user can also request the full detail of a single link.

---

### User Story 4 - User revokes an established link (Priority: P2)

A user who no longer wants a partner to be able to act on their account can revoke it at any time, immediately and permanently invalidating it for any future payment activity.

**Why this priority**: Ongoing user control over active connections is a core trust requirement, but it only matters once links can reach a connected state, making it secondary to establishing and viewing links.

**Independent Test**: Can be fully tested by revoking a `CONNECTED` link and confirming its status changes to `REVOKED` and the change is recorded in history.

**Acceptance Scenarios**:

1. **Given** a `CONNECTED` link, **When** the user revokes it, **Then** the link status becomes `REVOKED` and the action and actor are recorded in history. No notification or webhook event is published; the partner sees `REVOKED` on its next read.
2. **Given** a link that is `PENDING`, `REVOKED`, `REJECTED`, or `EXPIRED`, **When** the user attempts to revoke it, **Then** the action is refused since no active connection exists to disconnect.
3. **Given** a link that has been revoked, **When** the partner later attempts a payment operation referencing that link, **Then** that operation is rejected because the link is no longer valid (payment operations themselves are out of scope of this feature).

---

### User Story 5 - Auditing link history (Priority: P3)

Anyone responsible for investigating a dispute or compliance question about a link needs to see the full sequence of recorded status changes for that link, who performed each change, and when.

**Why this priority**: Auditability is a compliance and support necessity but doesn't block the core linking flow from being usable, so it can follow the primary interactions.

**Independent Test**: Can be fully tested by performing several status changes on a link (created, approved, later revoked) and confirming the retrieved history shows all three entries, in order, each with a timestamp and the responsible actor.

**Acceptance Scenarios**:

1. **Given** a link that has gone through creation, approval, and revocation, **When** its history is retrieved, **Then** all three events appear in chronological order, each with a timestamp and the actor responsible (the user, the requesting partner, or NOX).
2. **Given** a link whose deadline passed and the user then tried to approve or reject it, **When** its history is retrieved, **Then** the expiry is recorded as a NOX-initiated event timestamped at the moment the late action was refused.

---

### Edge Cases

- What happens when a partner submits a link request for a user who has a link with a *different* partner? This MUST NOT be treated as a duplicate; each user-partner pair is independent.
- What happens when the notification exchange or the partner webhook delivery fails after an approval or rejection? The status change itself MUST NOT be lost or rolled back; delivery MUST be retried independently of the state transition that already occurred.
- What happens when two status-changing actions race on the same link (e.g. a late user approval and a new partner request that supersedes the past-deadline link)? The system MUST apply a single consistent final state and record exactly one history entry for the transition that actually won.
- What happens when a partner polls the link-request fallback endpoint after the link has already reached a final outcome? It MUST continue to return the current reportable status rather than an error.
- What happens when the lookup to the user management service (Owl) fails or times out? The partner-facing response MUST remain the same generic `ACCEPTED` acknowledgment; NOX MUST retry the lookup in the background within a bounded retry limit. If it never resolves, the request reports `PROCESSING` until its deadline and `EXPIRED` afterwards — exactly as an unknown email would — never surfacing the dependency failure to the partner.
- What happens when a link passes its deadline with no user action? Reads report it as `EXPIRED`; no history entry, notification, or webhook event is produced until something touches the link (a late user action or a new request for the same pair).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST accept a link request from a partner identifying the user by email and the partner's own external identifier for that user, and MUST respond with an accepted acknowledgment that does not reveal, by content or timing, whether a matching user account exists.
- **FR-002**: System MUST resolve the user by looking up the supplied email with the user management service (Owl) to obtain the Owl `user_id` and the user's Keycloak id, after acknowledging the request and without exposing the outcome of that lookup to the partner. Only a single matching user whose verification status is `APPROVED` counts as found; no match, more than one match, or any other verification status MUST be treated identically as "no user". If the lookup fails or times out, it MUST be retried in the background within a bounded retry limit.
- **FR-003**: System MUST NOT create a new link when the same user already has a `CONNECTED` link, or a `PENDING` link whose deadline has not passed, with the same partner; the request is still acknowledged generically. A user has at most one active (`PENDING` or `CONNECTED`) link per partner.
- **FR-004**: System MUST support idempotent submission of the same link request (via a caller-supplied idempotency key), returning the original outcome instead of creating a second link request on retry.
- **FR-005**: System MUST persist the link request before acknowledging it, and MUST persist the link before sending the user's approval notification.
- **FR-006**: System MUST send the user a secure approval notification for every newly created link, by publishing to the notification service.
- **FR-007**: System MUST allow the user to approve or reject a `PENDING` link before its deadline, and MUST refuse the action if the link has already reached a final outcome. Approval moves the link directly to `CONNECTED`.
- **FR-008**: System MUST update the link's status whenever the user approves, rejects, or revokes it, and when NOX records an expiry.
- **FR-009**: System MUST record every status transition it writes to a link in an auditable history, including the timestamp and the actor responsible (user, partner, or NOX). A status derived on read (an undecided link past its deadline reported as `EXPIRED`) is not a written transition and has no history entry until it is recorded.
- **FR-010**: System MUST make a link's full history retrievable for audit purposes.
- **FR-011**: System MUST publish, when and only when a user approves or rejects a link, one notification to the user and one message intended for partner webhook consumption; the partner-facing message MUST include the link identifier for an approval. Revocation and expiry publish no messages.
- **FR-012**: System MUST let a partner retrieve the status of a submitted link request by its request identifier, returning `PROCESSING` before any reportable outcome exists (`CONNECTED`, `REJECTED`, `EXPIRED`, `REVOKED`), with no leakage of account existence, eligibility, or notification delivery.
- **FR-013**: System MUST let a partner retrieve an established (`CONNECTED` or `REVOKED`) link by the NOX-issued link identifier or by the partner's own external identifier; for an external identifier with several links over time, the most recently created link is the one returned. Any other case returns the same not-found response.
- **FR-014**: System MUST let a user retrieve the full list of their links with current statuses, and retrieve the full detail of any single link they own.
- **FR-015**: System MUST let a user revoke a `CONNECTED` link at any time, immediately and permanently invalidating it for future payment activity.
- **FR-016**: System MUST refuse revoke actions on links that are not `CONNECTED`.
- **FR-017**: Each link MUST carry an approval deadline (date-time), fixed when the link request is submitted. The system MUST refuse a user approval or rejection received at or after that deadline, record the link as `EXPIRED` at that moment, and record the expiry as a NOX-initiated history event. No background job expires links; any read of an undecided link at or after its deadline MUST report it as `EXPIRED`, and such a link MUST NOT block a new link request for the same user-partner pair (FR-003).
- **FR-018**: System MUST use only these statuses in externally observable responses: link-request creation returns `ACCEPTED`; the request status endpoint returns `PROCESSING`, `CONNECTED`, `REJECTED`, `EXPIRED`, or `REVOKED`; links are `PENDING`, `CONNECTED`, `REJECTED`, `EXPIRED`, or `REVOKED`. `SUSPENDED` is reserved for a future feature and is not produced by this one.
- **FR-019**: System MUST accept partner-facing calls only from the BFF's application-to-application token, and MUST honour the partner identity header only on such calls; user-facing calls MUST be accepted only with the user's own Keycloak token and MUST act only on links belonging to that user.

### Key Entities

- **Link**: The connection between one user and one partner. Key attributes: link identifier, Owl `user_id`, the user's Keycloak id, partner reference, the partner's external identifier for the user, licensee, current status, approval deadline, created/updated timestamps. Has one ordered history.
- **Link Request**: One attempt by a partner to establish a link. Key attributes: request identifier, idempotency key, partner reference, submission timestamp, approval deadline, the resolved link (if any). Exists even when no matching user is found.
- **Link History Entry**: An immutable, timestamped record of one written status transition for a link. Key attributes: link reference, previous status, new status, actor (user, partner, or NOX), timestamp, reason.
- **User**: The NOX account holder, identified by the Owl `user_id` (and authenticated by a Keycloak token whose subject is the user's Keycloak id), who owns zero or more links. NOX never uses the terms "player" or "PAM".
- **Partner**: The external service identified by a client/service identifier, which owns zero or more links and consumes webhook-style status update messages.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A partner can submit a link request and receive an acknowledgment in under 2 seconds, regardless of whether the referenced user exists.
- **SC-002**: 100% of link-request and status-check responses issued before a reportable outcome are indistinguishable between an existing and a non-existing user account (verified by automated tests).
- **SC-003**: A user can view the complete, current status of every link they have in a single request.
- **SC-004**: A user can revoke any `CONNECTED` link, with the change taking effect (blocking new payment activity) immediately and without needing partner cooperation.
- **SC-005**: 100% of written link status transitions produce a corresponding, retrievable history entry with a timestamp and responsible actor.
- **SC-006**: After a user approves or rejects a link, both the user notification and the partner webhook event are confirmed by the message broker within 5 seconds (95th percentile) of the decision being committed.
- **SC-007**: Duplicate link requests never result in more than one active link between the same user and partner.

## Assumptions

- The "secure approval notification" sent to the user leads to a user-facing approval surface (e.g. a web page or app screen reached via a link in the email); building that surface is out of scope here, as is the BFF that fronts it — this specification covers only the resulting NOX API behavior once an approve/reject/revoke action occurs.
- The approval window duration is a configuration value, not a product decision needed at this stage.
- After a link reaches `EXPIRED`, `REJECTED`, or `REVOKED`, a partner may submit a new link request for the same user (no permanent lockout).
- "Licensee" scopes a link to a brand/jurisdiction context but does not change the status model.
- Message delivery is at-least-once: the status transition is recorded first, and delivery is guaranteed afterwards through a transactional outbox, independently of the request that caused it.
- Owl's `keycloak_id` for a user is the subject of that user's Keycloak token, and does not change while a link exists. The lookup contract NOX relies on is in [contracts/owl-user-lookup.md](contracts/owl-user-lookup.md).
- Suspending a link (`SUSPENDED`) is out of scope for this feature.
- Only the user, the owning partner, and NOX itself can be the actor recorded against a history entry.
