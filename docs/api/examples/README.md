# Internal API examples and participant visibility

These JSON files illustrate the backend-to-runner contract. They are fixtures,
not live responses and not payloads to send directly to a participant browser.
`manifest.json` maps each JSON example to its OpenAPI schema.

## Public tests without source access

For `public-1` in `run-request.json`, the backend may store a public title such as
"Count vowels in ordinary text" and a simplified description explaining the case.
The backend joins that metadata to the `public-1` record in `run-accepted.json`:

- Status: `ACCEPTED`.
- Input: `{"text":"codelobby"}`.
- Expected output: `3`.
- Actual output: `3`.

Titles and descriptions belong to backend storage and its participant API;
they are not extra properties in these runner JSON examples.

The backend sends `tests/PublicTests.java` to the runner as `VISIBLE_TEST` so the
runner can compile and execute it. Its source is never included in participant
file listings, downloads or browser payloads. The participant receives only
`EDITABLE`/`READ_ONLY` source files and permitted public test feedback. The same
boundary applies to the C++ and Python examples. Run still selects all public
tests, while Submit includes public and hidden tests.

The role name `VISIBLE_TEST` is unchanged: visibility describes feedback, not
source access. `HIDDEN_TEST` source and detailed hidden results remain unavailable
to participants. Backend exposes aggregate hidden counts rather than individual
hidden records or identifiers.

`run-compilation-error.json` demonstrates a safe diagnostic about the participant
file `src/Solution.java`. Diagnostics must not expose public or hidden test source,
assertion implementations or harness stack traces. If safe attribution and
redaction cannot be ensured, optional diagnostics are `null`; test configuration
failures use generic errors. Redaction does not guarantee test-source secrecy
against malicious code running in the same sandbox; see CONTRACT.md section 7.
