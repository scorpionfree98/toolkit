package com.autojump.app.automation;
import com.autojump.app.bean.Rule;
import com.google.gson.Gson;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;
public class PendingExecutionDecodeTest {
    @Test public void validQueueRoundTrips() {
        ExecutionSession s = ExecutionSession.fromRule(new Rule("safe-test", "Synthetic"), 1);
        assertEquals(s.getId(), PendingExecutionStore.decode(new Gson().toJson(Collections.singletonList(s))).get(0).getId());
    }
    @Test public void invalidQueuesFailBeforeDispatch() {
        for (String value : new String[]{"{", "null", "{}", "[null]", "[{}]", "[1]"}) {
            try { PendingExecutionStore.decode(value); fail(value); }
            catch (RuntimeException expected) { }
        }
    }
}
