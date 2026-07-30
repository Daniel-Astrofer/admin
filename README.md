# kerosene-jctl

Read-only administrative client for Core and KFE. It never connects directly to
PostgreSQL or Redis. Authentication is supplied at execution time; profiles do
not contain permanent tokens.

The first commands define the Admin API contract for ledger, P2P, on-ramp,
reconciliation and provider diagnostics. Services must authenticate, authorize
and audit every request. Missing server capabilities return a non-zero exit code
and are never emulated by direct database access.
