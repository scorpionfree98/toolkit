package local.jdcapture;
import org.junit.Test;
import static org.junit.Assert.*;
public class CaptureLifecycleTest {
    @Test public void expiredRecoveryWindowDoesNotScheduleAnotherAttempt(){
        long[] now={1};CaptureLifecycle l=new CaptureLifecycle(()->now[0]);long g=l.begin(CaptureLifecycle.State.RUNNING);
        assertEquals(2,l.nextRecoveryDelay(g));now[0]+=java.util.concurrent.TimeUnit.SECONDS.toNanos(61);
        assertEquals(-1,l.nextRecoveryDelay(g));assertEquals(CaptureLifecycle.State.RECOVERY_REQUIRED,l.state());
    }
    @Test public void oldHeartbeatAndRetryCannotMatchNewSession(){
        CaptureLifecycle l=new CaptureLifecycle();long old=l.begin(CaptureLifecycle.State.RUNNING);
        assertEquals(2,l.nextRecoveryDelay(old));long fresh=l.begin(CaptureLifecycle.State.STARTING);
        assertFalse(l.matches(old));assertEquals(-1,l.nextRecoveryDelay(old));assertEquals(CaptureLifecycle.State.STARTING,l.state());assertTrue(l.matches(fresh));
    }
    @Test public void recoveryIsBoundedAndManualStopGetsNewBudget(){
        CaptureLifecycle l=new CaptureLifecycle();long g=l.begin(CaptureLifecycle.State.RUNNING);
        assertEquals(2,l.nextRecoveryDelay(g));assertEquals(5,l.nextRecoveryDelay(g));assertEquals(10,l.nextRecoveryDelay(g));assertEquals(-1,l.nextRecoveryDelay(g));
        assertEquals(CaptureLifecycle.State.RECOVERY_REQUIRED,l.state());assertEquals(2,l.nextRecoveryDelay(l.begin(CaptureLifecycle.State.STOPPING)));
    }
}
