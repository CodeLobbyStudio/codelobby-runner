# Local Judge0 development environment

Owned by codelobby-runner. The default Compose configuration uses the prebuilt image `ghcr.io/ptrthdr/codelobby-judge0:1.0.0` on the maintainer's personal GitHub Container Registry account. The image is independent of the organization repository. Its immutable manifest digest is pinned in `compose.yaml` and `start.sh`.

The image provides Java 21, Python 3.12 and C++17, including the local workspace, OOM and compilation-budget adapters. Normal startup downloads it automatically. No image archive or local image build is required.

This is a local development and MVP demonstration environment. The Java Run endpoint uses
this environment through the runner's HTTP client. Start the runner separately with its
internal key. Submit and C++/Python Run are not implemented.

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
By default, the HTTP adapter admits one active Run and ten pending Runs. The working sources support `RUNNER_EXECUTION_MAX_ACTIVE=2` with the rebuilt `COUNT=2` worker variant. If an infrastructure
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

The working Compose configuration mounts the named `judge0-worker-state` volume at `/var/lib/codelobby-worker-state` in the worker only. Its `jobs` and `slots` directories preserve journals and reservations across worker restart and container recreation. The root-owned parent permits traversal without worker writes, and each worker-owned child requires mode `0700`. Startup initializes missing directories but rejects unexpected ownership, permissions or symlinks without recursively changing existing journal files. Recovery after recreation also handles an empty host cgroup whose ephemeral Isolate directory is already gone, using ordinary `rmdir` after checking the full cgroup is unpopulated. Missing journals, deleted volumes, multiple worker containers and shared distributed ownership are outside this stage. Keep the same Compose project name, state volume and database when recreating the worker. Normal `stop` and `down` preserve named volumes. `down -v` removes the worker state and database volumes and is not a recovery operation. Drain work before upgrading from an earlier experimental image that stored journals under `/run`, since those old files are not automatically migrated. Unknown historical `Processing` submissions are not automatically changed. Keep `COUNT=1` by default. The working sources also accept `COUNT=2` for the rebuilt two-process variant. Do not enable concurrent worker container replicas.

A separate local recovery image was verified on 2026-11-19 on Apple Silicon. All three language smoke checks passed, as did compilation failure, execution timeout and subsequent execution. After SIGKILL during an active Python sandbox, worker restart changed the interrupted submission from `Processing` to `Internal Error` only after confirmed cleanup. Slot 0 was released and reused successfully. A second restart preserved the recovered status and finish timestamp. Source tests also cover blocked recovery for populated cgroups, active locks, wrong owners, malformed journals and database/cleanup failures. The test environment was stopped afterwards. The published image and standard startup configuration were not changed.

A separate persistent-state image was verified on 2026-11-19 on Apple Silicon. The worker was killed during an active Python sandbox and its container was deleted. A read-only check of the surviving named volume confirmed the pending journal and occupied reservation before a replacement worker existed. The replacement had a different container ID and mounted the same state volume. It reconciled the interrupted submission to `Internal Error`, released slot 0 after verified cleanup, and successfully executed subsequent Python and Java programs using that slot. Linux initialization tests cover fresh volume ownership, repeat initialization without data loss, unsafe permissions, wrong owners and symlinks.

These source changes are not activated by normal startup of the currently pinned published image. They require a separately built and verified replacement image. Serial execution remains the default for compatibility with the published image. To test the rebuilt two-process variant, set `COUNT=2` in the private Judge0 configuration and `RUNNER_EXECUTION_MAX_ACTIVE=2` in the runner. The current stage supports one or two active operations, with bounded pending capacity, within one runner and one worker container. More processes, replicas and multi-instance queue ownership are outside this verification.

Local concurrency verification on 2026-11-19 used a separate two-process image, API port and runner. Two HTTP Run requests were observed in distinct populated sandbox cgroups at the same time. Accepted and Wrong Answer results remained associated with the correct requests and outputs. One pending request started automatically and an excess request received HTTP 429. A compilation error did not cancel another execution. Killing and deleting the worker container during two active Python executions preserved both journals on the state volume. The replacement worker reconciled both submissions to Internal Error, released both slots and successfully executed subsequent programs. The worker boot wrapper now runs recovery once in a dedicated Rails runner before starting the scheduler and Resque processes. Runtime Rails processes do not repeat recovery. A regression test also covers the initial shared-directory creation race found during the first concurrent trial. These are local functional checks, not capacity or cross-platform certification.

Network access is disabled. Per-file and output limits remain enforced separately by the engine. tmpfs pages can count toward execution memory. A caught resource error can still result in a successful process, so the runner must evaluate the host test report rather than infer a verdict from output text. Host tests sharing a participant sandbox are not tamper-proof grading.

Use `base64_encoded=true` when retrieving Judge0 diagnostics to handle arbitrary diagnostic bytes. HTTP polling timeouts do not prove program termination.

## Verification and image sources

The published packaged image was checked on Apple Silicon with Docker Desktop. The recorded local verification covered 68 engine submissions and 35 adapter tests, including language features, memory allocation, compiler/runtime budgets, network and secret isolation, output/process/storage limits, cleanup, multi-file tasks, recovery and restart. This does not claim verification on every Mac or Linux host. Anonymous registry download was verified with an empty Docker authentication configuration. Repository startup and restart each passed all three language checks. Local layer caches were available during this verification, so a completely cold installation on a second computer remains untested.

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
