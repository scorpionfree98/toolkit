package com.autojump.app.data;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecoveryPlanTest {
    private static final String VALID="[{\"id\":\"test\",\"name\":\"Synthetic\",\"enabled\":true,\"actions\":[\"example://local\"]}]";
    @Test public void corruptOriginalPreservedAndRecoveredRulesDisabled(){
        RecoveryPlan p=new RecoveryPlan("{broken",VALID);
        assertEquals("{broken",p.original);assertFalse(StrictRuleImport.decode(p.replacement).get(0).isEnabled());
        assertTrue(p.summary.contains("不会恢复或执行旧队列"));
    }
    @Test(expected=RuntimeException.class) public void diagnosticIsNotExecutableBackup(){new RecoveryPlan("", "{\"diagnosticSchema\":1,\"executionEvidenceOnly\":{}}");}
    @Test(expected=RuntimeException.class) public void emptyImportCannotEraseRules(){new RecoveryPlan(VALID,"[]");}
    @Test(expected=RuntimeException.class) public void concurrentEditInvalidatesPreview(){new RecoveryPlan("",VALID).requireUnchanged(VALID);}
    @Test(expected=RuntimeException.class) public void invalidActionNotSilentlyDiscarded(){new RecoveryPlan(VALID,"[{\"id\":\"x\",\"actions\":[{\"type\":\"UNSUPPORTED\"}]}]");}
}
