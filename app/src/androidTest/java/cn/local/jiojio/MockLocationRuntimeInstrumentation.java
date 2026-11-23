package cn.local.jiojio;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Location-only regression: refuses production package or physical devices; never writes rules. */
public final class MockLocationRuntimeInstrumentation extends Instrumentation {
    private Context context;
    private int checks;
    private StringBuilder details=new StringBuilder();

    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        context=getTargetContext();
        if (!"cn.local.toolkit.runtime".equals(context.getPackageName())
                || !(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"))) {
            result.putString("stream","FAIL: isolated emulator required\n");
            finish(Activity.RESULT_CANCELED,result);return;
        }
        SharedPreferences rules=context.getSharedPreferences("jump_config_sp",0);
        Map<String,?> originalRules=rules.getAll();
        SharedPreferences positions=context.getSharedPreferences("positions",0);
        String history=positions.getString("history",null);
        int code=Activity.RESULT_OK;
        LocationManager manager=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        java.util.List<LocationListener> listeners=new java.util.ArrayList<>();
        try {
            // A foreground consumer avoids conflating Android background-delivery throttling
            // with the service's injection cadence.
            startActivitySync(new Intent(context,ToolboxActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check(!MockLocationService.running,"Service must start stopped");
            check(MockLocationService.authorized(context),"Mock grant missing");
            startPoint("First",39.90886,116.39739);
            waitFor(()->MockLocationService.running,10000,"Start");
            android.app.PendingIntent notificationOpen=null;
            for(android.service.notification.StatusBarNotification entry:context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
                if(entry.getId()==1) { notificationOpen=entry.getNotification().contentIntent;break; }
            }
            check(notificationOpen!=null,"Foreground notification missing");
            // PendingIntent equality checks its actual component/request identity. Android may
            // reuse an existing activity, so waiting for a newly created activity is incorrect.
            android.app.PendingIntent tools=android.app.PendingIntent.getActivity(context,0,
                new Intent(context,ToolboxActivity.class),android.app.PendingIntent.FLAG_NO_CREATE|android.app.PendingIntent.FLAG_IMMUTABLE);
            check(tools!=null && tools.equals(notificationOpen),"Location notification must target tools, not default instruction");
            for (String provider:new String[]{"gps","network"}) {
                waitFor(()->matches(manager.getLastKnownLocation(provider),39.90886,116.39739),5000,provider+" coordinates");
                check(manager.getLastKnownLocation(provider).isFromMockProvider(),provider+" mock marker retained");
                Set<Long> ticks=new HashSet<>();
                long deadline=SystemClock.elapsedRealtime()+1800;
                while (SystemClock.elapsedRealtime()<deadline) {
                    Location location=manager.getLastKnownLocation(provider);
                    if (location!=null) ticks.add(location.getElapsedRealtimeNanos());
                    SystemClock.sleep(40);
                }
                details.append(provider).append(" distinct timestamps / 1.8s: ").append(ticks.size()).append('\n');
                check(ticks.size()>=6,provider+" injection cadence regressed: "+ticks.size());
            }
            java.util.List<java.util.concurrent.atomic.AtomicReference<Location>> received=new java.util.ArrayList<>();
            for(String provider:new String[]{"gps","network"}) {
                java.util.concurrent.atomic.AtomicReference<Location> latest=new java.util.concurrent.atomic.AtomicReference<>();
                LocationListener listener=new LocationListener() {
                    @Override public void onLocationChanged(Location point) { latest.set(point); }
                    @Override public void onStatusChanged(String p,int s,Bundle extras) { }
                    @Override public void onProviderEnabled(String p) { }
                    @Override public void onProviderDisabled(String p) { }
                };
                received.add(latest);listeners.add(listener);
                manager.requestLocationUpdates(provider,0,0,listener,android.os.Looper.getMainLooper());
            }
            startPoint("Second",31.2304,121.4737);
            for (String provider:new String[]{"gps","network"})
                waitFor(()->matches(manager.getLastKnownLocation(provider),31.2304,121.4737),5000,provider+" retarget");
            for(java.util.concurrent.atomic.AtomicReference<Location> latest:received)
                waitFor(()->matches(latest.get(),31.2304,121.4737),5000,"Consumer callback after retarget");
            check(MockLocationService.running,"Retarget stopped service");
            check(context.getSharedPreferences("session",0).getBoolean("active",false),"Recovery intent not saved");
            stop();
            waitFor(()->!MockLocationService.running && MockLocationService.status(context).contains("已停止"),5000,"Explicit stop");
            check(!context.getSharedPreferences("session",0).getBoolean("active",true),"Stop left recovery enabled");
            check(context.getSharedPreferences("session",0).getStringSet("providers",Collections.emptySet()).isEmpty(),"Stop left owned providers");

            startPoint("Invalid",91,0);
            waitFor(()->MockLocationService.status(context).contains("启动失败"),5000,"Invalid coordinates error");
            check(!MockLocationService.running,"Invalid coordinates accepted");

            startPoint("Revocation",39.90886,116.39739);
            waitFor(()->MockLocationService.running,5000,"Restart after invalid input");
            shell("appops set "+context.getPackageName()+" android:mock_location deny");
            waitFor(()->!MockLocationService.running,5000,"Revoke stops injection");
            check(!context.getSharedPreferences("session",0).getBoolean("active",true),"Revocation left recovery enabled");
            waitFor(()->MockLocationService.status(context).contains("中断"),5000,"Revocation hidden as success");
            check(!context.getSharedPreferences("session",0).getStringSet("providers",Collections.emptySet()).isEmpty(),
                    "Revocation falsely claimed provider cleanup");
            shell("appops set "+context.getPackageName()+" android:mock_location allow");
            stop();
            waitFor(()->context.getSharedPreferences("session",0).getStringSet("providers",Collections.emptySet()).isEmpty(),5000,"Regrant cleanup");
            // Exercise serialized start/stop commands without touching rule storage.
            startPoint("Rapid A",39,116);stop();startPoint("Rapid B",31,121);
            waitFor(()->MockLocationService.running && matches(manager.getLastKnownLocation("gps"),31,121),5000,"Rapid start/stop/start");
            SystemClock.sleep(500);
            check(MockLocationService.running,"Old stop killed newer start");
            stop();
            waitFor(()->!MockLocationService.running && !context.getSharedPreferences("session",0).getBoolean("active",true),5000,"Final stop");
            check(originalRules.equals(rules.getAll()),"Rules were modified");
            result.putString("stream","PASS: "+checks+" checks\n"+details);
            result.putInt("checks_passed",checks);
        } catch(Throwable error) {
            code=Activity.RESULT_CANCELED;
            result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error)+"\n"+details);
        } finally {
            for(LocationListener listener:listeners) manager.removeUpdates(listener);
            try {
                shell("appops set "+context.getPackageName()+" android:mock_location allow");
                stop();
                long until=SystemClock.elapsedRealtime()+5000;
                while (MockLocationService.running && SystemClock.elapsedRealtime()<until) SystemClock.sleep(50);
            } catch(Exception ignored) { }
            SharedPreferences.Editor editor=positions.edit();
            if(history==null) editor.remove("history");else editor.putString("history",history);
            editor.commit();
        }
        finish(code,result);
    }
    private void startPoint(String name,double lat,double lon) {
        context.startForegroundService(new Intent(context,MockLocationService.class)
            .putExtra("name",name).putExtra("lat",Double.toString(lat)).putExtra("lon",Double.toString(lon)));
    }
    private void stop() { context.startService(new Intent(context,MockLocationService.class).setAction("stop")); }
    private void shell(String command) throws Exception {
        try(ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand(command);
                java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer=new byte[256];while(input.read(buffer)!=-1) { /* Drain command completion. */ }
        }
    }
    private static boolean matches(Location point,double lat,double lon) {
        return point!=null && Math.abs(point.getLatitude()-lat)<0.000001 && Math.abs(point.getLongitude()-lon)<0.000001;
    }
    private interface Condition { boolean ready(); }
    private void waitFor(Condition condition,long timeout,String name) {
        long end=SystemClock.elapsedRealtime()+timeout;
        while(!condition.ready() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(40);
        check(condition.ready(),name+" timed out");
    }
    private void check(boolean condition,String message) { if(!condition)throw new AssertionError(message);checks++; }
}
