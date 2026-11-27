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
container registry. Start it with `./dev/judge0/start.sh` after starting Docker.
The script includes the adapters required by Java Run. Judge0 uses localhost port
2359 and is separate from the runner application on port 8081.
Follow the [local setup guide](docs/LOCAL_SETUP.md) for macOS, Linux or Windows with WSL2.
The guide distinguishes configured environments from those actually verified.

## Current scope

The service provides HTTP health and `POST /internal/v1/runs`, strict request
parsing, validation and safe error responses. Authenticated Java Run compiles and
executes all supplied public test suites through the local Judge0 environment.
It returns evaluated results and enforces queue, compilation and runtime limits.
Judge0 must be started with the bundled adapters. Java Submit and job polling are
available through opt-in internal HTTP routes with the experimental artifact
overlay described below. Capabilities and C++/Python Run are not implemented.

Internal Submit models, `InMemoryEvaluationJobStore` and background coordination
are wired to the real Java evaluator when `runner.submit.enabled=true`.
Submit is disabled by default and needs the separate artifact transport key.
The store retains at most
100 job snapshots and fingerprints. It deduplicates concurrent identical IDs,
rejects changed payloads under retained IDs, and removes terminal jobs on the next
store access at or after 30 minutes from completion. Active jobs are not evicted.
All snapshots and fingerprints are lost on runner restart. `SubmitAdmission` atomically reserves shared execution capacity before a new job
becomes visible. A replay does not acquire capacity, and queue rejection leaves no
new job. Reservations preserve FIFO order and include dispatch delay in the
180-second Submit budget. A reservation may execute once, or be closed before
execution. Closing running work does not release capacity before it finishes.
`SubmitRequestValidator` checks identifiers, resource budgets, file manifests and
suite relationships before the coordinator fingerprints, stores or admits work.
Run and Submit share these rules. Submit allows public and hidden suites, requires
public suites before hidden suites, and matches each test file to exactly one
suite of the same visibility. Only declared EDITABLE files can be overridden.
Invalid requests do not create jobs, reserve queue capacity or reach the evaluator.
This structural check does not prove the backend supplied every case of a task.
`AsyncSubmitCoordinator` dispatches validated payloads through two bounded worker
threads and updates job states using an injected `EvaluationExecutor`. Duplicate
submissions are not dispatched again. A deadline covers admission, dispatch and
execution. Expired active work is interrupted and new execution is quarantined
without claiming engine termination. Late results cannot overwrite FAILED.
Closing the coordinator fails waiting work, initiates stop of active work and
shuts down its worker and deadline services. Running reservations remain held
until their execution actually returns. Source-list snapshots are immutable.
The default dispatcher has a 100-task bounded buffer, additionally constrained
by shared admission and the store's 100-record limit. Completed deadline timers
are removed rather than retained.
`SourceFileOverlay.applySubmit` validates and applies editable overrides in memory.
`JavaSuitePlanner.prepareSubmit` selects shared EDITABLE/READ_ONLY sources and only
the current suite's test files, preserving suite and case order. Hidden test files
never enter public plans or other hidden suites. Plans are internal immutable
snapshots, not browser responses. Planning does not compile, execute or benchmark.
`JavaSuiteArchive.create` remains public-only for Run. Its explicit `createSubmit`
path accepts one suite's matching public or hidden test role and rejects mixed roles.
`JavaSubmitSuiteExecutor` submits one such archive, polls its token, reads the
separate report and returns evaluated cases with external compilation/runtime
measurements. Hidden case diagnostics are null and raw engine streams are discarded.
The caller must hold admission capacity and supply remaining aggregate budgets.
This component is not a complete Submit evaluator or a registered production bean.
It does not perform benchmarks or prove cleanup after transport failures.

Optional real-engine tests for this stage, after starting local Judge0:

```sh
CODELOBBY_TEST_LOCAL_JUDGE0=true CODELOBBY_TEST_JUDGE0_REPORTS=true \
  ./mvnw -B -ntp -Dtest=JavaSubmitSuiteExecutorIntegrationTests test
```

These run accepted, wrong-answer and compilation-error scenarios separately for
public and hidden suites. They verify suite execution and hidden-field redaction,
not shared request budgets, asynchronous job lifecycle or benchmark behavior.
`JavaSubmitEvaluationRunner` evaluates the complete selected Java manifest in order
and produces an internal pre-benchmark summary. Compilation shares 10000 ms CPU
and 20000 ms wall across targets. Runtime CPU and wall budgets are deducted from
external suite measurements, rounded down before the next suite. Memory caps each
sandbox and the summary reports the maximum observed peak, not a sum. Exhausted
budgets prevent further launches and leave remaining cases NOT_RUN. Independent
targets may continue after a solution failure if budgets remain. One monotonic
deadline includes validation, preparation and engine communication, using the time
remaining after admission. Transport failures propagate without resubmission.
The runner does not reserve capacity or create a terminal job by itself.

Optional whole-manifest execution check against the local engine:

```sh
CODELOBBY_TEST_LOCAL_JUDGE0=true CODELOBBY_TEST_JUDGE0_REPORTS=true \
  ./mvnw -B -ntp -Dtest=JavaSubmitEvaluationRunnerIntegrationTests test
```

`JavaSubmitBenchmarkRunner` implements the benchmark policy against an injected
`CompiledJavaSubmission` session. It skips nonaccepted evaluations, executes two
warmup rounds and five measured rounds, and uses a fresh aggregate execution
budget for every round within the remaining operation deadline. Only measured
rounds contribute to median external CPU and maximum observed memory. Incomplete,
incorrect, out-of-budget or recompiled round results fail without partial metrics.
The session must match both submissionId and the validated payload fingerprint.
Uncertain execution failures propagate to the owner rather than implying cleanup.
The owner retains admission and must close the session after confirmed termination.

`Judge0CompiledJavaSubmission` connects the initial evaluation and artifact-only
rounds using the optional private Judge0 adapter. It freezes the selected request,
binds the session to submissionId and its payload fingerprint, fetches captures
after terminal suite execution, and deletes the remote records. It retains at most
8 MiB of decoded class data across the request, represented by private replay
archives. Missing, oversized or corrupt captures prevent benchmarking without
changing the earlier solution verdict. Failed cache deletion falls back to the
bounded adapter TTL. The session permits at most seven rounds and close releases
its archive references. Unknown execution termination propagates and blocks reuse.

`HttpJudge0ArtifactStore` uses authenticated bounded HTTP GET/DELETE, no redirects
or proxies, and a separate private artifact key. It reuses the engine transport's
bounded response and timeout handling. Artifact contents are never part of the
participant API. No Docker CLI is invoked by application code. The default image,
startup and Run configuration remain unchanged. The optional
[artifact adapter](dev/judge0/README.md#experimental-compiled-java-artifact-capture)
is required for these sessions and is not enabled by default.

`JavaCompiledArtifacts` verifies snapshot structure, size, paths and SHA-256.
`JavaCompiledSuiteArchive` restores immutable classes through class-only archives,
without source or compiler scripts. Every round uses fresh per-suite sandboxes,
fresh aggregate execution limits and the operation's remaining monotonic deadline.

`JavaSubmitEvaluationExecutor` implements EvaluationExecutor using the real session
and benchmark runner. It assembles EvaluationResult only after the session closes,
keeps evaluation measurements separate from benchmark metrics, returns skipped
benchmarks for solution failures, and preserves ACCEPTED on a verified benchmark
failure. Compiler and message diagnostics remain null. One caller-supplied deadline
covers session opening, evaluation, benchmarking and close.

The optional worker publishes a private cleanup receipt only after the bounded
workspace lease has been verified released. The session waits for this receipt for
each initial suite and replay, including compilation failures whose terminal status
can precede cleanup. Missing confirmation fails closed, without a partial result.
Receipt transport failure does not certify process termination or release capacity.

Spring registers the real executor, private artifact transport and coordinator
only when Submit is enabled. They share the Run execution queue. Tests cover real
FINISHED results, stored replay without reexecution, and FAILED jobs with
quarantined capacity after uncertain infrastructure failures. Deployment/image
packaging and further real expiry/concurrency verification remain separate work.
No fake evaluator is registered as a production bean.


### Optional internal Java Submit HTTP API

Submit currently requires the experimental artifact overlay in
[dev/judge0/README.md](dev/judge0/README.md#experimental-compiled-java-artifact-capture).
The published Judge0 image alone does not enable the artifact endpoints.
Enable the overlay first, with no active work. In the runner terminal, configure:

```sh
export RUNNER_SUBMIT_ENABLED=true
export RUNNER_JUDGE0_ARTIFACT_KEY="$CODELOBBY_ARTIFACT_KEY"
```

`CODELOBBY_ARTIFACT_KEY` must contain the same private value used for the Judge0
overlay. It is separate from `CODELOBBY_INTERNAL_KEY`, which authenticates the
backend to the runner. Never give either key to the frontend. Use the existing
Run startup settings for the runner port, Judge0 URL and internal key.
Missing or invalid artifact configuration prevents an enabled Submit service from
starting. With Submit disabled, authenticated valid Submit requests return 503.

From the repository root, using the runner's actual port (8081 by default):

```sh
curl -sS -i -X POST http://127.0.0.1:8081/internal/v1/evaluation-jobs \
  -H 'Content-Type: application/json' \
  -H "X-CodeLobby-Internal-Key: $CODELOBBY_INTERNAL_KEY" \
  --data-binary @docs/api/examples/submit-request.json
```

A new job returns 202 and a relative `Location` header. Poll that location with
GET and the same internal header, for example:

```sh
curl -sS http://127.0.0.1:8081/internal/v1/evaluation-jobs/submission-001 \
  -H "X-CodeLobby-Internal-Key: $CODELOBBY_INTERNAL_KEY"
```

Replace `submission-001` with the ID from your request or use the returned location.
Active jobs contain no partial result. FINISHED
contains the complete evaluation and benchmark outcome. FAILED contains a safe
job error. Solution errors normally finish with an unsuccessful verdict, rather
than becoming infrastructure failures.

The backend saves `submissionId` and the complete authoritative public/hidden
payload before POST. Reposting the same payload returns the existing job, with
202 while active or 200 when terminal. Changing a retained ID's payload returns
409. Use a new ID for a new attempt. Unknown or expired jobs return 404. Full
shared capacity or storage returns 429 with `Retry-After: 1`. GET never starts
execution. The backend must persist final results and filter participant feedback
before sending it to the frontend. Runner job memory is lost on restart.

To verify the real HTTP flow against the experimental overlay:

```sh
CODELOBBY_TEST_SUBMIT_HTTP=true \
  ./mvnw -B -ntp -Dtest=SubmitEndpointIntegrationTests test
```

Keep `CODELOBBY_ARTIFACT_KEY` exported for this optional test. It starts a temporary
runner on a random port and exercises accepted, wrong-answer and compilation-error
jobs, polling, replay and conflict responses. It does not publish an image.


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
backend are not required to build, run the standard tests or start the runner.
Judge0 and its backing services are required to execute real submissions.

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
| `JUDGE0_REQUEST_TIMEOUT` | `10s` | Limit for one HTTP request |
| `JUDGE0_POLL_INTERVAL` | `500ms` | Interval between result queries |
| `JUDGE0_RESULT_TIMEOUT` | `60s` | Overall wait for one engine submission result |

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
Base64, status IDs or mismatched tokens fail safely. Judge0's LF/CRLF-wrapped
Base64 diagnostics are accepted, while other invalid characters remain rejected.
Non-UTF-8 program output is
decoded with replacement characters. The result body is limited to 300000 bytes,
stdout/stderr/compile output to 65536 decoded bytes each and message to 4096 bytes.
Oversized data is rejected rather than silently presented as complete output.
Engine data may include hidden information and must pass evaluation/redaction
before any participant-facing response. Result `toString()` hides diagnostics.

Communication timeouts are separate from participant CPU/wall-time budgets and
do not implement the complete Run/Submit deadline, queueing or resource cleanup.
The Java Run executor uses this client and applies the request-wide deadline and
shared capacity separately. Client HTTP tests use an in-process server and require
neither Docker nor Judge0. Verification against the real sandbox is opt-in as described below.

### Judge0 client integration checks

With JDK 21 selected, start Docker Desktop and run from the repository root:

```sh
./dev/judge0/start.sh
./mvnw -B -ntp -Pjudge0-integration verify
```

On Windows, prefer the complete WSL2 path in [the local setup guide](docs/LOCAL_SETUP.md).
If testing the runner natively in PowerShell against an already running Judge0, use:

```powershell
.\mvnw.cmd -B -ntp -Pjudge0-integration verify
```

The `judge0-integration` profile activates Maven Failsafe for `HttpJudge0LiveIT`.
It runs in addition to the normal tests and uses the application's actual
configuration and client beans, without starting the runner HTTP server.
Judge0 must already be running. An unavailable or incompatible Judge0 fails the
live checks, rather than silently skipping them. The target is restricted to
localhost, with the default `JUDGE0_URL` and Java language ID shown above.

The checks submit two controlled programs to the sandbox. One uses Java 21
collection methods, verifies runtime version 21 and must print exactly `5` plus a
newline with successful exit and available time/memory measurements. The other
contains a syntax error and must return a compilation-error result with compiler
diagnostics. A third check verifies a refused connection at an unused local port
without stopping Judge0. It does not create a sandbox execution.

Reports are written to `target/failsafe-reports/`. Ordinary `./mvnw clean verify`
and the current CI workflow do not execute `*IT` tests or require Docker.
The checks do not start/stop containers, change sandbox configuration, clean the
Judge0 database or test the public Run endpoint. Keep submitted probe records as
local diagnostic data. CPU, memory exhaustion and communication timeout behavior
are covered by other environment/client tests, not by these three live checks.

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

See [Java Run result evaluation](docs/TEST_RESULT_EVALUATION.md) for comparison ownership,
Host configuration checks and failure attribution.

See [internal API authentication and validation](docs/INTERNAL_API_VALIDATION.md)
for the implemented checks, limits, signature-validation scope and regression tests.

Start Judge0 from the repository root. The required suite-report overlay is included:

~~~sh
./dev/judge0/start.sh
~~~

The overlay reads the local adapters in dev/judge0/image. After updating those files,
wait for active work to finish, then recreate the server and workers. A plain
`restart` does not apply new bind mounts and may retain previously mounted files:

~~~sh
cd dev/judge0
docker compose --env-file judge0.conf -f compose.yaml -f compose.reports.yaml up -d --force-recreate --wait server workers
cd ../..
~~~

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

A completed Java Run returns HTTP 200 with RunResponse, including the request and
task-version IDs, the solution verdict, the number of passed tests and ordered
public test results. Negative solution verdicts such as WRONG_ANSWER,
COMPILATION_ERROR and TIME_LIMIT_EXCEEDED also use HTTP 200.

The application provides one shared `ExecutionQueue` in `core`. Java Run uses it
for admission, waiting and failure quarantine. Future execution operations must use
the same instance rather than create independent queues. Run uses synchronous waiting. Submit can reserve capacity before asynchronous
dispatch. Both share this process-local queue and its failure quarantine.

By default, two Runs execute at a time and up to ten wait in FIFO order with the pinned Judge0 1.1.0 image. The 60-second deadline
includes queue waiting, compilation, evaluation and engine communication. A full
queue returns 429 QUEUE_CAPACITY_EXCEEDED. Engine unavailability returns
503 EXECUTION_UNAVAILABLE. An operation or engine communication timeout returns
504 REQUEST_DEADLINE_EXCEEDED. Capacity errors and unavailability carry
the Retry-After header with a value of 1.

Active and waiting limits are deployment configuration, not participant request fields:

| Property | Environment variable | Default | Meaning |
| --- | --- | --- | --- |
| `runner.execution.max-active` | `RUNNER_EXECUTION_MAX_ACTIVE` | `2` | Active operations, currently `1` or `2` |
| `runner.execution.max-pending` | `RUNNER_EXECUTION_MAX_PENDING` | `10` | Maximum number of waiting operations, excluding active executions |

For example, set `RUNNER_EXECUTION_MAX_PENDING=20` in the runner process environment
before starting it. Restart the runner to apply a change. Setting `0` disables
waiting, so a request arriving when all active slots are occupied is rejected immediately.
The pending limit must be nonnegative, and active plus pending capacity must not exceed `2147483647`. Negative, nonnumeric,
out-of-range and unknown `runner.execution` settings prevent startup.

This range prevents invalid semaphore capacity and is not a verified deployment
sizing recommendation. A larger queue does not increase throughput or the number
of active executions. Waiting still counts towards the 60-second Run deadline.
Choose capacity using measured execution times and available resources. Judge0 image 1.1.0 supports two processes in one worker container. Fresh configuration defaults to `COUNT=2` and `RUNNER_EXECUTION_MAX_ACTIVE=2`. Existing private `judge0.conf` files are preserved, so update their `COUNT` setting to `2` before using the default two-slot runner. For serial operation, explicitly set both limits to `1`. A communication failure still quarantines new work globally, while operations already active are allowed to finish. Do not start multiple worker containers or multiple runner instances with this local-only queue. The supported active limit is currently `1` or `2`.

An unconfirmed execution or cleanup failure quarantines the pipeline. Further Runs
receive 503. To recover locally, stop or restart the Judge0 workers and verify
their readiness, then restart Runner. An HTTP timeout by itself does not establish
that the engine stopped the program.

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
exception details. An ordinary negative solution verdict uses HTTP `200`.

HTTP validation and error tests use a controlled executor. Real HTTP execution tests
run against the local Judge0 with the suite-report overlay. With that environment
running, use JDK 21 and enable the tests explicitly:

~~~sh
CODELOBBY_TEST_LOCAL_JUDGE0=true CODELOBBY_TEST_JUDGE0_REPORTS=true \
  ./mvnw -B -ntp -Dtest=RunEndpointIntegrationTests test
~~~

Set CODELOBBY_TEST_JUDGE0_URL when the test engine uses a different local port.
Java is the implemented Run language. C++ and Python Run requests are rejected
with 400 INVALID_REQUEST until their execution adapters are integrated.

## Project layout

Application code lives in `src/main/java/pl/codelobby/runner`, configuration in
`src/main/resources`, and tests in `src/test/java`. See
[docs/STRUKTURA.md](docs/STRUKTURA.md) for package responsibilities.
