# Local Java Run and optional Submit setup

Run Judge0 in Linux containers and the runner with JDK 21. The browser calls the backend,
which calls the runner. For a direct integration check, the commands below call the runner
from a local terminal. No public server or local Judge0 image build is required.

## Environments and verification

| Environment | Intended setup | Verification status |
| --- | --- | --- |
| macOS Apple Silicon | Docker Desktop, amd64 image emulation, local JDK 21 | Real Java Run previously verified |
| macOS Intel | Docker Desktop, amd64 image, local JDK 21 | Not yet verified on an Intel Mac |
| Linux x86-64 | Rootful Docker Engine, Compose v2, cgroup v2, local JDK 21 | Runner tests pass in Ubuntu CI. Full Judge0 execution on a native Linux host remains unverified |
| Windows x86-64 | Docker Desktop WSL2 backend, integrated Ubuntu WSL2 distribution, JDK 21 inside WSL | Full startup and Java Run remain unverified |

Local lifecycle verification on 2026-11-14 used macOS 14.6 on Apple Silicon,
Docker client/engine 29.8.2, Compose 5.5.1 and cgroup v2. The updated `start.sh`
started Judge0 with the Run adapters. Six real authenticated HTTP evaluations passed:
accepted, wrong answer, compilation error, timeout, accepted after timeout, and accepted
after stopping/starting Judge0 and restarting the runner. No sandbox processes or
workspace mounts remained after the checks. The private configuration fingerprint and
PostgreSQL volume identity were preserved. This used cached images and existing private
configuration, not a cold installation on another computer. The package build also passed
478 tests with 11 optional tests skipped. These results do not certify Linux or Windows.

Linux ARM64 and Windows ARM64 require separate emulation verification. The published Judge0
image is `linux/amd64`, not a native multi-architecture image. A configuration or successful
HTTP health check alone is not evidence that sandbox execution works on a new machine.

Prerequisites:

- Git, Bash, JDK 21 (`java` and `javac`), and internet access for the first download/build.
- A running Docker engine with Compose v2 supporting `up --wait --wait-timeout`.
- Linux containers, cgroup v2 and a rootful engine. Judge0 requires privileged services,
  host cgroup namespace access and writable delegated CPU/memory/IO/PID controllers.
  The scripts do not change the host's cgroup configuration or security settings.
- Host `openssl` to generate the initial private configuration and runner key.
- `curl` for the example request. Python 3 is optional for engine smoke checks.
- Enough Docker disk space and memory. Previous Mac verification used approximately
  8 GiB allocated to the Docker VM. Allow about 20 GB free Docker storage for this setup.
  These are preparation guidelines, not a verified minimum for every host.

Use a local development machine whose Docker configuration permits these operations.
Rootless Docker and additional isolation products are not certified by this setup.
Do not expose Judge0 outside localhost.

## 1. Prepare your terminal

### macOS

Start Docker Desktop. Install JDK 21 and select it in the terminal if necessary:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

### Linux

Install Docker Engine with the Compose plugin and JDK 21 using your distribution's supported
installation procedure. Ensure your terminal can access the rootful daemon. Select JDK 21
through `JAVA_HOME`/`PATH` if multiple JDKs are installed. A Docker daemon on cgroup v1
is not supported by these adapters. Do not change cgroups blindly to get past a check.

### Windows with WSL2

1. Install/update WSL2 and an Ubuntu distribution.
2. Install/start Docker Desktop, select its WSL2 backend and enable integration for Ubuntu.
   Use Linux containers. Do not install a second Docker daemon inside Ubuntu for this route.
3. Open the Ubuntu terminal. Install Git, JDK 21, OpenSSL and curl inside that distribution.
4. Clone the repository inside the Linux filesystem, for example `~/projects/codelobby-runner`.
   Avoid `/mnt/c/...` for this setup's mounted scripts and private configuration.
5. Run all remaining shell commands inside Ubuntu, including `./mvnw`.
   Running `start.sh` in Git Bash is not a supported replacement for WSL2.

This follows Docker's [WSL2 setup](https://docs.docker.com/desktop/features/wsl/)
and [Linux filesystem recommendations](https://docs.docker.com/desktop/features/wsl/best-practices/).
Native Windows runner commands (`.\mvnw.cmd`) are documented in the root README, but the
complete recommended Windows route here keeps the runner and startup commands inside WSL2.

From the repository root, check:

```sh
java -version
javac -version
docker version
docker compose version
```

Both Java commands must report version 21. On macOS/Windows, the relevant Linux kernel is
inside Docker's environment. On Linux it is the daemon host's kernel.

## 2. Start Judge0

From the repository root:

```sh
./dev/judge0/start.sh
```

The script checks basic engine requirements, downloads the pinned image if absent,
generates private `dev/judge0/judge0.conf` once, and applies both Compose files.
Existing passwords and database volumes are preserved. Packaged adapters required
by Java Run and Submit are included automatically. A separate private artifact key
is generated once next to the configuration file. Credentials are never printed.

Open `http://127.0.0.1:2359/about`. To verify actual engine execution:

```sh
cd dev/judge0
python3 smoke-test.py
cd ../..
```

The optional smoke check executes Java, Python and C++ programs in Judge0. It does not
verify the runner API, Host report transport or every resource-isolation requirement.
Python and C++ are available in the engine but their runner Run adapters are not implemented.

If startup fails, read the generic error and check engine mode, cgroup support, available
space/memory and mount permissions. Do not print or share resolved Compose configuration
because it contains passwords. Do not delete the database volume as a troubleshooting step.

## 3. Start the runner with Run and Submit

From the repository root:

```sh
./dev/start-runner.sh
```

This also ensures Judge0 is running. It loads the engine artifact key from
`dev/judge0/judge0.conf.artifact-key` and enables Submit. For backend authentication,
it preserves `CODELOBBY_INTERNAL_KEY` if provided, otherwise generates and reuses
`dev/runner-internal.key`. These files use private permissions and remain outside Git.
The script prints the internal key file's location, never its contents. Configure
the backend with that internal key through its protected configuration. Do not copy
the artifact key into the backend or either key into the frontend.

`RUNNER_INTERNAL_KEY_FILE` overrides the internal key file. `JUDGE0_CONFIG_FILE`
and `JUDGE0_ARTIFACT_KEY_FILE` select the engine configuration and artifact key file.
Keep custom secret files outside version control. Existing files are preserved.
Ctrl+C stops the foreground runner. Use `./dev/judge0/stop.sh` to stop the engine.

Keep this terminal running. Give the backend the same internal key through its protected
configuration, never through the browser or Git. The runner does not automatically read
`.env` files. Its default HTTP address is `http://127.0.0.1:8081`.

The unauthenticated `/actuator/health` endpoint confirms runner startup, not Judge0 readiness.

## 4. Verify a real Run

Open a second terminal in the same environment and repository directory. Set
`CODELOBBY_INTERNAL_KEY` securely to the **same existing value** as the runner. Do not generate
a new key in that terminal. Then send the versioned Java example:

```sh
curl --fail-with-body --request POST 'http://127.0.0.1:8081/internal/v1/runs' \
  --header 'Content-Type: application/json' \
  --header "X-CodeLobby-Internal-Key: $CODELOBBY_INTERNAL_KEY" \
  --data-binary @docs/api/examples/run-request.json
```

Expected: HTTP 200, `result.status` equal to `ACCEPTED`, and every declared public case passed.
A `401` means the keys are missing/different. A `503` requires checking Judge0 and pipeline
availability. A `504` does not prove that sandbox execution has stopped.

For automated real-engine checks, with Judge0 running:

```sh
CODELOBBY_TEST_LOCAL_JUDGE0=true CODELOBBY_TEST_JUDGE0_REPORTS=true \
  ./mvnw -B -ntp -Dtest=Judge0ProjectIntegrationTests,JavaRunExecutorIntegrationTests,RunEndpointIntegrationTests test
```

## 5. Stop, restart and update

Stop the runner with Ctrl+C. Stop Judge0 from the repository root:

```sh
./dev/judge0/stop.sh
```

Start again with `./dev/judge0/start.sh`. The database and original secrets remain intact.
Use the same `JUDGE0_CONFIG_FILE` and `COMPOSE_PROJECT_NAME` if you set custom values.
For another Judge0 port, set `JUDGE0_PORT` for startup and match `JUDGE0_URL` in the runner.

After an image version update, drain all work, stop the runner and use
`./dev/judge0/start.sh` to apply the pinned packaged image. Pulling local adapter
source changes alone does not update files inside an existing image. Source
adapter overlays are for development and are documented in dev/judge0/README.md.

A plain container restart does not apply new mount definitions. If a failed Run quarantines
the pipeline, recover Judge0 workers, verify execution, then restart the runner. Run only
one standard modern worker deployment per Docker host because its cgroup subtree is fixed.

## Acceptance on each new platform

Record OS/CPU, Docker and Compose versions, cgroup version and the image digest. Verify a
fresh start, an actual accepted Run, wrong answer, compilation error, timeout, confirmed
memory-limit behavior, cleanup and a subsequent accepted Run, then stop/start and repeat a
valid Run. Engine and HTTP failures must remain controlled. Report unsupported permissions
or emulation problems explicitly rather than marking the platform verified from build-only CI.


## Optional Java Submit

The standard packaged image and `./dev/start-runner.sh` now enable both Java Run
and Submit without source-code overlays. The backend uses the same internal key
for POST `/internal/v1/evaluation-jobs` and GET of the returned `Location`.
The following records describe earlier verification stages and their configurations.

Local Submit HTTP verification on 2026-11-24 used Apple Silicon and the experimental
artifact overlay. Three real scenarios passed: accepted with a successful benchmark,
wrong answer with a skipped benchmark, and compilation error with a skipped
benchmark. Each used authenticated POST, GET polling, terminal replay and changed
payload conflict checks. Hidden diagnostic fields were null. Separate HTTP tests
covered active jobs, the shared Run/Submit queue, storage overflow, strict JSON and
disabled configuration. `./mvnw -B -ntp clean verify` passed 812 tests with 18
optional tests skipped and no failures or errors. The three real HTTP scenarios
were run separately with `CODELOBBY_TEST_SUBMIT_HTTP=true`. The default engine
configuration was restored and the containers stopped after verification. These
checks do not establish Linux/Windows portability or concurrent Submit capacity.


### Mixed Run and Submit verification

On 2026-11-27 the optional `RunSubmitConcurrencyIntegrationTests` passed against
real Judge0 on local Apple Silicon. The temporary runner used two active slots
and one pending slot. Two Submits overlapped in populated kernel sandbox cgroups.
A queued Run started after the wrong-answer Submit released capacity and overlapped
with the remaining accepted Submit in populated cgroups. Excess Submit received
429 and had no stored job. Active duplicate requests reused the existing jobs.
Results and public outputs matched their request IDs, hidden diagnostics remained
null, the accepted benchmark succeeded with five measured rounds, terminal replays
matched the saved jobs and a subsequent Run returned the expected result.
The test verified no remaining sandbox cgroups, journals, occupied reservations or
workspace mounts after completion.

The first trial found `COUNT=1` in the existing private `judge0.conf` and did not
confirm parallel sandbox execution. The passing trial used a private temporary
copy with `COUNT=2`, preserving all other configuration values. The original
configuration was not edited. Two runner admission slots alone do not make a
single Judge0 worker process execute two programs simultaneously. Deployment
configuration must align the intended active concurrency with worker `COUNT`.
This is a local functional check, not a load test or Linux/Windows certification.
See [the probe instructions](../dev/judge0/README.md#optional-real-run-and-submit-concurrency-probe).


### Submit failure and recovery verification

On 2026-11-27 two real HTTP failure/recovery probes passed locally on Apple
Silicon with the experimental artifact overlay. Each waited for a populated
sandbox cgroup before killing a Judge0 service. API server loss produced
FAILED/EXECUTION_INFRASTRUCTURE_ERROR. Worker loss reached the real 180-second
Submit deadline and produced FAILED/REQUEST_DEADLINE_EXCEEDED. The probe-only
Judge0 result timeout was 200 seconds so a shorter per-token timeout would not
mask the global deadline. Deployment timeout settings were not changed.

Both cases verified null results, retryable errors, failure of pending work without
starting it, terminal replay, HTTP 503 for new Run and Submit requests, and no
stored job for rejected work. Engine restart alone did not remove runner
quarantine. After verified cleanup and a fresh runner instance, a new Submit
finished with WRONG_ANSWER and a subsequent Run returned ACCEPTED. The old
in-memory job disappeared on runner restart as documented. Cleanup checks covered
sandbox cgroups, journals, occupied slot markers and workspace mounts.

The first deadline probe exposed a classification race: a transport failure could
publish the generic infrastructure error after the admission budget expired but
before the timer reported its deadline. The coordinator now checks that budget
when classifying a failure. Regression tests distinguish failure before expiry
from failure at expiry without firing the timer. Both real probes passed after
this fix. The test HTTP client also waits long enough for the existing 60-second
Run budget. These changes do not claim that interruption proves engine termination.

After verification the normal engine configuration was restored, containers were
stopped and the temporary artifact key was removed. The private configuration and
persistent database/worker-state volumes were retained. See
[the opt-in failure probes](../dev/judge0/README.md#optional-real-submit-failure-and-recovery-probes).

The final `./mvnw -B -ntp clean verify` run passed 814 tests with 20 optional
tests skipped and no failures or errors. The two destructive integration scenarios
were executed separately with `CODELOBBY_TEST_SUBMIT_FAILURES=true`.


### Packaged Submit image verification

On 2026-11-27 a local `codelobby-judge0:submit-candidate` image was built from the
pinned published 1.1.0 runtime using the `submit-runtime` Dockerfile target.
Its observed local image ID was
`sha256:7dcfc922c8af4599257f4220ccc4558ef821410559cda9a097f950ad05345b6d`.
It is not a published release or a registry download reference. Toolchains were
reused from the pinned runtime rather than rebuilt in this verification.

The candidate started with `compose.yaml` plus `compose.submit.yaml`, without the
Run source overlay or experimental artifact source overlay. Container inspection
confirmed the candidate image on both services and only the private read-only
`judge0.conf` bind mount. The worker-state volume was preserved. All eight packaged
adapter files matched repository SHA-256 values. The image user remained `judge0`
and no artifact key was present in the image's environment configuration.

Java 21 with heap allocation, Python 3.12 and C++17 smoke executions passed. Forty
Ruby adapter tests ran against files inside the image with no failures. Local
Python environment regressions passed 22 tests with 18 optional tests skipped.
Four real HTTP cases passed: accepted Submit with benchmarking, wrong-answer
Submit, compilation-error Submit, and the mixed Run/Submit concurrency scenario.
The latter verified populated sandbox cgroups, pending admission, overflow,
separate results, benchmarking, cleanup and a subsequent Run.

This packaging check did not repeat destructive recovery probes, which currently
restore their own source-overlay profile. It does not establish a cold installation
or portability to other hosts. See
[the packaged candidate instructions](../dev/judge0/README.md#packaged-java-submit-image-candidate).

The final runner build passed 814 tests with 20 optional tests skipped and no
failures or errors. Rollback recreated both services from the pinned published
1.1.0 image, with Java 21, Python 3.12 and C++17 smoke checks passing again.
Containers were then stopped and the temporary artifact key removed. Private
configuration and persistent volumes were retained. The local candidate image
remains available in Docker. No image was published and no Java code changed.


### 1.2.0 release startup preparation

The local 1.2.0 build contains the same packaged adapters verified above, with
release metadata updated. Startup now prepares a separate private artifact key,
uses the packaged Compose profile and mounts no source adapters. The new
`dev/start-runner.sh` starts Judge0 and the runner with Submit enabled, loading
that artifact key and either a provided backend internal key or a generated
private internal key file. Existing secrets are reused and not printed.

Local verification on 2026-11-27 passed 25 Python environment tests with 18 optional
tests skipped, including private key generation/reuse, invalid/symlink rejection,
custom configuration paths and preservation of a provided internal key. Shell
syntax checks passed. A real one-command startup on port 18081 passed authenticated
Run and asynchronous Submit with polling and hidden-feedback redaction. The full
runner build passed 814 tests with 20 optional tests skipped. The foreground test
runner and engine were stopped. Its temporary backend key was removed and the
persistent private artifact key preserved.

Registry publication of 1.2.0 subsequently completed. Anonymous manifest access
and Docker pull with an empty authentication configuration both succeeded. The
verified published digest is
`sha256:949f9e34c0797c3c4b5036f024e7ce9b02932b6f64b337391b5d6369f737b641`.
Local layers were cached, so this was not a cold installation test. No credentials
were written into the image or repository.


### Published 1.2.0 verification

The published digest above was fetched with an empty Docker authentication
configuration, using existing local layer caches. A fresh foreground runner on
port 18081 was then started through `dev/start-runner.sh`, using the published
pinned image on both engine services. Inspection confirmed no source-code adapter
bind mounts. Four real authenticated HTTP scenarios passed: accepted Run,
accepted Submit with five measured benchmark rounds, wrong-answer Submit and
compilation-error Submit. Submit probes checked polling, hidden diagnostic
redaction, active/terminal replay and changed-payload conflict responses.

The explicit `compose.previous.yaml` rollback started the preserved 1.1.0 image
with Run adapters. Java 21, Python 3.12 and C++17 engine smoke checks passed.
Normal startup then returned to 1.2.0 and all three smoke checks passed again.
The runner and engine were stopped, the temporary backend test key removed and
the persistent artifact key preserved with private permissions. Neither private
configuration nor database/worker-state volumes were removed. No Java code or
language toolchains changed in this rollout. Cross-platform runtime certification
and a cold installation on another machine remain unverified.


## Integration handoff: backend and frontend

Updated 2026-11-27 for runner `main` after PR #41 and the published Judge0 1.2.0
image. This section describes the runner interface and work required in the other
repositories. It does not claim the complete website has been tested.

At this check, backend `main` snapshot
[66b420b](https://github.com/CodeLobbyStudio/codelobby-backend/tree/66b420bdab6ffdd5b3d8ca6b782d31ebb8440478)
did not contain RunnerClient or Run/Submit controllers. An older local integration
checkout contains Run integration, but is not proof that those changes are merged.
Agree the backend/frontend revisions to use before the joint test. The public
backend routes below are proposals, not verified existing endpoints.

### Connection and startup

```text
Browser -> backend participant API -> runner internal API -> Judge0
```

The frontend implements the code editor and buttons. The backend authenticates
users, checks lobby permissions, constructs authoritative requests and persists
results. The runner validates and evaluates Java. Judge0 supplies sandboxed
compilation/execution. Keeping these programs in separate repositories does not
prevent HTTP communication between their running processes.

For runner startup, follow the prerequisites above and run from the runner root:

```sh
./dev/start-runner.sh
```

Keep this terminal open. The engine uses `http://127.0.0.1:2359` and the runner
uses `http://127.0.0.1:8081` by default. No registry login or image build is needed.
Start the backend and frontend according to their selected revisions' READMEs.
This command starts only the engine and runner, not the other repositories.
Default admission is two active operations and ten pending operations across the
whole runner instance, shared by Run and Submit, not a separate pool per lobby.
Keep engine COUNT aligned with the chosen active capacity. Existing private
configuration is preserved, including an older COUNT=1 setting.

| Setting | Owner and meaning |
| --- | --- |
| Runner base URL | Backend config, normally `http://127.0.0.1:8081` when both JVMs run directly on the same host |
| Internal key | Backend sends it as `X-CodeLobby-Internal-Key`. Default runner key file is `dev/runner-internal.key` |
| Artifact key | Runner-to-Judge0 only, normally `dev/judge0/judge0.conf.artifact-key`. Backend does not need it |
| Public backend URL | Frontend config. The browser calls this address, never runner/Judge0 directly |

If CODELOBBY_INTERNAL_KEY was provided before launching, the launcher preserves
that value instead of creating the default internal key file. Give the backend
the same value through its protected configuration. Backend config property names
must be implemented/agreed by its owner, they are not automatically supplied by
the runner. Never place either key in frontend environment variables or responses.
If the backend runs in a container, its localhost points to that container. Agree
a reachable private runner address and bind settings rather than copying the
host-only URL blindly. Keep Judge0 private.

The implemented internal routes are:

| Method and path | Result |
| --- | --- |
| POST `/internal/v1/runs` | Synchronous public-test evaluation, no benchmark |
| POST `/internal/v1/evaluation-jobs` | Asynchronous public and hidden evaluation plus benchmark policy |
| GET `/internal/v1/evaluation-jobs/{submissionId}` | Job snapshot, never starts work |

Use UTF-8 JSON, Content-Type/Accept `application/json` and the internal key header.
Only JAVA is currently implemented through the runner. The capabilities endpoint
and runner C++/Python flows remain unimplemented even though Judge0 has those
compilers. A health response alone does not prove sandbox execution works.

### Direct runner check before backend integration

Run these only from a trusted backend/developer terminal in the runner root,
not from browser code. If the launcher generated the default internal key file:

```sh
if [ -z "${CODELOBBY_INTERNAL_KEY:-}" ]; then
  export CODELOBBY_INTERNAL_KEY="$(cat dev/runner-internal.key)"
fi
curl -sS -i http://127.0.0.1:8081/internal/v1/runs \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -H "X-CodeLobby-Internal-Key: $CODELOBBY_INTERNAL_KEY" \
  --data-binary @docs/api/examples/run-request.json
curl -sS -i http://127.0.0.1:8081/internal/v1/evaluation-jobs \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -H "X-CodeLobby-Internal-Key: $CODELOBBY_INTERNAL_KEY" \
  --data-binary @docs/api/examples/submit-request.json
curl -sS http://127.0.0.1:8081/internal/v1/evaluation-jobs/submission-001 \
  -H 'Accept: application/json' \
  -H "X-CodeLobby-Internal-Key: $CODELOBBY_INTERNAL_KEY"
```

The Submit example uses the fixed ID submission-001. Repeat GET until terminal,
or use the relative Location returned by POST. Reposting this unchanged example
replays its job while retained. A different solution/attempt requires a new ID.
These responses are internal, do not forward them unfiltered to participants.

### Backend implementation steps

1. Configure an HTTP client for the private runner origin and internal key. Allow
   more than the 60-second total Run budget when waiting for synchronous Run,
   for example 65-70 seconds. Submit POST only registers a job, so do not hold a
   browser request open waiting for its evaluation/benchmark to finish.
2. On each participant action, authenticate the user and verify lobby membership,
   task availability, selected language and permission to read that submission.
   Apply ownership checks to every status request as well as creation.
3. Load the immutable task version from backend storage. Build roles, files,
   suites, entry classes and limits from it. Accept only declared EDITABLE file
   overrides from the browser. Do not accept browser-supplied tests, file roles,
   arbitrary entry points or trusted execution limits.
4. For Run, send every public suite and no HIDDEN_TEST files/suites. For Submit,
   send the complete authoritative public/hidden manifest, with public suites
   before hidden suites. Host test source is never sent to the participant.
5. Before POST Submit, create a durable submission record and freeze its complete
   request snapshot under a unique submissionId, such as a UUID. Preserve exact
   source contents and the taskVersionId. An edited solution is a new attempt.
6. POST the frozen request. Save the job ID and resolve the relative Location
   against the configured runner origin. Return the backend's own submission ID
   and processing state to the frontend. Do not blindly forward the internal Job.
7. Poll the runner independently of browser navigation, normally every second
   with backoff up to five seconds. GET is read-only. Stop polling a terminal job.
   Queue wait counts towards the 180-second Submit deadline.
8. Persist the final result/error and expose a participant-safe DTO. Use stored
   results for later reads. Runner storage is RAM-only, holds at most 100 jobs,
   expires terminal jobs after 30 minutes and is lost on restart. It is not the
   backend's results database or ranking store.

The complete payloads are already supplied in
[the Run example](api/examples/run-request.json) and
[the Submit example](api/examples/submit-request.json). These are full valid
requests, not fixed task signatures. Use your own authoritative task data.

| Submit field | Backend must supply |
| --- | --- |
| submissionId | Saved deduplication/attempt ID, unique across the integration |
| taskVersionId | Immutable authoritative version used for this attempt |
| language | JAVA |
| taskFiles | Complete original path/type/content manifest for the selected suites |
| submissionFiles | Participant path/content overrides of EDITABLE files only, possibly an empty array |
| testSuites | Suite IDs, PUBLIC/HIDDEN visibility, test file paths, JAVA_MAIN/mainClass and ordered case IDs |
| limits | Valid integer CPU, wall-time and memory budgets from trusted task settings |

Use all required fields. Strict parsing rejects unknown/missing/null fields and
inappropriate types. The response contractVersion remains `1.0.0-draft.2`, it is
not the image version `1.2.0`. See the [contract](api/CONTRACT.md) and
[OpenAPI schema](api/openapi.yaml) for the full shapes and cross-field rules.

### Status, retry and error handling

| Runner response | Backend/frontend meaning |
| --- | --- |
| New POST 202 | Accepted job, still inspect state in the body |
| Identical active POST 202 | Existing job, no second execution |
| Identical terminal POST 200 | Existing final snapshot, no new execution |
| GET 200 + QUEUED/RUNNING | Processing, result/error are null |
| GET 200 + FINISHED | Evaluation completed. Check result.status, not state alone |
| GET 200 + FAILED | Processing/infrastructure error, result is null. Use safe error.code/message/retryable |
| 400/413/415 | Invalid request or exceeded limits, correct the payload/configuration |
| 401 | Internal-key/configuration error, not a participant login prompt |
| 404 JOB_NOT_FOUND | Unknown, expired or lost job. Reconcile against durable backend state |
| 409 SUBMISSION_ID_CONFLICT | Same ID with different frozen data, do not silently overwrite |
| 429 | Admission/storage full, respect Retry-After and retry with bounded backoff |
| 503 | Execution unavailable or quarantined, report unavailability and use bounded retries/recovery |
| Run 504 | Run deadline exceeded. It does not prove the sandbox stopped |
| 500 | Internal processing error. Do not default to acceptance or automatic repeated submissions |

FINISHED may contain WRONG_ANSWER, COMPILATION_ERROR, RUNTIME_ERROR or other
negative verdicts. Only result.status ACCEPTED means accepted evaluation.
Handle every verdict and unknown values explicitly. Do not default to acceptance.
Benchmark state is separate: a nonaccepted evaluation skips benchmarking. A
verified benchmark-only failure may leave FINISHED/ACCEPTED with benchmark FAILED
and null metrics. Do not turn that into a wrong-answer verdict.

If the Submit POST response is lost, reconcile with GET or resend the same saved
ID and identical payload. Do not create a new attempt just because transport
failed. After a stored retryable FAILED result, a deliberate new evaluation needs
a new linked submissionId. Identical replay still returns that FAILED job.
After restart/expiry a lost ID can execute again, so there is no durable exactly-once
guarantee. Consult persisted backend results before reconstruction or retry.
For pre-admission 429/503, use bounded retries, for example 1/2/4 seconds with
jitter and at most three automatic retries, respecting a larger Retry-After.
A quarantined service may require operator recovery, not more repeated requests.
The runner has no cancellation/callback endpoint. Closing a browser or stopping
polling does not cancel accepted execution.

### Participant-safe response

Backend must whitelist fields for its public response. Include the overall
state/verdict, appropriate aggregate counts, public-case feedback and safe benchmark
summary. Match public test IDs to simplified names/descriptions stored with the
authoritative task version. Those descriptions are backend data, not runner fields.

Drop VISIBLE_TEST and HIDDEN_TEST file source from participant workspace/download
responses. Show only EDITABLE and READ_ONLY files. Drop individual hidden result
records, hidden IDs and suite metadata from participant results. Publish aggregate
hidden counts only. Runner already nulls hidden diagnostics, but that alone is not
a complete participant-facing filter. Treat compiler output, message, stdout/stderr
and public diagnostics as nullable. Display safe strings as text, not raw HTML.
Shared-sandbox tests are not tamper-proof external grading, as explained in the
contract. Do not promise runtime test secrecy or use these results as proof of
adversarially trusted execution.

### Frontend implementation steps

1. Agree the public backend endpoints and DTOs before wiring buttons. Suggested
   routes are listed below. They are not the runner routes or a promise that
   backend main already implements them.
2. Load an authorized workspace from the backend. Render the task description,
   selected language, simplified public test descriptions and participant-visible
   files. The backend must already have filtered test source out of the payload.
3. Initialize CodeMirror or the team's chosen editor in the frontend with Java
   syntax support, file tabs and per-path contents. READ_ONLY tabs cannot be
   edited. Preserve edits when changing tabs. The runner supplies no web editor.
4. On Run, collect current EDITABLE file contents and call the agreed backend Run
   route. Show a loading state and then public test feedback/overall verdict.
5. On Submit, capture one coherent editor snapshot and call the backend creation
   route once. Keep the returned public submission ID. Disable duplicate submits
   for that pending attempt and show QUEUED/RUNNING separately from the verdict.
6. Poll only the backend's status route, using its participant-safe DTO. Stop on
   terminal state, page/component cleanup or a controlled polling error. Allow
   reopening an attempt using the backend's persisted record. Browser polling is
   not the backend's worker that retrieves/persists final runner results.
7. Show FINISHED with its verdict and benchmark state. Show FAILED/unavailable as
   a processing problem, not as an incorrect solution. Handle nullable diagnostics,
   queue full, lost connections and backend authentication/session failures.
8. Treat edits made after clicking Submit as a new draft. Do not overwrite the
   pending attempt's frozen source. A fresh deliberate attempt receives a new ID.

Suggested public backend API to agree with its owner:

| Proposed method and path | Purpose |
| --- | --- |
| GET `/api/lobbies/{lobbyId}/tasks/{taskId}/workspace` | Authorized participant workspace |
| POST `/api/lobbies/{lobbyId}/tasks/{taskId}/run` | Public-suite Run |
| POST `/api/lobbies/{lobbyId}/tasks/{taskId}/submissions` | Persist and create an asynchronous attempt |
| GET `/api/submissions/{submissionId}` | Authorized public status/result from backend storage |

The browser body should contain the selected allowed language if required by the
agreed backend DTO, plus editable path/content overrides. It must not contain the
internal key, host tests or trusted execution configuration. Agree exact DTO field
names rather than copying the runner's authoritative envelope into the browser.

### Joint acceptance check

First select compatible runner, backend and frontend revisions and follow each
repository's startup instructions. These checks are a plan for the joint session,
not a claim that the complete UI was already exercised:

- Confirm Run and Submit work directly through the runner, then through backend
  HTTP before connecting the browser. Health alone is insufficient.
- Open an example multi-file Java task, edit its editable file and confirm the
  read-only helper cannot be changed through the normal editor.
- Run a correct solution, then Submit it. Confirm public feedback and an accepted
  result, with benchmark data shown only when available.
- Submit an incorrect solution and a compilation error. Both can be FINISHED with
  negative verdicts rather than FAILED. Verify safe error presentation.
- Confirm a repeated HTTP request for the same frozen attempt creates one runner
  job, while a new edited attempt receives a new ID.
- Confirm queue-full/unavailable handling and that leaving the page does not
  cancel backend processing. Reopen the persisted attempt after completion.
- Inspect browser Network responses for absence of both internal keys, public and
  hidden test source, hidden individual IDs/results and raw internal traces.
- Try reading another participant's submission and verify backend authorization.
- Restart runner and verify stored backend results remain readable. Missing active
  jobs must be reconciled rather than automatically classified as wrong answers.

Only the runner repository was changed to prepare this guide. The backend and
frontend owners must implement/confirm their public API and participate in the
end-to-end test. Backend persistence, frontend behavior and the complete website
are not certified by the earlier runner-only probes.


## Submit retention verification

On 2026-11-27 `SubmitJobRetentionTests` verified the real HTTP controller, error
mapping and InMemoryEvaluationJobStore using a controlled Clock and a test-only
executor. No Judge0 process or 30-minute real-time wait was used. The production
store and execution code did not need changes.

The four scenarios cover:

- FINISHED results remain readable one nanosecond before expiresAt, expire exactly
  at that instant and return HTTP 404/JOB_NOT_FOUND. Identical terminal replay
  does not refresh retention or dispatch evaluation again.
- FAILED retention is measured from completion rather than creation, with the
  same 30-minute boundary and HTTP 404 after expiry.
- QUEUED and RUNNING snapshots have no expiresAt and are not purged by terminal
  retention. These snapshots are seeded directly to isolate storage retention
  from the coordinator's separate, previously tested 180-second execution limit.
  The test does not claim real executions can run for hours.
- A full 100-record store rejects new work with 429 before expiry. At expiry it
  frees space, preserves the active snapshots and removes expired fingerprints.
  A changed payload under an expired ID can create a fresh job, whereas it gives
  409 before expiry. This confirms retention is not durable exactly-once execution.

The four HTTP scenarios and 12 existing store tests passed in the focused run.
This validation complements the earlier real-engine execution/recovery probes.
The backend must still persist results independently of runner RAM and authorize
participant access to its own public result endpoints.

Expiry is enforced when the store is accessed, including GET and registration.
There is no separate background retention timer. Reads at or after expiresAt
observe the record as absent and registration purges expired entries before
checking storage capacity. The final `./mvnw -B -ntp clean verify` run passed 818
tests with 20 optional tests skipped and no failures or errors.
