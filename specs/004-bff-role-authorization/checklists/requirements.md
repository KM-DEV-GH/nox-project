# Specification Quality Checklist: BFF Role-Based Access

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-30
**Updated**: 2026-10-30 (after $speckit-specify clarifications Q1–Q3, and $speckit-clarify Q1–Q4)
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Endpoint paths, permission names, and the `X-User-ID` header are named in the requirements as stable contract identifiers, not as implementation detail.
- Specification-time clarifications: the acting person comes from `X-User-ID`, a person's own token is no longer accepted on the user link surface, and the link query endpoints stay on `partner-access`.
- `$speckit-clarify` session (2026-10-30) resolved four further ambiguities: (1) `internal-access` and `partner-access` are honored only for a service-client caller — no person token reaches any partner link endpoint, even one carrying a matching permission; (2) this service-only rule is scoped to the partner link surfaces and does not change `GET /api/v1/me`; (3) `X-User-ID` carries the internal user id in UUID format (the id already stored on each link), distinct from the Keycloak subject id that identifies the calling token; (4) every denial on any of the three partner link surfaces — not just link requests — is recorded as an authorization failure event.
- Post-plan clarification (2026-10-30, Q5): because a front-end service always calls on a person's behalf, the `/api/v1/me/partner-links` prefix no longer describes the caller and is replaced by `/api/v1/user-partner-links` (FR-018), with the old prefix retired and no alias kept (FR-019). The acting person stays in `X-User-ID` rather than becoming a path variable, keeping the internal user id out of URLs and access logs. `GET /api/v1/me` remains unchanged, as decided earlier. FR-015 and SC-008 were narrowed accordingly (payloads and the partner-surface paths are preserved; the user-link prefix is not), and SC-009 was added for the retirement.
- **Constitution follow-up for `$speckit-plan`**: Principle II requires authorization from token roles, never client assertions alone. Access is decided entirely by the service client's token (`partner-access` / `internal-access`); `X-User-ID` only selects the subject within that already-authorized, service-only surface, and is ignored for any caller lacking `internal-access` (FR-007). The plan MUST record this trust boundary explicitly, including how untrusted callers are prevented from reaching Nox with that header. *(Resolved: plan.md Constitution Check row II + research.md D2.)*
