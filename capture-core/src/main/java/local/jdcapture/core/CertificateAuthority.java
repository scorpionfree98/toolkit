package local.jdcapture.core;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/** Device-local CA. Only the public certificate is exportable through the UI. */
public final class CertificateAuthority {
    private final Provider provider = new BouncyCastleProvider();
    private final PrivateKey key;
    private final X509Certificate certificate;
    private final Map<String, SSLContext> contexts = new HashMap<>();
    public CertificateAuthority(File directory) throws Exception {
        synchronized (CertificateAuthority.class) {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("CA_DIRECTORY_FAILED");
        File keyFile = new File(directory, "ca-key.pk8"), certFile = new File(directory, "ca.der");
        // Existing installations keep their trusted CA. New CAs publish both files
        // in one atomic directory move, so an interrupted first write is not active.
        if (!keyFile.exists() && !certFile.exists()) {
            keyFile = new File(new File(directory, "active"), "ca-key.pk8");
            certFile = new File(new File(directory, "active"), "ca.der");
        }
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            try (InputStream in = new FileInputStream(certFile)) {
                certificate = (X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
        } else {
            if (keyFile.exists() || certFile.exists()) throw new IOException("CA_INCOMPLETE");
            KeyPair pair = pair(); key = pair.getPrivate();
            X500Name subject = new X500Name("CN=JD Capture Local CA, O=Device Private Diagnostics");
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, serial(),
                new Date(System.currentTimeMillis() - 86400000L), new Date(System.currentTimeMillis() + 3650L * 86400000L), subject, pair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            certificate = sign(builder);
            Path staging = new File(directory, "creating").toPath();
            Files.createDirectories(staging);
            writeSynced(staging.resolve("ca-key.pk8"), key.getEncoded());
            writeSynced(staging.resolve("ca.der"), certificate.getEncoded());
            Files.move(staging, new File(directory, "active").toPath(), StandardCopyOption.ATOMIC_MOVE);
        }
        certificate.checkValidity();
        Signature check = Signature.getInstance("SHA256withRSA");
        byte[] challenge = "ca-key-check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        check.initSign(key); check.update(challenge); byte[] signed = check.sign();
        check.initVerify(certificate.getPublicKey()); check.update(challenge);
        if (!check.verify(signed)) throw new GeneralSecurityException("CA_KEY_MISMATCH");
        }
    }
    private static void writeSynced(Path target, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target.toFile())) {
            out.write(bytes); out.getFD().sync();
        }
    }
    /** Only for the authorized local runtime; never part of certificate/log export. */
    public byte[] runtimePrivateKey() { return key.getEncoded(); }
    private KeyPair pair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair();
    }
    private BigInteger serial() { return new BigInteger(120, new SecureRandom()).add(BigInteger.ONE); }
    private X509Certificate sign(JcaX509v3CertificateBuilder b) throws Exception {
        return new JcaX509CertificateConverter().setProvider(provider).getCertificate(
            b.build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(key)));
    }
    public byte[] publicCertificate() throws Exception { return certificate.getEncoded(); }
    public String fingerprint() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(publicCertificate());
        StringBuilder b = new StringBuilder(); for (byte v : hash) b.append(String.format("%02X", v)); return b.toString();
    }
    public synchronized SSLContext serverContext(String host) throws Exception {
        if (!Scope.ENTRY.decrypts(host) && !Scope.CHECKOUT.decrypts(host)) throw new IOException("HOST_NOT_ALLOWED");
        if (contexts.containsKey(host)) return contexts.get(host);
        KeyPair pair = pair(); X500Name issuer = X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded());
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(issuer, serial(),
            new Date(System.currentTimeMillis() - 86400000L), new Date(System.currentTimeMillis() + 7L * 86400000L), new X500Name("CN=" + host), pair.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, host)));
        KeyStore ks = KeyStore.getInstance("PKCS12"); ks.load(null, null);
        ks.setKeyEntry("leaf", pair.getPrivate(), new char[0], new java.security.cert.Certificate[]{sign(b), certificate});
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(ks, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(), null, new SecureRandom());
        contexts.put(host, context); return context;
    }
}
