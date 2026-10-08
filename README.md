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

> Early-stage project. Execution architecture and local run instructions are still to be defined.
