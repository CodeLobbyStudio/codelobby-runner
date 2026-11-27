# Local Judge0 development environment

Owned by codelobby-runner. The default Compose configuration uses the prebuilt image `ghcr.io/ptrthdr/codelobby-judge0:1.1.0` on the maintainer's personal GitHub Container Registry account. The image is independent of the organization repository. Its immutable manifest digest is pinned in `compose.yaml` and `start.sh`.

The image provides Java 21, Python 3.12 and C++17, including the local workspace, OOM and compilation-budget adapters. Normal startup downloads it automatically. No image archive or local image build is required.

This is a local development and MVP demonstration environment. The Java Run endpoint uses
this environment through the runner's HTTP client. Start the runner separately with its
internal key. Java Submit is opt-in with the experimental artifact overlay below.
C++/Python Run are not implemented.

See [local setup for macOS, Linux and Windows/WSL2](../../docs/LOCAL_SETUP.md) for the full
start-to-Run procedure, requirements and verification status.

## Start

Install and start Docker Desktop on macOS or Windows, or a rootful Docker Engine with Compose v2 on Linux. On Windows, use the WSL2 backend and run the scripts in a Linux distribution. The image is amd64 and Apple Silicon uses emulation. Docker needs sufficient storage and memory. The tested Docker VM had approximately 8 GiB RAM. Allow at least 20 GB free Docker storage for image layers and the backing services. The first start requires internet access to download the public images.

From the repository root:

```sh
cd dev/judge0
./start.sh
```

The script checks that the engine runs Linux containers with cgroup v2 and is not rootless.
These checks do not prove all controller/mount permissions or sandbox behavior on a new host.
It downloads the pinned image if missing, verifies its registry manifest digest through Docker,
and creates private `judge0.conf` with three independent random secrets if the file is absent.
Both `compose.yaml` and `compose.reports.yaml` are applied automatically. Existing configuration is preserved. PostgreSQL and Redis are pinned by digest. Readiness checks wait for the database, Redis and HTTP API.

Open `http://127.0.0.1:2359/about` and `/languages`. HTTP availability alone does not verify sandbox execution. If Python 3 is installed on the host, run:

```sh
python3 smoke-test.py
```

This executes Java 21 with a 40 MiB allocation, a Python 3.12 feature, and a C++17 feature without caller compiler options. It writes ignored `smoke-results.json`. Host Python is only required for this optional verification.

Stop and start again:

```sh
./stop.sh
./start.sh
```

The Compose project is `codelobby-runner-modern`. Stopping preserves database data. Do not use `down --volumes` for an environment whose submissions must be retained. API access is bound to localhost.

Optional overrides:

```sh
JUDGE0_PORT=2360 ./start.sh
```

`JUDGE0_CONFIG_FILE` selects a different private configuration file, using the same value for start and stop. `COMPOSE_PROJECT_NAME` selects a different project. Run only one modern worker deployment per Docker host because the image uses a dedicated fixed cgroup subtree.

<a id="local-suite-report-prototype"></a>

## Local suite-report adapters

`./start.sh` includes the overlay required by Java Run. After changing adapter files or
mount definitions, wait for active work to finish, then recreate the server and workers.
For the default private configuration, run from this directory:

```sh
docker compose --env-file judge0.conf -f compose.yaml -f compose.reports.yaml up -d --force-recreate --wait server workers
```

After polling the submission to a terminal state, the runner can retrieve the suite report by
token through the local Judge0 HTTP listener. Reports stay separate from stdout and stderr.
Redis retains at most 32 report entries for up to five minutes. Use these local adapters on the
loopback-only listener. The submission token grants access to its report.

The overlay also returns external compiler CPU/wall measurements and enforces the remaining
per-target compilation budget supplied by the runner. Each project carries a trusted budget
header in its generated compile script.
The worker also captures a fixed host-configuration failure marker during compilation,
before any participant runtime starts. Runtime code cannot change that captured outcome.
The overlay mounts the matching Java launcher so the runner-owned compiler driver receives
the compiler memory allowance. Recreate workers after pulling changes to this overlay.

The internal JavaRunExecutor composes suite planning, archive creation, engine communication,
report evaluation and request-level result aggregation. Evaluation and compilation share
request-wide budgets. Communications use the remaining portion of the 60-second Run deadline.
Compiler diagnostics are omitted from the aggregate response because they can include host
test source. The default Spring configuration connects this executor to POST /internal/v1/runs.
By default, the HTTP adapter admits two active Runs and ten pending Runs with the pinned 1.1.0 image and fresh `COUNT=2` configuration. If an infrastructure
failure leaves execution or cleanup unconfirmed, admission stays quarantined.
Recover locally by stopping or restarting Judge0 workers, verifying readiness,
and restarting Runner.

From the runner repository root, verify report transfer and the Java executor with JDK 21:

```sh
CODELOBBY_TEST_LOCAL_JUDGE0=true CODELOBBY_TEST_JUDGE0_REPORTS=true \
  ./mvnw -B -ntp -Dtest=Judge0ProjectIntegrationTests,JavaRunExecutorIntegrationTests test
```

Set `CODELOBBY_TEST_JUDGE0_URL` for a different local port. The report remains untrusted data
until the runner validates it against the declared cases.


## Private configuration

`judge0.conf` and temporary variants are ignored by Git. The file is mounted read-only into Judge0. Compose reads it through `--env-file` for PostgreSQL and Redis credentials. Never print resolved Compose configuration, commit secrets, or put the runner's internal key in this file.

Do not replace an existing database password independently of its PostgreSQL volume. Password rotation requires updating the database role and configuration consistently.

## Execution profiles and budgets

| Language | Judge0 ID | Toolchain |
| --- | --- | --- |
| Java | `1001` | Temurin 21.0.13 |
| Python | `1002` | CPython 3.12.16 |
| C++17 | `54` | GCC 9.2.0 with explicit `-std=c++17` |
| Multi-file project | `89` | Trusted compile/run scripts |

The C++ profile selects C++17 after caller options. Execution adapters must construct fixed trusted commands and must not accept arbitrary participant compiler flags.

For multi-file Java, trusted scripts invoke `/usr/local/bin/java-with-budget.sh compiler ...` for the runner-owned compilation driver and `/usr/local/bin/java-with-budget.sh java ...` for runtime. The `javac` mode remains available for direct compiler calls. Multi-file C++ commands explicitly select `-std=c++17`. No Maven or pip downloads take place in submitted programs.

The Isolate wrapper passes the effective memory budget to the Java launcher. The launcher reserves 48 MiB for runtime JVM overhead and 64 MiB for the compiler, then derives the maximum heap from the remaining budget. The runtime young generation is capped at 4 MiB to leave room for large arrays. The minimum heap is 8 MiB. A very small total request limit can still prevent JVM startup, and the aggregate cgroup memory limit remains authoritative.

A compilation receives 10 CPU seconds, 20 wall seconds and 512000000 bytes. Execution retains its own request limits. Compiler cgroup accounting is reset before execution so compiler peaks and OOM events do not contaminate runtime measurements. The Java Run executor supplies the remaining aggregate compilation budget across distinct suite builds.

## Isolation and local extensions

The image inherits an unofficial Judge0 fork and an older Debian base. Both sandbox services are privileged and the worker uses the Docker host cgroup namespace. Keep the API local. Production deployment and other hosts require separate review and verification.

The modern adapters are baked into the image from `image/`. They provide:

- Suppression of the upstream environment dump while retaining ordinary logs.
- A shared 64 MiB / 4096-inode tmpfs quota across `/box`, `/tmp` and `/dev/shm`.
- Strict fixed-path workspace preparation and cleanup with ordinary unmounts.
- Confirmed runtime memory-limit reporting based on Isolate metadata and new cgroup OOM-kill events. Exit 137 and participant text alone are not evidence.
- Separate compilation budgets and fresh runtime accounting.
- Reuse of Isolate box IDs after verified cleanup within one worker container.

The working image sources also include a sandbox reservation pool. An exclusive process lock and an occupied marker reserve an ID before Isolate initialization. The reservation stays held through compilation, execution and cleanup. Release requires confirmation that the workspace, Isolate directory and per-box cgroup are absent. Failed cleanup or a crashed process leaves the ID quarantined. Worker restarts preserve occupied markers. The worker state volume preserves markers across container recreation. The adapter also checks for stale filesystem and cgroup artifacts before initializing a reserved ID. Operators must investigate quarantined slots before recovery, rather than delete reservation files while jobs may be running.

Local lifecycle verification on 2026-11-19 used a separate derived image, Compose project and API port on Apple Silicon. Java 21, Python 3.12 and C++17 programs passed. Compilation failure and execution timeout released their reserved ID after verified cleanup, followed by a successful Java execution. Killing the test worker during an active Python sandbox retained slot 0 across restart, while a subsequent successful Python execution used and released slot 1. In that reservation-only probe, the interrupted submission remained `Processing` in Judge0. The restart-recovery probe below covers the additional reconciliation adapter. Test-only stale workspace and cgroup resources were removed after confirming that no processes remained. The quarantine marker was preserved and the test services were stopped. These checks do not establish concurrent execution support or portability to all hosts.

The working sources also journal each submission before Judge0 can mark it `Processing`. A private, atomic journal links the database ID and token to the reserved sandbox. Recovery runs only during worker boot, before consuming jobs. It requires exclusive job and sandbox locks, matching database and reservation identities, an unpopulated sandbox cgroup including its descendants, and verified workspace/Isolate/cgroup cleanup. Only then does it store Judge0 `Internal Error` (13) with a generic message, clear diagnostics and measurements, release the reservation and remove the pending journal. It never treats HTTP timeout or journal age as proof of termination and does not kill processes during recovery. Unconfirmed cleanup, live locks, identity mismatch or a failed database write prevent worker boot and retain the pending journal for investigation or a later restart.

The working Compose configuration mounts the named `judge0-worker-state` volume at `/var/lib/codelobby-worker-state` in the worker only. Its `jobs` and `slots` directories preserve journals and reservations across worker restart and container recreation. The root-owned parent permits traversal without worker writes, and each worker-owned child requires mode `0700`. Startup initializes missing directories but rejects unexpected ownership, permissions or symlinks without recursively changing existing journal files. Recovery after recreation also handles an empty host cgroup whose ephemeral Isolate directory is already gone, using ordinary `rmdir` after checking the full cgroup is unpopulated. Missing journals, deleted volumes, multiple worker containers and shared distributed ownership are outside this stage. Keep the same Compose project name, state volume and database when recreating the worker. Normal `stop` and `down` preserve named volumes. `down -v` removes the worker state and database volumes and is not a recovery operation. Drain work before upgrading from an earlier experimental image that stored journals under `/run`, since those old files are not automatically migrated. Unknown historical `Processing` submissions are not automatically changed. Fresh private configuration uses `COUNT=2`. Existing configuration is not overwritten, so update an existing `COUNT=1` setting to `2` to match the runner default, or explicitly keep both execution limits at `1`. Do not enable concurrent worker container replicas.

A separate local recovery image was verified on 2026-11-19 on Apple Silicon. All three language smoke checks passed, as did compilation failure, execution timeout and subsequent execution. After SIGKILL during an active Python sandbox, worker restart changed the interrupted submission from `Processing` to `Internal Error` only after confirmed cleanup. Slot 0 was released and reused successfully. A second restart preserved the recovered status and finish timestamp. Source tests also cover blocked recovery for populated cgroups, active locks, wrong owners, malformed journals and database/cleanup failures. The test environment was stopped afterwards. During that earlier probe, the published image and standard startup configuration were unchanged.

A separate persistent-state image was verified on 2026-11-19 on Apple Silicon. The worker was killed during an active Python sandbox and its container was deleted. A read-only check of the surviving named volume confirmed the pending journal and occupied reservation before a replacement worker existed. The replacement had a different container ID and mounted the same state volume. It reconciled the interrupted submission to `Internal Error`, released slot 0 after verified cleanup, and successfully executed subsequent Python and Java programs using that slot. Linux initialization tests cover fresh volume ownership, repeat initialization without data loss, unsafe permissions, wrong owners and symlinks.

The pinned public image 1.1.0 includes these adapters. Fresh startup uses `COUNT=2` and the runner defaults to `RUNNER_EXECUTION_MAX_ACTIVE=2`. Preserve existing private secrets while updating older execution settings. Version 1.0.0 remains available, but does not support this two-process configuration. The current stage supports one or two active operations, with bounded pending capacity, within one runner and one worker container. More processes, replicas and multi-instance queue ownership are outside this verification.

Local concurrency verification on 2026-11-19 used a separate two-process image, API port and runner. Two HTTP Run requests were observed in distinct populated sandbox cgroups at the same time. Accepted and Wrong Answer results remained associated with the correct requests and outputs. One pending request started automatically and an excess request received HTTP 429. A compilation error did not cancel another execution. Killing and deleting the worker container during two active Python executions preserved both journals on the state volume. The replacement worker reconciled both submissions to Internal Error, released both slots and successfully executed subsequent programs. The worker boot wrapper now runs recovery once in a dedicated Rails runner before starting the scheduler and Resque processes. Runtime Rails processes do not repeat recovery. A regression test also covers the initial shared-directory creation race found during the first concurrent trial. These are local functional checks, not capacity or cross-platform certification.

Network access is disabled. Per-file and output limits remain enforced separately by the engine. tmpfs pages can count toward execution memory. A caught resource error can still result in a successful process, so the runner must evaluate the host test report rather than infer a verdict from output text. Host tests sharing a participant sandbox are not tamper-proof grading.

Use `base64_encoded=true` when retrieving Judge0 diagnostics to handle arbitrary diagnostic bytes. HTTP polling timeouts do not prove program termination.

## Verification and image sources

The original 1.0.0 packaged image was checked on Apple Silicon with Docker Desktop. The recorded local verification covered 68 engine submissions and 35 adapter tests, including language features, memory allocation, compiler/runtime budgets, network and secret isolation, output/process/storage limits, cleanup, multi-file tasks, recovery and restart. This does not claim verification on every Mac or Linux host. Anonymous registry download was verified with an empty Docker authentication configuration. Repository startup and restart each passed all three language checks. Local layer caches were available during this verification, so a completely cold installation on a second computer remains untested.

Release 1.1.0 was published and checked on 2026-11-19. Its pinned digest was fetched using an empty Docker authentication configuration. Local layer caches were present, so this is not a cold-installation test. Fresh startup through repository `start.sh` generated private configuration with two worker processes. Java 21, Python 3.12 and C++17 smoke checks passed. A separately started runner using its default two active slots passed the concurrent HTTP Run, result association, pending admission and overflow checks against this published digest. This release was verified locally on Apple Silicon with Docker Desktop, not certified across Linux and Windows hosts.

Run source-level adapter checks from this directory:

```sh
ruby tests/confirmed_oom_test.rb "$PWD/image/report-confirmed-oom.rb"
ruby tests/worker_recovery_test.rb "$PWD/image/worker-recovery.rb"
ruby tests/sandbox_slots_test.rb "$PWD/image/sandbox-slots.rb"
ruby tests/bounded_workspace_test.rb "$PWD/image/bounded-workspace.rb"
ruby tests/compilation_budget_test.rb "$PWD/image/compilation-budget.rb"
ruby tests/suite_configuration_test.rb "$PWD/image/suite-report.rb"
python3 tests/startup_test.py
python3 tests/isolate_phase_test.py
python3 tests/java_budget_test.py
```

The release-verification and worker-state initialization fixture tests require an isolated Linux container running as root: `python3 tests/workspace_release_test.py` and `python3 tests/worker_state_test.py`. It checks filesystem and cgroup path rejection without creating real mounts or cgroups. Pool tests also cover multiple processes reserving distinct IDs and stale markers after process exit. These source-level checks do not replace real Judge0 lifecycle tests of a rebuilt image.

`Dockerfile`, `.dockerignore`, `image/` and `vendor/checksums.txt` record how the image is built. Optional rebuilding:

```sh
./fetch-toolchains.sh
docker build --platform linux/amd64 --network none -t codelobby-judge0:local-build .
```

The fetch step downloads the pinned official Java archive and Python source, then verifies their SHA-256 checksums. The actual Docker build runs without network access. Downloaded build inputs and image archives are ignored by Git. Rebuilding is for image maintenance, not normal startup. Publishing a replacement version requires separate authorization and verification.

## Legacy environment

`compose.yaml` is the active modern recipe. `compose.mac.yaml` is an optional compatibility override and does not select another image.

`compose.legacy.yaml` preserves the previous image and project `codelobby-runner-judge0` on port 2358. Root-level adapter scripts are retained unchanged because the existing legacy containers bind-mount them. The modern recipe does not load these root-level scripts. Keeping this recipe does not start a second environment automatically.

To manage the retained legacy environment explicitly:

```sh
docker compose --env-file judge0.conf -f compose.legacy.yaml ps
docker compose --env-file judge0.conf -f compose.legacy.yaml stop
```

Its original image has Java 13 and Python 3.8 and does not meet the modern contract baseline. Existing legacy containers and their database are preserved during the modern migration.

## Experimental compiled Java artifact capture

This is a local, opt-in prototype used by Java Submit benchmarks. It captures
classes, supports class-only replay and supplies an explicit Java session for
all benchmark rounds. It is disabled by default. The runner can register the real
Submit service and HTTP routes with `RUNNER_SUBMIT_ENABLED=true` and
`RUNNER_JUDGE0_ARTIFACT_KEY` set to the overlay's `CODELOBBY_ARTIFACT_KEY`.
The published image and default startup configuration remain unchanged.

After starting the normal local environment, enable the extra worker overlay from
this directory, with no active executions. Generate a separate temporary private
transport key in this terminal and keep it out of Git and browser payloads:

```sh
export CODELOBBY_ARTIFACT_KEY="$(openssl rand -hex 32)"
docker compose --env-file judge0.conf -f compose.yaml -f compose.reports.yaml \
  -f compose.artifacts.experimental.yaml up -d --wait server workers
```

The optional initializer captures after successful project compilation and before
participant execution. The helper requires the compiler cgroup to be empty. It
pins directories by file descriptors, rejects symlinks and hardlinked files, and
reads only bounded regular class files from .codelobby/classes on the same device.
It checks the class-file magic header, not full JVM validity. Paths must be ASCII.
Limits are 256 files, 512 visited entries, 240 path bytes, 256 KiB per class,
1 MiB combined class bytes and 1,500,000 serialized snapshot bytes. Snapshots are
versioned JSON with sorted relative class paths and Base64 bytes. SHA-256 covers
the serialized snapshot. Runtime writes cannot change the captured copy.

The worker stores snapshots under its submission token in a separate private Redis
namespace, codelobby:java-artifacts:prototype:v1:, for 300 seconds. At most 32
records are retained. Older records may be evicted, so this is not yet an artifact
store suitable for every allowed multi-suite request or deployment load. Failures,
missing classes and size breaches do not replace the original solution verdict.
The experimental server overlay adds private GET/DELETE endpoints at
/codelobby/v1/java-artifacts/{token}. Both require X-CodeLobby-Artifact-Key,
matching CODELOBBY_ARTIFACT_KEY. Missing or invalid configuration fails closed.
Responses use no-store. Only the runner gets the key, execution tokens and classes.
Do not expose this listener publicly. Use protected service transport when moving
beyond the loopback-only local configuration.

Run the controlled real-engine probes from the repository root:

```sh
CODELOBBY_TEST_JAVA_ARTIFACTS=true \
  python3 dev/judge0/tests/java_artifacts_integration_test.py
```

The probes check pre-runtime snapshots despite runtime class mutation, compilation
failure, oversized artifacts, link handling and Redis capacity/expiry policy.
The Linux collector tests separately require rejection of accessible symbolic
links, hardlinks, FIFO, invalid names and oversized trees. In the pinned engine,
a compile-created symbolic link can already be absent before capture and runtime.
The integration probe then requires a valid snapshot without that entry and
confirms the link is absent during execution.

Disable the experiment by recreating the worker with only the normal files,
with no active work, then stop the local environment if it is no longer needed:

```sh
docker compose --env-file judge0.conf -f compose.yaml -f compose.reports.yaml \
  up -d --wait server workers
./stop.sh
unset CODELOBBY_ARTIFACT_KEY
```

### Class-only replay in fresh sandboxes

The runner's JavaCompiledArtifacts reader verifies the private snapshot digest,
strict UTF-8/JSON structure, unique safe paths, class magic and capture-compatible
size/count bounds. It rejects unknown fields, duplicate JSON keys and noncanonical
Base64. SHA-256 verifies integrity only, not authorization or request ownership.
The Java session binds private retrieval to tokens returned by its own evaluation,
and checks the selected request fingerprint before the benchmark is run.

JavaCompiledSuiteArchive writes only .codelobby/classes entries and a generated
run script. It includes no compile/compile.sh script, Java source or compiler driver.
Judge0 therefore skips its compiler phase and extracts the same class bytes into
each fresh sandbox. The trusted phase adapter reports zero compiler usage only
when no compiler was invoked. An actual compiler with missing measurements is
never treated as zero. Replay does not capture another copy into the artifact cache.

With the experimental overlay already running, verify from the repository root:

```sh
CODELOBBY_TEST_JAVA_ARTIFACTS=true \
  ./mvnw -B -ntp -Dtest=JavaCompiledSuiteArchiveIntegrationTests test
```

For each public/hidden suite the test compiles once, captures the original classes,
and replays the class-only archive twice. The test program modifies a class and
writes local state during every run. Each replay must start from the original
classes and lack the previous runtime state. Compiler usage must be zero and
hidden case diagnostics must remain redacted. Snapshot retrieval in this test uses
the maintainer's local Docker CLI only and is not production runner transport.

### Full compiled session and benchmark

Judge0CompiledJavaSubmission owns immutable copies of the selected request and
replay archives. Initial suite outcomes carry private execution tokens to an
observer. Successful captures are validated and copied immediately, then deleted
from Redis, reducing retention across larger suite sets. The session's aggregate
class-data ceiling is 8 MiB. Captures are not fetched again during benchmark rounds.
If a capture cannot be used, benchmarking fails without altering evaluation.
The caller holds admission throughout, closes the session, and handles uncertain
termination before releasing or quarantining capacity. Closing the Java session
releases class archive references and never claims to terminate an active sandbox.

With the overlay running and the same CODELOBBY_ARTIFACT_KEY in this terminal,
run from the repository root:

```sh
CODELOBBY_TEST_COMPILED_SESSION=true \
  ./mvnw -B -ntp -Dtest=Judge0CompiledJavaSubmissionIntegrationTests test
```

This verifies accepted evaluation with two warmups and five measured rounds across
public/hidden suites, wrong-answer benchmark skipping, and controlled artifact
unavailability. All application retrieval uses authenticated HTTP, not Docker CLI.
Remote records are deleted and hidden case diagnostics remain redacted.

### Verified cleanup and final asynchronous results

The optional worker also publishes a cleanup receipt after a previously held
bounded-workspace lease has been released and verified. Failed cleanup, a retained
lease or a job that never held a lease cannot publish success. Receipts use a
separate bounded Redis namespace, the same 300-second retention and 32-record cap.
The private GET /codelobby/v1/java-artifacts/{token}/cleanup route returns PENDING
or CLEANED_UP. It requires the artifact key and no response contains source paths.
Deleting a token also removes its receipt and both index entries.

The session waits for this receipt after each initial suite and replay. This is
required even after compilation errors because Judge0 can persist their terminal
status before cleanup completes. Missing or invalid confirmation throws an
infrastructure failure. The owner must quarantine uncertain execution capacity.
Interrupted waits propagate. No endpoint claims that an HTTP timeout killed a job.

JavaSubmitEvaluationExecutor assembles the final EvaluationResult after evaluation,
benchmark and session close. The initial solution verdict and measurements remain
separate from benchmark results. A verified benchmark failure preserves ACCEPTED.
Solution failures skip benchmarking. SubmitConfiguration connects this executor
to AsyncSubmitCoordinator and the shared execution queue when explicitly enabled.

With the experimental overlay running and CODELOBBY_ARTIFACT_KEY available, run
from the repository root:

```sh
CODELOBBY_TEST_SUBMIT_EXECUTOR=true \
  ./mvnw -B -ntp -Dtest=JavaSubmitEvaluationExecutorIntegrationTests test
```

This runs real asynchronous jobs for accepted, wrong-answer and compilation-error
solutions. It also injects missing artifacts after verified cleanup, and missing
cleanup confirmation. The former produces a FINISHED/ACCEPTED result with failed
benchmark. The latter produces FAILED with no result and quarantines the runner
queue. Identical submissions are not executed twice. The published image is unchanged.

The optional Submit HTTP routes are documented in the root README. To test them
against this overlay, run the following from the repository root with the artifact
key exported:

```sh
CODELOBBY_TEST_SUBMIT_HTTP=true \
  ./mvnw -B -ntp -Dtest=SubmitEndpointIntegrationTests test
```

Image packaging and further real expiry/concurrency verification remain separate work.


## Optional real Run and Submit concurrency probe

Start the experimental artifact overlay above in an otherwise idle local Compose
environment. The mounted private Judge0 configuration must contain `COUNT=2`.
Existing `judge0.conf` files are preserved by startup and may still contain
`COUNT=1`, which serializes engine work even if the runner admits two operations.
The probe checks this setting before accepting work. Keep `CODELOBBY_ARTIFACT_KEY`
exported with the same value as the server overlay. From the repository root:

```sh
CODELOBBY_TEST_MIXED_HTTP=true \
  ./mvnw -B -ntp -Dtest=RunSubmitConcurrencyIntegrationTests test
```

This test starts a temporary real runner with two active slots and one pending
slot. The pending limit is specific to this test and does not change deployment
settings. It starts two different Submit jobs, queues a Run, checks HTTP 429 for
an excess job and verifies that duplicate Submit requests do not consume more
capacity. Read-only Docker observers check populated sandbox cgroups during the
two Submits and later during Run plus Submit. Assertions verify separate verdicts,
public outputs, redacted hidden feedback, successful accepted benchmarking, replay,
cleanup of cgroups/journals/reservations/workspace mounts and a subsequent Run.
The observer requires Docker CLI access and the Compose project used above.
Do not run this global worker inspection alongside unrelated sandbox work.
