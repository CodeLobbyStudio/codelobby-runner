# Local Judge0 development environment

Owned by codelobby-runner. This is an experimental local environment, not a production-approved deployment. The Mac configuration uses an unofficial image pinned by digest. Its complete provenance and security have not been audited.

## Files

- `compose.yaml`: Judge0 API, worker, PostgreSQL and Redis.
- `compose.mac.yaml`: Apple Silicon override using AMD64 emulation and the worker host cgroup namespace.
- `judge0.conf.example`: local configuration template without secrets.
- `start-without-env-log.sh`: checksum-guarded startup adapter suppressing the image's exported-environment dump. Also removes group/other write access from the worker's `/var/local` ancestors.
- `report-confirmed-oom.rb`: recognizes confirmed runtime OOM from Isolate metadata and a new sandbox-specific OOM-kill event.
- `bounded-workspace.rb`: prepares the limited workspace before source/extracted files are written and releases it before Isolate cleanup.
- `workspace-helper.sh`: root-only, fixed-path tmpfs mounting/unmounting with strict numeric argument checks.
- `isolate-with-workspace.sh`: preserves Isolate options and maps `/dev/shm` into the same bounded filesystem for every `--run`, including compilation and extraction.
- `tests/`: focused adapter regression tests.

## Requirements and limitations

Upstream Judge0 CE 1.13.1 documents Linux, Docker, Compose and cgroups v1, recommending Ubuntu 22.04: https://github.com/judge0/judge0/releases/tag/v1.13.1.

The experimental Mac override runs Isolate 2.2.1 with cgroups v2 on Docker Desktop. Both Judge0 containers are privileged and the worker uses the Docker VM's host cgroup namespace. Use a dedicated local environment and keep the API bound to localhost. These broad privileges must be reviewed separately before any shared deployment.

The retained PostgreSQL/Redis versions reproduce the existing prototype, not a claim that they are current. The image provides Python 3.8.1, GCC 9.2.0 and OpenJDK 13.0.1. This does not meet the runner contract's Java 21/Python 3.12 target. Java compilation still emits thread warnings. API startup can warn about cgroup controller delegation even when the checked executions succeed. No production readiness is claimed.

## Private configuration

Run from this directory. Copy `judge0.conf.example` to `judge0.conf` only for a new environment. Set three independently generated secrets: `POSTGRES_PASSWORD`, `REDIS_PASSWORD` and `SECRET_KEY_BASE`. Generate them locally, use hexadecimal values and restrict the file to owner read/write. `judge0.conf` is ignored by Git. Never overwrite an existing private configuration or print resolved Compose output.

Judge0 loads the file through a read-only mount. Compose also needs `--env-file` to supply the backing services with their individual passwords. An existing PostgreSQL role password must be changed in the database itself when rotating it. No runner internal key or other application secret belongs here.

## Start and stop the Mac environment

```sh
docker compose --env-file judge0.conf -p codelobby-runner-judge0 -f compose.yaml -f compose.mac.yaml config --quiet
docker compose --env-file judge0.conf -p codelobby-runner-judge0 -f compose.yaml -f compose.mac.yaml up -d db redis
```

Check PostgreSQL and authenticated Redis readiness before starting Judge0:

```sh
docker compose --env-file judge0.conf -p codelobby-runner-judge0 -f compose.yaml -f compose.mac.yaml exec -T db pg_isready -U judge0 -d judge0
docker compose --env-file judge0.conf -p codelobby-runner-judge0 -f compose.yaml -f compose.mac.yaml exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
docker compose --env-file judge0.conf -p codelobby-runner-judge0 -f compose.yaml -f compose.mac.yaml up -d server workers
docker compose --env-file judge0.conf -p codelobby-runner-judge0 -f compose.yaml -f compose.mac.yaml ps
```

Check `/about` at `http://127.0.0.1:2358/about`. API availability does not prove that submissions can execute. A representative compilation/execution check is also required.

To stop without deleting data:

```sh
docker compose --env-file judge0.conf -p codelobby-runner-judge0 -f compose.yaml -f compose.mac.yaml stop
```

Do not use `down -v` when preserving database data. The separate older `codelobby-judge0` project is not managed by these commands. Both projects cannot publish localhost port 2358 simultaneously.

## Per-submission writable storage (Mac override only)

`WORKSPACE_SIZE_MIB=64` and `WORKSPACE_INODES=4096` provide one tmpfs filesystem shared by a submission's `/box`, `/tmp` and `/dev/shm`. Source, extracted files, compilation output artifacts and temporary files in those directories consume the same budget. The count includes directories and infrastructure files, so fewer than 4096 participant-created files fit. The budget is shared across compilation and execution for that submission, not reset for each phase.

Allowed settings are 4..256 MiB and 128..16384 inodes. Defaults apply when settings are absent. Invalid settings stop worker initialization or submission preparation rather than silently disabling protection. The adapter only supports the pinned image and Isolate box IDs 0..999. The existing provider's IDs beyond that range must be addressed before long-term use.

The helper accepts no arbitrary paths. It requires root-owned, non-writable ancestors, rejects symlinks, checks mounted filesystem identity, and uses ordinary unmount rather than lazy unmount. There is no recursive root deletion in the helper. Failed preparation is rolled back. Failed release prevents the original cleanup from deleting through an active mount. Preparation/release failures produce an infrastructure error, not a participant verdict.

The persistent bind mounts are released before Isolate removes its directories. No workspace is reused for the next submission. A worker crash can leave mounts in a running container: stop/recreate that worker before resuming, inspect for leftovers and never manually remove mounted directories. Recreating the container discards these temporary filesystems. This does not provide a durable recovery coordinator or automatic orphan reclamation.

**Memory impact:** tmpfs consumes RAM. Pages written by the participant can be charged to its memory cgroup. It may hit the memory limit before filling its storage allocation. Recorded memory therefore includes filesystem activity and must not be presented as isolated Java-heap/function memory. Worker-wide aggregate capacity/concurrency still needs separate sizing. This is not an external persistent-disk quota.

Other checked locations (`/var/tmp`, `/run`, `/home` and `/usr/local`) are absent or not writable in the sandbox. The ordinary `/dev/shm` mount was found writable and is explicitly replaced for each run.

The existing per-file `max_file_size` still applies. Stdout/stderr and Isolate metadata outside `/box` and `/tmp` are not charged to this tmpfs. Output sizes remain bounded separately by the engine's per-file limit. A caught ENOSPC error can produce a successful program result: the storage restriction is enforced by the filesystem, not inferred from participant text. There is no new runner API verdict in this change.

## OOM and startup logging adapters

The Mac startup adapter checks original startup-script hashes and suppresses only the `tee` environment dump. Normal service logs remain enabled. The OOM adapter checks the original job source hash, requires `cg-oom-killed:1` and an increase of the runtime sandbox's OOM-kill counter, and returns Judge0 status 12 with `Memory limit exceeded`. Exit 137, stderr text and peak-memory values alone are not proof. Timeout/internal failure classification is preserved. These adapters do not prove complete image confidentiality or cover all compilation OOM cases.

## Local verification

Verified in this Docker Desktop environment:

- Python, C++ and Java simple programs succeed after workspace limiting, subject to the version limitations above.
- `/box`, `/tmp` and `/dev/shm` report the same filesystem with 67,108,864 bytes and 4096 inodes.
- Multiple 512 KiB files split between `/box`, `/tmp` and `/dev/shm` reach ENOSPC at the shared 64 MiB capacity.
- Empty-file creation reaches ENOSPC after 4087 files while data blocks remain available.
- A valid program succeeds after both resource-exhaustion attempts.
- Success, runtime failure, timeout and compilation failure release workspace mounts and directories. A follow-up request cannot see the previous files.
- Earlier bounded checks confirmed CPU/wall timeout, runtime OOM reporting, output/per-file/process limits, network rejection and absence of the checked secret paths/environment variables. These are focused checks, not an isolation audit.

The original API can reject raw compiler diagnostics containing invalid text encodings. Use `base64_encoded=true` for diagnostic retrieval where needed. No API text-decoding change is included here.

Run adapter regression tests from this directory:

```sh
ruby tests/confirmed_oom_test.rb "$PWD/report-confirmed-oom.rb"
ruby tests/bounded_workspace_test.rb "$PWD/bounded-workspace.rb"
bash -n isolate-with-workspace.sh
bash -n workspace-helper.sh
bash -n start-without-env-log.sh
```

All adapters are local provider extensions. The runner API contract and application implementation are unchanged.
