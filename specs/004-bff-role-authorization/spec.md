# Feature Specification: BFF Role-Based Access

**Feature Branch**: `004-bff-role-authorization`

**Created**: 2026-10-30

**Status**: Draft

**Input**: User description: "two different BFF with two different roles will call nox. - \"nox-bff\" with role \"partner-access\" - \"bff\" with role \"internal-access\". `partner-link-query-controller` and `my-partner-link-controller` are accable by `internal-roll` and `partner-link-request-controller` accassble by `partner-access` role. I also prefer to rename \"my-partner-link-controller\" to \"partner-link-controller\"" — clarified: the link query endpoints stay on `partner-access`; the internal service identifies the person through an `X-User-ID` header; a person's own token is no longer accepted on the user link endpoints; and the user link surface moves from `/api/v1/me/partner-links` to `/api/v1/user-partner-links`.

## Clarifications

### Session 2026-10-30

- Q: Should `internal-access` only be honored for a service-account caller, the way the partner surface already restricts `nox-bff` to service callers? → A: Option A — only a SERVICE-type caller is honored; any person's own token is refused on the user link surface whatever roles it carries. As a general rule, no person calls Nox directly: a front-end service is always in between.
- Q: Does the "no person calls Nox directly" rule also close `GET /api/v1/me`, the caller identity endpoint, to person-held tokens? → A: Option A — leave `/api/v1/me` unchanged; the service-only rule applies only to partner link surfaces.
- Q: What identifier must the internal BFF send in `X-User-ID` to identify the person whose links it manages? → A: the internal user id, in UUID format — the same internal user identifier already stored on each link, distinct from the Keycloak subject id used to identify the token's own caller.
- Q: Should every denied call across all three partner link surfaces (link requests, link queries, and user links) be recorded as an authorization failure event, not just denials on the link-request endpoints? → A: Option A — every denial on any of the three surfaces is recorded as an authorization failure event, carrying the caller, the endpoint, and the reason.
- Q: The `/api/v1/me/partner-links` prefix reads as "the caller's own links", which is no longer true now that a front-end service always calls on a person's behalf. What should replace it? → A: Option B — move the surface to `/api/v1/user-partner-links` and keep the acting person in the `X-User-ID` header, so the person's id stays out of URLs and access logs. `GET /api/v1/me` is still left unchanged.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Partner BFF uses the partner surface (Priority: P1)

The partner-facing front-end service calls Nox on behalf of a partner: it starts an account
link, checks the outcome of a request it created, and reads the resulting link by identifier
or by the partner's own external identifier. Nox must accept that caller only when it is a
service client whose access token carries the partner-access permission, and must refuse it on
the user link surface.

**Why this priority**: Partner-initiated linking is the entry point of the whole flow; without
it no link exists to decide on. It is also the surface exposed to the least-trusted caller, so
its boundary must be the tightest.

**Independent Test**: Issue a token for the partner-facing caller carrying partner-access and
confirm it can create and read link requests and read links, while the same token is rejected
on the user link surface.

**Acceptance Scenarios**:

1. **Given** a caller with the partner-access permission and a valid partner identification, **When** it submits a link request, **Then** the request is created and returned as today.
2. **Given** a caller with the partner-access permission, **When** it reads a link request it created, **Then** the request is returned as today.
3. **Given** a caller with the partner-access permission, **When** it reads a link by identifier or by the partner's external identifier, **Then** only links of the partner it acts for are returned, exactly as today.
4. **Given** a caller with the partner-access permission, **When** it calls any user link endpoint, **Then** access is refused with the standard "forbidden" response and no data is disclosed.
5. **Given** a caller without the partner-access permission, **When** it calls any partner surface endpoint, **Then** access is refused with the standard "forbidden" response.

---

### User Story 2 - Internal BFF drives user link decisions (Priority: P2)

The internal front-end service calls Nox on behalf of a signed-in person to list that person's
links, read one link, read its history, and approve, reject, or revoke it. Nox must accept that
caller only when it is a service client whose access token carries the internal-access
permission, and every call must be scoped to the person named in the request so no caller can
see or change another person's links.

**Why this priority**: These are the actions a person takes on their own links — the visible
value of the product — but they depend on links created in Story 1.

**Independent Test**: Issue a token for the internal caller carrying internal-access, act on a
known person's links, and confirm that another person's links are reported as not found.

**Acceptance Scenarios**:

1. **Given** a caller with the internal-access permission naming a person, **When** it lists that person's links, **Then** only that person's links are returned.
2. **Given** a caller with the internal-access permission naming a person, **When** it reads one of that person's links or its history, **Then** the data is returned exactly as today.
3. **Given** a caller with the internal-access permission naming a person, **When** it approves, rejects, or revokes one of that person's links, **Then** the decision is applied exactly as today.
4. **Given** a caller with the internal-access permission naming a person, **When** it addresses a link belonging to someone else, **Then** the response is "not found" and no data is disclosed.
5. **Given** a caller without the internal-access permission, **When** it calls any user link endpoint, **Then** access is refused with the standard "forbidden" response.

---

### User Story 3 - Only front-end services reach the partner link surfaces (Priority: P2)

A person's own access token is no longer accepted on any partner link endpoint; every one of
them is reached through a front-end service acting for that person or partner. This keeps one
single rule per surface and one place where the acting person is established.

**Why this priority**: It closes the previous access path and must land together with Story 2
so the user link surface is never reachable by two different rules at once.

**Independent Test**: Call a user link endpoint with a person's own token and confirm it is
refused, while the internal service naming the same person succeeds.

**Acceptance Scenarios**:

1. **Given** a valid token belonging to a person, **When** that token is used on any partner link endpoint, **Then** access is refused with the standard "forbidden" response.
2. **Given** a valid token belonging to a person that also carries the internal-access or partner-access permission, **When** it is used on any partner link endpoint, **Then** access is still refused: the permission counts only for a front-end service's own token.
3. **Given** a valid token belonging to a front-end service, **When** it calls the surface its permission opens, **Then** the person or partner it acts for is taken from the request, never from the token.

---

### User Story 4 - Consistent naming of the user link surface (Priority: P3)

The component serving the user link surface is named "partner link controller" instead of
"my partner link controller", and the surface moves from `/api/v1/me/partner-links` to
`/api/v1/user-partner-links`, so both the name and the path match a surface that is now called
by a front-end service rather than by the person themselves.

**Why this priority**: Naming and path shape only; it carries no behaviour change and can ship
last without blocking any other story, provided the front-end service is updated with it.

**Independent Test**: The user link endpoints answer exactly as before under their new prefix,
with the same payloads and error responses, and the old prefix no longer serves them.

**Acceptance Scenarios**:

1. **Given** the rename is applied, **When** any user link endpoint is called under `/api/v1/user-partner-links`, **Then** its request payload, response payload, and error behaviour are unchanged from the old prefix.
2. **Given** the rename is applied, **When** any user link endpoint is called under the old `/api/v1/me/partner-links` prefix, **Then** the user link surface no longer serves it.

---

### Edge Cases

- A token carries **both** permissions: each endpoint is reached if the service client's token carries the permission that endpoint requires; holding the other permission neither grants nor blocks anything extra.
- A token carries **neither** permission but is otherwise valid: every partner link endpoint is refused with "forbidden"; the caller identity endpoint and the public probes are unaffected.
- A token is missing, expired, or malformed: the request fails as "unauthenticated", before any permission is considered.
- A **person's own token** reaches any partner link endpoint: it is refused with "forbidden" before any permission or header is considered, because only a service client may call these surfaces.
- The person header is **missing or blank** on a user link call: the request is refused with a client error; the calling service's own identity is never used instead.
- The person header names someone who does not exist or has no links: list calls return an empty result and single-link calls return "not found", with no indication of whether the person exists.
- The person header is sent by a caller that is **not** a service client holding internal-access: it is ignored entirely and the request is refused on the caller and permission alone.
- The partner-facing caller presents a partner identification for a partner that may not call: the request is refused exactly as today; the permission alone is never enough.
- The permission names are not yet assigned in the identity provider: all partner link endpoints deny, and the denials are recorded as authorization failures for diagnosis.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST grant access to the link request endpoints (`POST /api/v1/partner-link-requests`, `GET /api/v1/partner-link-requests/{requestId}`) only to callers that are a service client whose validated access token carries the `partner-access` permission.
- **FR-002**: System MUST grant access to the link query endpoints (`GET /api/v1/partner-links/{linkId}`, `GET /api/v1/partner-links/by-external/{externalId}`) only to callers that are a service client whose validated access token carries the `partner-access` permission, keeping today's scoping to the partner the caller acts for.
- **FR-003**: System MUST grant access to the user link endpoints (listing, reading, history, approve, reject, revoke under `/api/v1/user-partner-links`) only to callers that are a service client whose validated access token carries the `internal-access` permission.
- **FR-004**: System MUST refuse, with the standard "forbidden" response, any request to a partner link endpoint made with a person's own access token, whatever permissions that token carries; only a front-end service's own token is honored.
- **FR-005**: System MUST determine the person a user link call acts for solely from the internal user id supplied in the `X-User-ID` request header, and MUST NOT fall back to the identity in the token.
- **FR-006**: System MUST refuse a user link request whose `X-User-ID` header is absent, blank, or not a valid UUID, with a client error that names the missing or invalid header.
- **FR-007**: System MUST ignore the `X-User-ID` header entirely for any caller that is not a service client holding the `internal-access` permission, so the header can never widen access on its own.
- **FR-008**: System MUST keep every user link operation scoped to the person named by the `X-User-ID` internal user id, and MUST report any link belonging to another person as "not found".
- **FR-009**: System MUST refuse, with the standard "forbidden" response and no data disclosure, any caller that reaches an endpoint without the permission that endpoint requires, including a caller holding only the other permission.
- **FR-010**: System MUST deny by default: an endpoint that is not explicitly granted to a permission remains closed to the front-end services.
- **FR-011**: System MUST decide permissions solely from the validated access token, never from a header, body field, or other client assertion.
- **FR-012**: System MUST continue to require, in addition to the `partner-access` permission, that the partner the caller acts for is identified and currently allowed to call, and MUST keep returning today's response when it is not.
- **FR-013**: System MUST record every denied call to any of the three partner link surfaces — link requests, link queries, and user links — as an authorization failure event carrying the caller, the endpoint, and the reason, without logging token contents.
- **FR-014**: System MUST record the internal user id named in `X-User-ID` on user link audit and history entries so a decision remains attributable to that person rather than to the calling service.
- **FR-015**: System MUST preserve the existing request payloads, response payloads, and error responses of all partner link endpoints, and the existing paths of the link request and link query endpoints; this feature changes who may call them, how the acting person is supplied, the user link path prefix, and one internal component name.
- **FR-016**: System MUST name the component serving the user link surface "partner link controller" (previously "my partner link controller"), with its tests and documentation renamed to match.
- **FR-017**: System MUST allow the two permissions to be held by different callers independently, so adding or removing one front-end service requires no change to the other's access.
- **FR-018**: System MUST serve the user link endpoints under `/api/v1/user-partner-links`, replacing the `/api/v1/me/partner-links` prefix whose "me" no longer describes the caller, keeping each endpoint's remaining path, verb, and payloads as they are today.
- **FR-019**: System MUST stop serving the `/api/v1/me/partner-links` paths once this feature ships, so no caller can reach the user link surface by the old prefix.

### Key Entities

- **Calling front-end service**: a service client that calls Nox for someone else. Two are expected: the partner-facing one (`nox-bff`) and the internal one (`bff`). Each is known by its client identity and the permissions in its token.
- **Access permission**: a named permission carried in the access token. `partner-access` opens the link request and link query surfaces; `internal-access` opens the user link surface.
- **Endpoint group**: a set of endpoints sharing one permission requirement — the partner surface (link requests and link queries) and the user link surface.
- **Acted-for person**: the person whose links a user link call concerns, identified by their internal user id (UUID) supplied per request in the `X-User-ID` header and trusted only from a service client holding `internal-access`. This is distinct from the Keycloak subject id that identifies the calling token itself.
- **Acted-for partner**: the partner whose data a partner surface call concerns, established per request exactly as today.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of calls to an endpoint group without that group's permission are refused, with no business data in the response.
- **SC-002**: 100% of calls to an endpoint group with that group's permission and valid acted-for information succeed, with results identical to today's behaviour.
- **SC-003**: No request is ever served using a permission taken from anywhere other than the validated access token, confirmed by tests that place permission names in headers and bodies.
- **SC-004**: 100% of user link calls with a missing, blank, or non-UUID `X-User-ID` header are refused, and none is served using the caller's own identity.
- **SC-005**: 100% of calls to any partner link endpoint made with a person's own token are refused, including tokens that carry one of the two permissions.
- **SC-006**: Adding or removing one front-end service's permission changes access for that service only, with zero observed change in the other service's results.
- **SC-007**: Every denial is traceable within one minute from the recorded authorization failure events to the caller, endpoint, and reason.
- **SC-008**: The rename introduces no behavioural change: every pre-existing endpoint behaviour test passes with only the user link path prefix and the component name updated.
- **SC-009**: 100% of calls to the retired `/api/v1/me/partner-links` paths are no longer served by the user link surface, and 100% of the same calls under `/api/v1/user-partner-links` return what the old paths returned.

## Assumptions

- The identity provider issues the `partner-access` and `internal-access` permissions to the `nox-bff` and `bff` clients respectively; Nox only reads them and does not manage their assignment.
- No person calls Nox directly to the partner link surfaces: every request on those surfaces comes from a front-end service acting for a person or a partner. Person-held tokens are therefore never a supported caller of the partner link endpoints. This restriction does not apply to `GET /api/v1/me`, which remains unchanged.
- Permissions are read from the same place in the token as today's roles, so no new token format is introduced.
- The previously used `nox-bff` permission is replaced by `partner-access` on the partner surface and is no longer honoured once this feature ships.
- The internal front-end service is a trusted caller: it authenticates the person itself and is responsible for the correctness of the `X-User-ID` value it sends. The network path to Nox prevents untrusted callers from reaching it with that header, and the `internal-access` permission is granted to no other client.
- `X-User-ID` carries the internal user id (UUID) already stored on each link; this is the same identifier produced by the existing user lookup, not the Keycloak subject id used for the caller's own token identity, so no new identifier is introduced.
- The user link surface moves to the `/api/v1/user-partner-links` prefix, because "me" no longer describes the caller once a front-end service always acts for a person. The individual endpoint suffixes, verbs, and payloads are unchanged, and no compatibility window is kept for the old prefix: both front-end services are updated alongside this change, and no other caller exists.
- The partner surface keeps its existing way of identifying the partner being acted for, unchanged by this feature.
- The caller identity endpoint and the public health probes are unaffected by this feature.
