<!--
Kerosene documentation metadata
status: review-required
audience: internal
owner: admin
source_of_truth: admin
last_reviewed: 2026-09-03
-->

# Kerosene Admin (`kerosene-jctl`)

This repository independently owns the Java administrative CLI. The executable
name remains `kerosene-jctl` for command-line compatibility.

Documentation: [English](docs/en/README.md) ·
[Português (Brasil)](docs/pt-BR/README.md) ·
[documentation portal](docs/README.md) ·
[status](docs/STATUS.md) ·
[CLI catalog](../reference/CLI_CATALOG.md) ·
[quickstart](docs/QUICKSTART.md)

Administrative client for authenticated, audited Core APIs. It never connects directly to
PostgreSQL or Redis. Authentication is supplied at execution time; profiles do
not contain permanent tokens.

The first commands define the Admin API contract for ledger, P2P, on-ramp,
reconciliation and provider diagnostics. Services must authenticate, authorize
and audit every request. Missing server capabilities return a non-zero exit code
and are never emulated by direct database access.

Production is fail-closed: the endpoint must use HTTPS, a short-lived
`KEROSENE_ADMIN_TOKEN` must be present, and the JVM must be configured with
operator mTLS key/trust stores. The key store must be a regular private file.

```bash
export KEROSENE_ADMIN_TOKEN="$(security-tool issue-token --scope core:ledger:read)"
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.keyStore=/run/credentials/operator.p12 \
 -Djavax.net.ssl.trustStore=/run/credentials/kerosene-ca.p12"
export KEROSENE_KEYSTORE_PASSWORD="$(systemd-creds cat jctl-keystore-password)"
kerosene-jctl --profile production --output json ledger account inspect ACCOUNT_ID
```

The example token command is illustrative; secrets must come from the selected
secret manager and must not be pasted into profiles or committed environment
files. Local HTTP requires both `KEROSENE_ENVIRONMENT=local` and
`--allow-http-local`.

Cell monitoring, offline package verification and recorded update plans are
documented in the [CLI catalog](../reference/CLI_CATALOG.md). Plans do not deploy
services; package verification checks exact deployment configuration bytes.
