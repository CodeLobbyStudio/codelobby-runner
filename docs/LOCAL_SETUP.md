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
Existing passwords and database volumes are preserved. The report/compiler adapters
required by Java Run are included automatically. Credentials are never printed.

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

## 3. Build and start the runner

From the repository root:

```sh
./mvnw -B -ntp clean verify
export CODELOBBY_INTERNAL_KEY="$(openssl rand -hex 32)"
export JUDGE0_URL="http://127.0.0.1:2359"
./mvnw spring-boot:run
```

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

After pulling changes to mounted adapters, wait for active work to finish and recreate
server/workers from `dev/judge0`. For the default private configuration:

```sh
docker compose --env-file judge0.conf -f compose.yaml -f compose.reports.yaml \
  up -d --force-recreate --wait server workers
```

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

The normal setup above enables Java Run. Java Submit also needs the experimental
artifact overlay and a separate runner artifact key. Follow
[the optional Submit HTTP instructions](../README.md#optional-internal-java-submit-http-api)
and [the overlay setup](../dev/judge0/README.md#experimental-compiled-java-artifact-capture).
The published image and default startup remain unchanged. Do not enable Submit
against an engine without that overlay. The backend uses the same internal key
for POST `/internal/v1/evaluation-jobs` and GET of the returned `Location`.
Neither internal nor artifact keys belong in browser requests.

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
