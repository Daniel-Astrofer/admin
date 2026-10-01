# Admin boundaries and source inventory

This isolated baseline uses the flat `io.kerosene.jctl` package. CLI commands call
the Core HTTPS client; no command owns service state, deployment or databases.

- `Main`: CLI bootstrap.
- `KeroseneJavaCli`: root options, authenticated Core transport, existing reads.
- `CellCommands`: Cell reads, local package verification and Core plan requests.
- `PackageVerifier`: offline signature/artifact/exact-configuration verification.
- `OutputFormatter`: complete text/JSON presentation, including evidence arrays.
- `ProfileLoader`, `TlsContextFactory`: runtime profile and mTLS configuration.

The inherited unresolved `application.AdminApiClient` and
`presentation.OutputFormatter` imports were removed from this snapshot; their
paths had no source files. This change restores compilation without copying
primary working-tree edits. All permanent credentials remain outside the repo.
