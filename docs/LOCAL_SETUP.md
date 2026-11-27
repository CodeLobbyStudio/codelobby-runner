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
