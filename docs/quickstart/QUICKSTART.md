# Operator quickstart

This client inspects service APIs and records approved update intent. It does
not install/start the complete Cell, access databases or activate Vault signers.
The current deploy installer remains unqualified; do not infer production
installation readiness from a successful CLI build or diagnostic response.

## Build this checkout

```sh
./gradlew --no-daemon test installDist --max-workers=1
build/install/kerosene-jctl/bin/kerosene-jctl --help
```

Use the independently approved installed binary in operational environments.
The build command alone does not establish a release's signature or provenance.

## Core and complete-Cell observations

Configure the existing production Core profile with its HTTPS origin. Supply
short-lived KEROSENE_ADMIN_TOKEN and operator mTLS references/passwords at runtime,
outside the repository and profile file. Then:

```sh
kerosene-jctl --profile production --output json cell status
kerosene-jctl --profile production --output json cell quorum
kerosene-jctl --profile production --output json cell blockers
kerosene-jctl --profile production --output json cell update status
```

## Standalone KFE pending work

Supply a separately scoped KEROSENE_KFE_ADMIN_TOKEN through the session/secret
manager, plus the production operator mTLS references. Select the KFE HTTPS
origin explicitly; there is no fallback to Core or its credentials/profile.

```sh
kerosene-jctl --kfe-endpoint https://kfe.example.invalid --output json kfe maintenance status
kerosene-jctl --kfe-endpoint https://kfe.example.invalid --output json kfe maintenance admissions --limit 50
```

Copy nextCursor from the response into the next invocation's --cursor option.
Each page is a separate database snapshot; resolving entries can disappear
between pages. Operation IDs and cursor positions cannot authorize completion.
The read controls are available during drain, under the server's authorization.
No clear/resume/drain/update mutation command is provided by this group.

The HTTPS origin above is illustrative. An endpoint mismatch or absent server
capability returns an error; the client never emulates it by reading the DB.
For local synthetic testing only, set KEROSENE_ENVIRONMENT=local and supply
--allow-http-local with a localhost/127.0.0.1 origin. Do not use that mode for
staging/production. See the [catalog](../reference/CLI_CATALOG.md) for exact
arguments, TLS requirements, errors and response limits.
