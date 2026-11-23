package local.jdcapture.core;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;
public class CertificatePersistenceTest {
    @Test public void unfinishedCreationDoesNotBecomeActive() throws Exception {
        File root = Files.createTempDirectory("ca-transaction-test").toFile();
        File staging = new File(root, "creating"); assertTrue(staging.mkdir());
        Files.write(new File(staging, "ca-key.pk8").toPath(), new byte[]{1});
        CertificateAuthority first = new CertificateAuthority(root);
        assertTrue(new File(root, "active/ca-key.pk8").isFile());
        assertTrue(new File(root, "active/ca.der").isFile());
        assertArrayEquals(first.publicCertificate(), new CertificateAuthority(root).publicCertificate());
    }
    @Test public void legacyCaIsNotRotated() throws Exception {
        File root = Files.createTempDirectory("ca-legacy-test").toFile();
        CertificateAuthority first = new CertificateAuthority(root);
        Files.move(new File(root,"active/ca-key.pk8").toPath(), new File(root,"ca-key.pk8").toPath());
        Files.move(new File(root,"active/ca.der").toPath(), new File(root,"ca.der").toPath());
        assertArrayEquals(first.publicCertificate(), new CertificateAuthority(root).publicCertificate());
    }
}
