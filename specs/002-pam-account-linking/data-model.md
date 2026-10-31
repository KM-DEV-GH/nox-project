# Phase 1 Data Model: Partner Link Management API

Terminology: NOX always says **user** (never "player") and **partner** (never "PAM"). A user is
identified by Owl's **`user_id`**; a partner is an external platform integrating with NOX for its users.

## Entity: Partner

Master-data record for a partner integration, so links reference a stable internal identity instead
of the partner's `client_id` (a `client_id` rotation updates one row). **Provisioning is out of scope**:
rows are created and maintained out-of-band; this feature only reads them.

| Field | Type | Notes |
|---|---|---|
| `id` (`partner_id`) | UUID, PK | What `PartnerLink` and `PartnerLinkRequest` reference |
| `client_id` | String, unique, NOT NULL | The value the BFF sends as `x-service-id`; resolved to a `Partner` row |
| `name` | String, NOT NULL | Display name |
| `website` | String, nullable | |
| `status` | enum `PartnerStatus` (`ACTIVE`, `SUSPENDED`, `DEREGISTERED`), NOT NULL | Only `ACTIVE` partners may call |
| `valid_from` | timestamp (UTC), NOT NULL | |
| `valid_to` | timestamp (UTC), nullable | Null while still valid |
| `created_at` / `updated_at` | timestamp (UTC), NOT NULL | |

**Validation rules**: an `x-service-id` that does not resolve to an `ACTIVE` `Partner` inside
`[valid_from, valid_to)` is rejected with `403` before any request processing. This reveals nothing
about any user, only about the calling partner.

## Entity: PartnerHistoryEntry

Append-only audit of changes to a `Partner` row (one row per changed field per update).

| Field | Type | Notes |
|---|---|---|
| `id` | UUID, PK | |
| `partner_id` | UUID, FK → `Partner.id`, NOT NULL | |
| `field` | String, NOT NULL | e.g. `client_id`, `status`, `valid_to` |
| `old_value` | String, nullable | |
| `new_value` | String, NOT NULL | |
| `changed_by` | String, NOT NULL | Operator or system that made the change |
| `occurred_at` | timestamp (UTC), NOT NULL | |
| `reason` | String, nullable | |

## Entity: PartnerLinkRequest

One submission by a partner (FR-001, FR-004, FR-005, FR-012). Created **before** the Owl lookup and
always persisted, even when no user matches (research.md decision 1).

| Field | Type | Notes |
|---|---|---|
| `id` (`requestId`) | UUID, PK | Returned to the partner |
| `partner_id` | UUID, FK → `Partner.id`, NOT NULL | Resolved from `x-service-id` |
| `idempotency_key` | String, NOT NULL | Unique per `partner_id`: unique constraint on `(partner_id, idempotency_key)` (FR-004) |
| `partner_external_id` | String, NOT NULL | The partner's `externalId` for the user, copied to the link once created |
| `licensee_id` | String, NOT NULL | Copied to the link once created |
| `email` | String, nullable | Kept only until the lookup finishes (resolved, not found, or failed), then set to null |
| `lookup_status` | enum `LookupStatus` (`PENDING`, `RESOLVED`, `NOT_FOUND`, `FAILED`), NOT NULL | Internal only; never exposed to the partner |
| `link_id` | UUID, FK → `PartnerLink.id`, nullable | Set when a user is resolved: the new link, or the existing active link if the request was a duplicate (FR-003) |
| `correlation_id` | UUID, NOT NULL | Carried on the async lookup and on messages as `correlationId` |
| `submitted_at` | timestamp (UTC), NOT NULL | |
| `expires_at` | timestamp (UTC), NOT NULL | `submitted_at` + configured approval window (FR-017) |

**Reported status** (request status endpoint, FR-012, FR-018), derived — never stored:

| Situation | Reported |
|---|---|
| `link_id` is null (lookup pending, not found, or failed) and now < `expires_at` | `PROCESSING` |
| `link_id` is null and now ≥ `expires_at` | `EXPIRED` |
| Link is `PENDING` and now < link `expires_at` | `PROCESSING` |
| Link is `PENDING` and now ≥ link `expires_at` | `EXPIRED` |
| Link is `CONNECTED`, `REJECTED`, `EXPIRED`, or `REVOKED` | that status |

A known and an unknown email therefore produce the same sequence of responses at the same times.

## Entity: PartnerLink

The connection between one user and one partner.

| Field | Type | Notes |
|---|---|---|
| `id` (`linkId`) | UUID, PK | Sent to the partner in the approval webhook |
| `user_id` | String, NOT NULL | Owl `user_id` (Owl's `id`); the canonical user identity, used for uniqueness |
| `user_keycloak_id` | String, NOT NULL | Owl's `keycloak_id` for the same user = the subject of the user's Keycloak token; user-facing calls are matched on this (research.md decision 6) |
| `partner_id` | UUID, FK → `Partner.id`, NOT NULL | |
| `partner_external_id` | String, NOT NULL | The partner's `externalId` for this user |
| `licensee_id` | String, NOT NULL | Per-link, not per-partner |
| `status` | enum `PartnerLinkStatus` (`PENDING`, `CONNECTED`, `REJECTED`, `EXPIRED`, `REVOKED`, `SUSPENDED`), NOT NULL | `SUSPENDED` is reserved; this feature never writes it |
| `expires_at` | timestamp (UTC), NOT NULL | Copied from the request that created the link (FR-017) |
| `version` | bigint, NOT NULL | JPA `@Version` optimistic lock (research.md decision 4) |
| `created_at` / `updated_at` | timestamp (UTC), NOT NULL | |

**Constraints and indexes**:

- Partial unique index on `(user_id, partner_id)` WHERE `status IN ('PENDING', 'CONNECTED')` — at most
  one active link per user per partner (FR-003, SC-007).
- Index on `(partner_id, partner_external_id, created_at DESC)` — "the most recently created link"
  lookup by external identifier (FR-013).
- Index on `(user_keycloak_id)` — the user's link list and ownership checks on user-facing calls (FR-014, FR-019).

**State transitions**:

```text
(created) ──► PENDING ──┬──► CONNECTED ──► REVOKED   (user approves, then revokes)
                        ├──► REJECTED                  (user rejects)
                        └──► EXPIRED                   (written by NOX: late approve/reject,
                                                        or superseded by a new request)
```

- Every status that is not `CONNECTED`, `REJECTED`, `REVOKED` or `EXPIRED` is `PENDING`. There is
  no intermediate post-approval state.
- Only the user moves a link to `CONNECTED`, `REJECTED` or `REVOKED`; only NOX writes `EXPIRED`.
- `REJECTED`, `EXPIRED` and `REVOKED` are terminal; a new request creates a new link.
- **Effective status on read**: a `PENDING` link at or after `expires_at` is reported as `EXPIRED`
  without being written (`PartnerLink.effectiveStatus(now)`).
- **Deadline rule**: an approve or reject at or after `expires_at` is refused and the link is
  written as `EXPIRED` in the same transaction (the use case returns an `Expired` outcome rather
  than throwing, so the write commits).

## Entity: PartnerLinkHistoryEntry

Append-only record of each **written** transition (FR-009, FR-010, SC-005).

| Field | Type | Notes |
|---|---|---|
| `id` | UUID, PK | |
| `link_id` | UUID, FK → `PartnerLink.id`, NOT NULL | |
| `from_status` | enum `PartnerLinkStatus`, nullable | Null for the creation entry |
| `to_status` | enum `PartnerLinkStatus`, NOT NULL | |
| `actor_type` | enum `ActorType` (`USER`, `PARTNER`, `NOX`), NOT NULL | |
| `actor_reference` | String, nullable | `user_id` for `USER`, `Partner.id` for `PARTNER`, null for `NOX` |
| `occurred_at` | timestamp (UTC), NOT NULL | |
| `reason` | String, NOT NULL | One of `link_created`, `user_approved`, `user_rejected`, `user_revoked`, `expired_on_late_action`, `expired_on_new_request` |

**Validation rules**: append-only; exactly one row per written transition, including the creation
entry `(null → PENDING, actor PARTNER)`. A derived `EXPIRED` on read writes no row.

## Domain events and the outbox

The transactional outbox is Spring Modulith's own `event_publication` table (research.md
decision 3a), created by a generated Liquibase changeset. Events are plain records in
`application.event`; their exchange and routing key are configured in
`PartnerLinkEventExternalizationConfig` (infrastructure), not on the classes. Payload schemas are in
[contracts/events.md](contracts/events.md).

| Domain event | Exchange :: routing key | Published when |
|---|---|---|
| `PartnerLinkRequestSubmitted` | not externalized — internal trigger for the async Owl lookup | Request persisted (research.md decision 1) |
| `PartnerLinkApprovalRequested` | `partnerlink.events` :: `partner-link.approval-requested.v1` | Link created as `PENDING` (FR-006) |
| `PartnerLinkApprovedForUser` / `PartnerLinkApprovedForPartner` | `partnerlink.events` / `partnerlink.webhooks` :: `partner-link.approved.v1` | User approves (FR-011; partner payload includes `linkId`) |
| `PartnerLinkRejectedForUser` / `PartnerLinkRejectedForPartner` | `partnerlink.events` / `partnerlink.webhooks` :: `partner-link.rejected.v1` | User rejects (FR-011) |

Revocation and expiry publish no events (spec clarification, FR-011).

A feature-owned table `partner_link_stuck_publication` records publications still incomplete after the
configured age (research.md decision 3a):

| Field | Type | Notes |
|---|---|---|
| `id` | UUID, PK | |
| `event_publication_id` | UUID, NOT NULL, unique | Modulith publication id |
| `event_type` | String, NOT NULL | |
| `flagged_at` | timestamp (UTC), NOT NULL | |

## Relationships

```text
Partner (1) ──< (0..n) PartnerHistoryEntry
Partner (1) ──< (0..n) PartnerLinkRequest
Partner (1) ──< (0..n) PartnerLink
PartnerLink (1) ──< (0..n) PartnerLinkRequest      (duplicates point at the existing active link)
PartnerLink (1) ──< (1..n) PartnerLinkHistoryEntry
```

## Out of scope for this data model

- `currencies` / payment limits.
- A local users table — Owl owns users; NOX stores only `user_id`.
- Payment records (PAYIN/PAYOUT).
- `Partner` provisioning.
- Writing `SUSPENDED` (reserved status).
