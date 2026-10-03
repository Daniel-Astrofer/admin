# Operator CLI catalog

Existing network commands use the authenticated, audited **Core API**. The new
`kfe maintenance` group makes two explicit standalone KFE diagnostic reads only.
There is no direct Bank/DB access, shell deploy or activation command.

Global flags: `--endpoint` (Core HTTPS origin) or `--profile`, `--output text|json|json-pretty`,
`--timeout 1..120` (default 10 seconds), `--request-id` (ASCII letters/digits/._-,
1..128 characters), `--verbose`, `--allow-http-local` (explicit localhost,
non-production only). Production requires runtime `KEROSENE_ADMIN_TOKEN`, private
JVM mTLS key store and explicit trust store, `KEROSENE_KEYSTORE_PASSWORD` and
optional `KEROSENE_TRUSTSTORE_PASSWORD`. Redirects are never followed.
Tokens/certificates are not stored in profiles or outputs. Text output includes
array members so blockers/votes/history are visible; JSON output preserves the API.
HTTP denials/failures return 4; argument/verification errors return nonzero.

| Command | Core route suffix under `/api/admin/operations/cell` |
| --- | --- |
| `cell status` | (full snapshot) |
| `cell releases` | `/releases` |
| `cell quorum` | `/quorum` |
| `cell blockers` | `/blockers` |
| `cell backups` | `/backups` |
| `cell update status` | `/updates` (phase/history/KFE/plans) |
| `cell update plans` | `/updates/plans` |
| `cell update inspect PLAN_UUID` | `/updates/plans/{uuid}` |
| `cell update plan` | POST `/updates/plans`, after offline verification |
| `cell package verify` | Offline only; no API or download |

Both `cell package verify` and `cell update plan` require:
`--manifest`, `--signature`, `--trusted-key`, `--artifacts`, `--deployment-manifest`.
The trusted public key is obtained and pinned **out of band**, never selected from
the package. Signature is detached Ed25519 over the exact manifest bytes, base64;
key is base64 X509 DER. Files must be regular; metadata is limited to 1 MiB.
Symlinked parent paths are rejected. Artifact verification is bounded to 1024
files of at most 2 GiB each; metadata reads remain bounded if a file grows.
`verification:VERIFIED` describes only the local package signature and bytes.
The result explicitly says `releaseAuthorized:false`: this flow does not verify
TUF, ordered consensus, Vault compatibility, or source-to-image provenance.
It is not directly consumable from Deploy's unsigned candidate pipeline; a
qualified signed publication/descriptor integration remains outstanding.
The deployment manifest must be a JSON object; its exact byte digest (whitespace
included) must equal the signed manifest's `deploymentManifestDigest`.

The signed manifest format is `kerosene.cell-package/v1`:

```json
{
  "schema":"kerosene.cell-package/v1",
  "releaseId":"RELEASE_ID",
  "targetSequence":2,
  "releaseLockCanonicalDigest":"sha256:64_LOWER_CASE_HEX",
  "deploymentManifestDigest":"sha256:64_LOWER_CASE_HEX",
  "artifacts":[{"path":"images/core.tar","size":123,"sha256":"64_LOWER_CASE_HEX"}]
}
```

Artifact paths must be unique relative local paths beneath `--artifacts`. Absolute
paths, traversal, symlinks and remote URL paths are rejected. Every artifact's
size and SHA256 is checked by streaming its bytes. Verification never unpacks,
downloads, installs or executes artifacts. Duplicate JSON keys and invalid
manifest versions fail verification. This package descriptor is an operator
integration proposal, not a change to Deploy's release-lock schema; the package
producer must generate and sign it alongside its release material.

Plan submission binds release ID, sequence, release lock digest, deployment
configuration digest and signed package-manifest digest to Bank's current target
through Core. A changed/missing target is rejected. Plans may be `BLOCKED` when
evidence is not ready. `PLANNED` records intent only; both states report
`deploymentExecuted:false`. The real deployment belongs to Deploy's coordinator
and requires rechecking current evidence and its init/status/preflight flow.

```sh
kerosene-jctl --profile production --output json cell status
kerosene-jctl --profile production --output json cell update status
kerosene-jctl --output json cell package verify \
  --manifest /approved/package/manifest.json --signature /approved/package/manifest.sig \
  --trusted-key /approved/trust/release-key.b64 --artifacts /approved/package \
  --deployment-manifest /approved/config/deployment.json
kerosene-jctl --profile production --output json cell update plan \
  --manifest /approved/package/manifest.json --signature /approved/package/manifest.sig \
  --trusted-key /approved/trust/release-key.b64 --artifacts /approved/package \
  --deployment-manifest /approved/config/deployment.json
```

Existing commands remain `ledger account inspect ID`, `ledger journal inspect ID`,
`p2p order inspect ID`, `onramp order inspect ID`, `reconciliation status`,
`provider connection validate ID`. These call their existing Core admin routes.
Use `--help` on the root, cell, update and package groups for command discovery.

## Standalone KFE maintenance diagnostics

These commands require explicit `--kfe-endpoint` (an HTTPS origin), never the Core
`--endpoint` or `--profile`. Combining Core options with a KFE command is rejected.
The dedicated runtime credential is `KEROSENE_KFE_ADMIN_TOKEN`; Core's token is
never reused or forwarded. The session must authenticate a positive ROLE_ADMIN
identity at the KFE API. Production retains private operator mTLS key/trust stores
and the existing runtime passwords; no credential is stored in profiles or output.
KEROSENE_ENVIRONMENT defaults to production; accepted values are production,
staging and local. Staging requires HTTPS and the dedicated token; production
additionally requires mTLS. HTTP requires exactly local, explicit localhost or
127.0.0.1 and --allow-http-local. No redirects, alternative route or credential
fallback is supported. --verbose does not expose token, response errors or cursor.

| Command | Exact standalone KFE route |
| --- | --- |
| `kfe maintenance status` | GET `/api/admin/kfe/maintenance/status` |
| `kfe maintenance admissions` | GET `/api/admin/kfe/maintenance/admissions?limit=50` |

Admissions accepts `--limit 1..100` and `--cursor NEXT_CURSOR`. The cursor is a
bounded base64url position marker from the preceding API page, not authority.
Only one page is fetched; there is no auto-pagination, replay, clear, drain, resume
or update command in this group. Empty pages and a successful read authorize
nothing. API payloads are raw versioned diagnostic objects, not Core ApiResponse.
The client checks schema and admissions diagnosticOnly=true, rejects duplicate
keys/trailing JSON, caps streamed responses at 256 KiB and applies the configured
deadline through complete body consumption. It does not independently prove the
status/entries accurate or provider work complete. HTTP rejection returns 4
without printing its body; invalid/unavailable responses return nonzero.

```sh
# Obtain KEROSENE_KFE_ADMIN_TOKEN and mTLS references from the operator's secret
# manager/session flow; do not paste tokens into this file or command arguments.
kerosene-jctl --kfe-endpoint https://kfe.example.invalid --output json kfe maintenance status
kerosene-jctl --kfe-endpoint https://kfe.example.invalid --output json kfe maintenance admissions --limit 50
kerosene-jctl --kfe-endpoint https://kfe.example.invalid --output json kfe maintenance admissions --limit 50 --cursor NEXT_CURSOR
```

Replace the illustrative origin and cursor. These are operator diagnostics, not
Admin artifact installation, an automated KFE recovery policy or a complete Cell
installer. Consult KFE's service-owned maintenance-admission-diagnostics runbook.

Verification: `./gradlew test installDist --no-daemon --max-workers=1`. Tests cover
CLI hierarchy/output/transport validation, package signature/hash/path safety and
exact deployment configuration binding. No deployment is performed by tests.
