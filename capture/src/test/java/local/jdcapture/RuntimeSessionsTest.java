package local.jdcapture;
import org.junit.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
public class RuntimeSessionsTest {
    File root; RuntimeSessions sessions;
    @Before public void setup() throws Exception {root=Files.createTempDirectory("runtime-test").toFile().getCanonicalFile();sessions=new RuntimeSessions(root);}
    @After public void cleanup() throws Exception {try(var paths=Files.walk(root.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(p->p.toFile().delete());}}
    File session(boolean done)throws Exception {File f=new File(root,"session-"+UUID.randomUUID());assertTrue(f.mkdir());if(done)Files.write(new File(f,"stopped").toPath(),new byte[0]);return f;}
    @Test public void protectsActiveOwnerAndUnfinished()throws Exception {
        File active=session(true),owner=session(true),unfinished=session(false),old=session(true);
        Files.write(new File(root,"owner.json").toPath(),new JSONObject().put("session",owner.getName()).toString().getBytes());
        assertEquals(1,sessions.clearFinished(active.getName()));
        assertTrue(active.exists());assertTrue(owner.exists());assertTrue(unfinished.exists());assertFalse(old.exists());
    }
    @Test public void symlinkAbortsBeforeDeletingAnySession()throws Exception {
        File old=session(true),bad=session(true);
        Files.createSymbolicLink(new File(bad,"outside").toPath(),root.toPath().getParent());
        try{sessions.clearFinished("");fail();}catch(IOException expected){}
        assertTrue(old.exists());assertTrue(bad.exists());
    }
    @Test public void countLimitRefusesStartWithoutDeleting()throws Exception {
        for(int i=0;i<RuntimeSessions.MAX_SESSIONS;i++)session(true);
        try{sessions.requireCapacity();fail();}catch(IOException expected){}
        assertEquals(RuntimeSessions.MAX_SESSIONS,sessions.inventory("").getInt("sessions"));
    }
    @Test public void byteLimitRefusesStartAndPreservesEvidence()throws Exception {
        File s=session(true),log=new File(s,"engine.private.log");
        try(RandomAccessFile file=new RandomAccessFile(log,"rw")){file.setLength(RuntimeSessions.MAX_BYTES);}
        try{sessions.requireCapacity();fail();}catch(IOException expected){}
        assertTrue(log.exists());
    }
    @Test public void boundedLogDrainsInputAfterCap()throws Exception {
        ByteArrayInputStream input=new ByteArrayInputStream(new byte[100000]);ByteArrayOutputStream output=new ByteArrayOutputStream();
        RuntimeSessions.copyLog(input,output,1234);assertEquals(0,input.available());assertEquals(1234,output.size());
    }
    @Test public void secretCleanupFailureIsReported()throws Exception {
        File s=session(false),blocked=new File(s,"config.json");assertTrue(blocked.mkdir());Files.write(new File(blocked,"child").toPath(),new byte[1]);
        try{sessions.cleanSecrets(s);fail();}catch(IOException expected){}
        assertTrue(blocked.exists());
    }
}
