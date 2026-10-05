<!--
status: active
audience: internal
owner: admin
source_of_truth: admin source tree and build.gradle.kts
last_reviewed: 2026-09-26
-->

# Kerosene Admin

Authenticated, audited operator CLI. Start at the
[documentation](../../kerosene-global-docs/services/server-administration/docs/quickstart/README.md), [quickstart](../../kerosene-global-docs/services/server-administration/docs/quickstart/QUICKSTART.md),
and [architecture](../../kerosene-global-docs/architecture/server-administration/docs/architecture/ADMIN_HEXAGONAL_ORGANIZATION.md).

> [!NOTE]
> All administrative endpoints invoked by this CLI (`/api/admin/ledger/**`, `/api/admin/p2p/**`, `/api/admin/onramp/**`, `/api/admin/reconciliation/**`, `/api/admin/providers/**`) are served exclusively by the `core` service (`auth-service`). Ensure `--url` / `KEROSENE_ADMIN_URL` targets the Core service port, not KFE.

Run `./gradlew test` for the repository verification.

## Documentação global

Arquitetura transversal, regras de negócio compartilhadas e infraestrutura/operação global estão no repositório externo [kerosene-global-docs](../../kerosene-global-docs/README.md). A documentação inline de implementação permanece junto ao código neste repositório.
