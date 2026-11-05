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

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) builds and tests pull
requests targeting `main`, including new commits pushed to an open PR. It also
runs after pushes to `main`. Once the workflow is on the default branch, it can
be started manually from GitHub Actions.

The `CI` workflow runs `Build and test (Java 21)` on Ubuntu 24.04 with Temurin
JDK 21. It uses the Maven Wrapper and caches downloaded Maven dependencies.
The matching local command, with JDK 21 selected, is:

```sh
./mvnw -B -ntp clean verify
```

In PowerShell, use `.\mvnw.cmd -B -ntp clean verify`.

A compilation or test failure fails the PR check. See the build step log for
details. Surefire XML and text reports are uploaded as a `test-reports-...`
artifact on the Actions run page, including after a failed test run, and retained
for seven days. Compilation or setup failures can occur before reports exist,
in which case the upload step warns and the original failure remains visible.
Superseded runs for the same PR/ref are cancelled and each job has a 15-minute
timeout. An artifact upload failure also leaves the check failed.

CI requires no Judge0, Docker services, database, backend or configured repository
secrets. Executor doubles remain test-only. Official actions are pinned to commit
SHAs and the workflow token has read-only repository contents access. It does
not commit, deploy, merge or change branch protection. Making the check required
for merge is a separate repository setting.

## Local Judge0 environment

The optional [Judge0 development environment](dev/judge0/README.md) downloads a
prebuilt image with Java 21, Python 3.12 and C++17 from the maintainer's personal
container registry. Start it with `dev/judge0/start.sh` after starting Docker
Desktop. It uses localhost port 2359 and is separate from the runner application.
The runner's Judge0 client is not implemented by this infrastructure setup.

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
| `JUDGE0_URL` | `http://127.0.0.1:2359` | Judge0 HTTP(S) origin, without credentials, path, query or fragment |
| `JUDGE0_JAVA_LANGUAGE_ID` | `1001` | Java 21 ID in the published development image, not a universal Judge0 ID |
| `JUDGE0_CONNECT_TIMEOUT` | `2s` | Maximum time to establish a new HTTP connection |
| `JUDGE0_REQUEST_TIMEOUT` | `10s` | Planned limit for one HTTP request |
| `JUDGE0_POLL_INTERVAL` | `500ms` | Planned interval between result queries |
| `JUDGE0_RESULT_TIMEOUT` | `60s` | Planned overall wait for a submission result |

Judge0 settings are bound to `Judge0Properties` and validated at startup. Durations
must be at least `1ms`, with connect timeout no greater than request timeout,
request timeout no greater than result timeout, and poll interval shorter than
result timeout. The base URL may have a single trailing slash, which is removed.
Use a protected channel such as HTTPS when moving beyond localhost.

The configuration creates one reusable HTTP client with a connection timeout,
no proxy and no automatic redirects. Startup does not contact Judge0.
`Judge0Client.submitJava` can send one controlled Java source file and return its
validated engine token. It uses the configured Java language ID and sends
`POST /submissions?base64_encoded=true&wait=false`, with UTF-8 source/stdin encoded
as Base64. It disables participant network access, sets one execution and zero CPU
extra time, converts milliseconds to seconds exactly, and rounds bytes down to
KiB so the memory allowance is never increased. The internal probe input allows
131072 source bytes and 65536 stdin bytes with the contract's runtime budget range.
It does not replace the public multi-file Run model or implement host test grading.

Submission creation enforces `request-timeout` through receipt of the complete
response, including a stalled body, and bounds the token response to 4096 bytes.
Only HTTP 201 with a JSON UUID token is accepted. Connection failures, timeouts,
HTTP errors, invalid responses and interruptions produce a `Judge0ClientException`
with a typed reason and generic diagnostics. Source, stdin and engine response
bodies are not logged or attached to these failures. The client does not retry
POST requests at application level. Failure to receive a token does not establish
whether Judge0 accepted the submission or whether execution has stopped, so callers
must not automatically resubmit or release an execution slot on that assumption.

`Judge0Client.getSubmission(token)` performs one GET and can return a queued,
processing or terminal engine state. It requests only token, status, stdout,
stderr, compile output, message, CPU/wall time, memory and process exit information
using `base64_encoded=true`. The returned token must match the requested token.
Engine status IDs are validated, and Judge0 Internal Error becomes an
infrastructure exception rather than a participant verdict. Compatible extra JSON
fields are ignored. Optional missing/null diagnostics and measurements remain null,
while available empty diagnostics remain empty strings.

`Judge0Client.awaitResult(token)` polls this existing submission with `poll-interval`
pauses until a terminal state or `result-timeout`. The overall deadline starts
when this method is called, after token creation, and uses a monotonic clock.
Each request timeout and each pause is capped by the remaining overall budget.
HTTP/protocol failures stop polling immediately without retry or resubmission.
Polling failures retain the known token through the exception's `submissionToken()`
accessor for later reconciliation. Cancellation/timeout only ends local waiting
and never confirms sandbox termination or cleanup.

`Judge0Result` is internal engine data, not the public Run response or a completed
CodeLobby grade. It decodes Base64 diagnostics, converts CPU/wall seconds to
milliseconds and KiB memory to bytes, and retains exit code/signal. Invalid numbers,
Base64, status IDs or mismatched tokens fail safely. Non-UTF-8 program output is
decoded with replacement characters. The result body is limited to 300000 bytes,
stdout/stderr/compile output to 65536 decoded bytes each and message to 4096 bytes.
Oversized data is rejected rather than silently presented as complete output.
Engine data may include hidden information and must pass evaluation/redaction
before any participant-facing response. Result `toString()` hides diagnostics.

Communication timeouts are separate from participant CPU/wall-time budgets and
do not implement the complete Run/Submit deadline, queueing or resource cleanup.
The client is not connected to the Run executor, which still returns execution
unavailable. Client HTTP tests use an in-process server and require neither Docker
nor Judge0. Verification against the real sandbox is a separate step.

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
