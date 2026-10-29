# Local Judge0 development environment

Owned by codelobby-runner. This is an experimental local environment, not a production-approved deployment. The Mac configuration uses an unofficial image pinned by digest. Its complete provenance and security have not been audited.

## Files

- `compose.yaml`: Judge0 API, worker, PostgreSQL and Redis.
- `compose.mac.yaml`: experimental Apple Silicon override using the pinned AMD64 image.
- `judge0.conf.example`: configuration template without secrets.
- `start-without-env-log.sh`: checksum-guarded startup adapter suppressing the exported-environment dump.

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

## Startup logging

The pinned unofficial image prints exported variables through `tee` in both startup scripts. The startup adapter checks the original script checksum and suppresses that output while preserving the environment file used by Judge0. Normal service logs remain enabled. This is not a complete image audit.

## Local verification

API startup, authenticated PostgreSQL/Redis access and simple Python, C++ and Java executions have been checked locally. Language-version and deployment limitations above remain unresolved.

```sh
bash -n start-without-env-log.sh
```
