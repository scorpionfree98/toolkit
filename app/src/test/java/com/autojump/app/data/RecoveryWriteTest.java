package com.autojump.app.data;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecoveryWriteTest {
    private static final String VALID="[{\"id\":\"test\",\"actions\":[\"example://local\"]}]";
    static class Preferences {
        final Map<String,Object> data=new HashMap<>();int commits;boolean fail;
        SharedPreferences instance() {return (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            switch(m.getName()) {
                case "getString":return data.getOrDefault(a[0],a[1]);
                case "contains":return data.containsKey(a[0]);
                case "edit":
                    Map<String,Object> changes=new HashMap<>();Set<String> removals=new HashSet<>();
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.Editor.class},(editor,method,args)->{
                        if(method.getName().startsWith("put")){changes.put((String)args[0],args[1]);return editor;}
                        if(method.getName().equals("remove")){removals.add((String)args[0]);return editor;}
                        if(method.getName().equals("commit")){commits++;for(String key:removals)data.remove(key);data.putAll(changes);return !fail;}
                        throw new AssertionError(method.getName());
                    });
                default:throw new AssertionError(m.getName());
            }
        });}
    }
    @Test public void originalAndReplacementShareOneCommitAndFirstBackupSurvives(){
        Preferences p=new Preferences();p.data.put("rule_list","{broken");RuleRepository r=new RuleRepository(p.instance());
        r.restoreReviewed(new RecoveryPlan("{broken",VALID));assertEquals(1,p.commits);assertEquals("{broken",p.data.get("first_pre_recovery_raw"));
        String previous=r.rawRules();r.restoreReviewed(new RecoveryPlan(previous,VALID));assertEquals("{broken",p.data.get("first_pre_recovery_raw"));assertEquals(previous,p.data.get("previous_pre_recovery_raw"));
    }
    @Test public void changedSincePreviewDoesNotWrite(){
        Preferences p=new Preferences();p.data.put("rule_list",VALID);RuleRepository r=new RuleRepository(p.instance());
        try{r.restoreReviewed(new RecoveryPlan("",VALID));fail();}catch(IllegalStateException expected){}assertEquals(0,p.commits);
    }
    @Test public void corruptQueueIsArchivedNeverReplayedOrOverwritesFirstEvidence(){
        Preferences p=new Preferences();p.data.put("pending_sessions","bad");SharedPreferences prefs=p.instance();
        assertTrue(CorruptDataArchive.quarantine(prefs,"pending_sessions","bad"));
        assertFalse(p.data.containsKey("pending_sessions"));assertEquals("bad",p.data.get("pending_sessions_corrupt_backup"));
        p.data.put("pending_sessions","later-bad");assertFalse(CorruptDataArchive.quarantine(prefs,"pending_sessions","later-bad"));
        assertEquals("bad",p.data.get("pending_sessions_corrupt_backup"));assertEquals("later-bad",p.data.get("pending_sessions"));
    }
    @Test public void diskFailureIsNotReportedAsSuccessAndRawRemainsInTransaction(){
        Preferences p=new Preferences();p.data.put("rule_list","{broken");p.fail=true;
        try{new RuleRepository(p.instance()).restoreReviewed(new RecoveryPlan("{broken",VALID));fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("未确认成功"));}
        assertEquals("{broken",p.data.get("previous_pre_recovery_raw"));
    }
}
