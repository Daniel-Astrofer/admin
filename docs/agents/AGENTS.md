# Agent guide — Kerosene Admin

## Scope

Admin owns authenticated, audited operator clients only. It does not contain
service implementations, deployment manifests, permanent credentials or direct
database access.

## Documentation

- Start at `docs/README.md`.
- Keep CLI behavior in `reference/` and design/boundaries in `architecture/`.
- Operator examples use placeholders and link to service-owned runbooks.

## Safety and integration

- `kerosene-jctl` communicates only through authenticated, audited service APIs.
- Never store permanent tokens, certificates, customer data or direct database
  access instructions.

## Verification

Run the relevant Gradle verification and update the CLI catalog for every
command, flag, authentication or output change.
