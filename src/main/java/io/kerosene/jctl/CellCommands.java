package io.kerosene.jctl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "cell", mixinStandardHelpOptions = true, description = "Cell operational evidence and update intent",
        subcommands = {CellCommands.Status.class, CellCommands.Releases.class, CellCommands.Quorum.class,
                CellCommands.Blockers.class, CellCommands.Backups.class, CellCommands.Update.class, CellCommands.Package.class})
public final class CellCommands implements Runnable {
    static final String BASE = "/api/admin/operations/cell";
    public void run() { picocli.CommandLine.usage(this, System.out); }
    @Command(name = "status") static final class Status extends KeroseneJavaCli.ReadCommand { String path() { return BASE; } }
    @Command(name = "releases") static final class Releases extends KeroseneJavaCli.ReadCommand { String path() { return BASE + "/releases"; } }
    @Command(name = "quorum") static final class Quorum extends KeroseneJavaCli.ReadCommand { String path() { return BASE + "/quorum"; } }
    @Command(name = "blockers") static final class Blockers extends KeroseneJavaCli.ReadCommand { String path() { return BASE + "/blockers"; } }
    @Command(name = "backups") static final class Backups extends KeroseneJavaCli.ReadCommand { String path() { return BASE + "/backups"; } }
    @Command(name = "update", mixinStandardHelpOptions = true, subcommands = {Update.Status.class, Update.Plans.class, Update.Inspect.class, Update.Plan.class})
    static final class Update implements Runnable {
        public void run() { picocli.CommandLine.usage(this, System.out); }
        @Command(name = "status") static final class Status extends KeroseneJavaCli.ReadCommand { String path() { return BASE + "/updates"; } }
        @Command(name = "plans") static final class Plans extends KeroseneJavaCli.ReadCommand { String path() { return BASE + "/updates/plans"; } }
        @Command(name = "inspect") static final class Inspect extends KeroseneJavaCli.ReadCommand {
            @Parameters(index = "0") String id;
            String path() { return BASE + "/updates/plans/" + KeroseneJavaCli.segment(id); }
        }
        @Command(name = "plan", description = "Verify local package and persist a plan; does not execute deployment")
        static final class Plan extends VerificationCommand {
            @Override public Integer call() throws Exception {
                var verified = verify();
                var body = new ObjectMapper().createObjectNode().put("targetReleaseId", verified.path("releaseId").asText())
                        .put("targetSequence", verified.path("targetSequence").asLong()).put("targetDigest", verified.path("targetDigest").asText())
                        .put("deploymentManifestDigest", verified.path("deploymentManifestDigest").asText())
                        .put("packageManifestDigest", verified.path("packageManifestDigest").asText());
                return root().post(BASE + "/updates/plans", body);
            }
        }
    }
    @Command(name = "package", mixinStandardHelpOptions = true, subcommands = Package.Verify.class)
    static final class Package implements Runnable {
        public void run() { picocli.CommandLine.usage(this, System.out); }
        @Command(name = "verify", description = "Offline signature, artifact and deployment configuration verification")
        static final class Verify extends VerificationCommand {
            @Override public Integer call() throws Exception { System.out.println(KeroseneJavaCli.formatOutput(verify(), root().output)); return 0; }
        }
    }
    @Command abstract static class VerificationCommand implements Callable<Integer> {
        @Spec CommandSpec spec;
        @Option(names = "--manifest", required = true) Path manifest;
        @Option(names = "--signature", required = true) Path signature;
        @Option(names = "--trusted-key", required = true, description = "Out-of-band pinned Ed25519 X509 DER base64 public key") Path key;
        @Option(names = "--artifacts", required = true) Path artifacts;
        @Option(names = "--deployment-manifest", required = true, description = "Exact JSON service configuration file") Path deployment;
        KeroseneJavaCli root() { return (KeroseneJavaCli) spec.root().userObject(); }
        com.fasterxml.jackson.databind.JsonNode verify() throws Exception { return PackageVerifier.verify(manifest, signature, key, artifacts, deployment); }
    }
}
