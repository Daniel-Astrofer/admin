package io.kerosene.jctl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;

/** Offline verification only: no artifact URL is ever fetched or executed. */
final class PackageVerifier {
    private static final long MAX_ARTIFACT_BYTES = 2L * 1024 * 1024 * 1024;
    static JsonNode verify(Path manifest, Path signatureFile, Path trustedKey, Path artifacts, Path deploymentManifest) throws Exception {
        byte[] bytes = boundedFile(manifest);
        byte[] key = Base64.getDecoder().decode(new String(boundedFile(trustedKey), java.nio.charset.StandardCharsets.UTF_8).trim());
        byte[] signature = Base64.getDecoder().decode(new String(boundedFile(signatureFile), java.nio.charset.StandardCharsets.UTF_8).trim());
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(key)));
        verifier.update(bytes);
        if (!verifier.verify(signature)) throw new IllegalArgumentException("Package manifest signature invalid");
        var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode data = mapper.readTree(bytes);
        if (!"kerosene.cell-package/v1".equals(data.path("schema").asText())
                || !data.path("releaseId").asText().matches("[a-z0-9][a-z0-9._-]{2,127}")
                || !data.path("targetSequence").isIntegralNumber() || !data.path("targetSequence").canConvertToLong()
                || data.path("targetSequence").asLong() < 1 || data.path("targetSequence").asLong() > 9007199254740991L
                || !data.path("releaseLockCanonicalDigest").asText().matches("sha256:[0-9a-f]{64}")
                || !data.path("artifacts").isArray() || data.path("artifacts").isEmpty()
                || data.path("artifacts").size() > 1024) throw new IllegalArgumentException("Unsupported package manifest");
        Path root = artifacts.toAbsolutePath().normalize();
        rejectLinks(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) throw new IllegalArgumentException("Artifact directory required");
        var seen = new HashSet<String>();
        for (JsonNode artifact : data.path("artifacts")) {
            String name = artifact.path("path").asText();
            if (name.isBlank() || name.contains("\\") || name.contains(":") || name.startsWith("/") || !seen.add(name)) throw new IllegalArgumentException("Unsafe or duplicate artifact path");
            Path relative = Path.of(name);
            for (Path segment : relative) if (segment.toString().equals("..") || segment.toString().equals(".")) throw new IllegalArgumentException("Artifact path traversal");
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Artifact missing or escaped root");
            Path walked = root;
            for (Path segment : relative) { walked = walked.resolve(segment); if (Files.isSymbolicLink(walked)) throw new IllegalArgumentException("Artifact symlink rejected"); }
            if (!artifact.path("size").isIntegralNumber() || artifact.path("size").asLong(-1) < 0
                    || !artifact.path("size").canConvertToLong() || artifact.path("size").asLong() > MAX_ARTIFACT_BYTES
                    || Files.size(file) != artifact.path("size").asLong()) throw new IllegalArgumentException("Artifact size mismatch: " + name);
            String hash = artifact.path("sha256").asText();
            if (!hash.matches("[0-9a-f]{64}") || !hash.equals(hash(file, artifact.path("size").asLong()))) throw new IllegalArgumentException("Artifact digest mismatch: " + name);
        }
        if (!mapper.readTree(boundedFile(deploymentManifest)).isObject()) throw new IllegalArgumentException("Deployment manifest must be a JSON object");
        String deploymentDigest = "sha256:" + hash(deploymentManifest, 1_048_576);
        if (!deploymentDigest.equals(data.path("deploymentManifestDigest").asText())) throw new IllegalArgumentException("Deployment manifest bytes differ from signed package");
        var result = mapper.createObjectNode().put("verification", "VERIFIED").put("releaseId", data.path("releaseId").asText())
                .put("targetSequence", data.path("targetSequence").asLong()).put("targetDigest", data.path("releaseLockCanonicalDigest").asText())
                .put("deploymentManifestDigest", deploymentDigest).put("packageManifestDigest", "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .put("artifactCount", seen.size()).put("deploymentExecuted", false)
                .put("releaseAuthorized", false).put("verificationScope", "LOCAL_PACKAGE_SIGNATURE_AND_BYTES_NOT_TUF_OR_CONSENSUS");
        return result;
    }
    private static byte[] boundedFile(Path file) throws Exception {
        rejectLinks(file.toAbsolutePath());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1_048_576) throw new IllegalArgumentException("Regular metadata file <=1 MiB required");
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw new IllegalArgumentException("Metadata exceeds byte limit");
            return bytes;
        }
    }
    private static String hash(Path file, long maximum) throws Exception {
        rejectLinks(file.toAbsolutePath());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Regular local file required");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536]; int count;
            long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximum) throw new IllegalArgumentException("File exceeds approved byte limit");
                hash.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static void rejectLinks(Path path) {
        Path current = path.getRoot();
        for (Path part : path) {
            if (part.toString().equals("..")) throw new IllegalArgumentException("Parent traversal in metadata path");
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Link in local verification path");
        }
    }
}
