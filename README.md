# CodeLobby Runner

Isolated code execution and evaluation component for **CodeLobby**.

## Responsibilities

- Receive evaluation requests from the backend
- Compile or interpret participant code for the languages enabled for each task (planned: Java, C++ and Python)
- Execute Host-provided test cases and return structured results
- Report test outcomes and execution-time measurements
- Enforce per-run limits and clean up execution resources

## Security

Submitted source code is **untrusted**. The runner must isolate execution and enforce CPU, memory, time, output, filesystem and network restrictions as appropriate. Do **not** execute user submissions inside the main backend process.

## Development workflow

Track tasks using the **CodeLobby Development** project. Use small Issues and reviewed Pull Requests.

## Current scope

The service provides HTTP health and `POST /internal/v1/runs`, strict request
parsing, validation and safe error responses. It does not compile or execute
participant code. A valid authenticated Run returns `503 EXECUTION_UNAVAILABLE`
until a verified executor is supplied. Simulated successful execution exists only
in tests. Submit, job polling and capabilities endpoints are not implemented.
The full execution API contract is tracked in issue #5.

## Proposed internal API contract

The multi-file evaluation API proposal is documented in
[docs/api/CONTRACT.md](docs/api/CONTRACT.md), with an
[OpenAPI specification](docs/api/openapi.yaml) and
[JSON examples](docs/api/examples/). It covers host-defined tasks for Java, C++
and Python. This remains a proposal for the complete execution API, not a claim
of implemented sandbox or language support. The current Run transport is described below.

Public test visibility means public descriptions and results, not access to test
source. Both `VISIBLE_TEST` and `HIDDEN_TEST` source files are internal to the
backend/runner flow. Backend stores public descriptions and joins results by
`testId`; see the [example visibility notes](docs/api/examples/README.md).

## Requirements

- JDK 21 (both `java` and `javac` must be available)
- Internet access on the first build to download Maven and dependencies

The project uses Spring Boot 4.1.1 and Maven Wrapper with Maven 3.9.11.
A separate Maven installation is not required. Docker, Judge0, a database and the
backend are not required to build, test or start this bootstrap.

## Local development

Run the following commands from the repository root.

### macOS / Linux

On macOS, select an installed JDK 21 for the current terminal:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

On Linux, set `JAVA_HOME` to your JDK 21 directory and add its `bin` directory to
`PATH`. Confirm the version on either platform:

```sh
java -version
javac -version
./mvnw clean verify
./mvnw spring-boot:run
```

### Windows (PowerShell)

Select JDK 21 through `JAVA_HOME` and `PATH` if it is not already your default.
Then run:

```powershell
java -version
javac -version
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

### Health check

Open <http://127.0.0.1:8081/actuator/health> after startup. Expected HTTP status:
`200`. The JSON response contains:

```json
{"status":"UP"}
```

Actuator may also include health group names such as `liveness` and `readiness`.

Stop the service with `Ctrl+C`. The startup test uses a random port and checks
the real HTTP health endpoint without external services.

### Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `RUNNER_PORT` | `8081` | HTTP port, separate from the backend's default port |
| `RUNNER_BIND_ADDRESS` | `127.0.0.1` | Listening address; local access by default |
| `CODELOBBY_INTERNAL_KEY` | empty; internal requests rejected | Shared backend/runner secret, at least 32 characters when configured |

For example, if port 8081 is already in use:

```sh
RUNNER_PORT=8082 ./mvnw spring-boot:run
```

In PowerShell:

```powershell
$env:RUNNER_PORT = '8082'
.\mvnw.cmd spring-boot:run
```

To run the packaged application after a successful build:

```sh
java -jar target/codelobby-runner-0.0.1-SNAPSHOT.jar
```

The same `java -jar` command works in PowerShell. The application does not load
`.env` files automatically; configure variables in your terminal or IDE. No
credentials are needed to start the service or read health. Internal API calls
require the shared key described below. Only the health Actuator endpoint is
exposed, and health details are hidden.

### Internal Run endpoint

Use a cryptographically random key, keep it out of Git, browser payloads and logs,
and configure the same secret in the backend. For example, on macOS/Linux:

```sh
export CODELOBBY_INTERNAL_KEY="$(openssl rand -hex 32)"
./mvnw spring-boot:run
```

Keep this terminal's environment available when making the request, or configure
the same variable securely in your terminal/IDE on Windows. Do not publish the key.
Outside local development, use TLS or an equivalent protected service channel.

```sh
curl --request POST 'http://127.0.0.1:8081/internal/v1/runs' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json' \
  --header "X-CodeLobby-Internal-Key: $CODELOBBY_INTERNAL_KEY" \
  --data-binary @docs/api/examples/run-request.json
```

Currently this valid request returns HTTP `503`, `Retry-After: 1`, and:

```json
{"code":"EXECUTION_UNAVAILABLE","message":"Execution unavailable.","retryable":true}
```

A missing/incorrect key returns `401`; without configured key all internal calls
are rejected. Configuring a nonempty key shorter than 32 characters prevents startup.
Health does not require the key and does not certify execution readiness.

The endpoint accepts uncompressed UTF-8 JSON only. Unknown fields, repeated JSON
keys, missing/null required fields, scalar type coercion, non-integer budget tokens
and trailing JSON are rejected. The raw body is limited to 2000000 bytes while
reading, including chunked requests. Invalid metadata/files return `400`, exceeded
size/count ceilings `413`, unsupported media/encoding `415`, unexpected processing
failures a generic `500`. Responses include `Cache-Control: no-store`; error bodies
contain exactly `code`, `message`, `retryable` and never echo submitted source or
exception details. An ordinary negative solution verdict would still be HTTP `200`.

HTTP tests use an executor supplied only through test configuration. No runtime
switch enables a fake successful grader. Parser/report evaluation, output redaction,
sandbox integration, aggregate resource accounting, deadlines, queuing and cleanup
are not implemented by this transport layer. Do not connect an unverified executor.

## Project layout

Application code lives in `src/main/java/pl/codelobby/runner`, configuration in
`src/main/resources`, and tests in `src/test/java`. See
[docs/STRUKTURA.md](docs/STRUKTURA.md) for package responsibilities.
