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

The bootstrap provides a Spring Boot service, an HTTP health endpoint and minimal
internal input/result records. It does not compile or execute participant code.
There are no Run or Submit endpoints yet. The execution API contract is tracked
in issue #5; the records in `model` do not define that contract.

## Proposed internal API contract

The multi-file evaluation API proposal is documented in
[docs/api/CONTRACT.md](docs/api/CONTRACT.md), with an
[OpenAPI specification](docs/api/openapi.yaml) and
[JSON examples](docs/api/examples/). It covers host-defined tasks for Java, C++
and Python. This is a contract for review, not implemented execution endpoints;
the bootstrap behavior described below is unchanged.

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
credentials are needed for this bootstrap. Only the health Actuator endpoint is
exposed, and health details are hidden.

## Project layout

Application code lives in `src/main/java/pl/codelobby/runner`, configuration in
`src/main/resources`, and tests in `src/test/java`. See
[docs/STRUKTURA.md](docs/STRUKTURA.md) for package responsibilities.
