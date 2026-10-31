# Research: API Authentication and Authorization with Keycloak

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-25

Versions were checked against Maven Central on 2026-10-25. Constitution Principle VIII forbids
pre-releases, so release candidates (e.g. Spring Boot 4.2.0-RC1) were excluded.

## R1. Runtime and framework versions

- **Decision**: Java 25 (LTS) and Spring Boot **4.1.2**, using Spring Boot's dependency
  management for Spring Framework 7, Spring Security 7, Micrometer, and Jackson.
- **Rationale**: Mandated by the constitution (Principle I). 4.1.2 is the newest GA release;
  4.2.0 is only at RC1.
- **Alternatives considered**: Spring Boot 4.2.0-RC1 (rejected: pre-release); 4.0.9 (older
  line).

## R2. Build tool

- **Decision**: Maven **3.10.0** via the Maven Wrapper **3.3.4** (`./mvnw`), single module.
- **Rationale**: Neither Maven nor Gradle is installed locally; the wrapper downloads the
  pinned version, so every developer and CI agent builds the same way. Maven is Spring Initializr's
  default and keeps the build declarative.
- **Alternatives considered**: Gradle (equally viable; no project preference stated, Maven
  chosen for simplicity); Maven 4.0.0 (rejected: still release candidate).

## R3. Token validation mode (spec clarification Q1, FR-002, FR-011, FR-013)

- **Decision**: Spring Security OAuth 2.0 **resource server** with a `NimbusJwtDecoder` built on
  a Nimbus `JWKSourceBuilder` pointed at the realm's JWKS endpoint, configured with:
  - caching of the key set, with refresh-ahead before expiry;
  - an immediate refresh when a token carries an unknown key ID (handles key rotation), rate
    limited so forged key IDs cannot flood Keycloak;
  - outage tolerance: if Keycloak is unreachable, the last loaded key set keeps being used;
  - **HTTP timeouts** (analysis U1): connect **2 s** and read **2 s** (configurable as
    `nox.auth.jwks-connect-timeout` / `nox.auth.jwks-read-timeout`) and a 50 KiB response size
    limit, via Nimbus `DefaultResourceRetriever`. A slow Keycloak fails the fetch quickly, so a
    request with an unknown key ID gets a 503 within about 4 s instead of hanging (spec edge
    case "unreachable **or slow**").
- **Rationale**: Satisfies "verify locally, never call Keycloak per request", survives key
  rotation without a restart, and keeps already-ready instances serving during a Keycloak outage.
- **Alternatives considered**: token introspection per request (rejected by clarification Q1);
  Spring Boot's auto-configured decoder from `issuer-uri` (rejected: no control over outage
  tolerance and readiness latch; discovery adds a startup dependency on Keycloak).

## R4. Validation rules (FR-002, FR-015)

- **Decision**: Validators, all mandatory in every environment:
  - signature with an algorithm allowlist of **RS256** only (Keycloak's default); `none` and
    HMAC algorithms are rejected;
  - `iss` equals the configured realm issuer URL exactly;
  - `aud` contains the configured service audience (`nox-api` by default);
  - `exp` / `nbf` with **30 s** clock-skew tolerance, using an injectable `Clock` so tests can
    produce expired tokens deterministically.
- **Rationale**: Exact issuer and audience checks implement the single-realm and audience
  clarifications. A tight algorithm allowlist blocks algorithm-confusion attacks.
- **Alternatives considered**: Spring's 60 s default skew (rejected: longer than needed for
  NTP-synced hosts); also accepting `azp` as audience (rejected: weaker than clarification Q4).

## R5. Mapping roles (FR-005, FR-006, FR-007)

- **Decision**: A custom JWT-to-authentication converter reads `realm_access.roles` and
  `resource_access.<audience-client>.roles` and merges them into a single set of role names,
  exposed as `ROLE_<name>` authorities. Endpoints declare roles by name with
  `@PreAuthorize("hasRole('...')")` (method security enabled). Scope claims are not used for
  authorization in this feature.
- **Rationale**: Keycloak puts realm roles and per-client roles in these two claims; merging
  them gives endpoints one simple vocabulary. Roles from other clients' `resource_access`
  entries are ignored so another app's roles cannot grant access here.
- **Alternatives considered**: Distinguishing realm and client roles by prefix (rejected:
  extra complexity with no current need; can be added later without breaking endpoints that
  use unique names).

## R6. Caller identity and caller type (FR-008 – FR-010, FR-016)

- **Decision**: `CallerIdentity` is a **domain** value object (framework-free; its constructor
  enforces the invariants). A web-security adapter maps validated JWT claims to it during
  authentication and makes it the authentication principal. Controllers receive it via
  `@AuthenticationPrincipal` and pass it **explicitly** to use cases, so application code never
  touches the security context.
  - `subjectId` ← `sub`
  - `callerType` ← `SERVICE` if the token has a `client_id` claim (Keycloak adds it to
    service-account tokens) or `preferred_username` starts with `service-account-`;
    otherwise `USER`
  - `username` ← `preferred_username` for users; `null` for services
  - `clientId` ← `client_id` (falling back to `azp`) for services; `null` for users
  - `email` ← `email` if present
  - `roles` ← the merged set from R5
- **Rationale**: One source of truth, built only from the validated token. Handlers never see
  raw claims or client-supplied identity.
- **Alternatives considered**: A request-scoped bean that reads `SecurityContextHolder`
  (rejected: hides the dependency and is harder to unit test).
- **Verification**: The `client_id` claim behavior is confirmed against the real Keycloak
  26.8.1 container in integration tests (the user and service-client cases from US3).

## R7. Error responses for 401, 403, 503 (FR-003, FR-005, FR-011, FR-014)

- **Decision**: Security failures happen in the filter chain before controllers, so
  `@RestControllerAdvice` cannot see them. One shared `ProblemResponseWriter` produces the
  constitution's error body (`application/problem+json`) and is used by:
  - a custom `AuthenticationEntryPoint` → **401** `urn:problem:unauthenticated`, with header
    `WWW-Authenticate: Bearer` and no `error_description` (FR-014);
  - a custom `AccessDeniedHandler` → **403** `urn:problem:forbidden`;
  - a bearer-token failure handler that turns `AuthenticationServiceException` (keys
    unobtainable, i.e. Keycloak unreachable for an unknown key ID) into **503**
    `urn:problem:identity_provider_unavailable` instead of blaming the caller (FR-011);
  - the global `@RestControllerAdvice` for all other errors: **404** `not_found`, **405**
    `method_not_allowed` (with `Allow`), **406** `not_acceptable`, **500** `internal_error`.
- **Contract and test coverage** (owner decision, analysis C2): every type, including 404, 405,
  406 and 500, is documented in `contracts/openapi.yaml` and covered by an integration test
  (`ErrorResponsesIT` for 404/405/406/500).
- **Rationale**: One error shape everywhere (Principle VI) and a correct split between the
  caller's fault (401/403) and the service's fault (503).
- **Alternatives considered**: Spring's default `BearerTokenAuthenticationEntryPoint`
  (rejected: empty body, and its `WWW-Authenticate` reveals which check failed).

## R8. Readiness and health probes (FR-017, SC-010)

- **Decision**: Custom root-level controllers (outside `/api`), unauthenticated:
  - `GET /readyz` → `200` or `503`, **no body**. Not ready until a `SigningKeysReadiness`
    component has loaded the JWKS once. It retries in the background with backoff from
    startup. The ready state latches; later Keycloak loss does not change it.
  - `GET /healthz` → knowledge-base health body (`status`, `serviceId`, `version`, `checks[]`)
    with one check `keycloak` / `external-service`: `UP` when reachable, `DEGRADED` when keys
    are loaded but Keycloak is unreachable, `DOWN` before keys were ever loaded. HTTP `200` for
    `UP`/`DEGRADED`, `503` for `DOWN` (the knowledge base does not define a status mapping; this
    is an interpretation).
  - Actuator is not used. The `/healthz` `version` comes from Spring Boot `BuildProperties`,
    generated by the `spring-boot-maven-plugin` `build-info` goal (analysis U2).
  - Layering (R16): the health aggregation rule (DOWN if a critical check is down, DEGRADED if
    only non-critical checks are down) is a **domain** rule. Use cases `GetServiceHealth` and
    `CheckReadiness` depend on outbound ports `DependencyHealthProbe` and `ReadinessCondition`.
    The Keycloak signing-key adapter implements both ports. Keycloak counts as non-critical once
    keys are loaded (cached keys keep the service working), and as critical before then.
- **Rationale**: The knowledge base requires root, unversioned `/healthz` and `/readyz` and a
  bodiless readiness response, which Actuator's endpoints do not produce.
- **Alternatives considered**: Actuator `additional-path` for probes (rejected: Actuator returns
  a JSON body for readiness and uses its own health format).

## R9. Structured logging (Principle VII, FR-012, SC-005)

- **Decision**: Spring Boot's built-in structured logging
  (`logging.structured.format.console`) with a custom `StructuredLogFormatter` that emits the
  knowledge-base schema: `timestamp` (UTC), `level`, `service{name,version,environment}`,
  `message`, plus `trace`, `request`, `user`, `exception` when present. One-line JSON in
  `stage`/`prod`, and in `dev` too, for parity.
  - **The formatter resolves all general context itself** (owner decision, analysis I2).
    Application code logs only a message plus event-specific SLF4J key-values (e.g. `reason`).
    General context is never passed as log arguments, and MDC is not used. From the current
    thread, the formatter reads:
    - `trace{traceId, spanId}` from the current OpenTelemetry span (Micrometer Tracing OTel
      bridge), and `parentSpanId` **only if present** (valid parent span context);
    - `trace.correlationId` from the request's `X-Correlation-Id` header, **only if present**;
    - `request{method, url (path only), userAgent, clientIp}` from `RequestContextHolder`;
    - `user{id, clientId?}` from `SecurityContextHolder` when the principal is a domain
      `CallerIdentity`.
  - Event key-values are written as extra top-level camelCase fields and can never override
    reserved schema keys.
  - Thread-local resolution requires the formatter to run on the logging thread: the console
    appender stays synchronous (no `AsyncAppender`). Logs from non-request threads simply omit
    `request` and `user`.
  - Placement: `infrastructure.logging`, the composition-root layer, which may depend on
    domain (`CallerIdentity`), Spring Web, Spring Security, and OpenTelemetry.
  - The `CRITICAL` level is not native to Logback; `ERROR` covers it until a need appears.
  - Tokens are never logged: request logging records method, path (no query string), user
    agent, and client IP only, never headers. A log-scanning integration test (SC-005) asserts
    that no JWT-shaped string appears in captured output.
- **Rationale**: Native support avoids an extra encoder dependency; a custom formatter is the
  supported extension point for organization-specific schemas.
- **Alternatives considered**: `logstash-logback-encoder` (rejected: extra dependency, and
  Logstash field names differ from the knowledge-base schema anyway); Boot's built-in `ecs`
  format (rejected: different schema).

## R10. Trace ID in responses

- **Decision**: `meta.traceId` and error `traceId` use the current Micrometer trace ID (W3C,
  32 hex chars), which is also the log `trace.traceId`. Incoming `traceparent` headers are
  honored.
- **Dependencies** (analysis U2, checked on Maven Central for Spring Boot 4.1.2):
  - `spring-boot-micrometer-tracing-opentelemetry` (tracing auto-configuration) plus
    `micrometer-tracing-bridge-otel`.
  - Not `spring-boot-starter-opentelemetry`: it also pulls `opentelemetry-exporter-otlp` and
    `micrometer-registry-otlp`, which would try to export to a collector this service does not
    have.
  - Not Actuator: no HTTP endpoints are exposed, and probes are custom (R8).
- **Rationale**: The constitution requires logs and API responses to share the same
  `traceId`.

## R11. Testing approach (Principles III, IV; SC-001 – SC-010)

- **Decision**:
  - Unit tests (JUnit 5, AssertJ, Mockito) for the role converter, identity mapping, caller
    type detection, validators, problem writer, and log formatter.
  - Integration tests: `@SpringBootTest(webEnvironment = RANDOM_PORT)` with Spring's
    `RestTestClient` against a **real Keycloak 26.8.1** container (Testcontainers **2.0.5** +
    `testcontainers-keycloak` **4.4.0**) importing a versioned test realm
    (`src/test/resources/keycloak/nox-test-realm.json`). The realm file contains **no literal
    credentials**: secrets and passwords are `${ENV}` placeholders that Keycloak resolves on
    import, and the test harness fills them with values generated at runtime (owner decision,
    analysis C4). The realm defines:
    - client `nox-api` (this service's audience, bearer-only);
    - confidential client `nox-test-client`, whose tokens include `nox-api` in the audience;
    - client `other-app`, whose tokens do not;
    - users `alice` (has `nox-tester`) and `bob` (no roles), plus `nox-test-client`'s service
      account.
  - Role-restricted behavior (US2) is tested with a controller that exists **only in test
    sources**, so no test endpoint ships to production.
  - Expired / not-yet-valid tokens are tested by moving the injectable `Clock`. Tampered,
    foreign-issuer, and `alg=none` tokens are crafted with Nimbus in the test.
  - Key rotation (SC-007): add a higher-priority RSA key via the Keycloak admin client, get a
    new token, and call the API with no restart.
  - Outage (SC-006, SC-010): pause the Keycloak container (separate test class with its own
    container).
  - SC-001: a test enumerates every registered handler mapping and asserts 401 without a token
    (except `/healthz` and `/readyz`).
  - SC-004: a `@Tag("perf")` test comparing p95 latency with and without security; excluded
    from the default build and run in CI nightly.
- **Rationale**: Real Keycloak is the only way to prove the token format, audience mapper,
  service-account claims, and key rotation behave as assumed.
- **Alternatives considered**: WireMock serving a static JWKS (rejected for this feature — see
  R12); `spring-security-test`'s `jwt()` post-processor (kept for fast controller slice tests
  only; it bypasses real token validation).

## R12. Mock server rule (constitution Principle IV) and Keycloak

- **Decision** (**confirmed by the project owner, 2026-10-25**): Keycloak is exempt from the
  Principle IV mock-server rule. Development and tests run a **real Keycloak in Docker**:
  `compose.yaml` for local runs, and a disposable Testcontainers instance for automated tests.
  Shared environments are never used. No third-party APIs are called in this feature, so no
  mock server is needed yet.
- **Rationale**: A mock would only prove the service agrees with the mock's assumptions about
  Keycloak.
- **Note**: Recorded as an accepted exception in plan Complexity Tracking. The mock-server tool
  decision required by Principle IV stays open for the first feature that calls a third-party
  API.

## R13. Container image and environments

- **Decision**:
  - Multi-stage `Dockerfile`: build with `eclipse-temurin:25-jdk` and `./mvnw`, extract Spring
    Boot layers, run on `eclipse-temurin:25-jre` as a non-root user (UID 10001). Exposes port
    8080. No shell scripts that log the environment.
  - Profiles: `dev`, `stage`, `prod` in `application-<profile>.yaml`. Realm issuer, JWKS URI,
    and audience come from environment variables (`NOX_AUTH_ISSUER_URI`,
    `NOX_AUTH_JWKS_URI`, `NOX_AUTH_AUDIENCE`). `prod` rejects startup if any is missing.
  - `compose.yaml` for local development runs Keycloak 26.8.1 in dev mode with the dev realm
    import, used only for local runs (not by tests, which use Testcontainers).
- **Rationale**: Principle-mandated Dockerfile and three environments; one image runs
  everywhere.
- **Alternatives considered**: Buildpacks via `spring-boot:build-image` (rejected: the
  constitution requires a Dockerfile).

## R14. Base package and coordinates

- **Decision**: Maven `groupId` `com.nox`, `artifactId` `nox`, base package `com.nox`.
- **Rationale**: No organization namespace was specified. This is easy to rename before the
  first release and is flagged in the plan for confirmation.

## R15. Database and cache

- **Decision**: No PostgreSQL, Liquibase, or Redis in this feature. Nothing is persisted
  (tokens and identities live only for a request), and no caching beyond the in-memory key set
  is needed.
- **Rationale**: Principles I and IX govern data the service stores. This feature stores none,
  and adding an unused datastore would be speculative. The first feature that persists data
  introduces PostgreSQL and Liquibase together.

## R16. Clean Architecture layering (constitution Principle X)

- **Decision**: Packages under `com.nox` follow the four constitution layers:
  - `domain` — `caller` (`CallerIdentity`, `CallerType`) and `health` (`HealthStatus`,
    `ComponentHealth`, `ServiceHealth` with the aggregation rule). Plain Java only.
  - `application` — inbound ports (use case interfaces) in `port.in`, outbound ports in
    `port.out`, and use case implementations in `service`. Plain Java only: **no Spring
    annotations**; beans are declared in `infrastructure.config.UseCaseConfig`.
  - `adapter.in.web` — controllers, response envelope, problem writer, global exception
    handler, and the web-security adapters (JWT → `CallerIdentity` mapping, 401/403/503
    handlers, logging-context filter).
  - `adapter.out.identityprovider` — Keycloak signing-key source, key-state tracker, and the
    health/readiness port implementations.
  - **Configuration values reach adapters as plain constructor arguments** (owner decision,
    analysis I1). `AuthProperties` stays in `infrastructure.config`, which passes
    `URI jwksUri` and `String audience` into `SigningKeySource` and `KeycloakRoleExtractor`.
    Adapters never import `infrastructure`.
  - `infrastructure` — `config` (Spring Security filter chain, JWT decoder wiring, typed
    properties, use case beans) and `logging` (structured log formatter).
- **Use case for `/me`**: `GetCurrentCallerUseCase.execute(CallerIdentity)` returns the caller
  view. It is trivial today, but it keeps the controller to "translate → call use case →
  translate", as Principle X requires, and gives later features a home for profile logic.
- **Rationale**: Business rules (identity invariants, health aggregation) stay testable without
  Spring. Spring Security, Nimbus, and Keycloak specifics stay in adapters and infrastructure.
- **Alternatives considered**: Package-by-capability (the previous plan revision; rejected:
  violates Principle X); putting Spring `@Service` on use cases (rejected: application layer
  must not depend on frameworks).

## R17. Architecture tests

- **Decision**: ArchUnit **1.5.1** (`archunit-junit5`) test `ArchitectureTest` enforcing:
  - `domain` depends only on `java..`;
  - `application` depends only on `domain` and `java..`;
  - `adapter.in` does not depend on `adapter.out` and vice versa;
  - only `infrastructure` (the composition root) may depend on all layers, and nothing depends
    on `infrastructure` except Spring bootstrapping;
  - controllers depend on `application.port.in`, never on `application.service`;
  - no field injection (`@Autowired` on fields is forbidden).
- **Rationale**: Principle X requires automated enforcement that fails the build.
- **Alternatives considered**: Spring Modulith verification (rejected: models modules, not
  layers); Maven multi-module layering (rejected: heavier build for a single small service;
  ArchUnit gives the same guarantee).

## R18. Asynchronous messaging (constitution Principle XI)

- **Decision**: Not applicable. This feature publishes and consumes no messages, so RabbitMQ is
  not added. The `messaging` log fields stay unused until a messaging feature arrives.

## Local environment findings

- The machine's default Java is 1.8 (Corretto). **JDK 25 must be installed** (e.g. via SDKMAN
  or Homebrew `temurin@25`) for builds.
- **Docker (or a compatible runtime such as Colima or Podman) is not installed**, and
  Testcontainers integration tests and the image build require it.
- The repository contains a `jdk/` directory with JDK binaries; it must be excluded from
  version control (`.gitignore`) when the project is scaffolded.
