package com.autojump.app.shizuku;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryConnectionPolicyTest {
    @Test public void settingsAloneNeverConfirmRecovery() {
        assertFalse(RecoveryConnectionPolicy.confirmed(true, false, 1, 2));
    }
    @Test public void oldServiceReferenceNeverConfirmsRecovery() {
        assertFalse(RecoveryConnectionPolicy.confirmed(true, true, 1, 1));
    }
    @Test public void disabledSettingNeverConfirmsRecovery() {
        assertFalse(RecoveryConnectionPolicy.confirmed(false, true, 1, 2));
    }
    @Test public void newlyBoundEnabledServiceConfirmsRecovery() {
        assertTrue(RecoveryConnectionPolicy.confirmed(true, true, 1, 2));
    }
}
