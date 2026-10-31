# Contract: Access Control Matrix

This feature changes **who may call** existing endpoints and **how the acted-for person is
supplied**; it adds no endpoint, path, verb, or payload shape (FR-015). This file is the
authoritative matrix `PartnerLinkAuthorizationIT` must assert against (quickstart.md).

## Endpoint groups and required caller

| Group | Endpoints | Required permission | Required caller type | Additional per-request input |
|---|---|---|---|---|
| Link requests | `POST /api/v1/partner-link-requests`, `GET /api/v1/partner-link-requests/{requestId}` | `partner-access` | `SERVICE` only | `x-service-id` → resolves the acted-for partner (unchanged) |
| Link queries | `GET /api/v1/partner-links/{linkId}`, `GET /api/v1/partner-links/by-external/{externalId}` | `partner-access` | `SERVICE` only | `x-service-id` → resolves the acted-for partner (unchanged) |
| User links | `GET /api/v1/user-partner-links`, `GET /api/v1/user-partner-links/{linkId}`, `GET /api/v1/user-partner-links/{linkId}/history`, `POST /api/v1/user-partner-links/{linkId}/approve`, `POST /api/v1/user-partner-links/{linkId}/reject`, `POST /api/v1/user-partner-links/{linkId}/revoke` | `internal-access` | `SERVICE` only | `X-User-ID` (UUID) → resolves the acted-for person |

`GET /api/v1/me` is **not** in this matrix: unaffected by this feature (clarify Q2).

The user-link endpoints previously lived under `/api/v1/me/partner-links`. That prefix is retired
with no alias (FR-019): after this feature it matches no handler, so an authenticated caller gets
`404` and an unauthenticated one `401`. Callers must use `/api/v1/user-partner-links` (research.md D6).

## Decision order (every request)

1. **Token validity** → missing/expired/malformed: `401 unauthenticated`, before any permission
   or header is read (unchanged).
2. **Caller type + permission** (`ServiceCallerAuthorization.requiresRole`, research.md D1) →
   not `SERVICE`, or missing the group's permission: `403 forbidden`. `X-User-ID` and
   `x-service-id` are not read at this step (FR-007, FR-011).
3. **Group-specific resolution**:
   - Link requests/queries: `x-service-id` missing or not an active, callable partner →
     `403 forbidden` (unchanged from today).
   - User links: `X-User-ID` missing, blank, or not a valid UUID → `400 bad_request` (FR-006).
4. **Resource scoping** (unchanged): a link not belonging to the resolved partner/person →
     `404 not_found`, same as every other unknown-resource case.

## Caller matrix (every combination `PartnerLinkAuthorizationIT` must cover)

| Caller | Link requests/queries | User links |
|---|---|---|
| No token | `401` | `401` |
| Token for another audience | `401` | `401` |
| `SERVICE` + `partner-access`, valid `x-service-id` | 2xx (unchanged behavior) | `403` |
| `SERVICE` + `partner-access`, no/invalid `x-service-id` | `403` | `403` |
| `SERVICE` + `internal-access`, valid `X-User-ID` | `403` | 2xx (unchanged behavior, now keyed by `userId`) |
| `SERVICE` + `internal-access`, missing `X-User-ID` | `403` | `400` |
| `SERVICE` + `internal-access`, non-UUID `X-User-ID` | `403` | `400` |
| `SERVICE` with neither permission | `403` | `403` |
| `SERVICE` with **both** permissions | same as each row above, independently, for the respective surface | same |
| Person's own `USER` token, no permission | `403` | `403` |
| Person's own `USER` token that happens to carry `internal-access` or `partner-access` | `403` | `403` |

Every `403` row above MUST be observable as one `SecurityEvents.accessDenied()` log line
(`reason=missing_role`) — already emitted by the shared `ProblemAccessDeniedHandler` for any
secured path, so no new logging code is required (FR-013).
