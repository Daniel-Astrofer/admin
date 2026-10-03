package io.kerosene.jctl;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import java.util.concurrent.Callable;

@Command(name = "kfe", mixinStandardHelpOptions = true,
        description = "Explicit standalone KFE diagnostic reads only",
        subcommands = KfeMaintenanceCommands.Maintenance.class)
public final class KfeMaintenanceCommands implements Runnable {
    public void run() { CommandLine.usage(this, System.out); }

    @Command(name = "maintenance", mixinStandardHelpOptions = true,
            subcommands = {Status.class, Admissions.class})
    static final class Maintenance implements Runnable {
        public void run() { CommandLine.usage(this, System.out); }
    }

    @Command abstract static class Read implements Callable<Integer> {
        @Spec CommandSpec spec;
        abstract String path();
        abstract String schema();
        public Integer call() throws Exception {
            String path = path(); // Validate pagination before credentials or transport.
            KeroseneJavaCli root = (KeroseneJavaCli) spec.root().userObject();
            if (root.endpoint != null || root.profile != null) {
                throw new IllegalArgumentException("KFE diagnostics require --kfe-endpoint, not a Core endpoint/profile.");
            }
            return KfeDiagnosticClient.get(new KfeDiagnosticClient.Settings(root.kfeEndpoint,
                    System.getenv().getOrDefault("KEROSENE_ENVIRONMENT", "production"), root.allowHttpLocal,
                    root.timeout, root.requestId(), root.output, System.getenv("KEROSENE_KFE_ADMIN_TOKEN")), path, schema());
        }
    }

    @Command(name = "status", mixinStandardHelpOptions = true, description = "Read maintenance mode and blockers; does not authorize update")
    static final class Status extends Read {
        String path() { return "/api/admin/kfe/maintenance/status"; }
        String schema() { return "kerosene.kfe-maintenance/v1"; }
    }

    @Command(name = "admissions", mixinStandardHelpOptions = true, description = "Read one bounded pending-admission page; IDs are not completion tokens")
    static final class Admissions extends Read {
        @Option(names = "--limit", defaultValue = "50", description = "Page size 1..100") int limit;
        @Option(names = "--cursor", description = "Opaque nextCursor from the preceding diagnostic page") String cursor;
        String path() {
            if (limit < 1 || limit > 100) { throw new IllegalArgumentException("limit must be 1..100."); }
            if (cursor != null && (cursor.length() > 128 || !cursor.matches("[A-Za-z0-9_-]+"))) {
                throw new IllegalArgumentException("Invalid diagnostic cursor.");
            }
            return "/api/admin/kfe/maintenance/admissions?limit=" + limit
                    + (cursor == null ? "" : "&cursor=" + cursor);
        }
        String schema() { return "kerosene.kfe-maintenance-admissions/v1"; }
    }
}
