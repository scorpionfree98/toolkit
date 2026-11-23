package local.jdcapture;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;
public class CaptureLimitsTest {
    @Test public void refusesOverflowWithoutDeletingEvidence() throws Exception {
        File dir = Files.createTempDirectory("capture-limit").toFile();
        File evidence = new File(dir, "synthetic.json");
        Files.write(evidence.toPath(), new byte[8]);
        CaptureLimits.requireSpace(dir, 2, 10, 2);
        assertThrows(IOException.class, () -> CaptureLimits.requireSpace(dir, 3, 10, 2));
        assertThrows(IOException.class, () -> CaptureLimits.requireSpace(dir, 0, 10, 1));
        assertEquals(8, evidence.length());
    }
    @Test public void explicitCleanupOnlyTouchesCaptureRecords() throws Exception {
        File dir = Files.createTempDirectory("capture-cleanup").toFile();
        Files.write(new File(dir,"one.json").toPath(), new byte[8]);
        Files.write(new File(dir,"events.jsonl").toPath(), new byte[8]);
        File unrelated = new File(dir,"keep.bin"); Files.write(unrelated.toPath(), new byte[]{1});
        new CaptureStore(dir).clearLogs();
        assertFalse(new File(dir,"one.json").exists());
        assertFalse(new File(dir,"events.jsonl").exists());
        assertTrue(unrelated.exists());
    }
}
