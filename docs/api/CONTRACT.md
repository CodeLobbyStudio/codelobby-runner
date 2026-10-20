# CodeLobby Runner Internal API — multi-file contract

Revision: **1.0.0-draft.2**, 2026-10-20. Proposal for runner Issue #5; not implemented or approved as a deployed API.

This revision replaces the unpublished single-file draft with the requested planned CodeLobby model. `CONTRACT.md` and `openapi.yaml` jointly define the contract: OpenAPI defines shapes; this document adds cross-field validation, byte limits, execution and security semantics. Examples are not actual runtime responses. Both drafts use `/internal/v1` because neither is released; an incompatible change to a released v1 would require v2.

## 1. Scope and decisions

| Area | Contract |
| --- | --- |
| Languages | JAVA, CPP, PYTHON, with independent files for each language |
| Files | Multiple UTF-8 files, safe nested relative paths |
| Roles | EDITABLE, READ_ONLY, VISIBLE_TEST, HIDDEN_TEST |
| Tasks and signatures | Defined by Host files and execution metadata, not two fixed adapters |
| Tests | Host-authored programs, assertions and optional framework-based tests |
| Run | All public suites and cases |
| Submit | Complete selected public and hidden suites, asynchronously |

A task can enable one or several languages; each needs its own complete file set. No translation is performed. One request selects one language. Java is implemented first per repository planning; the contract supports C++ and Python too, while capabilities advertises only actually verified support.

The API is not FUNCTION-only and requires neither Solution.java nor a fixed method signature. Host tests can exercise functions, objects or complete programs. Invocation profiles are JAVA_MAIN, CPP_MAIN and PYTHON_SCRIPT, referring to **host test entry points**, not participant-selected shell commands. Frameworks such as JUnit, gtest or pytest are usable only through a pinned, preinstalled runner profile advertised in builtinLibraries. Baseline examples use standard libraries. ZIPs, binaries, arbitrary build scripts, external dependency downloads and native library uploads are excluded.

## 2. Responsibilities

Backend owns users, lobby permissions, immutable task versions, allowed languages, authoritative files/tests, participant drafts, submissions, result persistence and rankings. It supplies every public test for Run and the complete selected public/hidden set for Submit. Runner can validate references and counts but cannot prove completeness without the task database.

Runner validates, overlays permitted edits, builds and executes suites in Judge0, validates reports, controls resources/jobs and returns results. It does not query backend storage or authorize participants. Browser calls backend only. Backend must reconstruct roles, suites, entry points and limits from the saved task version instead of trusting participant input. Both host and participant source are untrusted for infrastructure access.

## 3. Transport and authentication

- UTF-8 JSON; POST requires Content-Type: application/json; clients use Accept: application/json. Other request media types give 415 INVALID_REQUEST.
- All four internal endpoints require X-CodeLobby-Internal-Key. Generate at least 32 cryptographically random characters, preferably 32 random bytes encoded for transport. Store outside source control/browser/logs/URLs; compare without data-dependent early exit and coordinate rotation.
- Missing/wrong key gives 401 UNAUTHORIZED without revealing job existence. A service key does not replace backend user authorization.
- Use TLS or equivalent protected service transport outside local development. Responses use Cache-Control: no-store.
- Do not log raw files, hidden tests, reports, output or secrets. Safe IDs, durations and generic codes are acceptable.
- Unknown request fields and duplicate JSON keys are rejected. Required fields cannot be omitted; null is separate from omission. Clients tolerate compatible added response fields.
- /actuator/health is outside this contract and does not certify sandbox readiness. Unavailable execution configuration gives 503.

## 4. Requests and identifiers

Identifiers are case-sensitive ASCII, 1–128 characters, matching `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`.

| Field | Meaning |
| --- | --- |
| requestId | Run correlation only; not persistent or idempotent |
| submissionId | Submit job/deduplication key, persisted by backend before POST |
| taskVersionId | Immutable snapshot of selected language, files, suites and limits |
| language | JAVA, CPP or PYTHON, available in deployed capabilities |
| taskFiles | Authoritative host array of path/type/content |
| submissionFiles | Participant path/content overrides of declared EDITABLE files only |
| testSuites | Ordered suites with declared case IDs and execution metadata |
| limits | Explicit CPU/wall/memory budgets from trusted backend |

All request fields are required and non-null. Empty submissionFiles means evaluate unchanged starters. At least one EDITABLE file and one test suite are required. Total cases across suites: 1–50. suiteId and testId values are globally unique within a request. Public suites come first, hidden suites second; cases preserve declared order. There is no adapterId, fixed FUNCTION mode, single stdin, sourceCode alias or expected-integer-only schema. A changed signature or test manifest creates a new taskVersionId.

## 5. File roles and assembly

| type | Participant access | Source and execution |
| --- | --- | --- |
| EDITABLE | read/write through backend | Host starter overridden only at matching participant paths |
| READ_ONLY | read only | Original authoritative helper code |
| VISIBLE_TEST | read only | Original public test source |
| HIDDEN_TEST | never in participant views | Original hidden test source, Submit only |

Runner rejects overrides that add a path or target READ_ONLY/VISIBLE_TEST/HIDDEN_TEST. submissionFiles cannot contain roles. Omitted editable overrides preserve starters; deleting a task file needs a host task version. Empty editable contents are allowed and may fail compilation. Multiple editable files are supported.

Run contains no hidden files/suites; backend removes them before transmission. Submit contains the complete authoritative selected manifest. READ_ONLY files are public: no hidden answers/secrets belong there.

Validate before starting a compiler, then copy taskFiles into a fresh workspace and overlay permitted edits. Build each suite in an isolated workspace containing all EDITABLE/READ_ONLY files plus exactly that suite's testFilePaths. Never copy hidden tests into public builds. Every test file belongs to exactly one suite; share public helpers through READ_ONLY.

Build once **per distinct target per request**, not per test case. C++ suites with separate main files require separate targets. Reuse artifacts only inside the same request for benchmark rounds. Each suite executes in a fresh sandbox process/workspace; different suites share no writable runtime state. Cases within one suite may share static/process state, so host tests must account for their order. Clean up all processes/artifacts after the operation.

Paths are canonical POSIX relative paths, e.g. src/Solution.java or tests/public_tests.py. Allow nested ASCII letter/digit/underscore/hyphen/dot segments. Reject absolute paths, drive prefixes, backslashes, dot/dot-dot segments, NUL/control/whitespace, empty segments, trailing dots, reserved Windows device basenames, runner-reserved .codelobby paths, case-insensitive collisions, duplicate paths and file/directory prefix collisions. Maximum length is 240 characters. Validate containment during materialization; reject symlinks and prevent runtime-created links from escaping workspace roots. Request paths never denote host files.

## 6. Host suites and language profiles

Each suite contains suiteId, visibility PUBLIC/HIDDEN, testFilePaths, execution and cases. Each case declares a stable testId; input generation, signatures, assertions and comparison rules are defined by host test code and task instructions. PUBLIC references only VISIBLE_TEST; HIDDEN only HIDDEN_TEST. Entry points must be defined in selected test files, not EDITABLE sources. Total cases are known before execution; no undeclared discovery is accepted.

| Language | execution | Fixed runner behavior |
| --- | --- | --- |
| JAVA | JAVA_MAIN plus mainClass | Compile selected .java files with packages; run host fully qualified test main |
| CPP | CPP_MAIN plus mainSourcePath | Compile/link selected .cpp files with host main; include project root and header parent directories |
| PYTHON | PYTHON_SCRIPT plus scriptPath | Run host .py test script from project root; host test arranges source-module imports |

Accepted file extensions: .java for JAVA; .cpp/.h/.hpp for CPP; .py for PYTHON. No arbitrary resource/binary files in this revision. Java packages, C++ headers and Python modules are supported. Baseline standards: Java 21, C++17, Python 3.12; deployment reports exact compiler/interpreter versions and installed test libraries. No automatic Maven/pip downloads or host-defined compiler flags. Commands use fixed argument arrays, never concatenated shell strings.

Host tests may invoke any task-defined method, assert complex values, compare floats with task-defined tolerances or launch a solution program within sandbox limits. No universal signature, normalization or output-type restriction exists. Unsupported language/profile/library is rejected; ordinary participant syntax/signature errors are compilation errors, while broken trusted test configuration is a processing/configuration failure, not automatically participant fault.

## 7. Host report protocol and grading integrity

Protocol CODELOBBY_NDJSON_V1: runner creates an empty suite-private report path and sets CODELOBBY_REPORT_PATH inside the sandbox. Host tests write one UTF-8 JSON object per line there, separately from stdout/stderr. The location is not a secret.

```json
{"testId":"public-1","status":"ACCEPTED","input":{"text":"codelobby"},"expectedOutput":3,"actualOutput":3}
```

testId and status are required. Host statuses: ACCEPTED, WRONG_ANSWER, RUNTIME_ERROR. Optional input/expectedOutput/actualOutput accept any JSON value; omission becomes null. Host assertions determine correctness. Suite identity/visibility comes from trusted metadata, never from report text.

Reports must match declared IDs/order exactly: reject unknown, duplicate, reordered or extra records, duplicate JSON keys, unknown properties, non-finite numbers and malformed JSON. Maximum 100000 UTF-8 bytes per optional diagnostic value, 310000 per line and 1000000 per suite report; total limits may be reached before every individual field is full. Report storage is bounded. Oversized reports give OUTPUT_LIMIT_EXCEEDED; malformed/incomplete reports without a verified termination cause give EXECUTION_ERROR. After a verified fatal runtime/resource cause, missing case records become NOT_RUN with that overall verdict preserved.

A report saying ACCEPTED cannot override a later timeout, memory/output breach or unsuccessful process exit. Nonzero exit without a more specific verified cause is RUNTIME_ERROR. Wrong assertions should not stop subsequent cases; fatal exceptions may stop the suite. Build failures mark that target's cases NOT_RUN, with overall COMPILATION_ERROR. Other independent targets can continue if budgets remain.

**Grading limitation:** host tests and participant code share a sandbox and may share process/filesystem access. A malicious solution may inspect test source/assertions or forge the report. Report validation is not cryptographic proof of correct execution. Sandbox isolation protects infrastructure; API redaction protects participant-facing responses. Neither alone guarantees test secrecy inside the participant runtime or tamper-proof grading.

Results explicitly report gradingIntegrity = HOST_TESTS_IN_PARTICIPANT_SANDBOX. Do not claim TRUSTED_EXTERNAL_GRADING. Strict adversarial grading/test secrecy requires a stronger external evaluator or additional isolation boundary, agreed separately. HIDDEN_TEST files remain absent from browser responses even though runtime secrecy is limited.

## 8. Result fields and verdicts

Result contains status, passedTests, totalTests, testResults, compileOutput, message, sandboxCpuTimeMs, sandboxPeakMemoryBytes, benchmark and gradingIntegrity. Required response fields are always present; null means unavailable/redacted/not applicable, empty string means available and empty.

Per-case result: testId, suiteId, visibility, status, input, expectedOutput, actualOutput, stdout, stderr. Preserve suite/case request order. totalTests equals declared case count and result count; passedTests counts ACCEPTED only. Hidden input/expectedOutput/actualOutput/stdout/stderr are always null. Public diagnostics may also be null if absent or not safely attributable.

Overall verdicts: ACCEPTED, WRONG_ANSWER, COMPILATION_ERROR, RUNTIME_ERROR, TIME_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED, OUTPUT_LIMIT_EXCEEDED, EXECUTION_ERROR. Cases additionally allow NOT_RUN, without per-case COMPILATION_ERROR. ACCEPTED requires every case accepted and every suite successfully completed. Overall priority when multiple failures occur: COMPILATION_ERROR, EXECUTION_ERROR, OUTPUT_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED, TIME_LIMIT_EXCEEDED, RUNTIME_ERROR, WRONG_ANSWER, ACCEPTED. Use verified engine evidence; OutOfMemoryError text alone does not prove MLE.

Only bounded, sanitized **public-suite** compiler diagnostics may be returned. For an evaluation involving hidden builds, top-level compileOutput is null. Hidden source paths/assertions/exceptions/reports/streams never enter message, logs or public diagnostics. Shared multi-case streams are null unless per-case attribution is reliable. Backend exposes aggregate hidden counts rather than individual hidden records/IDs.

sandboxCpuTimeMs sums external suite CPU; sandboxPeakMemoryBytes is the maximum process peak across evaluation suites. Both exclude compilation/queueing but include startup/test-framework overhead; unavailable measurements are null. They do not measure isolated method complexity.

## 9. Endpoints and lifecycle

| Endpoint | Behavior |
| --- | --- |
| POST /internal/v1/runs | Synchronous all-public-suite evaluation |
| POST /internal/v1/evaluation-jobs | Asynchronous complete public/hidden evaluation |
| GET /internal/v1/evaluation-jobs/{submissionId} | Current Job snapshot |
| GET /internal/v1/capabilities | Verified runtime profiles and limits |

Run returns requestId/taskVersionId/contractVersion/result. Completed solution verdicts, including compilation/wrong answers, use HTTP 200. Run has no stored job, no benchmark and no deduplication by requestId. It shares FIFO capacity. Run deadline is 60000 ms from admission, including queue/build; 504 REQUEST_DEADLINE_EXCEEDED starts termination but is not proof of stopped processes.

New Submit returns 202 with Job and Location. Identical active replays return 202; terminal replays 200. GET returns 200 for any known state and never exposes active partial results.

| Job state | startedAt | finishedAt / expiresAt | result | error |
| --- | --- | --- | --- | --- |
| QUEUED | null | null | null | null |
| RUNNING | UTC time | null | null | null |
| FINISHED | UTC time | UTC times | complete result | null |
| FAILED | UTC time or null | UTC times | null | safe Error |

Transitions: QUEUED → RUNNING → FINISHED/FAILED; QUEUED → FAILED allowed before execution/deadline. Terminal states do not restart. RFC3339 UTC times end in Z and are chronologically consistent. FINISHED means completion of the chosen grading process, not necessarily ACCEPTED or adversarially trusted grading. FAILED means processing/configuration/infrastructure prevented a reliable result.

Submit deadline is 180000 ms across queue, all builds, evaluation, benchmark and cleanup. Exceeding it produces FAILED/REQUEST_DEADLINE_EXCEEDED/retryable true after initiating termination. A benchmark-only failure after accepted evaluation leaves FINISHED/ACCEPTED with benchmark FAILED/null metrics.

HTTP timeout/disconnection never proves stopped execution. Keep capacity occupied until verified process-tree cleanup or quarantine the slot and reject new execution with 503 until recovery. Run infrastructure failures give controlled HTTP errors; accepted Submit infrastructure failures become FAILED jobs.

Backend may map its PENDING_EVALUATION to runner QUEUED and its COMPILE_ERROR to runner COMPILATION_ERROR. Runner FINISHED includes a separate verdict; every other negative verdict and FAILED requires explicit backend handling, never default acceptance.

## 10. Limits, queue and isolation

| Limit | Draft profile |
| --- | --- |
| Files / overrides | at most 50 each; effective project at most 50 files |
| Per file | 131072 UTF-8 bytes |
| Effective project after overlay | 1048576 UTF-8 bytes |
| HTTP body, including originals and overrides | 2000000 bytes enforced while reading |
| Path | 240 characters plus containment checks |
| Suites/cases | 1–50 nonempty suites; 1–50 cases total |
| Per diagnostic JSON value | 100000 bytes compact UTF-8 serialization |
| stdout/stderr | 65536 bytes each per suite execution |
| Report | 1000000 bytes/suite; 310000 bytes/line |
| Returned compileOutput | 65536 bytes, sanitized/truncated at UTF-8 boundary |
| Evaluation CPU | 1–2000 ms; recommended 2000 |
| Evaluation wall | 1–5000 ms and >= CPU; recommended 5000 |
| Evaluation memory | 16000000–128000000 bytes; recommended 128000000 |
| Compilation | aggregate CPU 10000 ms, wall 20000 ms across targets; peak 512000000 bytes |

Evaluation CPU/wall budgets are aggregate sums of externally observed suite sandbox durations, not reset per case/suite. Queue, compiler and transport overhead are excluded from those sums but included in the overall operation deadline. Each next suite gets at most remaining budget; no budget means no launch. Memory caps each sandbox peak. Each benchmark round gets a fresh aggregate evaluation budget. Multi-target compilation shares one aggregate build budget.

Schema maxLength counts characters; enforce bytes separately. Validate files/metadata before starting a compiler. Invalid paths, overrides/count structure give 400 INVALID_SOURCE_FILES; invalid metadata 400 INVALID_REQUEST; exceeded size/count ceilings 413 PAYLOAD_TOO_LARGE. Empty manifests remain 400. Unsupported profile/language is 400; temporarily unavailable configured profile is 503. Do not silently clamp or raise requested limits. Judge0 conversion must not weaken limits: conservatively enforce or reject.

One active pipeline and ten pending operations share FIFO capacity across Run/Submit. Builds/evaluation/benchmark occupy one slot throughout. Store at most 100 active-plus-terminal Submit jobs. Purge expired records, not unexpired jobs to create room. Deduplicate before capacity rejection. Queue/storage full gives 429 QUEUE_CAPACITY_EXCEEDED with Retry-After: 1; rejected work is not scheduled. Run has no stored job.

Compile/run only inside sandbox: no participant network, host mounts, Docker socket, secrets or shared writable roots. Bound writable disk and process creation, including host test runners and children. Runtime links cannot escape workspace. Verify infinite loops, memory/output/process/disk exhaustion, process-tree termination, cleanup and a subsequent valid run. Host code gets no extra infrastructure privileges; deployment permissions must be documented separately.

## 11. Idempotency, retention and retries

Persist submissionId and immutable payload before POST. IDs are unique across the backend integration, not just a lobby. Concurrent identical requests atomically create one Job. Same ID and same fingerprint replays the job (202 active/200 terminal, Location in both). Changed fingerprint gives 409 SUBMISSION_ID_CONFLICT, retryable false.

Fingerprint = SHA-256 over compact UTF-8 JSON with recursively sorted object keys. Include taskVersionId, language, taskFiles(path/type/exact content), submissionFiles(path/exact content), testSuites(execution and ordered cases) and limits. Sort file arrays by canonical path and testFilePaths within suites; preserve suite/case order. Exclude submissionId/headers. Preserve source whitespace/line endings/Unicode exactly; no normalization. Integer fields use integer tokens, not decimal/exponent notation, canonicalized to ordinary base-10 integers.

After POST timeout, GET by ID or identical POST; do not create a new ID merely because the response was lost. Retry 429/503 before admission with the same ID/payload after delay. Stored FAILED does not re-execute on replay; a new attempt after retryable FAILED requires a new linked submissionId.

Results/errors expire 1800 seconds after finishedAt. Unknown/expired GET gives 404 JOB_NOT_FOUND. RAM storage loses all jobs/fingerprints on restart; backend remains durable source of truth. After restart/expiry the same request may execute again; there is no durable exactly-once guarantee.

Recommended backend retries: delays 1/2/4 seconds plus small jitter, maximum three automatic retries, respecting larger Retry-After. Reconcile ambiguous POST first. No automatic retry for 400/401/409/413/415/500. Backend decides reconstruction after lost-job 404. Poll GET every one second, backing off to five; GET never restarts work. No cancellation/callback endpoint is promised.

## 12. Errors

Handled errors have exactly code/message/retryable. Messages are generic: no hidden paths, contents, assertions, secrets or raw engine reports. Transport/proxy failures need not be JSON. Ordinary validation follows authentication; early body-size rejection can precede it.

| HTTP | Code | retryable |
| --- | --- | --- |
| 400 | INVALID_REQUEST / INVALID_SOURCE_FILES | false |
| 401 | UNAUTHORIZED | false |
| 404 | JOB_NOT_FOUND, GET only | false; backend reconciles loss |
| 409 | SUBMISSION_ID_CONFLICT | false |
| 413 | PAYLOAD_TOO_LARGE | false |
| 415 | INVALID_REQUEST | false |
| 429 | QUEUE_CAPACITY_EXCEEDED | true |
| 503 | EXECUTION_UNAVAILABLE | true |
| 504 | REQUEST_DEADLINE_EXCEEDED, Run only | true; stopping not implied |
| 500 | INTERNAL_SERVER_ERROR | false |

FAILED jobs use EXECUTION_INFRASTRUCTURE_ERROR or REQUEST_DEADLINE_EXCEEDED/retryable true, or INTERNAL_SERVER_ERROR/false. Invalid host metadata detected before admission is 400; a broken trusted test generator/configuration discovered later is a processing failure. Do not automatically blame the participant where shared-process evidence cannot establish attribution.

Provider engineStatus IDs are internal, not a stable API field. Solution verdicts normally return Run HTTP 200 or Submit FINISHED, not HTTP 500.

## 13. Benchmark

Run: NOT_REQUESTED/RUN_OPERATION/null metrics. Nonaccepted Submit: SKIPPED/EVALUATION_NOT_ACCEPTED/null metrics. Accepted Submit: two warmup rounds, five measured rounds across the full suite set, fresh sandbox per suite, fresh aggregate budgets per round, same request's compiled artifacts. Every round must produce correct complete reports; bad results/timeout/missing metrics fail benchmarking, not the earlier accepted evaluation.

This revision measures **external CPU across host suites**, not two fixed functions:

- runtimeMs = median of five per-round sums of external suite CPU.
- sandboxCpuTimeMs = the same median, explicitly identifying the measurement source.
- peakMemoryBytes = largest external process peak across measured suites/rounds.
- roundRuntimeMs/roundSandboxCpuTimeMs = identical arrays of five CPU sums; roundPeakMemoryBytes = each round's peak.
- measurementScope = ALL_TEST_SUITES; measurementTrust = SANDBOX_EXTERNAL.

Measurements include startup/test-framework/participant overhead, excluding compiler/queue. They are not isolated method times or algorithmic complexity. Compare neither different task versions/languages/profiles/environments nor different test sets. The verified engine must account for child processes too.

SUCCEEDED requires complete finite nonnegative metrics and reason null. FAILED uses MEASUREMENT_UNAVAILABLE/BUDGET_EXCEEDED/INFRASTRUCTURE_FAILURE and metrics null; no partial readings. FINISHED/ACCEPTED remains if only benchmark failed. warmupRounds=2/measuredRounds=5 describe policy, not completed-count claims. External CPU integrity does not solve HOST_TESTS_IN_PARTICIPANT_SANDBOX grading limitations.

## 14. Capabilities

Publish actual languages/versions, executionProfiles, pinned builtinLibraries, file roles/multi-file support, byte/path/test limits, compilation budgets, aggregate scope, queue/deadlines, report protocol, grading integrity and benchmark policy. Example capabilities show the full planned three-language environment. A Java-only deployment lists JAVA only until other languages are verified. Never silently substitute languages. Change environmentId when relevant compiler/runtime/library/measurement conditions change. Contract support is not implementation readiness.

## 15. Integration and examples

1. Host creates independent language file sets and suite metadata; backend saves an immutable task version.
2. Participant gets EDITABLE/READ_ONLY/VISIBLE_TEST, edits allowed files only.
3. Backend authorizes Run and sends authoritative public files/suites plus participant overrides.
4. Runner assembles isolated suite targets and returns public feedback.
5. Submit includes full authoritative selected public/hidden manifest and a saved submissionId/payload.
6. Backend polls Location, persists terminal results, redacts hidden details and applies ranking policy.
7. Retry/idempotency handling never blocks unrelated backend operations.

Examples demonstrate Java multiple files plus Run/Submit, C++ headers/sources, Python modules, host reports, all job states, compile/wrong-answer results, capabilities and errors. countVowels/twice/square are **examples**, not an allowlist of task signatures.

```sh
curl --request POST 'http://127.0.0.1:8081/internal/v1/runs' \
  --header 'Content-Type: application/json' \
  --header "X-CodeLobby-Internal-Key: $CODELOBBY_INTERNAL_KEY" \
  --data-binary @examples/run-request.json
```

These endpoints are not implemented in bootstrap #3.

## 16. Acceptance checklist

- [ ] Backend/runner review authoritative overlays, test profiles and reporting together.
- [ ] Each language has independent multi-file fixtures; no automatic translation.
- [ ] Participants cannot alter protected files, roles, test selection or entry points.
- [ ] Nested paths, traversal, duplicates, case collisions and runtime links are checked.
- [ ] Run includes all public cases/no hidden files; Submit includes the complete set.
- [ ] Java packages, C++ headers and Python modules work through verified profiles.
- [ ] Missing/duplicate/reordered/unknown/oversized reports are rejected.
- [ ] Fatal/resource failures override earlier success; grading limitations are explicit.
- [ ] Hidden compiler/runtime/report data does not leak through API/logs.
- [ ] Aggregate budgets, children, cleanup and subsequent valid execution are verified.
- [ ] Fingerprints include file roles, overrides and suite execution metadata.
- [ ] Backend handles outage/overload and capabilities never advertises missing implementations.
- [ ] OpenAPI, document and examples agree.

## 17. Sources and superseded decisions

Latest user instruction adopts planned CodeLobby: three languages, multiple files, four roles, host-defined tasks/signatures and test files. It replaces earlier one-file/JAVA-only/two-adapter assumptions. All-public Run remains unchanged.

Read-only references: [backend #16](https://github.com/CodeLobbyStudio/codelobby-backend/issues/16), [#17](https://github.com/CodeLobbyStudio/codelobby-backend/issues/17), [#18](https://github.com/CodeLobbyStudio/codelobby-backend/issues/18), [#29](https://github.com/CodeLobbyStudio/codelobby-backend/issues/29), [runner #5](https://github.com/CodeLobbyStudio/codelobby-runner/issues/5), [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html).

This revision changes local review artifacts only: no implementation, repository settings, Issues, commits or PRs.
