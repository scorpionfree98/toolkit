package local.jdcapture;
import org.junit.Test;
import static org.junit.Assert.*;
public class InFlightBudgetTest {
    @Test public void enforcesCountAndRelease(){InFlightBudget b=new InFlightBudget();assertTrue(b.acquire(1));assertTrue(b.acquire(1));assertFalse(b.acquire(1));b.release(1);assertTrue(b.acquire(1));}
    @Test public void enforcesBytesBeforeAllocation(){InFlightBudget b=new InFlightBudget();assertFalse(b.acquire(InFlightBudget.MAX_REQUEST+1));assertTrue(b.acquire(8*1024*1024));assertFalse(b.acquire(5*1024*1024));b.release(8*1024*1024);assertTrue(b.acquire(5*1024*1024));}
}
