package local.jdcapture;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import com.autojump.app.data.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Device checks only in a disposable application ID; never access production preferences. */
public final class StabilityRuntimeInstrumentation extends Instrumentation {
    private int checks;
    private void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Context c=getTargetContext();
        if(!c.getPackageName().equals("cn.local.toolkit.runtime")){
            result.putString("stream","FAIL: isolated package required\n");finish(Activity.RESULT_CANCELED,result);return;
        }
        try {
            var prefs=c.getSharedPreferences("jump_config_sp",0);
            check(prefs.edit().putString("rule_list","{synthetic-corruption").commit(),"seed isolated preferences");
            RuleRepository repository=new RuleRepository(c);
            RecoveryPlan plan=new RecoveryPlan(repository.rawRules(),"[{\"id\":\"synthetic\",\"enabled\":true,\"actions\":[\"example://synthetic\"]}]");
            check(repository.rawRules().equals("{synthetic-corruption"),"preview cannot mutate preferences");
            repository.restoreReviewed(plan);
            check(repository.getRules().size()==1&&!repository.getRules().get(0).isEnabled(),"restore disabled");
            check(prefs.getString("first_pre_recovery_raw","").equals("{synthetic-corruption"),"raw evidence retained");
            try{repository.restoreReviewed(plan);throw new AssertionError("stale preview accepted");}catch(IllegalStateException expected){checks++;}
            var queue=c.getSharedPreferences("automation_execution_sp",0);
            queue.edit().putString("pending_sessions","{synthetic-corruption").commit();
            check(new com.autojump.app.automation.PendingExecutionStore(c).getAll().isEmpty(),"no queue replay");
            check(queue.contains("pending_sessions_corrupt_backup"),"queue evidence retained");
            File root=new File(c.getCacheDir(),"synthetic-sessions");check(root.mkdirs(),"new isolated root");
            RuntimeSessions sessions=new RuntimeSessions(root.getCanonicalFile());
            File active=new File(root,"session-"+UUID.randomUUID()),done=new File(root,"session-"+UUID.randomUUID());
            active.mkdir();done.mkdir();Files.write(new File(active,"stopped").toPath(),new byte[0]);Files.write(new File(done,"stopped").toPath(),new byte[0]);
            check(sessions.clearFinished(active.getName())==1&&active.exists()&&!done.exists(),"active session protected");
            File link=new File(active,"escape");Files.createSymbolicLink(link.toPath(),c.getFilesDir().toPath());
            try{sessions.clearFinished("");throw new AssertionError("symlink accepted");}catch(IOException expected){checks++;}
            Files.delete(link.toPath());
            ByteArrayInputStream input=new ByteArrayInputStream(new byte[10000]);ByteArrayOutputStream output=new ByteArrayOutputStream();
            RuntimeSessions.copyLog(input,output,1024);check(output.size()==1024&&input.available()==0,"log cap drains pipe");
            CaptureStore store=new CaptureStore(new File(c.getCacheDir(),"synthetic-capture"));
            try(EmbeddedBridge bridge=new EmbeddedBridge(store,true)){
                JSONObject data=new JSONObject().put("url","https://localhost:9443/capture-selftest").put("method","GET")
                    .put("requestHeaders",new JSONArray()).put("responseHeaders",new JSONArray()).put("requestBodyBase64","")
                    .put("responseBodyBase64",Base64.getEncoder().encodeToString("{\"message\":\"original\"}".getBytes(StandardCharsets.UTF_8))).put("status",200);
                try(Socket conn=new Socket("127.0.0.1",bridge.port())) {
                    conn.setSoTimeout(3000);byte[] body=data.toString().getBytes(StandardCharsets.UTF_8);
                    String head="POST /response HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+bridge.token+"\r\nContent-Length: "+body.length+"\r\n\r\n";
                    conn.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));conn.getOutputStream().write(body);conn.getOutputStream().flush();
                    InputStream in=new BufferedInputStream(conn.getInputStream());var response=local.jdcapture.core.HttpWire.head(in);
                    check(response.firstLine().contains("200"),"authenticated loopback response");
                    byte[] bytes=new byte[Integer.parseInt(response.value("Content-Length"))];new DataInputStream(in).readFully(bytes);
                    check(new JSONObject(new String(bytes,StandardCharsets.UTF_8)).getBoolean("changed")&&store.count==1,"original persisted before modification");
                }
            }
            CaptureLifecycle lifecycle=new CaptureLifecycle();long old=lifecycle.begin(CaptureLifecycle.State.RUNNING);
            check(lifecycle.nextRecoveryDelay(old)==2,"recovery scheduled");lifecycle.begin(CaptureLifecycle.State.STARTING);
            check(lifecycle.nextRecoveryDelay(old)==-1,"old recovery rejected");
            result.putString("stream","PASS: "+checks+" isolated Android checks; no production data touched\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL after "+checks+" checks: "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");finish(Activity.RESULT_CANCELED,result);}
    }
}
