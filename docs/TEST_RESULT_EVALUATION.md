# Java Run result evaluation

The current Java implementation combines the terminal Judge0 outcome with the Host's
`CODELOBBY_NDJSON_V1` report. Comparison rules belong to the Host test code and task
instructions. The runner does not compare `expectedOutput` and `actualOutput` itself.
Those optional JSON values are public feedback. Different values can be correct when
Host assertions accept multiple answers or use a task-specific numerical tolerance.

## Failure attribution

| Evidence | Overall result | Unreported cases |
| --- | --- | --- |
| Ordinary participant compilation or signature error | COMPILATION_ERROR | NOT_RUN |
| Verified Host syntax error or invalid Host entry point | EXECUTION_ERROR | NOT_RUN |
| Successful process and complete valid Host report | Aggregate Host verdicts | Complete report required |
| Verified runtime or resource failure | Corresponding engine verdict, respecting contract priority | NOT_RUN |
| Missing, malformed or incomplete report after successful process exit | EXECUTION_ERROR | NOT_RUN |

The runner packages `CompileSuite.java` as a trusted resource, not a participant API file.
It executes only inside Judge0's compilation sandbox, under the existing aggregate
compilation budgets. It parses Host test files without resolving participant calls,
checks that the requested entry class belongs to the selected Host sources, compiles
all selected sources with annotation processing disabled, and checks for a public static
void `main(String[])`. Nested and inherited Host entry methods are supported.
Class loading for this check disables initialization. Static initializers execute only
at runtime, under the runtime resource limits.

A fixed configuration-error marker is captured by the worker during compilation and
transferred separately from stdout/stderr and the Host report. A marker written by
participant code later at runtime cannot alter this captured flag. Compiler diagnostics,
Host source and raw stack traces remain absent from the API response.

These checks identify syntax and entry-point configuration failures. They cannot prove
that arbitrary Host assertions, expected values or helper logic are correct, nor infer
author intent from every compiler type error. A method-signature mismatch still follows
the existing compilation-error rule. Host authors must validate their tasks against
reference solutions. Host tests and participant code still share a sandbox, so this
change does not provide tamper-proof grading or test-source secrecy inside the sandbox.

## Verification

Ordinary `./mvnw -B -ntp clean verify` covers parser, engine mapping, aggregation,
HTTP report decoding and archive generation without requiring Docker.

The opt-in `JavaRunExecutorIntegrationTests` and `Judge0ProjectIntegrationTests` use real
local Judge0. They cover correct/wrong answers, participant compilation failures,
timeouts, invalid Host syntax/entry points, inherited entry methods, compile/runtime
phase separation, multiple accepted answers and numerical tolerance boundaries.

```sh
CODELOBBY_TEST_LOCAL_JUDGE0=true CODELOBBY_TEST_JUDGE0_REPORTS=true \
  ./mvnw -B -ntp -Dtest=JavaRunExecutorIntegrationTests,Judge0ProjectIntegrationTests test
python3 dev/judge0/tests/java_budget_test.py
ruby dev/judge0/tests/suite_configuration_test.rb "$PWD/dev/judge0/image/suite-report.rb"
```

Start Judge0 with `compose.reports.yaml` first, as described in
[the environment guide](../dev/judge0/README.md#local-suite-report-prototype).
