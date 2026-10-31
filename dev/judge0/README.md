# Local Judge0 development environment

Owned by codelobby-runner. The default Compose configuration uses the prebuilt image `ghcr.io/ptrthdr/codelobby-judge0:1.0.0` on the maintainer's personal GitHub Container Registry account. The image is independent of the organization repository. Its immutable manifest digest is pinned in `compose.yaml` and `start.sh`.

The image provides Java 21, Python 3.12 and C++17, including the local workspace, OOM and compilation-budget adapters. Normal startup downloads it automatically. No image archive or local image build is required.

This is a local development and MVP demonstration environment. The runner's Judge0 client and complete execution flow are separate implementation work. Starting these services does not make the current runner HTTP endpoint execute submissions.

## Start

Install and start Docker Desktop on macOS. The image is amd64 and Apple Silicon uses emulation. Docker needs sufficient storage and memory. The tested Docker VM had approximately 8 GiB RAM. Allow at least 20 GB free Docker storage for image layers and the backing services. The first start requires internet access to download the public images.

From the repository root:

```sh
cd dev/judge0
./start.sh
```

The script downloads the pinned image if missing, verifies its registry manifest digest through Docker, and creates private `judge0.conf` with three independent random secrets if the file is absent. Existing configuration is preserved. PostgreSQL and Redis are pinned by digest. Readiness checks wait for the database, Redis and HTTP API.

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

The C++ profile selects C++17 after caller options. The future runner must construct fixed trusted commands and must not accept arbitrary participant compiler flags.

For multi-file Java, trusted scripts invoke `/usr/local/bin/java-with-budget.sh javac ...` and `/usr/local/bin/java-with-budget.sh java ...`. Multi-file C++ commands explicitly select `-std=c++17`. No Maven or pip downloads take place in submitted programs.

The Isolate wrapper passes the effective memory budget to the Java launcher. The launcher reserves 48 MiB for runtime JVM overhead and 64 MiB for the compiler, then derives the maximum heap from the remaining budget. The runtime young generation is capped at 4 MiB to leave room for large arrays. The minimum heap is 8 MiB. A very small total request limit can still prevent JVM startup, and the aggregate cgroup memory limit remains authoritative.

A compilation receives 10 CPU seconds, 20 wall seconds and 512000000 bytes. Execution retains its own request limits. Compiler cgroup accounting is reset before execution so compiler peaks and OOM events do not contaminate runtime measurements. These are per-engine-submission budgets. Aggregate budgets across multiple distinct runner compilation calls remain a runner responsibility.

## Isolation and local extensions

The image inherits an unofficial Judge0 fork and an older Debian base. Both sandbox services are privileged and the worker uses the Docker host cgroup namespace. Keep the API local. Production deployment and other hosts require separate review and verification.

The modern adapters are baked into the image from `image/`. They provide:

- Suppression of the upstream environment dump while retaining ordinary logs.
- A shared 64 MiB / 4096-inode tmpfs quota across `/box`, `/tmp` and `/dev/shm`.
- Strict fixed-path workspace preparation and cleanup with ordinary unmounts.
- Confirmed runtime memory-limit reporting based on Isolate metadata and new cgroup OOM-kill events. Exit 137 and participant text alone are not evidence.
- Separate compilation budgets and fresh runtime accounting.
- Reuse of Isolate box IDs after cleanup for the supported single worker (`COUNT=1`).

Network access is disabled. Per-file and output limits remain enforced separately by the engine. tmpfs pages can count toward execution memory. A caught resource error can still result in a successful process, so the runner must evaluate the host test report rather than infer a verdict from output text. Host tests sharing a participant sandbox are not tamper-proof grading.

Use `base64_encoded=true` when retrieving Judge0 diagnostics to handle arbitrary diagnostic bytes. HTTP polling timeouts do not prove program termination.

## Verification and image sources

The packaged image was checked on Apple Silicon with Docker Desktop. The recorded local verification covered 68 engine submissions and 35 adapter tests, including language features, memory allocation, compiler/runtime budgets, network and secret isolation, output/process/storage limits, cleanup, multi-file tasks, recovery and restart. This does not claim verification on every Mac or Linux host. Anonymous registry download was verified with an empty Docker authentication configuration. Repository startup and restart each passed all three language checks. Local layer caches were available during this verification, so a completely cold installation on a second computer remains untested.

Run source-level adapter checks from this directory:

```sh
ruby tests/confirmed_oom_test.rb "$PWD/image/report-confirmed-oom.rb"
ruby tests/bounded_workspace_test.rb "$PWD/image/bounded-workspace.rb"
ruby tests/compilation_budget_test.rb "$PWD/image/compilation-budget.rb"
python3 tests/isolate_phase_test.py
python3 tests/java_budget_test.py
```

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
