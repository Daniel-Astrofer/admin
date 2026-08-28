# Kerosene Admin

Owner: operator-facing clients. Current executable: `kerosene-jctl`.

Build with `./gradlew test` or `./gradlew installDist`. The client calls
authenticated service APIs only. It must never connect directly to databases,
store permanent tokens or implement server authority.

Pending: create the GitHub remote, enable branch protection and replace example
secret-provider commands with the selected production integration.
