package local.jdcapture;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;
public class ResourceCleanupTest {
    @Test public void failuresDoNotSkipOtherResources() {
        List<Integer> calls = new ArrayList<>();
        try {
            ResourceCleanup.run(() -> { calls.add(1); throw new Exception("vpn"); },
                    () -> { calls.add(2); throw new Exception("proxy"); }, () -> calls.add(3));
            fail();
        } catch (Exception error) {
            assertEquals("vpn", error.getMessage());
            assertEquals(1, error.getSuppressed().length);
        }
        assertEquals(Arrays.asList(1, 2, 3), calls);
    }
    @Test public void successfulCleanupCompletes() throws Exception {
        ResourceCleanup.run(() -> {}, () -> {});
    }
}
