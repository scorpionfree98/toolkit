package com.autojump.app.domain;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuleConditionEditsTest {
    @Test public void replacesInPlaceWithoutAddingOrReorderingConditions() {
        RuleCondition a = RuleCondition.wifi("before"), b = RuleCondition.charging(true);
        List<RuleCondition> conditions = new ArrayList<>(Arrays.asList(a, b));
        RuleConditionEdits.apply(conditions, 0, () -> RuleCondition.wifi("after"));
        assertEquals(2, conditions.size()); assertSame(b, conditions.get(1));
        assertEquals("after", conditions.get(0).getSsid());
        assertEquals("before", a.getSsid());
    }
    @Test public void invalidInputDoesNotDeleteOrChangeOriginal() {
        List<RuleCondition> conditions = new ArrayList<>(Arrays.asList(RuleCondition.wifi("before")));
        String before = new Gson().toJson(conditions);
        try { RuleConditionEdits.apply(conditions, 0, () -> RuleCondition.wifi("  ")); fail(); }
        catch (IllegalArgumentException expected) { assertEquals(before, new Gson().toJson(conditions)); }
    }
    @Test public void wrongTypeCannotReplaceOriginal() {
        RuleCondition a = RuleCondition.wifi("before");
        List<RuleCondition> conditions = new ArrayList<>(Arrays.asList(a));
        try { RuleConditionEdits.apply(conditions, 0, () -> RuleCondition.charging(true)); fail(); }
        catch (IllegalArgumentException expected) { assertSame(a, conditions.get(0)); }
    }
    @Test public void staleIndexDoesNotAppendOrEvaluateDraft() {
        List<RuleCondition> conditions = new ArrayList<>();
        for (int index : new int[]{-2, 0, 4}) {
            try { RuleConditionEdits.apply(conditions, index, () -> { throw new AssertionError(); }); fail(); }
            catch (IllegalArgumentException expected) { assertTrue(conditions.isEmpty()); }
        }
    }
    @Test public void addingStillAppendsNormally() {
        List<RuleCondition> conditions = new ArrayList<>(Arrays.asList(RuleCondition.charging(true)));
        RuleConditionEdits.apply(conditions, -1, () -> RuleCondition.bluetooth("headset"));
        assertEquals(2, conditions.size()); assertEquals(ConditionType.BLUETOOTH, conditions.get(1).getType());
    }
    @Test public void allFiveConditionTypesCanBeReplacedAndReloaded() {
        List<RuleCondition> conditions = new ArrayList<>(Arrays.asList(
                RuleCondition.time(new int[]{1}, 8, 0, 9, 0), RuleCondition.wifi("wifi-a"),
                RuleCondition.bluetooth("device-a"), RuleCondition.charging(true),
                RuleCondition.location(10, 20, 500, "place-a")));
        List<Supplier<RuleCondition>> replacements = Arrays.asList(
                () -> RuleCondition.time(new int[]{6, 7}, 22, 30, 2, 15),
                () -> RuleCondition.wifi("wifi-b"), () -> RuleCondition.bluetooth("device-b"),
                () -> RuleCondition.charging(false), () -> RuleCondition.location(-10, -20, 150, "place-b"));
        for (int i = 0; i < conditions.size(); i++) RuleConditionEdits.apply(conditions, i, replacements.get(i));
        com.autojump.app.bean.Rule r = new com.autojump.app.bean.Rule("test", "test");
        r.setConditions(conditions); r.getActions().add(RuleAction.deeplink("demo://test"));
        com.autojump.app.data.RuleJsonCodec codec = new com.autojump.app.data.RuleJsonCodec();
        List<RuleCondition> restored = codec.decode(codec.encode(Arrays.asList(r))).get(0).getConditions();
        assertArrayEquals(new int[]{6, 7}, restored.get(0).getDaysOfWeek());
        assertEquals(22, restored.get(0).getStartHour()); assertEquals(2, restored.get(0).getEndHour());
        assertEquals("wifi-b", restored.get(1).getSsid()); assertEquals("device-b", restored.get(2).getDeviceName());
        assertFalse(restored.get(3).isShouldBeCharging());
        assertEquals(-10, restored.get(4).getLatitude(), 0); assertEquals(-20, restored.get(4).getLongitude(), 0);
        assertEquals(150, restored.get(4).getRadiusMeters()); assertEquals("place-b", restored.get(4).getPlaceName());
    }
    @Test public void invalidTimeAndLocationLeaveExistingValuesUntouched() {
        List<RuleCondition> list = new ArrayList<>(Arrays.asList(
                RuleCondition.time(null, 8, 0, 9, 0), RuleCondition.location(10, 20, 500, "p")));
        String before = new Gson().toJson(list);
        try { RuleConditionEdits.apply(list, 0, () -> RuleCondition.time(null, 25, 0, 9, 0)); fail(); }
        catch (IllegalArgumentException expected) { assertEquals(before, new Gson().toJson(list)); }
        try { RuleConditionEdits.apply(list, 1, () -> RuleCondition.location(10, 20, 0, "p")); fail(); }
        catch (IllegalArgumentException expected) { assertEquals(before, new Gson().toJson(list)); }
    }
}
