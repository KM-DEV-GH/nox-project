# Implementation Plan: API Authentication and Authorization with Keycloak

**Branch**: `001-keycloak-api-auth` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-keycloak-api-auth/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

**Revision**: 2 — re-aligned to constitution v1.2.0 (Principle X Clean Architecture, Principle XI
RabbitMQ). Revision 1 used a package-by-capability layout.

## Summary

Secure the headless Nox API so that every endpoint, except the `/healthz` and `/readyz` probes,
requires a Keycloak-issued OAuth 2.0 Bearer token. Tokens are verified locally against the
realm's published signing keys (no per-request call to Keycloak), with exact issuer, required
`nox-api` audience, RS256-only, and expiry checks. Roles from the realm and the `nox-api`
client drive endpoint access (401 vs 403 in the standard problem format). Each authenticated
request carries a trusted domain `CallerIdentity`. Controllers pass it explicitly to use cases,
and `GET /api/v1/me` returns it. Key caching tolerates Keycloak outages, and `/readyz` latches
ready once keys first load.

This is the project's first feature, so it also establishes the service skeleton the
constitution requires: Clean Architecture layers with ArchUnit enforcement, Maven build,
Dockerfile, `dev`/`stage`/`prod` configuration, knowledge-base JSON logging, standard success and
error envelopes, and probes.

## Technical Context

**Language/Version**: Java 25 (LTS)

**Primary Dependencies**: Spring Boot 4.1.2 — `spring-boot-starter-web`,
`spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`,
`spring-boot-starter-validation`, `spring-boot-micrometer-tracing-opentelemetry` +
`micrometer-tracing-bridge-otel` (no OTLP exporter, no Actuator; R10). The
`spring-boot-maven-plugin` `build-info` goal supplies the version for `/healthz`. No UI or
OpenAPI-UI dependencies (headless service). No RabbitMQ (no async messaging in this feature;
R18).

**Storage**: N/A for this feature. Nothing is persisted; the signing key set is held in memory.
PostgreSQL and Liquibase arrive with the first persisting feature (research R15).

**Testing**: JUnit 5, AssertJ, Mockito, ArchUnit 1.5.1, `spring-boot-starter-test`,
`spring-security-test`, Spring `RestTestClient`, Testcontainers 2.0.5, `testcontainers-keycloak`
4.4.0 with a Keycloak 26.8.1 image.

**Target Platform**: Linux container (`eclipse-temurin:25-jre`), orchestrated with
liveness/readiness probes.

**Project Type**: Headless web service (REST API only).

**Performance Goals**: Authentication adds at most 50 ms at p95 compared with an unsecured
request (SC-004). Local signature verification is expected to be well under 1 ms.

**Constraints**: No per-request network call to Keycloak; key fetches time out after 2 s connect
+ 2 s read (R3); fail closed; tokens never logged;
error `detail` never reveals which check failed; one image for all environments; domain and
application layers free of Spring and other frameworks.

**Scale/Scope**: One API endpoint (`/api/v1/me`) plus two probes. The security, response, and
layering foundation is reused by every later feature.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Checked against constitution **v1.2.0**.

| # | Principle | Status | How this plan complies |
|---|-----------|--------|------------------------|
| I | Mandated stack | ✅ | Java 25, Spring Boot 4.1.2 (latest GA). PostgreSQL and Redis not needed: nothing persisted or cached (R15). |
| II | Security by default | ✅ | Resource server, Keycloak JWT, deny by default. Only `/healthz` and `/readyz` are public, listed and justified. Server-side role checks. Secrets only via environment variables. |
| III | Test-first | ✅ | `tasks.md` orders failing tests before each implementation step. |
| IV | Integration tests and mocks | ✅ Accepted exception | Every endpoint has integration tests against a real Keycloak in Docker. The owner confirmed on 2026-10-25 that Keycloak is exempt from the mock-server rule (Complexity Tracking, R12). No third-party APIs, so no mock server decision is needed yet. |
| V | RESTful design | ✅ | `/api/v1/me` noun resource, versioned path, camelCase, success envelope. Probes are exempt from the envelope, per the constitution. |
| VI | Error handling | ✅ | One `ProblemResponseWriter` for 401/403/503 from the security filters and 404/405/406/500 from controller advice; `application/problem+json`; generic `detail`. Every type is in the contract and has an integration test (R7). |
| VII | Structured logging | ✅ | Custom Spring Boot `StructuredLogFormatter` emitting the knowledge-base schema. It resolves trace (incl. `parentSpanId`/`correlationId` when present), request, and user context itself, with no MDC or log arguments. `traceId` is shared with responses; log scan test (R9, R10). |
| VIII | Current dependencies | ✅ | All versions checked on Maven Central 2026-10-25; RCs excluded (R1, R2, R11, R17). |
| IX | Liquibase migrations | ✅ N/A | No schema in this feature. |
| X | Clean Code & Clean Architecture | ✅ | `domain` / `application` / `adapter` / `infrastructure` layers with inward dependencies. Use cases are Spring-free and wired in `infrastructure.config`. Controllers only translate and call `port.in`. ArchUnit test fails the build on violations; field injection is forbidden (R16, R17). |
| XI | RabbitMQ messaging | ✅ N/A | No asynchronous communication in this feature (R18). |
| — | Dockerfile and 3 environments | ✅ | Multi-stage, non-root image; `dev`/`stage`/`prod` profiles; `prod` fails fast on missing auth config (R13). |
| — | Security agent review | ✅ Planned | `/security-review` gate before merge (quickstart section 4). |

**Gate result (pre-research)**: PASS with one justified item (IV).
**Gate result (post-design)**: PASS. The layered design, data model, contract, and quickstart
introduce no new violations; Principle X compliance is enforced by `ArchitectureTest`.

## Project Structure

### Documentation (this feature)

```text
specs/001-keycloak-api-auth/
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1–R18
├── data-model.md        # Phase 1: entities with layer placement, token rules, key-set states, errors
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   └── openapi.yaml     # Phase 1: /api/v1/me, /healthz, /readyz, Problem schema
└── tasks.md             # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
pom.xml
mvnw, mvnw.cmd, .mvn/wrapper/
Dockerfile
.dockerignore
.gitignore                         # includes jdk/, target/
compose.yaml                       # local dev Keycloak 26.8.1 + dev realm import
docker/keycloak/nox-dev-realm.json

src/main/java/com/nox/
├── NoxApplication.java
│
├── domain/                                    # plain Java only
│   ├── caller/
│   │   ├── CallerIdentity.java                # record; invariants in constructor
│   │   └── CallerType.java                    # USER | SERVICE
│   └── health/
│       ├── HealthStatus.java                  # UP | DOWN | DEGRADED
│       ├── ComponentType.java
│       ├── ComponentHealth.java
│       └── ServiceHealth.java                 # aggregation rule
│
├── application/                               # plain Java only (no Spring annotations)
│   ├── port/
│   │   ├── in/
│   │   │   ├── GetCurrentCallerUseCase.java
│   │   │   ├── GetServiceHealthUseCase.java
│   │   │   └── CheckReadinessUseCase.java
│   │   └── out/
│   │       ├── DependencyHealthProbe.java
│   │       └── ReadinessCondition.java
│   └── service/
│       ├── GetCurrentCallerService.java
│       ├── GetServiceHealthService.java
│       └── CheckReadinessService.java
│
├── adapter/
│   ├── in/web/
│   │   ├── me/
│   │   │   ├── MeController.java              # GET /api/v1/me → GetCurrentCallerUseCase
│   │   │   └── CallerIdentityResponse.java
│   │   ├── probe/
│   │   │   ├── ReadinessController.java       # GET /readyz (no body)
│   │   │   ├── HealthController.java          # GET /healthz
│   │   │   └── HealthResponse.java
│   │   ├── response/
│   │   │   ├── ApiResponse.java               # { data, meta }
│   │   │   ├── Meta.java
│   │   │   └── MetaFactory.java               # traceId, timestamp, instance
│   │   ├── error/
│   │   │   ├── ProblemType.java               # catalogue from data-model.md
│   │   │   ├── ProblemResponseWriter.java
│   │   │   └── GlobalExceptionHandler.java    # @RestControllerAdvice
│   │   └── security/
│   │       ├── JwtCallerIdentityMapper.java   # claims → domain CallerIdentity
│   │       ├── KeycloakRoleExtractor.java     # realm_access + resource_access.<audience>
│   │       ├── CallerAuthentication.java      # principal = CallerIdentity
│   │       ├── CallerAuthenticationConverter.java
│   │       ├── ProblemAuthenticationEntryPoint.java   # 401
│   │       ├── ProblemAccessDeniedHandler.java        # 403
│   │       └── BearerFailureHandler.java              # 401 vs 503 split
│   └── out/identityprovider/
│       ├── SigningKeySource.java              # Nimbus JWKSource: cache, refresh-ahead, outage-tolerant, rate-limited
│       ├── SigningKeyState.java               # NOT_LOADED | LOADED | LOADED_STALE
│       ├── SigningKeyStateTracker.java        # latch + background retry
│       ├── KeycloakReadinessCondition.java    # implements ReadinessCondition
│       └── KeycloakHealthProbe.java           # implements DependencyHealthProbe
│
└── infrastructure/
    ├── config/
    │   ├── SecurityConfig.java                # filter chain, deny-by-default, public probes, method security
    │   ├── JwtDecoderConfig.java              # decoder + validators (RS256, issuer, audience, time)
    │   ├── AuthProperties.java                # issuer, jwksUri, audience (validated)
    │   ├── UseCaseConfig.java                 # @Bean wiring of application services
    │   └── ClockConfig.java                   # injectable Clock
    └── logging/
        └── NoxStructuredLogFormatter.java     # knowledge-base JSON schema; resolves trace, correlation, request, user itself

src/main/resources/
├── application.yaml                           # shared defaults, structured logging
├── application-dev.yaml
├── application-stage.yaml
└── application-prod.yaml

src/test/java/com/nox/
├── architecture/
│   └── ArchitectureTest.java                  # ArchUnit layer rules (R17)
├── domain/                                    # unit: CallerIdentity invariants, ServiceHealth aggregation
├── application/                               # unit: use cases with fake ports (no Spring)
├── adapter/in/web/                            # unit/slice: mapper, role extraction, problem writer, envelopes
├── adapter/out/identityprovider/              # unit: key-state latch transitions
├── infrastructure/                            # unit: audience validator, log formatter
└── integration/
    ├── KeycloakContainerSupport.java          # shared Keycloak 26.8.1 container + test realm
    ├── TestTokens.java                        # real tokens; crafted tampered/alg=none/foreign tokens
    ├── TestOnlyRoleController.java            # role-restricted endpoint (test sources only)
    ├── AuthenticationIT.java                  # US1 token matrix, FR-014 generic details
    ├── ErrorResponsesIT.java                  # 404/405/406/500 problem responses (Principle VI)
    ├── EndpointEnumerationIT.java             # SC-001
    ├── RoleAuthorizationIT.java               # US2
    ├── CurrentCallerIT.java                   # US3, /api/v1/me, spoofed-header test
    ├── KeyRotationIT.java                     # SC-007
    ├── KeycloakOutageIT.java                  # SC-006, SC-010, FR-011, FR-017 (own container)
    ├── LogHygieneIT.java                      # SC-005, FR-012
    └── SecurityLatencyPerfIT.java             # SC-004, @Tag("perf")
src/test/resources/keycloak/nox-test-realm.json
```

**Structure Decision**: A single Maven module (headless service, no frontend) organized by Clean
Architecture layer, per constitution Principle X:

- `domain` holds framework-free business types and rules.
- `application` holds use case ports and Spring-free implementations.
- `adapter.in.web` and `adapter.out.identityprovider` hold all framework and Keycloak specifics.
- `infrastructure` is the composition root (Spring configuration and logging).

Later features add sub-packages within each layer (e.g. `domain.payment`,
`adapter.out.persistence`, `adapter.out.messaging`). Integration tests use the `*IT` suffix
(Failsafe, `mvn verify`); `ArchitectureTest` runs in the unit phase so violations fail fast.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Principle IV — Keycloak runs as a real container (Docker) in development and tests, not behind a mock server | The feature's correctness depends on Keycloak's actual token format, `client_id` claim for service accounts, audience mapper, and key rotation | A mock JWKS server would only prove the service matches the mock's assumptions about Keycloak, not Keycloak itself. **Accepted by the project owner on 2026-10-25.** |

## Security Review Gate (T077, 2026-10-25)

An independent security-review agent reviewed the working tree. The repository has no commits
yet, so `/security-review` (which diffs against `origin/HEAD`) could not run.

- **Critical/High**: none.
- **Fixed**: tokens without `exp` were accepted (now rejected); tokens other than access tokens
  were accepted (now `typ` must be `Bearer`); dev Keycloak listened on all interfaces (now bound
  to `127.0.0.1`).
- **Open (Low; for the owner to decide)**:
  - `azp` allowlist: any realm client whose token carries the `nox-api` audience is trusted.
  - Readiness latches on any 2xx JWKS response, before the body is parsed as a key set.
  - No `https` requirement on the JWKS URI in `stage`/`prod`.
  - Callers whose username starts with `service-account-` are classed as `SERVICE` (research
    R6 rule).
  - `X-Correlation-Id` and `User-Agent` are logged without a format check or length limit.
  - Base images are not pinned by digest.

## Open Items for the Project Owner

1. ~~Confirm the Keycloak testing approach~~ — resolved 2026-10-25: real Keycloak in Docker
   accepted.
2. Confirm Maven coordinates and base package `com.nox` (research R14).
3. Install JDK 25 and a Docker-compatible runtime locally (research, "Local environment
   findings").
4. `/healthz` HTTP mapping (`200` for UP/DEGRADED, `503` for DOWN) is an interpretation; the
   knowledge base does not define it.
