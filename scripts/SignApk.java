import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Signs an unsigned CI build locally. Private keys and passwords never enter the repository or CI. */
public class SignApk {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("input.apk output.apk private-keystore password-file expected-certificate-sha256");
        char[] password = Files.readString(Path.of(args[3])).toCharArray();
        KeyStore store = KeyStore.getInstance("JKS");
        try (var input = Files.newInputStream(Path.of(args[2]))) { store.load(input, password); }
        PrivateKey key = (PrivateKey) store.getKey("shiyi", password);
        Arrays.fill(password, '\0');
        List<X509Certificate> chain = Arrays.stream(store.getCertificateChain("shiyi"))
            .map(c -> (X509Certificate)c).toList();
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chain.get(0).getEncoded()));
        String expected = args[4].replace(":", "").toLowerCase(java.util.Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}") || !fingerprint.equals(expected))
            throw new IllegalStateException("Signing certificate differs from the published app. Use the original key; do not generate a new key.");
        var signer = new ApkSigner.SignerConfig.Builder("shiyi", key, chain).build();
        new ApkSigner.Builder(List.of(signer)).setInputApk(new File(args[0])).setOutputApk(new File(args[1]))
            .setMinSdkVersion(26).setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign();
        var result = new ApkVerifier.Builder(new File(args[1])).build().verify();
        if (!result.isVerified()) throw new IllegalStateException("APK verification failed: " + result.getErrors());
        if (!Arrays.equals(chain.get(0).getEncoded(), result.getSignerCertificates().get(0).getEncoded()))
            throw new IllegalStateException("Signer certificate mismatch");
        System.out.println("APK signatures verified. Certificate SHA256: " + fingerprint);
    }
}
