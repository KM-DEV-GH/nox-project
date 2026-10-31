# Quickstart: Validating Partner Link Management

**Feature**: [spec.md](spec.md) | **Contracts**: [openapi.yaml](contracts/openapi.yaml), [events.md](contracts/events.md) | **Data model**: [data-model.md](data-model.md)

How to prove the feature works end to end. Implementation steps are in [tasks.md](tasks.md).

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 25 | Set `JAVA_HOME` |
| Docker-compatible runtime | Current | Testcontainers: PostgreSQL, RabbitMQ, WireMock, Keycloak |
| Maven | — | Use `./mvnw` |
| `curl`, `jq` | any | Manual checks |

## 1. Automated validation (primary)

```bash
./mvnw verify                 # unit + integration tests
./mvnw verify -Dgroups=perf   # SC-001 and SC-006 timing checks
```

| Spec item | Proven by |
|-----------|-----------|
| Principle X (with the `@Transactional` exception) | `ArchitectureTest` |
| Principle IX | `LiquibaseSchemaIT`: the context starts with `ddl-auto=validate` against the Liquibase-built schema |
| US1, FR-001–FR-006, SC-002, SC-007 | `PartnerLinkRequestCreationIT`: known user, unknown email, existing active link, idempotency replay — identical `202` responses; a `PENDING` link and an `approval-requested` message only for the known user |
| FR-002, Owl outage | `PartnerLinkRequestOwlOutageIT`: WireMock times out, then recovers — response unchanged, lookup retried, link created after recovery; exhausted retries leave the request `PROCESSING` then `EXPIRED` |
| FR-002, Owl contract | `OwlUserLookupAdapterTest`: every row of contracts/owl-user-lookup.md — both `Authorization` and `api-key` sent; only one `APPROVED` match is found; zero, several, or non-`APPROVED` matches look exactly like an unknown email; 401/403 not retried |
| FR-019, Principle II | `PartnerLinkAuthorizationIT`: SERVICE token without `nox-bff` → 403; `x-service-id` of an inactive or unknown partner → 403; USER token on partner endpoints and SERVICE token on `/me` endpoints → 403; another user's link → 404 |
| US2, FR-007, FR-011, SC-006 | `PartnerLinkApprovalIT` / `PartnerLinkRejectionIT`: status, one history entry, both messages with required headers (approved partner message has `linkId`, rejected does not) |
| FR-017, US5 scenario 2 | `PartnerLinkExpiryIT` (fixed `Clock`): late approve/reject → `409 link_expired`, link written `EXPIRED` with one NOX history entry, no messages; undecided past-deadline link reads as `EXPIRED`; a new request supersedes it |
| Concurrency edge case | `PartnerLinkConcurrencyIT`: late approve racing a superseding request — one `EXPIRED` transition, one history entry |
| US3, FR-012–FR-014, SC-002, SC-003 | `PartnerLinkStatusQueryIT`: fallback `PROCESSING` → outcome; known vs unknown email identical over time; partner reads return only established links (others 404); latest link by external id; user list shows every status |
| US4, FR-015, FR-016, SC-004 | `PartnerLinkRevocationIT`: revoke `CONNECTED` → `REVOKED`, history, no messages; revoke anything else → `409 link_not_actionable` |
| US5, FR-009, FR-010, SC-005 | `PartnerLinkHistoryIT`: create → approve → revoke, three ordered entries with actor and timestamp |
| Principle XI, outbox | `PartnerLinkEventPublicationIT`: rolled-back transition leaves no publication; broker outage then recovery delivers once; payloads match contracts/events.md; `PartnerLinkStuckPublicationSweeperTest` |
| Principle VI | `PartnerLinkErrorResponsesIT`: 400/401/403/404/409 shapes and both new problem types |

## 2. Manual validation (local)

```bash
podman compose -f compose.yaml -f compose.dev.yaml up -d
# compose starts PostgreSQL and RabbitMQ; the dev profile seeds partner "demo-partner"
# $BFF_TOKEN: client-credentials token of a Keycloak client with the nox-bff role
# $ALICE_TOKEN: alice's user token
# Owl access: set NOX_OWL_API_KEY and NOX_OWL_CLIENT_SECRET in your untracked .env (never commit them);
# Owl must hold an APPROVED user for alice's email whose keycloak_id is alice's Keycloak subject

# 1. Partner (via BFF) submits a link request
curl -s -X POST http://localhost:8080/api/v1/partner-link-requests \
  -H "Authorization: Bearer $BFF_TOKEN" -H "x-service-id: demo-partner" \
  -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" \
  -d '{"externalId":"76490253-9de4-4988-b757-ad0f13a5c7fd","email":"alice@example.test","licenseeId":"nox-gaming-eu"}' | jq .

# 2. Poll the request status (PROCESSING until alice decides)
curl -s http://localhost:8080/api/v1/partner-link-requests/<requestId> \
  -H "Authorization: Bearer $BFF_TOKEN" -H "x-service-id: demo-partner" | jq .

# 3. Alice lists her links and approves the pending one
curl -s http://localhost:8080/api/v1/me/partner-links -H "Authorization: Bearer $ALICE_TOKEN" | jq .
curl -s -X POST http://localhost:8080/api/v1/me/partner-links/<linkId>/approve \
  -H "Authorization: Bearer $ALICE_TOKEN" | jq .

# 4. Partner reads the established link
curl -s http://localhost:8080/api/v1/partner-links/<linkId> \
  -H "Authorization: Bearer $BFF_TOKEN" -H "x-service-id: demo-partner" | jq .

# 5. Alice revokes it and views the history
curl -s -X POST http://localhost:8080/api/v1/me/partner-links/<linkId>/revoke \
  -H "Authorization: Bearer $ALICE_TOKEN" | jq .
curl -s http://localhost:8080/api/v1/me/partner-links/<linkId>/history \
  -H "Authorization: Bearer $ALICE_TOKEN" | jq .
```

Expected: shapes match `contracts/openapi.yaml`; steps 1–2 look the same for an unknown email;
repeating the revoke returns `409 link_not_actionable`; the RabbitMQ management UI shows
`partner-link.approval-requested.v1` and both `partner-link.approved.v1` messages, and nothing for revoke.

## 3. Done when

- `./mvnw verify` is green, including every IT above and `ArchitectureTest`.
- Every FR-001–FR-019 and SC-001–SC-007 maps to at least one passing test.
