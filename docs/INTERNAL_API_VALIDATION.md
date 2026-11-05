# Internal Run authentication and validation

This describes the implemented boundary for `POST /internal/v1/runs` and the
coverage for Issue #9. Valid requests currently reach the configured executor.
Without a verified execution implementation, that executor returns execution
unavailable. This is not a claim of complete Run/Submit evaluation support.

## Request processing

1. `InternalApiFilter` authenticates the single `X-CodeLobby-Internal-Key` header
   against `CODELOBBY_INTERNAL_KEY`, bound through `runner.internal-key`. Missing
   configuration rejects all internal requests. Nonempty keys shorter than 32
   characters prevent startup. Known oversized HTTP bodies may be rejected first.
2. `RunController` accepts uncompressed UTF-8 JSON and bounds body reads, including
   requests without Content-Length. It rejects malformed/duplicate JSON, unknown
   properties, missing/null required fields and inappropriate scalar coercion.
3. `RunRequestValidator` checks identifiers, budgets, file paths/roles, permitted
   editable overrides, test-suite references, public visibility, supported file
   extensions and language-specific execution metadata.
4. `RunSubmissionUseCase` calls the executor only after validation succeeds.
5. `RunErrorHandler` returns generic contract errors without raw request data or
   exception details. Responses use `Cache-Control: no-store`.

## Contract limits

| Item | Implemented ceiling |
| --- | --- |
| HTTP request body | 2000000 bytes, including originals and overrides |
| Each source or test file | 131072 UTF-8 bytes |
| Effective project after editable overrides | 1048576 UTF-8 bytes |
| Task files / submission overrides | 50 each |
| Suites / total declared cases | 50 each |
| Relative path | 240 characters |
| CPU budget | 1–2000 ms |
| Wall-clock budget | 1–5000 ms and at least the CPU budget |
| Memory budget | 16000000–128000000 bytes |

Structural file errors return `400 INVALID_SOURCE_FILES`, invalid metadata or
test configuration returns `400 INVALID_REQUEST`, and exceeded size/count limits
return `413 PAYLOAD_TOO_LARGE`. Authentication failures return `401 UNAUTHORIZED`.
Resource budgets are validated without silently increasing or clamping them.

## Function signatures and source validation

The multi-file contract has no fixed solution class or method-signature allowlist.
Host test code defines the expected calls and assertions. Request validation checks
the manifest and execution metadata, not source-language semantics. A valid Java
entry-class name does not establish that the class exists or has a working main.
Compilation in the sandbox will check syntax and solution-signature compatibility
when the execution stages are implemented. Broken Host configuration must remain
distinct from participant errors under the contract's evaluation rules.

## Verification

| Requirement | Existing implementation and regression coverage |
| --- | --- |
| Key authentication | `InternalApiFilter`, `InternalApiFilterTests`, `RunEndpointTests` |
| File manifests and editable overrides | `RunRequestValidatorTests` |
| Test IDs, references and execution profiles | `RunTestSuiteValidatorTests` |
| UTF-8 sizes, counts and resource budgets | `RunRequestLimitsTests` |
| HTTP parsing, errors and body ceiling | `RunEndpointTests` |
| Reject before invoking executor / accept valid requests | `RunEndpointTests`, `RunSubmissionUseCaseTests` |
| No keys, source or exception fragments in console logs | Output-capture regression tests in `RunEndpointTests` |

The log regression checks use synthetic markers, an HTTP server on a random local
port and a test-only executor. They cover valid requests, incorrect keys, malformed
JSON, rejected hidden tests, oversized source, executor exceptions and parser
failures. Assertions check captured output as well as generic error responses.
The checks exercise the standard application logging configuration and do not
execute submitted code or require Docker/Judge0.

Run the full default verification with JDK 21:

```sh
./mvnw -B -ntp clean verify
```

Future Submit/job endpoints must use the same internal authentication boundary
and validate their own payloads when introduced. Backend remains responsible for
participant authorization and authoritative task data. The internal key must stay
outside browser payloads and source control.
