# Repository scope

This repository owns operator-facing administrative clients only. It must not
contain service implementations, deployment manifests, permanent credentials or
direct database access. `kerosene-jctl` communicates through authenticated,
audited service APIs.
