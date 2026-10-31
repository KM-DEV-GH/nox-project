# Nox

Headless REST API (Java 25, Spring Boot 4.1). Every endpoint except the `/healthz` and
`/readyz` probes requires an OAuth 2.0 Bearer token issued by Keycloak.

This guide covers running it locally with **Podman**. Docker works the same way: replace
`podman` with `docker`.

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| JDK (host execution only; optional for container development) | 25 | `java -version` (or set `JAVA_HOME` to a JDK 25) |
| Podman | 5.x, with a running machine on macOS/Windows | `podman machine list` |
| Compose | `docker-compose` or `podman-compose` | `podman compose version` |
| `curl`, `jq` | any | for the manual checks |

Maven is not needed: use `./mvnw`, which downloads the pinned Maven 3.10.0.

## Run and debug in VS Code

1. Open this project folder in VS Code and install the recommended **Extension Pack for
   Java** and **REST Client** extensions when prompted.
2. Install JDK 25 and select it with **Java: Configure Java Runtime** if VS Code does not
   detect it. Start your Podman machine (see below).
3. Configure the root `.env` as described in **1. Configure local secrets**, including all
   five `NOX_OWL_*` settings. The launch configuration loads it automatically.
4. Wait for VS Code to import the Maven project, open **Run and Debug**, select
   **Nox: dev**, and press **F5** to debug or **Ctrl+F5** to run without debugging.

The launch task starts Keycloak, PostgreSQL and RabbitMQ using `compose.yaml`, compiles
the project with the Maven wrapper, and launches `com.nox.NoxApplication` on the host
with the `dev` profile. Keep port 8080 available; stop a container-based `app` first if
one is running. For Docker, change `podman` to `docker` in `.vscode/tasks.json`.

Set a breakpoint in a controller, then send a request from [nox.http](nox.http). Run its
token requests first for authenticated endpoints. The API is at <http://localhost:8080>;
wait for `/readyz` to return 200, especially on the first Keycloak startup. Swagger UI
is at <http://localhost:8080/docs>.

Stop debugging with **Shift+F5**. The dependencies stay running for the next session;
use **Tasks: Run Task → Nox: stop dependencies** to stop them without deleting containers.
You can also use **Run Test** / **Debug Test** above test methods in the Java editor.
Integration tests need the Podman Docker API socket described below.

The shared launch, task and extension recommendations are versioned. Machine-specific
`.vscode/settings.json` settings (such as your JDK path) remain ignored.

## Code style

Formatting rules are IDE-agnostic: they live in the repository, not in any editor's
configuration, so the same code comes out of VS Code, IntelliJ IDEA, Eclipse or a bare
terminal.

| File | Purpose |
|------|---------|
| [.editorconfig](.editorconfig) | Charset, LF endings, final newline, indentation. Supported natively by IntelliJ, Eclipse and VS Code — no plugin needed. |
| [.gitattributes](.gitattributes) | Normalises line endings in Git itself, independent of the editor. |
| [config/checkstyle/checkstyle.xml](config/checkstyle/checkstyle.xml) | Naming, imports and correctness rules. |

Java formatting is applied by [Spotless](https://github.com/diffplug/spotless) using
[palantir-java-format](https://github.com/palantir/palantir-java-format) (4-space indent,
120 columns). Neither goal is bound to the build lifecycle; run them on demand:

```bash
./mvnw spotless:apply     # reformat the code
./mvnw spotless:check     # fail if anything is unformatted
./mvnw checkstyle:check   # naming, imports, correctness
```

Checkstyle deliberately contains no whitespace, wrapping or line-length rules — those
belong to Spotless alone, so the two tools can never disagree about the same line.

## Develop entirely in containers (no local Java required)

Start your Podman machine and create `.env` as described in step 1 below. Then run:

```bash
podman compose -f compose.yaml -f compose.dev.yaml up app
```

This starts Keycloak, PostgreSQL and RabbitMQ and runs the application using Java 25 and the Maven wrapper inside
the development container. The API is at <http://localhost:8080>. The first startup downloads
the JDK image and Maven dependencies. Keycloak may take longer to start than the API;
wait for `curl -i http://localhost:8080/readyz` to return 200.

Edit the source files on your machine as usual; the project is mounted into the container.
After editing Java code, recompile and restart the application with:

```bash
podman compose -f compose.yaml -f compose.dev.yaml restart app
```

No image rebuild is needed. Maven dependencies and compiled output are stored in named
volumes. Run unit tests inside the running development container with:

```bash
podman compose -f compose.yaml -f compose.dev.yaml exec app ./mvnw test
```

Run tests when the application is idle, then restart it after changing code. Integration
tests use Testcontainers and additionally need access to the container engine; the development
container does not mount its socket. The host-based integration test setup is described below.

Stop the development stack with:

```bash
podman compose -f compose.yaml -f compose.dev.yaml down
```

The steps below also cover running Java directly on the host and building the packaged image.

### Podman setup (macOS)

```bash
podman machine init        # first time only
podman machine start
```

The tests (Testcontainers) and `docker-compose` look for a Docker API socket at
`/var/run/docker.sock`. Make Podman serve it there:

```bash
sudo podman-mac-helper install   # first time only, then restart the machine
podman machine stop && podman machine start
ls -l /var/run/docker.sock       # should point to the Podman socket
```

If you can't use the helper, point the tools at the Podman socket instead:

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
# Only needed if Testcontainers' cleanup container (Ryuk) fails to start, e.g. a rootless machine:
export TESTCONTAINERS_RYUK_DISABLED=true
```

## 1. Configure local secrets

The local Keycloak realm (`docker/keycloak/nox-dev-realm.json`) contains no passwords. They
come from environment variables. Create an untracked `.env` file in the project root (all
`.env*` files are git-ignored):

```bash
cat > .env <<EOF
KC_BOOTSTRAP_ADMIN_PASSWORD=$(uuidgen)
ALICE_DEV_PASSWORD=$(uuidgen)
BOB_DEV_PASSWORD=$(uuidgen)
NOX_DEV_SERVICE_SECRET=$(uuidgen)
NOX_DEV_BFF_SECRET=$(uuidgen)
NOX_DEV_INTERNAL_BFF_SECRET=$(uuidgen)
NOX_DB_PASSWORD=$(uuidgen)
NOX_RABBITMQ_PASSWORD=$(uuidgen)
EOF
```

| Variable | Used for |
|----------|----------|
| `KC_BOOTSTRAP_ADMIN_PASSWORD` | Keycloak admin console login (user `admin`) |
| `ALICE_DEV_PASSWORD` | Test user `alice` (role `nox-tester`, has an email) |
| `BOB_DEV_PASSWORD` | Test user `bob` (no roles, no email) |
| `NOX_DEV_SERVICE_SECRET` | Client secret of the service client `nox-dev-service` |
| `NOX_DEV_BFF_SECRET` | Client secret of `nox-dev-bff`, the partner BFF client holding the `partner-access` role (partner-facing endpoints) |
| `NOX_DEV_INTERNAL_BFF_SECRET` | Client secret of `nox-dev-internal-bff`, the internal BFF client holding the `internal-access` role (user partner link endpoints, with `X-User-ID`) |
| `NOX_DB_PASSWORD` | Password of the local PostgreSQL user `nox` |
| `NOX_RABBITMQ_PASSWORD` | Password of the local RabbitMQ user `nox` |

Partner linking also calls Owl, the user service. Add its settings to `.env` too — your own
credentials, never committed: `NOX_OWL_BASE_URL`, `NOX_OWL_TOKEN_URI`, `NOX_OWL_CLIENT_ID`,
`NOX_OWL_CLIENT_SECRET` and `NOX_OWL_API_KEY`. The application refuses to start without them.

These are local test accounts only. Never reuse real passwords here.

## 2. Start Keycloak

```bash
podman compose up -d keycloak
```

This starts Keycloak 26.8.1 on `http://localhost:8180` (bound to localhost only) and imports
realm `nox-dev`. Wait until it answers:

```bash
until curl -sf localhost:8180/realms/nox-dev/.well-known/openid-configuration >/dev/null; do sleep 2; done
```

Admin console: <http://localhost:8180/admin> (user `admin`, password from `.env`).

The realm data lives inside the container. `podman compose down` deletes it, and the next
`up` re-imports the realm file, so edit `nox-dev-realm.json` rather than the console when you
want a change to stay.

## 3. Run the service

```bash
podman compose up -d postgres rabbitmq
set -a; . ./.env; set +a
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

The `dev` profile points at the Keycloak from step 2 and at the local PostgreSQL and RabbitMQ.
Liquibase creates the schema at startup and, in `dev` only, the partner `demo-partner`. The service listens on
`http://localhost:8080` and logs one JSON object per line.

## 4. Try it

In VS Code, the quickest way is [nox.http](nox.http) with the
[REST Client](https://marketplace.visualstudio.com/items?itemName=humao.rest-client) extension.
It reads credentials from `.env`, gets tokens, and covers the success, 401 and other error
cases. Run the token requests at the top first.

With `curl`, load the variables into your shell first:

```bash
set -a; . ./.env; set +a
```

Probes (no token needed):

```bash
curl -i localhost:8080/readyz              # 200 with an empty body once signing keys are loaded
curl -s localhost:8080/healthz | jq
```

No token gives 401:

```bash
curl -i localhost:8080/api/v1/me
```

As user `alice`:

```bash
TOKEN=$(curl -s localhost:8180/realms/nox-dev/protocol/openid-connect/token \
  -d grant_type=password -d client_id=nox-dev-cli -d username=alice \
  --data-urlencode "password=$ALICE_DEV_PASSWORD" | jq -r .access_token)
curl -s localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN" | jq
```

As the service client:

```bash
TOKEN=$(curl -s localhost:8180/realms/nox-dev/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=nox-dev-service \
  --data-urlencode "client_secret=$NOX_DEV_SERVICE_SECRET" | jq -r .access_token)
curl -s localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN" | jq
```

Tokens are valid for 5 minutes. Don't paste them into tickets or chat.

## 5. Run the tests

```bash
./mvnw test                  # unit tests only, no containers
./mvnw verify                # unit + integration tests (starts its own containers)
./mvnw verify -Dgroups=perf  # also runs the latency tests
```

Integration tests don't use the compose services or your `.env`. Testcontainers starts a
throwaway Keycloak with the realm in `src/test/resources/keycloak/` and random credentials,
plus PostgreSQL, RabbitMQ and WireMock standing in for Owl. The first run pulls their images,
so it takes longer.

Run one integration test class:

```bash
./mvnw verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=CurrentCallerIT
```

## 6. Build and run the container image

```bash
podman build -t nox:local .
podman run --rm --entrypoint id nox:local      # uid=10001(nox): not root
```

Run it against the compose Keycloak. Tokens you get from the host have issuer
`localhost:8180`, so the issuer stays `localhost`. Only the key download URL uses the host
gateway name:

```bash
podman run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=dev \
  -e NOX_AUTH_ISSUER_URI=http://localhost:8180/realms/nox-dev \
  -e NOX_AUTH_JWKS_URI=http://host.containers.internal:8180/realms/nox-dev/protocol/openid-connect/certs \
  nox:local
```

On Linux with rootless Podman, `host.containers.internal` may not reach a port bound to
`127.0.0.1`. In that case add `--network host` and use `localhost` in both URLs.

## Configuration

Profiles are `dev`, `stage` and `prod`. `stage` and `prod` have no auth defaults and refuse to
start until these variables are set:

| Variable | Meaning | Default |
|----------|---------|---------|
| `NOX_AUTH_ISSUER_URI` | Exact `iss` value expected in tokens | dev: `http://localhost:8180/realms/nox-dev` |
| `NOX_AUTH_JWKS_URI` | Where signing keys are fetched from | dev: `<issuer>/protocol/openid-connect/certs` |
| `NOX_AUTH_AUDIENCE` | Value that must be in the token `aud` | `nox-api` |

## Stop everything

```bash
# stop the service with Ctrl+C, then:
podman compose down
```

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Tests fail with `Could not find a valid Docker environment` | Podman machine not running, or no socket: see Podman setup. |
| Tests fail at random with `localhost:2375 failed to respond` | Podman's API dropped an idle connection. Re-run; the tests already retry container calls. |
| `/readyz` stays 503 | Keycloak isn't reachable at `NOX_AUTH_JWKS_URI`. Check step 2. |
| Token request returns `Account is not fully set up` | The user is missing a field the realm requires. Recreate the realm with `podman compose down && podman compose up -d keycloak`. |
| Valid-looking token gets 401 | Check the token's `iss` matches `NOX_AUTH_ISSUER_URI` exactly (including host and port) and its `aud` contains `nox-api`. The service log has the `reason`. |
| App fails at startup with `nox.auth` binding errors | No profile or a `stage`/`prod` profile without the `NOX_AUTH_*` variables. Use `SPRING_PROFILES_ACTIVE=dev` locally. |

## More

- Feature spec, plan and API contract: [specs/001-keycloak-api-auth/](specs/001-keycloak-api-auth/)
- Full validation guide: [quickstart.md](specs/001-keycloak-api-auth/quickstart.md)
- Project rules: [.specify/memory/constitution.md](.specify/memory/constitution.md)
