# Consumed Contract: Owl user lookup by email

NOX **consumes** this endpoint of Owl (the user management service). It is the source for the
`OwlUserLookupAdapter` and for the WireMock stubs used in tests (research.md decisions 1, 2, 10).
Owl owns this API; if it changes, update this file first.

## Request

```http
GET {owlBaseUrl}/api/v1/service/user?email={urlEncodedEmail}&page=1&page_size=2&direction=DESC
Authorization: Bearer {oauth2AccessToken}
api-key: {owlApiKey}
Accept: application/json
```

| Part | Value |
|---|---|
| `email` | The email from the link request, URL-encoded |
| `page`, `page_size`, `direction` | Fixed: `1`, `2`, `DESC`. `page_size=2` is deliberate — it lets NOX detect an ambiguous match (more than one user) without fetching more |
| `Authorization` | OAuth2 client-credentials access token issued to NOX's Owl client (Spring Security OAuth2 client, registration `owl`) |
| `api-key` | Owl API key. **Both** headers are sent on every call |

Secrets (`owlApiKey`, the OAuth2 client secret) come only from runtime secret sources
(`NOX_OWL_API_KEY`, `NOX_OWL_CLIENT_SECRET`), never from the repository, and are never logged
(constitution Principle II). Tests use placeholder values such as `test-api-key`.

Because the email travels in the query string, NOX MUST NOT log the Owl request URL or query
string (Principle VII); it logs only the outcome and the request's `correlationId`.

## Response (200)

```json
{
  "success": true,
  "status": 200,
  "data": {
    "items": [
      {
        "id": "5cc42488-7c7b-4d49-b2d3-d35e9f67335c",
        "created_at": "2026-10-09T10:01:50.191Z",
        "updated_at": "2026-10-09T10:01:52.765Z",
        "keycloak_id": "cfc96be6-0e51-40e3-a6bb-d974af54c43b",
        "identifier": "7985222028",
        "type": "INDIVIDUAL",
        "verification_status": "APPROVED",
        "origin": "TONIVA"
      }
    ]
  },
  "meta": {
    "request_id": "18",
    "timestamp": "2026-10-26T08:54:19.229Z",
    "pagination": { "page": 1, "page_size": 2, "total_items": 1, "total_pages": 1, "has_next": false, "has_previous": false }
  }
}
```

NOX reads only `success`, `data.items[].id`, `data.items[].keycloak_id` and
`data.items[].verification_status`; unknown fields are ignored.

## Mapping to `UserLookupPort` results

| Owl response | Result | Retried? |
|---|---|---|
| 200, `success: true`, exactly one item with `verification_status = APPROVED` | `Found(userId = id, keycloakId = keycloak_id)` | — |
| 200, `success: true`, zero items | `NotFound` | No |
| 200, `success: true`, exactly one item with any other `verification_status` | `NotFound` (ineligible — treated exactly like no user, spec FR-002) | No |
| 200, `success: true`, more than one item | `NotFound`; log a WARN with the `correlationId` only (data-quality issue in Owl) | No |
| 200 with `success: false`, any 5xx, connection error, or timeout | transient failure | Yes, up to `max-attempts` with backoff; then `Failed` |
| 401 or 403 (bad token or api-key) | `Failed`; log an ERROR (configuration problem) | No |
| Any other 4xx | `Failed`; log an ERROR | No |
| Token endpoint unreachable or rejects NOX's client | transient failure | Yes, as above |

`NotFound` and `Failed` are internal outcomes only; the partner never sees them (spec FR-001,
FR-012).
