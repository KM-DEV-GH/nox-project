---

description: "Task list for 001-keycloak-api-auth"
---

# Tasks: API Authentication and Authorization with Keycloak

**Input**: Design documents from `/specs/001-keycloak-api-auth/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml),
[quickstart.md](quickstart.md)

**Tests**: REQUIRED. Constitution Principle III (TDD, non-negotiable) and Principle IV
(integration tests for every API). In every phase, test tasks come first and MUST be run and
seen to **fail** before the implementation tasks that follow them.

**Organization**: Tasks are grouped by user story so each story can be implemented and tested
independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

## Path Conventions

- Single Maven module at the repository root.
- Main code: `src/main/java/com/nox/` in layers `domain`, `application`, `adapter`,
  `infrastructure` (plan.md, research R16).
- Tests: `src/test/java/com/nox/`. Integration tests end in `IT` and live in
  `src/test/java/com/nox/integration/`.
- Layer rules: `domain` and `application` use plain Java only (no Spring, Nimbus, Jackson, or
  Jakarta annotations). Constructor injection only. No field `@Autowired`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and build tooling

- [X] T001 Create `.gitignore` at repository root ignoring `jdk/`, `target/`, `.idea/`, `*.iml`, `.vscode/`, `.DS_Store`, and `.env*`
- [X] T002 Create `pom.xml` at repository root:
  - **Coordinates and build**: parent `org.springframework.boot:spring-boot-starter-parent:4.1.2`, `groupId` `com.nox`, `artifactId` `nox`, `java.version` 25.
  - **Main dependencies**: `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-validation`, `org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry` (tracing auto-configuration, version managed by Boot), and `io.micrometer:micrometer-tracing-bridge-otel`. Do **not** add `spring-boot-starter-actuator`. Do **not** add `spring-boot-starter-opentelemetry`, because it brings an OTLP exporter that would try to ship traces to a non-existent collector (research R10).
  - **Test dependencies**: `spring-boot-starter-test`, `spring-security-test`, `com.tngtech.archunit:archunit-junit5:1.5.1`, Testcontainers BOM `org.testcontainers:testcontainers-bom:2.0.5` (import scope) with `testcontainers-junit-jupiter`, and `com.github.dasniko:testcontainers-keycloak:4.4.0`.
  - **Plugins**:
    - `spring-boot-maven-plugin` with the **`build-info`** goal, so `META-INF/build-info.properties` is generated and Spring Boot exposes a `BuildProperties` bean with the project version (used by `/healthz`, T046);
    - `maven-failsafe-plugin` runs `**/*IT.java` in `verify`;
    - surefire and failsafe exclude JUnit tag `perf` unless `-Dgroups=perf`.
  - **Versions**: no pre-release versions anywhere.
- [X] T003 Add the Maven Wrapper 3.3.4 pinned to Maven 3.10.0: `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` (`distributionUrl` for apache-maven 3.10.0)
- [X] T004 Create `src/main/java/com/nox/NoxApplication.java` (`@SpringBootApplication`, `@ConfigurationPropertiesScan`). Create the empty layer packages with a `package-info.java` each, stating the layer's dependency rule:
  - `src/main/java/com/nox/domain/package-info.java`
  - `src/main/java/com/nox/application/package-info.java`
  - `src/main/java/com/nox/adapter/package-info.java`
  - `src/main/java/com/nox/infrastructure/package-info.java`
- [X] T005 [P] Create a multi-stage `Dockerfile` and `.dockerignore` at repository root:
  - **Build stage**: `eclipse-temurin:25-jdk`, run `./mvnw -B -DskipTests package`, extract Spring Boot layers with `java -Djarmode=tools -jar app.jar extract --layers --launcher`.
  - **Runtime stage**: `eclipse-temurin:25-jre`, create and use non-root user UID 10001, `EXPOSE 8080`, `ENTRYPOINT` uses exec form.
  - **`.dockerignore`**: exclude `jdk/`, `target/`, `.git/`, `specs/`, `.specify/`, `.claude/`.
- [X] T006 [P] Create `compose.yaml` and the dev realm file for local runs only:
  - **`compose.yaml`**: service `keycloak` using `quay.io/keycloak/keycloak:26.8.1` in `start-dev --import-realm` mode on host port 8180, mounting `docker/keycloak/`.
  - **`docker/keycloak/nox-dev-realm.json`**: realm `nox-dev`, access-token lifespan 300 s, default signature algorithm RS256.
    - Client `nox-api`: bearer-only audience target.
    - Public client `nox-dev-cli`: direct-access grants enabled (dev only), with an audience mapper adding `nox-api`.
    - Confidential client `nox-dev-service`: service accounts enabled, with an audience mapper adding `nox-api`.
    - Realm role `nox-tester`.
    - Users `alice` (role `nox-tester`, email `alice@example.test`) and `bob` (no roles).
  - **Secrets**: user passwords and the client secret come from environment placeholders (`${ALICE_DEV_PASSWORD}`, `${BOB_DEV_PASSWORD}`, `${NOX_DEV_SERVICE_SECRET}`), never literal values.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Architecture enforcement, configuration, logging, response and error envelopes, and
the shared Keycloak test harness. Every user story depends on these.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Tests for Foundational (write first, confirm they fail)

- [X] T007 [P] Write `src/test/java/com/nox/architecture/ArchitectureTest.java` (ArchUnit, imports `com.nox`, excludes tests). Each rule uses `allowEmptyShould(true)` until its packages exist. Rules (research R17):
  - `domain` depends only on `java..` and `com.nox.domain..`;
  - `application` depends only on `java..`, `com.nox.domain..`, and `com.nox.application..`;
  - `adapter.in..` and `adapter.out..` do not depend on each other;
  - no class outside `infrastructure..` and `NoxApplication` depends on `infrastructure..`;
  - classes annotated `@RestController` depend on `application.port.in..`, never on `application.service..`;
  - no field is annotated `@Autowired`.
- [X] T008 [P] Write `src/test/java/com/nox/infrastructure/config/AuthPropertiesTest.java`:
  - binding of `nox.auth.issuer-uri`, `nox.auth.jwks-uri`, `nox.auth.audience` (default `nox-api`), `nox.auth.jwks-connect-timeout` (default `2s`), and `nox.auth.jwks-read-timeout` (default `2s`) works;
  - a zero or negative timeout fails validation;
  - the context fails to start when issuer or JWKS URI is blank under profile `prod`.
- [X] T009 [P] Write `src/test/java/com/nox/infrastructure/logging/NoxStructuredLogFormatterTest.java` asserting single-line JSON. The formatter resolves all general context **itself, from the current thread** (research R9). Callers never pass it as log arguments, and MDC is ignored entirely.
  - **Always present**: `timestamp` (UTC ISO-8601); `level` in {`DEBUG`,`INFO`,`WARN`,`ERROR`} (Logback `TRACE` maps to `DEBUG`); `service{name,version,environment}`; `message`.
  - **Trace**, resolved from the current OpenTelemetry span: `trace{traceId,spanId}`, plus `parentSpanId` only if the span has a valid parent.
  - **Correlation ID**, from the current request's `X-Correlation-Id` header: `trace.correlationId` only if present.
  - **Request**, from `RequestContextHolder` when on a request thread: `request{method,url,userAgent,clientIp}`, where `url` is the path only, never the query string or headers.
  - **Exception**: `exception{type,message,stacktrace[]}` for throwables.
  - **Event-specific fields**: SLF4J key-value pairs (e.g. `reason`) appear as extra top-level camelCase fields. They can never override the reserved keys `timestamp`, `level`, `service`, `message`, `trace`, `request`, `messaging`, `user`, `exception`.
  - **Missing context**: objects are omitted (not `null`) when there is no span, request, or key-values.
  - **Ignored input**: a value placed in MDC never appears in the output.
- [X] T010 [P] Write `src/test/java/com/nox/adapter/in/web/response/MetaFactoryTest.java` asserting that `Meta` has:
  - `traceId` equal to the current trace ID (32 lowercase hex);
  - `timestamp` from the injected `Clock` (ISO-8601 UTC);
  - `instance` equal to the request path without query string.
- [X] T011 [P] Write `src/test/java/com/nox/adapter/in/web/error/ProblemResponseWriterTest.java`. For each `ProblemType` in the data-model.md error catalogue (`unauthenticated` 401, `forbidden` 403, `not_found` 404, `method_not_allowed` 405, `not_acceptable` 406, `internal_error` 500, `identity_provider_unavailable` 503), assert:
  - `Content-Type: application/problem+json`;
  - a body with exactly `type` (`urn:problem:<title>`), `title`, fixed generic `detail`, `traceId`, `timestamp`, `instance`;
  - lowerCamelCase keys.
- [X] T012 [P] Write `src/test/java/com/nox/adapter/in/web/error/GlobalExceptionHandlerTest.java` (`@WebMvcTest` slice with security filters disabled):
  - an unknown path gives a 404 `urn:problem:not_found` body;
  - an unsupported HTTP method gives a 405 `urn:problem:method_not_allowed` body with an `Allow` header;
  - an unsatisfiable `Accept` header gives a 406 `urn:problem:not_acceptable` body;
  - an unexpected `RuntimeException` gives a 500 `urn:problem:internal_error` body with no stack trace, class name, or exception message in `detail`.

### Implementation for Foundational

- [X] T013 Create `src/main/resources/application.yaml` and three profile files `application-dev.yaml`, `application-stage.yaml`, `application-prod.yaml`.
  - **`application.yaml`**:
    - `spring.application.name: nox`;
    - `server.error.whitelabel.enabled: false`, `spring.mvc.problemdetails.enabled: false`;
    - `logging.structured.format.console: com.nox.infrastructure.logging.NoxStructuredLogFormatter`;
    - `nox.auth.issuer-uri: ${NOX_AUTH_ISSUER_URI:}`, `nox.auth.jwks-uri: ${NOX_AUTH_JWKS_URI:}`, `nox.auth.audience: ${NOX_AUTH_AUDIENCE:nox-api}`, `nox.auth.jwks-connect-timeout: 2s`, `nox.auth.jwks-read-timeout: 2s`;
    - `nox.service.environment` set per profile.
  - **`application-dev.yaml`**: defaults pointing at `http://localhost:8180/realms/nox-dev`.
  - **`application-stage.yaml`** and **`application-prod.yaml`**: no auth defaults (values must come from env).
- [X] T014 [P] Create `src/main/java/com/nox/infrastructure/config/AuthProperties.java`:
  - `@ConfigurationProperties("nox.auth")` record (`issuerUri`, `jwksUri`, `audience`, `Duration jwksConnectTimeout`, `Duration jwksReadTimeout`) with `@Validated`, `@NotBlank` on the three strings, and both durations defaulting to 2 s and required to be positive;
  - a `prod`/`stage` startup check that rejects blank values (makes T008 pass).
- [X] T015 [P] Create `src/main/java/com/nox/infrastructure/config/ClockConfig.java` exposing a `Clock` bean (`Clock.systemUTC()`), replaceable in tests
- [X] T016 [P] Implement `src/main/java/com/nox/infrastructure/logging/NoxStructuredLogFormatter.java` as a Spring Boot `StructuredLogFormatter<ILoggingEvent>` (makes T009 pass).
  - **Schema**: builds the knowledge-base schema from T009 using `JsonWriter`.
  - **Service fields**: reads `service.name`/`version`/`environment` from `Environment`.
  - **Context resolution**: resolves trace (OTel `Span.current()`), correlation ID and request (`RequestContextHolder`) itself, plus event key-value pairs. It never reads MDC.
  - **Appender**: must run on the logging thread, so the console appender stays synchronous (document this in the class Javadoc).
- [X] T017 [P] Implement the response envelope in `src/main/java/com/nox/adapter/in/web/response/`, making T010 pass:
  - `ApiResponse.java`: record `<T>(T data, Meta meta)`;
  - `Meta.java`: record `(String traceId, Instant timestamp, String instance)`;
  - `MetaFactory.java`: uses Micrometer `Tracer` and `Clock`.
- [X] T018 Implement the error catalogue and writer in `src/main/java/com/nox/adapter/in/web/error/` (makes T011 pass):
  - `ProblemType.java`: enum of the seven types in data-model.md, each with HTTP status and fixed generic `detail`;
  - `ProblemResponseWriter.java`: writes the problem body to an `HttpServletResponse` (for filters) and builds a `ResponseEntity` (for controllers).
- [X] T019 Implement `src/main/java/com/nox/adapter/in/web/error/GlobalExceptionHandler.java` (`@RestControllerAdvice`) using `ProblemResponseWriter`. It handles:
  - `NoResourceFoundException` → `not_found`;
  - `HttpRequestMethodNotSupportedException` → `method_not_allowed`, keeping the `Allow` header;
  - `HttpMediaTypeNotAcceptableException` → `not_acceptable`, always written as `application/problem+json`;
  - `Exception` → `internal_error`, logging the full exception at `ERROR`.

  Makes T012 pass; depends on T018.
- [X] T020 [P] Create the shared Keycloak test realm `src/test/resources/keycloak/nox-test-realm.json`. **No literal credentials**: client secrets and user passwords are environment placeholders (`${NOX_TEST_CLIENT_SECRET}`, `${OTHER_APP_SECRET}`, `${ALICE_PASSWORD}`, `${BOB_PASSWORD}`, `${CAROL_PASSWORD}`, `${DAVE_PASSWORD}`) that Keycloak resolves on import. Contents:
  - realm `nox-test`, RS256, access-token lifespan 300 s;
  - bearer-only client `nox-api` with client role `nox-api-admin`;
  - confidential client `nox-test-client` (service accounts on, direct-access grants on, audience mapper → `nox-api`);
  - confidential client `other-app` (no audience mapper);
  - realm role `nox-tester`;
  - users `alice` (realm role `nox-tester`, client role `nox-api-admin`, email `alice@example.test`), `bob` (no roles, no email), `carol` (email only), `dave` (realm role `nox-tester`; reserved for the role-removal test so shared users never change).
- [X] T021 Create `src/test/java/com/nox/integration/support/KeycloakContainerSupport.java`:
  - **Container**: a singleton `KeycloakContainer("quay.io/keycloak/keycloak:26.8.1")` importing `nox-test-realm.json`.
  - **Credentials**: generated at runtime (random UUIDs) and passed to the container with `withEnv` for the T020 placeholders. Exposed to `TestTokens` through an in-memory holder; never written to disk or logs.
  - **Spring properties**: `@DynamicPropertySource` sets `nox.auth.issuer-uri`, `nox.auth.jwks-uri`, and `nox.auth.audience=nox-api`.
  - **Clock**: a `MutableClock` test bean replaces `Clock`.
  - **Base class**: an abstract `@SpringBootTest(webEnvironment = RANDOM_PORT)` class exposing `RestTestClient`.
- [X] T022 Create `src/test/java/com/nox/integration/support/TestTokens.java`:
  - **Real tokens from Keycloak**: user tokens (password grant via `nox-test-client` for `alice`/`bob`/`carol`/`dave`), a service token (client credentials for `nox-test-client`), and an `other-app` token. All use the runtime credentials from `KeycloakContainerSupport`.
  - **Crafted tokens (Nimbus)**: tampered payload, `alg=none`, foreign-issuer RS256 token signed with a locally generated key, wrong-`kid` token.
  - **Safety**: never prints tokens.
- [X] T023 Run `./mvnw verify`. T007–T012 pass, and the Keycloak container starts with the test realm.

**Checkpoint**: Foundation ready. Layers are enforced, logging, envelopes, and errors work, and the
test harness is available.

---

## Phase 3: User Story 1 - Only authenticated callers can reach the API (Priority: P1) 🎯 MVP

**Goal**: Every non-probe endpoint requires a valid Keycloak token (local verification, exact
issuer, `nox-api` audience, RS256, time checks). Failures return 401 (or 503 when keys are
unobtainable) in the standard problem format. Probes report readiness (latched) and health.

**Independent Test**: Call the test-only `GET /api/v1/test/ping` with: no token, expired,
tampered, `alg=none`, foreign-issuer, `other-app` (wrong audience), valid user, and valid
service-client tokens. Only the last two get 200; all others get 401 with
`urn:problem:unauthenticated`. `/readyz` and `/healthz` respond without a token.

### Tests for User Story 1 (write first, confirm they fail)

- [X] T024 [P] [US1] Write `src/test/java/com/nox/domain/health/ServiceHealthTest.java` for the data-model.md rule "`DOWN` if any critical check is `DOWN`; else `DEGRADED` if any check is not `UP`; else `UP`. No checks means `UP`", covering all combinations
- [X] T025 [P] [US1] Write `src/test/java/com/nox/application/service/GetServiceHealthServiceTest.java` and `CheckReadinessServiceTest.java` in the same folder. Use hand-written fake `DependencyHealthProbe`/`ReadinessCondition` ports and no Spring, asserting aggregation and readiness pass-through.
- [X] T026 [P] [US1] Write `src/test/java/com/nox/adapter/out/identityprovider/SigningKeyStateTrackerTest.java` asserting the data-model.md transitions:
  - `NOT_LOADED → LOADED` on the first success;
  - `LOADED ↔ LOADED_STALE` on refresh failure or success;
  - no transition back to `NOT_LOADED`;
  - `isReady()` false only in `NOT_LOADED`.
- [X] T027 [P] [US1] Write `src/test/java/com/nox/infrastructure/config/JwtValidationTest.java` (unit, Nimbus-signed tokens with a local key, injected `Clock`). Assert rejection of:
  - `alg` other than RS256 (incl. `none`, HS256);
  - `iss` not exactly equal to the configured issuer;
  - `aud` not containing `nox-api`;
  - `exp` more than 30 s in the past;
  - `nbf` more than 30 s in the future.

  Assert acceptance of a token that satisfies all checks.
- [X] T028 [P] [US1] Write `src/test/java/com/nox/adapter/in/web/security/BearerFailureHandlerTest.java`:
  - `InvalidBearerTokenException` → 401 `unauthenticated` with header `WWW-Authenticate: Bearer` (no `error`/`error_description` params);
  - `AuthenticationServiceException` → 503 `identity_provider_unavailable`;
  - in both cases, a `WARN` security log event with `reason` code and request path is emitted, and the log contains no token.
- [X] T029 [P] [US1] Create the test-only protected endpoint `src/test/java/com/nox/integration/support/TestOnlyPingController.java` (`GET /api/v1/test/ping` → 200 `ApiResponse` with `data: "pong"`; `GET /api/v1/test/boom` → throws `IllegalStateException`), registered only via a `@TestConfiguration`
- [X] T030 [P] [US1] Write `src/test/java/com/nox/integration/AuthenticationIT.java` against `/api/v1/test/ping` covering spec US1 scenarios 1–6:
  - **401 cases**: no header; `Basic` scheme; empty Bearer; two tokens; expired (advance `MutableClock` past `exp`+30 s); tampered; `alg=none`; foreign issuer; `other-app` token (audience lacks `nox-api`).
  - **401 assertions**: status 401, `WWW-Authenticate: Bearer`, `application/problem+json`, `type=urn:problem:unauthenticated`, and an identical generic `detail` for every case (FR-014).
  - **200 cases**: valid `alice` user token and the `nox-test-client` service token.
- [X] T031 [P] [US1] Write `src/test/java/com/nox/integration/EndpointEnumerationIT.java`. It iterates every mapping in `RequestMappingHandlerMapping`, excluding `GET /healthz` and `GET /readyz`, and asserts each returns 401 without a token (SC-001).
- [X] T032 [P] [US1] Write `src/test/java/com/nox/integration/ProbesIT.java` (no token):
  - `GET /readyz` → 200 with empty body once keys are loaded;
  - `GET /healthz` → 200 matching contract `Health` (`status`, `serviceId: "nox"`, `version` equal to the Maven project version from build info, `checks[0].componentId: "keycloak"`, `componentType: "external-service"`, `status: "UP"`, `time`).
- [X] T033 [P] [US1] Write `src/test/java/com/nox/integration/KeyRotationIT.java` (SC-007, FR-013):
  1. Call with a token signed by the current key → 200.
  2. Add a new higher-priority `rsa-generated` key provider via the Keycloak admin client.
  3. Obtain a new token whose `kid` is the new key and call → 200, with no restart and no failed request.
- [X] T034 [P] [US1] Write `src/test/java/com/nox/integration/KeycloakOutageIT.java`. It uses its **own** Keycloak container and a fresh application context per scenario (`@DirtiesContext`). Cases:
  - **(a) Started while Keycloak is paused** (FR-017, SC-010): `/readyz` 503 with no body and `/healthz` 503 `DOWN`. After unpausing, `/readyz` becomes 200 with no restart.
  - **(b) Keys loaded, then Keycloak paused**: `/readyz` stays 200, `/healthz` 200 `DEGRADED`, and a valid token with a known `kid` still gets 200.
  - **(c) Keys loaded, then Keycloak paused, unknown `kid`**: 503 `urn:problem:identity_provider_unavailable`, and the request is not processed (FR-011, SC-006).
    - The paused container accepts TCP connections but never answers, which simulates a **slow** Keycloak.
    - The 503 MUST arrive within connect timeout + read timeout + 1 s (≤ 5 s with the 2 s defaults), proving the request does not hang (research R3).
- [X] T035 [P] [US1] Write `src/test/java/com/nox/integration/ErrorResponsesIT.java` (constitution Principle VI: every error type covered by an integration test). With a valid `alice` token:
  - `GET /api/v1/does-not-exist` → 404 `urn:problem:not_found`;
  - `POST /api/v1/test/ping` → 405 `urn:problem:method_not_allowed` with an `Allow` header;
  - `GET /api/v1/test/ping` with `Accept: application/xml` → 406 `urn:problem:not_acceptable`;
  - `GET /api/v1/test/boom` → 500 `urn:problem:internal_error` with generic `detail`.

  Every response is `application/problem+json` and matches contract `Problem`. 401, 403 and 503 are covered by T030, T051 and T034.

### Implementation for User Story 1

- [X] T036 [P] [US1] Create the domain health types in `src/main/java/com/nox/domain/health/`. All are plain Java and immutable; `ServiceHealth` holds the aggregation rule (makes T024 pass).
  - `HealthStatus.java`: `UP` | `DOWN` | `DEGRADED`
  - `ComponentType.java`: `REDIS` | `POSTGRESQL` | `MONGODB` | `RABBITMQ` | `EXTERNAL_SERVICE`, serialized lowercase with hyphen
  - `ComponentHealth.java`: record `componentId`, `componentType`, `status`, `critical`, `time`
  - `ServiceHealth.java`: `aggregate(List<ComponentHealth>)`
- [X] T037 [P] [US1] Create the application ports (plain Java interfaces) in `src/main/java/com/nox/application/port/`:
  - in `src/main/java/com/nox/application/port/in/`: `GetServiceHealthUseCase.java`, `CheckReadinessUseCase.java`;
  - in `src/main/java/com/nox/application/port/out/`: `DependencyHealthProbe.java` (`ComponentHealth check()`), `ReadinessCondition.java` (`boolean isReady()`).
- [X] T038 [US1] Implement the use cases in `src/main/java/com/nox/application/service/` (no Spring annotations; makes T025 pass; depends on T036, T037):
  - `GetServiceHealthService.java`: aggregates all `DependencyHealthProbe`s via `ServiceHealth`;
  - `CheckReadinessService.java`: all `ReadinessCondition`s must be ready.
- [X] T039 [P] [US1] Implement the key-state tracking in `src/main/java/com/nox/adapter/out/identityprovider/` (makes T026 pass):
  - `SigningKeyState.java`: enum;
  - `SigningKeyStateTracker.java`: thread-safe latch, `recordSuccess()`, `recordFailure()`, `state()`, `isReady()`.
- [X] T040 [US1] Implement `src/main/java/com/nox/adapter/out/identityprovider/SigningKeySource.java` (depends on T039):
  - **Key source**: wraps a Nimbus `JWKSourceBuilder` for a constructor-supplied `URI jwksUri` with cache, refresh-ahead, outage tolerance, and rate limiting (research R3). No dependency on `infrastructure` (I1); the value comes from `AuthProperties` in `UseCaseConfig`.
  - **Timeouts**: key fetches use a Nimbus `DefaultResourceRetriever` built from constructor-supplied `Duration connectTimeout` and `Duration readTimeout` (defaults 2 s each) and a 50 KiB response size limit. A slow or unresponsive Keycloak therefore fails the fetch, giving a 503 for an unknown `kid`, instead of hanging the request.
  - **State reporting**: reports every fetch success and failure to `SigningKeyStateTracker`.
  - **Background preload**: on startup, runs a load with exponential backoff (1 s → 30 s max) until the first success.
- [X] T041 [US1] Implement the port adapters in `src/main/java/com/nox/adapter/out/identityprovider/` (depends on T037, T039):
  - `KeycloakReadinessCondition.java` (implements `ReadinessCondition` → `tracker.isReady()`);
  - `KeycloakHealthProbe.java` (implements `DependencyHealthProbe`): `componentId "keycloak"`, `EXTERNAL_SERVICE`, status `DOWN`/`UP`/`DEGRADED` for `NOT_LOADED`/`LOADED`/`LOADED_STALE`, `critical = (state == NOT_LOADED)`, `time` from `Clock`.
- [X] T042 [US1] Implement the decoder and its validators in `src/main/java/com/nox/infrastructure/config/` (makes T027 pass; depends on T014, T040):
  - `JwtDecoderConfig.java`: `NimbusJwtDecoder` built on `SigningKeySource` with `JWSAlgorithm.RS256` only, and validators `JwtIssuerValidator` (exact), Spring Security's `JwtAudienceValidator` (requires `aud` to contain the configured audience), and `JwtTimestampValidator(Duration.ofSeconds(30))` with the injected `Clock`.
- [X] T043 [US1] Implement the 401/503 handlers in `src/main/java/com/nox/adapter/in/web/security/`, using `ProblemResponseWriter` (makes T028 pass):
  - `ProblemAuthenticationEntryPoint.java`: 401, `WWW-Authenticate: Bearer`;
  - `BearerFailureHandler.java`: `AuthenticationServiceException` → 503, otherwise delegate to the entry point.

  Both emit a `WARN` security log event with:
  - **`reason`**: `missing_token` | `malformed_token` | `invalid_signature` | `invalid_issuer` | `invalid_audience` | `token_expired` | `jwks_unavailable`, passed as the SLF4J key-value `reason`. It is derived from the exception, never from token contents.
  - **Request and trace context**: added by `NoxStructuredLogFormatter`, not passed by the handler (I2).
- [X] T044 [US1] Implement `src/main/java/com/nox/infrastructure/config/SecurityConfig.java` (depends on T042, T043):
  - **Session and headers**: stateless sessions; CSRF, form login, HTTP Basic, logout, and request cache disabled (headless bearer API).
  - **Access rules**: `permitAll` only for `GET /healthz` and `GET /readyz`; `anyRequest().authenticated()`.
  - **Bearer handling**: `oauth2ResourceServer().jwt()` with the T042 decoder, `authenticationEntryPoint` = `ProblemAuthenticationEntryPoint`, and bearer `authenticationFailureHandler` = `BearerFailureHandler`.
  - **Security headers**: `Cache-Control: no-store`, `X-Content-Type-Options`.
- [X] T045 [US1] Create `src/main/java/com/nox/infrastructure/config/UseCaseConfig.java` declaring `@Bean`s for `GetServiceHealthService` (all `DependencyHealthProbe` beans) and `CheckReadinessService` (all `ReadinessCondition` beans). Register `KeycloakHealthProbe`, `KeycloakReadinessCondition`, `SigningKeyStateTracker`, and `SigningKeySource` as beans here too. Pass `authProperties.jwksUri()` (as a plain `URI`), `jwksConnectTimeout()` and `jwksReadTimeout()` into `SigningKeySource`; adapters never import `AuthProperties`.
- [X] T046 [US1] Implement the probe adapters in `src/main/java/com/nox/adapter/in/web/probe/`, calling only `port.in` use cases (makes T032 pass):
  - `ReadinessController.java`: `GET /readyz` → 200 or 503 with **no body**;
  - `HealthController.java`: `GET /healthz` → `HealthResponse` with `serviceId "nox"`, 200 for `UP`/`DEGRADED` and 503 for `DOWN`. `version` comes from Spring Boot's `BuildProperties` (generated by the T002 `build-info` goal), injected via `ObjectProvider<BuildProperties>`; if build info is absent (e.g. an IDE run without Maven), the field is omitted.
  - `HealthResponse.java`: matches contract `Health` and does not serialize `critical`.
- [X] T047 [US1] Run `./mvnw verify`. T024–T035 and `ArchitectureTest` pass.

**Checkpoint**: MVP. The API is closed to unauthenticated callers, and probes work. US1 is
deployable on its own.

---

## Phase 4: User Story 2 - Access is limited by Keycloak roles (Priority: P2)

**Goal**: Endpoints declare required roles. Callers lacking them get 403 in the standard problem
format. Roles come only from `realm_access.roles` and `resource_access.nox-api.roles`.

**Independent Test**: Call the test-only `GET /api/v1/test/tester-only` (requires role
`nox-tester`) and `GET /api/v1/test/admin-only` (requires client role `nox-api-admin`):
- `bob` gets 403 `urn:problem:forbidden` on both;
- `alice` gets 200 on both;
- the service token gets 403;
- `GET /api/v1/test/ping` stays 200 for all of them.

### Tests for User Story 2 (write first, confirm they fail)

- [X] T048 [P] [US2] Write `src/test/java/com/nox/adapter/in/web/security/KeycloakRoleExtractorTest.java` covering:
  - realm roles only, client roles only, both merged and deduplicated;
  - roles under `resource_access.other-app` ignored;
  - missing `realm_access` and `resource_access` claims give an empty set;
  - non-list claim values give an empty set (no exception).
- [X] T049 [P] [US2] Write `src/test/java/com/nox/adapter/in/web/security/ProblemAccessDeniedHandlerTest.java` asserting:
  - 403 `application/problem+json` with `type=urn:problem:forbidden` and generic `detail`;
  - a `WARN` log event with key-value `reason=missing_role` and no token. Request path and caller are resolved by the formatter (I2), not passed by the handler.
- [X] T050 [P] [US2] Create the test-only `src/test/java/com/nox/integration/support/TestOnlyRoleController.java`, registered via `@TestConfiguration`:
  - `GET /api/v1/test/tester-only` with `@PreAuthorize("hasRole('nox-tester')")`;
  - `GET /api/v1/test/admin-only` with `@PreAuthorize("hasRole('nox-api-admin')")`.
- [X] T051 [P] [US2] Write `src/test/java/com/nox/integration/RoleAuthorizationIT.java` covering spec US2 scenarios 1–3 with the matrix from the Independent Test above. Scenario 4 (role removed → new token refused): remove `nox-tester` from `dave` (never `alice`) via the admin client, obtain a new token for `dave`, and expect 403 on `tester-only`.

### Implementation for User Story 2

- [X] T052 [P] [US2] Implement `src/main/java/com/nox/adapter/in/web/security/KeycloakRoleExtractor.java`. It returns a sorted, deduplicated `Set<String>` from `realm_access.roles` ∪ `resource_access.<clientId>.roles`, defensively typed. `clientId` is a constructor `String`, supplied from `authProperties.audience()` in `infrastructure.config`, never by importing `AuthProperties` (I1). Makes T048 pass.
- [X] T053 [P] [US2] Implement `src/main/java/com/nox/adapter/in/web/security/ProblemAccessDeniedHandler.java` with `ProblemResponseWriter` and the T049 log event (makes T049 pass)
- [X] T054 [US2] Implement `src/main/java/com/nox/adapter/in/web/security/CallerAuthenticationConverter.java`, a `Converter<Jwt, AbstractAuthenticationToken>` that maps extracted roles to `ROLE_<name>` authorities. Scope claims do not grant authorities. It returns a `JwtAuthenticationToken` for now; US3 changes the principal. Depends on T052.
- [X] T055 [US2] Update `src/main/java/com/nox/infrastructure/config/SecurityConfig.java`: declare the `KeycloakRoleExtractor` bean with `authProperties.audience()`, add `@EnableMethodSecurity`, set `jwtAuthenticationConverter` to `CallerAuthenticationConverter`, and set `accessDeniedHandler` to `ProblemAccessDeniedHandler` (depends on T053, T054)
- [X] T056 [US2] Run `./mvnw verify`. T048–T051 and all US1 tests pass.

**Checkpoint**: US1 and US2 both work independently.

---

## Phase 5: User Story 3 - The service knows exactly who is calling (Priority: P3)

**Goal**: Every authenticated request carries a domain `CallerIdentity` built only from the
validated token. Controllers pass it to use cases, `GET /api/v1/me` returns it, and logs record
`user.id`.

**Independent Test**: Call `GET /api/v1/me` as `alice`, `bob`, and the `nox-test-client`
service account. Each response matches contract `CurrentCallerResponse` and describes exactly
that caller. A spoofed `X-User-Id` header is ignored. Without a token, the response is 401.

### Tests for User Story 3 (write first, confirm they fail)

- [X] T057 [P] [US3] Write `src/test/java/com/nox/domain/caller/CallerIdentityTest.java` asserting the data-model.md invariants:
  - `subjectId` is "Non-blank";
  - "Exactly one of `username` / `clientId` is non-null, matching `callerType`";
  - `roles` is "Deduplicated, sorted" and never null (null → empty);
  - `email` is optional (`null` allowed).
- [X] T058 [P] [US3] Write `src/test/java/com/nox/adapter/in/web/security/JwtCallerIdentityMapperTest.java` with these cases:
  - user token → `USER`, `username=preferred_username`, `clientId=null`, `email` copied;
  - token with `client_id` claim → `SERVICE`, `clientId=client_id`, `username=null`;
  - token without `client_id` but `preferred_username` starting `service-account-` and `azp` set → `SERVICE`, `clientId=azp`;
  - missing `email` → `null`;
  - roles from `KeycloakRoleExtractor`.
- [X] T059 [P] [US3] Write `src/test/java/com/nox/application/service/GetCurrentCallerServiceTest.java` (plain JUnit, no Spring): it returns the given `CallerIdentity` unchanged
- [X] T060 [P] [US3] Extend `src/test/java/com/nox/infrastructure/logging/NoxStructuredLogFormatterTest.java` with caller cases:
  - when `SecurityContextHolder` holds an authentication whose principal is a `CallerIdentity`, the formatter emits `user{id: subjectId}` plus `user.clientId` only if `clientId` is non-null;
  - an anonymous or unauthenticated context emits no `user` object;
  - nothing is read from MDC or log arguments.
- [X] T061 [P] [US3] Write `src/test/java/com/nox/integration/CurrentCallerIT.java` covering spec US3 scenarios 1–3, 5, 6:
  - **`alice`** → 200 with `data.callerType=USER`, `username=alice`, `clientId=null`, `email=alice@example.test`, and `roles` containing `nox-tester` and `nox-api-admin`;
  - **`bob`** → `email=null`, `roles=[]`;
  - **service token** → `callerType=SERVICE`, `clientId=nox-test-client`, `username=null`, `email=null`;
  - **request with `X-User-Id: someone-else`** → still alice's `subjectId`;
  - **no token** → 401;
  - **every 200 response**:
    - `meta.traceId` matches `^[0-9a-f]{32}$`;
    - `meta.instance=/api/v1/me`;
    - no keys beyond contract `CallerIdentity`.
- [X] T062 [P] [US3] Write `src/test/java/com/nox/integration/LogHygieneIT.java` (Spring Boot `OutputCaptureExtension`):
  - **Requests to run**: valid, expired, wrong-audience, and forbidden requests.
  - **No credentials in output**: no JWT-shaped string (regex `eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.`) and no `Authorization` header value (SC-005).
  - **Security events**: logged with `reason` (FR-012).
  - **User attribution**: the authenticated request's log lines carry `user.id` equal to alice's `subjectId` (US3 scenario 4).
  - **Trace correlation**: log `trace.traceId` equals the response `meta.traceId` (constitution Principle VII).

### Implementation for User Story 3

- [X] T063 [P] [US3] Create the domain caller types in `src/main/java/com/nox/domain/caller/` (plain Java, makes T057 pass):
  - `CallerType.java`: `USER` | `SERVICE`;
  - `CallerIdentity.java`: record `subjectId`, `callerType`, `username`, `clientId`, `email`, `roles`, with a compact constructor enforcing the invariants and defensively copying `roles` to an unmodifiable sorted set.
- [X] T064 [P] [US3] Create the inbound port `src/main/java/com/nox/application/port/in/GetCurrentCallerUseCase.java` (`CallerIdentity execute(CallerIdentity caller)`) and its implementation `src/main/java/com/nox/application/service/GetCurrentCallerService.java` (plain Java, makes T059 pass)
- [X] T065 [US3] Implement `src/main/java/com/nox/adapter/in/web/security/JwtCallerIdentityMapper.java` (claims → `CallerIdentity` per research R6, using `KeycloakRoleExtractor`; makes T058 pass; depends on T063)
- [X] T066 [US3] Create `src/main/java/com/nox/adapter/in/web/security/CallerAuthentication.java` (`AbstractAuthenticationToken` with principal `CallerIdentity`, credentials erased, authorities `ROLE_<role>`). Update `CallerAuthenticationConverter.java` to return it via `JwtCallerIdentityMapper`. Depends on T065.
- [X] T067 [US3] Extend `src/main/java/com/nox/infrastructure/logging/NoxStructuredLogFormatter.java` to resolve `user{id, clientId}` from `SecurityContextHolder` when the principal is a domain `CallerIdentity` (makes T060 pass; depends on T063)
- [X] T068 [US3] Add a `GetCurrentCallerService` `@Bean` to `src/main/java/com/nox/infrastructure/config/UseCaseConfig.java`
- [X] T069 [US3] Implement the `/me` adapter in `src/main/java/com/nox/adapter/in/web/me/` (makes T061 pass):
  - `CallerIdentityResponse.java`: record matching contract `CallerIdentity`, nulls serialized explicitly;
  - `MeController.java`: `GET /api/v1/me` with `@AuthenticationPrincipal CallerIdentity` → `GetCurrentCallerUseCase` → `ApiResponse<CallerIdentityResponse>` via `MetaFactory`. No role requirement.
- [X] T070 [US3] Update `src/test/java/com/nox/integration/support/TestOnlyPingController.java` and `TestOnlyRoleController.java` to accept `@AuthenticationPrincipal CallerIdentity`. Run `./mvnw verify`: T057–T062 and all US1/US2 tests pass.

**Checkpoint**: All user stories are independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Performance proof, configuration hardening, container verification, review gate

- [X] T071 [P] Write `src/test/java/com/nox/integration/SecurityLatencyPerfIT.java` (`@Tag("perf")`):
  1. Send 500 warmed-up authenticated requests to `/api/v1/me`.
  2. Send 500 requests to a test-only unsecured echo endpoint.
  3. Assert the p95 difference is ≤ 50 ms (SC-004).
- [X] T072 [P] Write `src/test/java/com/nox/infrastructure/config/ProfileConfigurationTest.java` asserting:
  - `dev` loads with defaults;
  - `stage` and `prod` fail fast without `NOX_AUTH_*` variables;
  - no profile file contains a literal secret (scan `src/main/resources/application*.yaml` for `password`, `secret`, `token` values).
- [X] T073 Validate the contract: add an assertion helper in `src/test/java/com/nox/integration/support/ContractAssertions.java`. It checks `/api/v1/me`, `/healthz`, and problem responses against the field sets and patterns in `specs/001-keycloak-api-auth/contracts/openapi.yaml`. Use it in `CurrentCallerIT`, `ProbesIT`, and `AuthenticationIT`.
- [X] T074 Build and run the container per quickstart.md section 3:
  - `docker build -t nox:local .` succeeds;
  - `docker run --rm --entrypoint id nox:local` shows uid 10001;
  - the container logs single-line JSON at startup.
- [X] T075 Run the manual checks in `specs/001-keycloak-api-auth/quickstart.md` section 2 against `compose.yaml` Keycloak. Record any deviations in that file.
- [X] T076 Clean Code pass over `src/main/java/com/nox/`: no dead or commented-out code, no magic values (constants or `ProblemType`), intention-revealing names, methods ≤ ~20 lines. Confirm `ArchitectureTest` passes.
- [X] T077 Run the security expert review gate (constitution: every change) with `/security-review` on the branch. Resolve all high and critical findings, or record the owner's accepted justification in `specs/001-keycloak-api-auth/plan.md` Complexity Tracking.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Depends on Setup. BLOCKS all user stories.
- **US1 (Phase 3)**: Depends on Foundational.
- **US2 (Phase 4)**: Depends on Foundational and on US1's `SecurityConfig` and decoder
  (T042–T044). Roles cannot be tested without authentication.
- **US3 (Phase 5)**: Depends on Foundational, US1 (T044), and US2's `KeycloakRoleExtractor` and
  `CallerAuthenticationConverter` (T052, T054).
- **Polish (Phase 6)**: Depends on all stories.

### User Story Dependencies

- **US1 (P1)**: Independent after Foundational. This is the MVP.
- **US2 (P2)**: Builds on the US1 security chain. Independently testable via test-only role endpoints.
- **US3 (P3)**: Builds on the US1 chain and US2 role extraction. Independently testable via `/api/v1/me`.

Security is inherently layered, so stories are delivered in order (P1 → P2 → P3). Each one is
still verifiable on its own at its checkpoint.

### Within Each Story

- Tests are written and **fail** before implementation (Principle III).
- Domain → application ports and services → adapters → infrastructure wiring → controllers.
- Story checkpoint `./mvnw verify` before moving on.

### Parallel Opportunities

- Setup: T005, T006 in parallel after T002–T004.
- Foundational tests T007–T012 are all [P]. Implementations T014–T017 and T020 are [P].
- US1: tests T024–T035 are all [P]; T036, T037, T039 in parallel.
- US2: tests T048–T051 [P]; T052, T053 [P].
- US3: tests T057–T062 [P]; T063, T064 [P].
- Polish: T071, T072 [P].

---

## Parallel Example: User Story 1

```bash
# Write all US1 tests together (each in its own file):
Task: "T024 ServiceHealthTest in src/test/java/com/nox/domain/health/ServiceHealthTest.java"
Task: "T026 SigningKeyStateTrackerTest in src/test/java/com/nox/adapter/out/identityprovider/SigningKeyStateTrackerTest.java"
Task: "T027 JwtValidationTest in src/test/java/com/nox/infrastructure/config/JwtValidationTest.java"
Task: "T030 AuthenticationIT in src/test/java/com/nox/integration/AuthenticationIT.java"
Task: "T034 KeycloakOutageIT in src/test/java/com/nox/integration/KeycloakOutageIT.java"

# Then the independent building blocks together:
Task: "T036 domain health types in src/main/java/com/nox/domain/health/"
Task: "T037 application ports in src/main/java/com/nox/application/port/"
Task: "T039 SigningKeyStateTracker in src/main/java/com/nox/adapter/out/identityprovider/"
```

## Parallel Example: User Story 3

```bash
Task: "T057 CallerIdentityTest in src/test/java/com/nox/domain/caller/CallerIdentityTest.java"
Task: "T058 JwtCallerIdentityMapperTest in src/test/java/com/nox/adapter/in/web/security/JwtCallerIdentityMapperTest.java"
Task: "T061 CurrentCallerIT in src/test/java/com/nox/integration/CurrentCallerIT.java"
Task: "T063 CallerIdentity + CallerType in src/main/java/com/nox/domain/caller/"
Task: "T064 GetCurrentCallerUseCase + service in src/main/java/com/nox/application/"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 Setup → Phase 2 Foundational.
2. Phase 3 US1 → **STOP and VALIDATE** with `./mvnw verify` and quickstart.md probes and 401
   checks.
3. Run `/security-review` before merging the MVP.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. US1 → API closed to unauthenticated callers, probes live (MVP).
3. US2 → role-based access.
4. US3 → caller identity and `/api/v1/me`.
5. Polish → performance proof, container verification, security review gate.

---

## Notes

- Prerequisites before T023: JDK 25 and a Docker-compatible runtime installed locally (see
  research.md "Local environment findings").
- Open owner decisions from plan.md (Keycloak not mocked; `com.nox` package) must be confirmed
  before T002/T004. Renaming the package later touches every task path.
- Never print, log, or commit tokens, passwords, or client secrets, including in tests and
  fixtures.
- Commit after each task or logical group; stop at any checkpoint to validate.
