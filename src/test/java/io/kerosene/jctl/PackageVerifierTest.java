package io.kerosene.jctl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackageVerifierTest {
    @TempDir Path temp;
    Path manifest, signature, key, artifact, deployment;
    KeyPair keys;
    final ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        manifest = temp.resolve("manifest.json"); signature = temp.resolve("manifest.sig"); key = temp.resolve("public-key.b64");
        artifact = temp.resolve("service.tar"); deployment = temp.resolve("deployment.json");
        Files.writeString(artifact, "test image"); Files.writeString(deployment, "{\"service\":\"core\"}");
        Files.writeString(key, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        sign("service.tar");
    }
    void sign(String artifactPath) throws Exception {
        var node = mapper.createObjectNode().put("schema", "kerosene.cell-package/v1").put("releaseId", "release-new")
                .put("targetSequence", 2).put("releaseLockCanonicalDigest", "sha256:" + "a".repeat(64))
                .put("deploymentManifestDigest", "sha256:" + hash(Files.readAllBytes(deployment)));
        node.putArray("artifacts").addObject().put("path", artifactPath).put("size", Files.size(artifact)).put("sha256", hash(Files.readAllBytes(artifact)));
        byte[] bytes = mapper.writeValueAsBytes(node); Files.write(manifest, bytes);
        var signer = Signature.getInstance("Ed25519"); signer.initSign(keys.getPrivate()); signer.update(bytes);
        Files.writeString(signature, Base64.getEncoder().encodeToString(signer.sign()));
    }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    com.fasterxml.jackson.databind.JsonNode verify() throws Exception { return PackageVerifier.verify(manifest, signature, key, temp, deployment); }
    @Test void checksSignatureArtifactsAndExactDeploymentBytes() throws Exception {
        var result = verify(); assertEquals("VERIFIED", result.path("verification").asText()); assertFalse(result.path("deploymentExecuted").asBoolean());
        assertFalse(result.path("releaseAuthorized").asBoolean());
        Files.writeString(deployment, "{ \"service\": \"core\" }"); assertThrows(Exception.class, this::verify);
    }
    @Test void tamperedArtifactAndUntrustedSigningKeyReject() throws Exception {
        Files.writeString(artifact, "bad image!"); assertThrows(Exception.class, this::verify);
        setup(); Files.writeString(key, Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded()));
        assertThrows(Exception.class, this::verify);
    }
    @Test void traversalRemotePathsAndSymlinksReject() throws Exception {
        sign("../service.tar"); assertThrows(Exception.class, this::verify);
        sign("https://example.invalid/service.tar"); assertThrows(Exception.class, this::verify);
        Files.createSymbolicLink(temp.resolve("link.tar"), artifact); sign("link.tar"); assertThrows(Exception.class, this::verify);
    }
    @Test void modifiedManifestRejectsBeforeParsing() throws Exception {
        Files.writeString(manifest, Files.readString(manifest).replace("release-new", "release-bad")); assertThrows(Exception.class, this::verify);
    }
    @Test void symlinkedMetadataParentAndArtifactRootReject() throws Exception {
        Path alias = temp.resolve("alias"); Files.createSymbolicLink(alias, temp);
        assertThrows(Exception.class, () -> PackageVerifier.verify(alias.resolve("manifest.json"), signature, key, temp, deployment));
        assertThrows(Exception.class, () -> PackageVerifier.verify(manifest, signature, key, alias, deployment));
    }
}
