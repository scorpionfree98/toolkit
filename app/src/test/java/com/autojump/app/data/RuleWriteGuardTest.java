package com.autojump.app.data;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;
public class RuleWriteGuardTest {
    @Test public void acceptsEmptyAndValidDocuments() {
        RuleRepository.requireReadableBeforeSave("");
        RuleRepository.requireReadableBeforeSave(new RuleJsonCodec().encode(Collections.emptyList()));
    }
    @Test public void rejectsOverwriteOfUnreadableRules() {
        try { RuleRepository.requireReadableBeforeSave("{broken"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("阻止覆盖")); }
    }
}
