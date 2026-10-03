# Admin implementation status — 2026-10-03

Existing Core operator queries, Cell evidence/history, offline package verification
and intent-only plan submission remain implemented. The new read-only standalone
KFE group exposes maintenance status and one pending-admission page with explicit
--kfe-endpoint and a separate runtime KEROSENE_KFE_ADMIN_TOKEN. No Core origin,
profile or token is used for those reads. Production mTLS remains mandatory.

The bounded dedicated transport never follows redirects, permits only the two
exact GET routes, limits streamed body data to 256 KiB and applies a complete-body
deadline. Duplicate/trailing JSON, wrong schema and nondiagnostic admissions
pages fail without printing their bodies. HTTP failures remain nonzero. Cursor
and admission IDs carry no continuation/completion authority. The KFE group has
no drain/resume/clear/update, auto-pagination or direct database access.

Coordinator verification: `./gradlew --no-daemon check installDist --max-workers=1`
passed with **75 tests, zero failures/errors/skips**. Eleven added test methods
exercise hierarchy/limits, explicit target selection, origin and credential
validation, actual loopback HTTP GET, redirect refusal, JSON/body boundaries,
stalled-body cancellation and a real Main JVM process with separate environment
tokens. Help was also checked using the generated installed distribution.
Deploy's `stack-publication-test.py` also passed all 45 cases using this newly
built CLI distribution. This regression validates offline publication/package
integration, not deployment or new KFE server behavior.

These tests use synthetic local HTTP servers, not a deployed KFE, authentic ADMIN
session issuance, real operator mTLS or full-Cell recovery. The production TLS
implementation is reused, not independently requalified by the HTTP fixtures.
Response format validation is not proof that remote state is accurate or effects
have completed. Automatic Admin artifact installation/upgrade and complete-Cell
install/update acceptance remain unqualified in Deploy; no blocker was removed.

The [catalog](reference/CLI_CATALOG.md) and [quickstart](quickstart/QUICKSTART.md)
describe exact targets, credentials and supported commands. Existing Core profiles
and the Core command transport are unchanged apart from additive help options.
