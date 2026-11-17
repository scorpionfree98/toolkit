package com.autojump.app.automation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class BatchAdmissionTest {
    @Test public void noExternalLaunchUntilEveryPermissionResultArrives() {
        List<String> events = new ArrayList<>();
        BatchAdmission b = new BatchAdmission(2, () -> events.add("persist-and-launch"),
                (ok, message) -> { assertTrue(ok); events.add("callback"); });
        b.complete(true, "a"); assertTrue(events.isEmpty()); b.complete(true, "b");
        assertEquals(Arrays.asList("persist-and-launch", "callback"), events);
    }
    @Test public void partialPermissionFailureStillAllowsReadyTasks() {
        int[] launches = {0};
        BatchAdmission b = new BatchAdmission(2, () -> launches[0]++, (ok, message) -> {
            assertTrue(ok); assertTrue(message.contains("未入队 1"));
        });
        b.complete(false, "自动点击服务未连接"); b.complete(true, "ready"); assertEquals(1, launches[0]);
    }
    @Test public void totalFailureDoesNotLaunch() {
        BatchAdmission b = new BatchAdmission(1, () -> fail("must not launch"),
                (ok, message) -> assertFalse(ok)); b.complete(false, "no permission");
    }
    @Test public void stopDuringRecoveryDoesNotLaunchAlreadyReadyTasks() {
        BatchAdmission b = new BatchAdmission(2, () -> fail("cancelled batch"),
                (ok, message) -> assertFalse(ok));
        b.complete(true, "ready"); b.complete(false, "用户停止了等待权限恢复的任务");
    }
    @Test public void duplicateTerminalCallbackCannotDispatchTwice() {
        int[] launches = {0}; BatchAdmission b = new BatchAdmission(1, () -> launches[0]++, (ok,m) -> {});
        b.complete(true, "ready"); b.complete(true, "duplicate"); assertEquals(1, launches[0]);
    }
}
