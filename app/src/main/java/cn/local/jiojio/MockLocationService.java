package cn.local.jiojio;

import android.app.*;
import android.content.*;
import android.location.*;
import android.os.*;
import java.util.*;

public final class MockLocationService extends Service {
    public static final String CHANGE="cn.local.jiojio.LOCATION_CHANGED";
    public static volatile boolean running=false;
    private static volatile String status="定位尚未启动";
    // Serialize provider operations off the UI thread; never overlap two injections.
    static final long INTERVAL_MS=100;
    private final Handler main=new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler handler;
    private final Set<String> registered=new LinkedHashSet<>();
    private LocationManager manager;
    private Position target;
    private boolean foreground=false;
    private volatile boolean destroyed;
    private volatile int latestStartId;
    private final Object sessionLock=new Object();
    private long nextTickUptime;
    private final Runnable tick=new Runnable() {
        public void run() {
            if (destroyed) return;
            try { inject();scheduleNext(); }
            catch (Exception e) { finish("定位中断："+explain(e),latestStartId); }
        }
    };
    public static String status(Context c) {
        if (!running && (c.getSharedPreferences("session",0).getBoolean("active",false) || !c.getSharedPreferences("session",0).getStringSet("providers",Collections.emptySet()).isEmpty())
                && !status.contains("中断") && !status.contains("失败") && !status.contains("清理未完成"))
            return "上次服务已中断。请停止清理后重新启动；尚未验证定位恢复。";
        return status;
    }
    public static boolean authorized(Context c) {
        AppOpsManager ops=(AppOpsManager)c.getSystemService(APP_OPS_SERVICE);
        return ops!=null && ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,android.os.Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;
    }
    public void onCreate() {
        super.onCreate();manager=(LocationManager)getSystemService(LOCATION_SERVICE);
        thread=new HandlerThread("mock-location");thread.start();handler=new Handler(thread.getLooper());
    }
    public int onStartCommand(Intent i,int flags,int id) {
        synchronized(sessionLock) { latestStartId=id; }
        // Even invalid input must complete Android's foreground-start handshake before exit.
        try { showNotification(null); }
        catch(Exception e) {
            handler.post(()->finish("启动失败："+explain(e),id));return START_NOT_STICKY;
        }
        if (i!=null && "stop".equals(i.getAction())) {
            // Cancel recovery synchronously, before any pending injection/cleanup work.
            synchronized(sessionLock) {
                getSharedPreferences("session",0).edit().putBoolean("active",false).commit();
            }
            handler.post(()->finish("定位已停止",id));return START_NOT_STICKY;
        }
        try {
            Position next;
            if (i==null) {
                SharedPreferences session=getSharedPreferences("session",0);
                if (!session.getBoolean("active",false)) {
                    handler.post(()->finish("定位已停止",id));return START_NOT_STICKY;
                }
                next=Position.parse(session.getString("name",null),session.getString("lat",null),session.getString("lon",null));
            } else {
                next=Position.parse(i.getStringExtra("name"),i.getStringExtra("lat"),i.getStringExtra("lon"));
            }
            showNotification(next);
            handler.post(()->startLocation(next,id));
        } catch (Exception e) { handler.post(()->finish("启动失败："+explain(e),id)); }
        return START_STICKY;
    }
    private void startLocation(Position next,int id) {
        if (destroyed || id!=latestStartId) return;
        handler.removeCallbacks(tick);
        try {
            if (!authorized(this)) throw new SecurityException("未选择本应用为模拟位置应用");
            // Reuse live providers on retargeting to avoid dropping consumers' subscriptions.
            if (registered.isEmpty()) {
                cleanup();
                for (String provider:Arrays.asList(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)) {
                    // Record intent before adding, covering process death between binder and disk writes.
                    registered.add(provider);
                    getSharedPreferences("session",0).edit().putStringSet("providers",new HashSet<>(registered)).commit();
                    manager.addTestProvider(provider,false,false,false,false,true,true,true,android.location.provider.ProviderProperties.POWER_USAGE_LOW,android.location.provider.ProviderProperties.ACCURACY_FINE);
                    manager.setTestProviderEnabled(provider,true);
                }
            }
            target=next;
            inject();
            running=true;
            synchronized(sessionLock) {
                // A stop received while this start was in flight must stay durable.
                if (id==latestStartId && !destroyed)
                    getSharedPreferences("session",0).edit().putBoolean("active",true)
                        .putString("name",target.name).putString("lat",Double.toString(target.latitude))
                        .putString("lon",Double.toString(target.longitude)).commit();
            }
            new PositionStore(this).add("history",target);
            update("正在定位 · "+target.name+"\n"+target.coordinates()+"（WGS84）\n约每 100 毫秒注入；不代表其他应用已采纳");
            nextTickUptime=SystemClock.uptimeMillis();scheduleNext();
        } catch (Exception e) { finish("启动失败："+explain(e),id); }
    }
    private void scheduleNext() {
        long now=SystemClock.uptimeMillis();
        nextTickUptime+=INTERVAL_MS;
        // Skip missed deadlines after suspension instead of replaying a burst.
        if (nextTickUptime<=now) nextTickUptime=now+INTERVAL_MS;
        handler.postAtTime(tick,nextTickUptime);
    }
    private void inject() {
        if (!authorized(this)) throw new SecurityException("模拟位置授权已撤销");
        long wallTime=System.currentTimeMillis(),elapsedNanos=SystemClock.elapsedRealtimeNanos();
        for (String provider:registered) {
            Location loc=new Location(provider);
            loc.setLatitude(target.latitude);loc.setLongitude(target.longitude);
            loc.setAccuracy(5f);loc.setAltitude(0);loc.setSpeed(0);loc.setBearing(0);
            loc.setTime(wallTime);loc.setElapsedRealtimeNanos(elapsedNanos);
            manager.setTestProviderLocation(provider,loc);
        }
    }
    private void showNotification(Position point) {
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("location","模拟定位",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,ToolboxActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MockLocationService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"location").setSmallIcon(R.drawable.ic_app).setContentTitle("工具合集 · 模拟定位")
            .setContentText(point==null?"正在处理定位请求":point.name+" · "+point.coordinates()).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"停止定位",stop).build()).build();
        if (Build.VERSION.SDK_INT>=34) startForeground(1,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1,n);
        foreground=true;
    }
    private void cleanup() {
        Set<String> owned=new HashSet<>(getSharedPreferences("session",0).getStringSet("providers",Collections.emptySet()));
        owned.addAll(registered);
        Set<String> remaining=new HashSet<>();
        for (String provider:owned) {
            try {
                // Some Android versions silently ignore test-provider removal without AppOps.
                // Keep ownership for a later authorized cleanup rather than claiming success.
                if (!authorized(this)) throw new SecurityException("模拟位置授权已撤销");
                manager.removeTestProvider(provider);
                if (!authorized(this)) remaining.add(provider);
            }
            catch (IllegalArgumentException ignored) { /* Already removed. */ }
            catch (Exception e) { remaining.add(provider); }
        }
        registered.clear();
        getSharedPreferences("session",0).edit().putStringSet("providers",remaining).commit();
        if (!remaining.isEmpty()) throw new IllegalStateException("清理未完成，请重新授权模拟定位后点击停止，必要时重启手机");
    }
    private void finish(String message,int id) {
        handler.removeCallbacks(tick);running=false;
        getSharedPreferences("session",0).edit().putBoolean("active",false)
            .remove("name").remove("lat").remove("lon").commit();
        try { cleanup(); }
        catch (Exception e) { message+="\n"+e.getMessage(); }
        update(message);
        main.post(()->{
            if (!destroyed && id==latestStartId) {
                if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE);foreground=false; }
                stopSelfResult(id);
            }
        });
    }
    private void update(String text) { status=text;sendBroadcast(new Intent(CHANGE).setPackage(getPackageName())); }
    private String explain(Exception e) { return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }
    public void onDestroy() {
        destroyed=true;
        handler.removeCallbacksAndMessages(null);
        handler.post(()->{
            // An already finished instance must not overwrite a newer instance's static state.
            boolean interrupted=!registered.isEmpty();
            if (interrupted) {
                running=false;
                getSharedPreferences("session",0).edit().putBoolean("active",false).commit();
                try { cleanup();update("定位服务已停止"); }
                catch(Exception e) { update("定位服务已停止\n"+explain(e)); }
            }
            thread.quitSafely();
        });
        super.onDestroy();
    }
    public IBinder onBind(Intent intent) { return null; }
}
