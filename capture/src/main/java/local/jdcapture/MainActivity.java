package local.jdcapture;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import local.jdcapture.core.*;
import rikka.shizuku.Shizuku;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status, statusTitle, statusBadge, countLabel, responseStatus;
    private CaptureUi ui;
    private ViewFlipper pages;
    private final Button[] navButtons = new Button[3];
    private int selectedTab;
    private Spinner stage, modeSelector;
    private Button applicationsButton;
    private final Set<String> vpnApps = new LinkedHashSet<>();
    private ArrayList<String> pendingVpnApps;
    private String pendingVpnScope;
    private boolean pendingVpnTest;
    private EditText before, after;
    private LinearLayout column;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            CaptureService service = CaptureService.current;
            String shizuku;
            try { shizuku = Shizuku.pingBinder() ? (PrivilegeClient.granted() ? "已授权 · UID " + Shizuku.getUid() : "等待授权") : "未运行"; }
            catch (Exception e) { shizuku = "未运行"; }
            boolean running = service != null && service.running();
            boolean pendingRecovery=service==null&&!getSharedPreferences("capture_recovery",MODE_PRIVATE)
                    .getString("state","STOPPED").equals("STOPPED");
            String currentStage=service!=null?service.stage():pendingRecovery?"网络恢复待完成":"未启动";
            statusTitle.setText(running ? (service.stage().startsWith("隔离") ? "内核自检中" : "正在捕获") :
                    service!=null||pendingRecovery?currentStage:"准备就绪");
            if (service != null && !running && service.store.status.contains("失败")) statusTitle.setText("需要处理");
            statusBadge.setText(running ? "●  运行中" : "○  "+currentStage);
            countLabel.setText("本次记录  " + (service == null ? "—" : Integer.toString(service.store.count)));
            responseStatus.setText(service == null ? "尚无当前会话。先在工作台开启捕获。" :
                (service.store.rulesActive() ? "规则已启用" : "规则未启用") + " · " +
                ((service.store.latestEntry != null || service.store.latestCheckout != null) ? "已取得可预览的返回" : "等待当前阶段的返回"));
            status.setText("Shizuku：" + shizuku + "\n本机代理：" + currentStage +
                "\n" + (service == null ? "开启前先授权；HTTPS 还需安装并信任本机证书。" : service.store.status + (service.store.selfTestResult.isEmpty() ? "" : "\n" + service.store.selfTestResult) + (service.store.lastConnectionEvent.isEmpty() ? "" : "\n最近连接：" + service.store.lastConnectionEvent)));
            handler.postDelayed(this, 1500);
        }
    };
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (request, result) -> {
        try {
            if (PrivilegeClient.granted()) { PrivilegeClient.connect(this); message("Shizuku 已授权"); }
            else message("未获得 Shizuku 授权");
        } catch (RuntimeException error) { message("Shizuku 服务连接失败，请重试"); }
    };
    private final Shizuku.OnBinderReceivedListener binderListener = () -> {
        try { if (PrivilegeClient.granted()) PrivilegeClient.connect(this); }
        catch (RuntimeException error) { message("Shizuku 服务连接失败，请检查授权并重试"); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!CaptureSupport.supported(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)) {
            Toast.makeText(this, "网络抓包需要 Android 10+ 和 ARM64；其他工具不受影响。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ui = new CaptureUi(this);
        vpnApps.addAll(getPreferences(MODE_PRIVATE).getStringSet("vpnApps", Collections.emptySet()));
        if (saved != null) { pendingVpnApps=saved.getStringArrayList("pendingApps"); pendingVpnScope=saved.getString("pendingScope"); pendingVpnTest=saved.getBoolean("pendingTest"); }
        getWindow().setStatusBarColor(CaptureUi.CANVAS); getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = ui.stack(); root.setBackgroundColor(CaptureUi.CANVAS); root.setFocusableInTouchMode(true); setContentView(root); root.requestFocus();
        LinearLayout header = ui.stack(); header.setPadding(dp(22),dp(16),dp(22),dp(12)); root.addView(header);
        header.addView(ui.eyebrow("CAPTURE  /  LOCAL", CaptureUi.TEAL)); ui.space(header,6);
        TextView title = ui.text("抓包工作台",26,CaptureUi.INK); title.setTypeface(Typeface.SERIF,Typeface.BOLD); header.addView(title);
        ui.space(header,4); header.addView(ui.text("HTTP / VPN  ·  本机运行  ·  0.3.2",12,CaptureUi.MUTED));
        pages = new ViewFlipper(this); root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        buildWorkspace(); buildResponses(); buildSettings();
        CaptureService activeService = CaptureService.current;
        modeSelector.setSelection(activeService != null && activeService.running() ? (activeService.mode().equals("VPN") ? 1 : 0) : getPreferences(MODE_PRIVATE).getInt("connectionMode", 0));
        LinearLayout nav = new LinearLayout(this); nav.setPadding(dp(12),dp(10),dp(12),dp(10)); nav.setBackgroundColor(Color.WHITE);
        String[] tabs = {"工作台", "响应", "设置"};
        for (int i=0;i<tabs.length;i++) {
            final int index=i; Button item=ui.button(tabs[i],false,false); navButtons[i]=item;
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); lp.leftMargin=dp(3); lp.rightMargin=dp(3); nav.addView(item,lp);
            item.setOnClickListener(v -> selectTab(index));
        }
        root.addView(nav);
        if (saved != null) {
            modeSelector.setSelection(Math.max(0,Math.min(1,saved.getInt("mode",0))));
            stage.setSelection(Math.max(0,Math.min(2,saved.getInt("stage",0))));
            before.setText(saved.getString("before","8")); after.setText(saved.getString("after","0"));
        }
        selectTab(saved == null ? 0 : Math.max(0,Math.min(2,saved.getInt("tab",0))));
        Shizuku.addRequestPermissionResultListener(permissionListener); Shizuku.addBinderReceivedListenerSticky(binderListener);
    }
    private LinearLayout page() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content=ui.stack(); content.setPadding(dp(16),dp(4),dp(16),dp(12)); scroll.addView(content);
        pages.addView(scroll,new ViewFlipper.LayoutParams(-1,-1)); return content;
    }
    private void buildWorkspace() {
        LinearLayout page=page(); column=ui.card(page,true);
        statusBadge=ui.eyebrow("○  未运行",Color.rgb(141,215,186)); column.addView(statusBadge); ui.space(column,10);
        statusTitle=ui.text("准备就绪",26,Color.WHITE); statusTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD); column.addView(statusTitle);
        ui.space(column,12); status=ui.text("正在读取状态…",13,Color.rgb(213,230,222)); column.addView(status);
        ui.space(column,14); countLabel=ui.eyebrow("本次记录  —",Color.rgb(141,215,186)); column.addView(countLabel);
        column=ui.card(page,false); ui.title(column,"连接方式","HTTP 代理或按应用 VPN，均在手机本地处理。");
        modeSelector=new Spinner(this);
        modeSelector.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"HTTP 代理", "VPN · IPv4"}));
        column.addView(modeSelector);
        applicationsButton=ui.button("选择 VPN 应用（"+vpnApps.size()+"）",false,false);
        column.addView(applicationsButton,new LinearLayout.LayoutParams(-1,-2));
        applicationsButton.setOnClickListener(v -> selectApplications());
        modeSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> p,View v,int pos,long id){applicationsButton.setVisibility(pos==1?View.VISIBLE:View.GONE);}
            public void onNothingSelected(AdapterView<?> p){}
        });
        column.addView(ui.text("VPN 仅接入你选择的应用。首版阻断这些应用的 IPv6，HTTP/3 按阶段解密（QUIC v1）；需要系统 VPN 授权与 Shizuku。",12,CaptureUi.MUTED));
        column=ui.card(page,false); ui.title(column,"捕获范围","选择需要解密的阶段，其他 HTTPS 连接仅转发。");
        stage=new Spinner(this); stage.setBackground(ui.surface(CaptureUi.CANVAS,12,true));
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"只转发 · 不解密", "入口 · pro.m.jd.com", "结算 · m-sep.jd.com / api.m.jd.com"}) {
            @Override public View getView(int pos,View convert,ViewGroup parent) {
                TextView v=(TextView)super.getView(pos,convert,parent); v.setTextSize(14); v.setTextColor(CaptureUi.INK);
                v.setPadding(dp(12),dp(12),dp(30),dp(12)); v.setMinHeight(dp(52)); v.setSingleLine(false); return v;
            }
        };
        stage.setAdapter(adapter); column.addView(stage,new LinearLayout.LayoutParams(-1,-2));
        button("开始捕获 / 切换阶段",true,false,() -> {
            if (!PrivilegeClient.granted()) { selectTab(2); message("请先授权 Shizuku"); return; }
            if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);
            requestCapture();
        });
        button("停止代理并恢复网络",false,true,() -> startForegroundService(new Intent(this,CaptureService.class).setAction("STOP")));
        column=ui.card(page,false); ui.title(column,"首次使用","先授权 Shizuku，再做本机自检。HTTPS 解密还需要目标应用信任 CA。");
        button("前往设置与自检",() -> selectTab(2));
        TextView footer=ui.text("VPN IPv4 · 可在设置中运行独立测试应用自检",12,CaptureUi.MUTED); footer.setPadding(dp(6),dp(4),dp(6),dp(12)); page.addView(footer);
    }
    private void buildResponses() {
        LinearLayout page=page(); column=ui.card(page,false); ui.title(column,"返回修改","先捕获当前阶段的真实返回，再预览并启用规则。");
        responseStatus=ui.text("等待当前会话",13,CaptureUi.TEAL); column.addView(responseStatus); ui.space(column,16);
        LinearLayout row=new LinearLayout(this); column.addView(row);
        LinearLayout left=ui.stack(),right=ui.stack(); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); lp.rightMargin=dp(8); row.addView(left,lp);
        row.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        left.addView(ui.text("预期原值",12,CaptureUi.MUTED)); ui.space(left,6); before=ui.input("预期原值","8"); before.setId(R.id.capture_expected_before); left.addView(before);
        right.addView(ui.text("修改为",12,CaptureUi.MUTED)); ui.space(right,6); after=ui.input("修改为","0"); after.setId(R.id.capture_desired_after); right.addView(after);
        button("预览最新返回的修改",true,false,this::preview);
        button("查看最新原始返回",() -> {
            CaptureService service=service(); int selected=stage.getSelectedItemPosition();
            if(selected==0)throw new IllegalStateException("请先在工作台选择入口或结算阶段");
            String text=selected==1 ? service.store.latestEntry : service.store.latestCheckout;
            if(text==null)throw new IllegalStateException("尚未捕获当前阶段的可识别响应"); detail("原始返回",text);
        });
        button("停用全部改写规则",false,true,() -> { service().store.disableRules(); message("改写已停用，捕获继续"); });
        ui.space(column,8); column.addView(ui.text("切换阶段会停用规则。只修改目标字段；匹配失败时保留原始返回。",12,CaptureUi.MUTED));
        column=ui.card(page,false); ui.title(column,"请求记录","导出本机保存的请求、返回与事件，便于继续排查。");
        button("导出请求与响应日志 ZIP",() -> new AlertDialog.Builder(this).setTitle("导出私有日志")
            .setMessage("日志可能含 Cookie、票据和地址，请仅保存到可信位置。证书私钥不会导出。")
            .setPositiveButton("选择保存位置",(d,w) -> exportDocument(11,"application/zip","jd-capture-"+System.currentTimeMillis()+".zip"))
            .setNegativeButton("取消",null).show());
        button("清理抓包日志",false,true,() -> new AlertDialog.Builder(this).setTitle("清理抓包日志")
            .setMessage("请先停止抓包并导出需要保留的记录。只删除抓包记录和事件日志，保留证书、自动任务与工具配置。")
            .setPositiveButton("确认清理",(d,w) -> startForegroundService(new Intent(this,CaptureService.class).setAction("CLEAR_LOGS")))
            .setNegativeButton("取消",null).show());
        column.addView(ui.text("抓包记录上限 128 MiB / 2000 个文件，事件日志上限 1 MiB。达到上限停止保存，不自动删除证据。",12,CaptureUi.MUTED));
        ui.space(column,8); column.addView(ui.text("结算捕获阶段的最终提交会保存到本机并返回 409。当前版本不自动或定时发送。",12,CaptureUi.MUTED));
        column=ui.card(page,false); ui.title(column,"活动入口","从官方入口进入页面后，按实际阶段选择捕获范围。");
        button("打开官方活动入口",() -> {
            Intent open=new Intent(Intent.ACTION_VIEW,Uri.parse("https://pro.m.jd.com"+ResponseRules.ENTRY_PATH+"?categoryId=331801&serviceType=jdxc&skuId="+ResponseRules.SKU));
            open.setPackage("com.jingdong.app.mall"); startActivity(open);
        });
    }
    private void buildSettings() {
        LinearLayout page=page(); column=ui.card(page,false); ui.title(column,"连接方式","在工作台选择 HTTP 或 VPN，切换时会先停止旧模式。");
        column.addView(ui.text("●  HTTP 代理    已实现",14,CaptureUi.TEAL)); ui.space(column,10);
        column.addView(ui.text("●  VPN / IPv4    已实现 · 支持链路自检",14,CaptureUi.TEAL));
        column=ui.card(page,false); ui.title(column,"01  授权与诊断","Shizuku 启动并授权后，由 App 自动管理内核。");
        button("授权 Shizuku",true,false,() -> {
            if(!Shizuku.pingBinder()){message("请先打开 Shizuku，通过无线调试启动服务，再回来授权。");return;}
            if(PrivilegeClient.granted()){PrivilegeClient.connect(this);message("已授权");}else Shizuku.requestPermission(100);
        });
        button("内置内核自检（不改手机代理）",() -> {
            if(!PrivilegeClient.granted()){message("请先授权 Shizuku");return;}
            startForegroundService(new Intent(this,CaptureService.class).setAction("SELFTEST")); selectTab(0);
        });
        button("VPN 链路自检（仅测试应用）",() -> {
            if(!PrivilegeClient.granted())throw new IllegalStateException("请先授权 Shizuku");
            if(getPackageManager().checkSignatures(getPackageName(),"local.jdcapture.probe")!=android.content.pm.PackageManager.SIGNATURE_MATCH)
                throw new IllegalStateException("请先安装随附的 VPN 测试应用");
            CaptureService current=CaptureService.current;
            if(VpnController.hasVpn(this) && !(current!=null && current.running() && current.mode().equals("VPN")))
                throw new IllegalStateException("请先停止已有 VPN");
            pendingVpnApps=new ArrayList<>(Collections.singletonList("local.jdcapture.probe"));pendingVpnScope="TUNNEL";pendingVpnTest=true;
            Intent consent=android.net.VpnService.prepare(this);
            if(consent!=null)startActivityForResult(consent,12);else dispatchVpnTest();
        });
        button("查看内核日志",() -> io.execute(() -> {
            try{String text=PrivilegeClient.await(this).runtimeLog();runOnUiThread(() -> {if(!isFinishing()&&!isDestroyed())detail("内核日志",text);});}
            catch(Exception e){runOnUiThread(() -> {if(!isFinishing()&&!isDestroyed())message("读取失败："+e.getClass().getSimpleName());});}
        }));
        button("查看后台日志占用",() -> runtimeStorage(false));
        button("清理已结束的后台会话",false,true,() -> new AlertDialog.Builder(this)
            .setTitle("清理后台会话")
            .setMessage("请先停止抓包并保留所需日志。仅删除已结束的会话；运行中、网络恢复待完成和状态不明的会话会保留。")
            .setNegativeButton("取消",null).setPositiveButton("清理",(d,w)->runtimeStorage(true)).show());
        column.addView(ui.text("后台日志每份最多 2 MiB，会话总预算 64 MiB / 32 个；容量不足时拒绝启动，不自动删除。",12,CaptureUi.MUTED));
        column=ui.card(page,false); ui.title(column,"02  HTTPS 证书","复用本机 CA。仅导出公开证书，私钥不随日志导出。");
        button("导出本机 CA 证书",() -> exportDocument(10,"application/pkix-cert","jd-capture-ca.cer"));
        button("打开系统安全设置安装 CA",() -> startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
        ui.space(column,8); column.addView(ui.text("安装用户 CA 不代表目标应用一定信任它。遇到 TLS 错误，请先停止捕获并查看日志。",12,CaptureUi.MUTED));
        column=ui.card(page,false); ui.title(column,"关于","入口抓包台 · 0.3.2");
        column.addView(ui.text("本机内置 mitmproxy · ARM64\n无需电脑常驻或 Termux。",13,CaptureUi.MUTED));
    }
    private void selectTab(int index) {
        selectedTab=index; pages.setDisplayedChild(index);
        for(int i=0;i<navButtons.length;i++){
            Button item=navButtons[i]; if(item==null)continue;
            item.setBackground(ui.surface(i==index?CaptureUi.SOFT:Color.WHITE,12,false));
            item.setTextColor(i==index?CaptureUi.TEAL:CaptureUi.MUTED); item.setSelected(i==index);
            item.setContentDescription(item.getText()+(i==index?"，当前页面":"，切换页面"));
        }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (ui == null) { super.onSaveInstanceState(out); return; }
        out.putInt("tab",selectedTab);out.putInt("stage",stage.getSelectedItemPosition());out.putInt("mode",modeSelector.getSelectedItemPosition());
        out.putStringArrayList("pendingApps",pendingVpnApps);out.putString("pendingScope",pendingVpnScope);out.putBoolean("pendingTest",pendingVpnTest);
        out.putString("before",before.getText().toString());out.putString("after",after.getText().toString());super.onSaveInstanceState(out);
    }
    private void selectApplications() {
        try {
            android.content.pm.PackageManager pm=getPackageManager();
            List<android.content.pm.ResolveInfo> results=pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);
            TreeMap<String,String> byPackage=new TreeMap<>();
            for(android.content.pm.ResolveInfo r:results){
                android.content.pm.ApplicationInfo info=r.activityInfo.applicationInfo;
                if(info.uid>=10000 && info.uid!=getApplicationInfo().uid && !info.packageName.equals("moe.shizuku.privileged.api"))
                    byPackage.put(info.packageName,info.loadLabel(pm).toString());
            }
            List<String> packages=new ArrayList<>(byPackage.keySet());
            packages.sort((a,b)->byPackage.get(a).compareToIgnoreCase(byPackage.get(b)));
            String[] names=new String[packages.size()];boolean[] checked=new boolean[packages.size()];
            for(int i=0;i<packages.size();i++){String pkg=packages.get(i);names[i]=byPackage.get(pkg)+"\n"+pkg;checked[i]=vpnApps.contains(pkg);}
            new AlertDialog.Builder(this).setTitle("选择经过 VPN 的应用")
                .setMultiChoiceItems(names,checked,(d,i,selected)->checked[i]=selected)
                .setPositiveButton("保存",(d,w)->{
                    vpnApps.clear();for(int i=0;i<packages.size();i++)if(checked[i])vpnApps.add(packages.get(i));
                    getPreferences(MODE_PRIVATE).edit().putStringSet("vpnApps",new HashSet<>(vpnApps)).apply();
                    applicationsButton.setText("选择 VPN 应用（"+vpnApps.size()+"）");
                }).setNegativeButton("取消",null).show();
        }catch(Exception e){message("读取应用列表失败："+e.getClass().getSimpleName());}
    }
    private void requestCapture() throws Exception {
        pendingVpnTest=false;
        String scope=new String[]{"TUNNEL","ENTRY","CHECKOUT"}[stage.getSelectedItemPosition()];
        if(modeSelector.getSelectedItemPosition()==0){dispatchCapture("HTTP",scope,null);return;}
        VpnController.validateApps(this,vpnApps);
        CaptureService current=CaptureService.current;
        if(VpnController.hasVpn(this) && !(current!=null && current.running() && current.mode().equals("VPN"))){
            message("检测到已有 VPN，请先停止它；本应用不会自动替换其他 VPN。");return;
        }
        Intent consent=android.net.VpnService.prepare(this);
        if(consent!=null){pendingVpnApps=new ArrayList<>(vpnApps);pendingVpnScope=scope;startActivityForResult(consent,12);}
        else dispatchCapture("VPN",scope,new ArrayList<>(vpnApps));
    }
    private void dispatchVpnTest(){
        modeSelector.setSelection(1);
        startForegroundService(new Intent(this,CaptureService.class).setAction("SELFTEST").putExtra("mode","VPN").putExtra("scope","TUNNEL")
            .putStringArrayListExtra("apps",new ArrayList<>(Collections.singletonList("local.jdcapture.probe"))));
        pendingVpnTest=false;pendingVpnApps=null;pendingVpnScope=null;selectTab(0);
    }
    private void dispatchCapture(String mode,String scope,ArrayList<String> apps){
        getPreferences(MODE_PRIVATE).edit().putInt("connectionMode",mode.equals("VPN")?1:0).apply();
        startForegroundService(new Intent(this,CaptureService.class).setAction("START").putExtra("mode",mode).putExtra("scope",scope).putStringArrayListExtra("apps",apps));
    }
    private CaptureService service() { CaptureService s = CaptureService.current; if (s == null) throw new IllegalStateException("请先启动捕获"); return s; }
    private void preview() throws Exception {
        String selected = stage.getSelectedItemPosition() == 1 ? "entry" : stage.getSelectedItemPosition() == 2 ? "checkout" : "other";
        CaptureStore store = service().store; String original = selected.equals("entry") ? store.latestEntry : store.latestCheckout;
        if (original == null || selected.equals("other")) throw new IllegalStateException("请先选择阶段并捕获当前响应");
        int a = Integer.parseInt(before.getText().toString()), b = Integer.parseInt(after.getText().toString());
        ResponseRules.Preview preview = ResponseRules.preview(selected, original, a, b);
        new AlertDialog.Builder(this).setTitle("修改预览")
            .setMessage("字段 " + preview.field() + "：" + a + " → " + b + "\n只改该目标字段，其余返回保持不变。\n应用后持续匹配，直至停用或切换阶段。")
            .setPositiveButton("应用到后续响应", (dialog, which) -> {
                try {
                    String current = selected.equals("entry") ? store.latestEntry : store.latestCheckout;
                    if (!Objects.equals(current, original)) throw new IllegalStateException("响应已更新，请重新预览");
                    store.arm(selected, a, b); message("规则已启用，重新加载官方页面后生效");
                } catch (Exception e) { message(e.getMessage()); }
            }).setNegativeButton("取消", null).show();
    }
    private interface Action { void run() throws Exception; }
    private void runtimeStorage(boolean clear) {
        io.execute(()->{
            String result;
            try {
                IProxyControl c=PrivilegeClient.await(this);
                org.json.JSONObject r=new org.json.JSONObject(clear?c.clearRuntimeHistory():c.runtimeStorage());
                result="会话："+r.getInt("sessions")+"\n占用："+r.getLong("bytes")/1024+" KiB\n可清理："+r.getInt("removable")+"\n受保护："+r.getInt("protected")+(clear?"\n已清理："+r.getInt("removed"):"");
            } catch(Exception e) { result="操作未完成，请先停止抓包并确认 Shizuku 可用。错误："+e.getClass().getSimpleName(); }
            final String text=result;
            runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())detail("后台日志",text);});
        });
    }
    private void button(String text, Action action) { button(text,false,false,action); }
    private void button(String text, boolean primary, boolean destructive, Action action) {
        Button b=ui.button(text,primary,destructive);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);column.addView(b,p);
        b.setOnClickListener(v -> {try{action.run();}catch(Exception e){message(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}});
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    private void message(String text) { new AlertDialog.Builder(this).setMessage(text).setPositiveButton("知道了", null).show(); }
    private void detail(String title, String text) { TextView v = new TextView(this); v.setTextIsSelectable(true); v.setTextColor(CaptureUi.INK); v.setTextSize(13); v.setTypeface(Typeface.MONOSPACE); v.setPadding(dp(20),dp(12),dp(20),dp(12)); v.setText(text.length() > 100000 ? text.substring(0, 100000) + "\n显示截断，完整内容请导出日志" : text); ScrollView scroll = new ScrollView(this); scroll.addView(v); new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("关闭", null).show(); }
    private void exportDocument(int code, String type, String name) { Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name); startActivityForResult(intent, code); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==12){
            if(result==RESULT_OK && pendingVpnApps!=null && pendingVpnScope!=null){if(pendingVpnTest)dispatchVpnTest();else dispatchCapture("VPN",pendingVpnScope,pendingVpnApps);}
            else message("VPN 授权已取消，未切换当前连接方式。");
            pendingVpnApps=null;pendingVpnScope=null;pendingVpnTest=false;return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        io.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("EXPORT_UNAVAILABLE");
                if (request == 10) { CertificateAuthority ca = new CertificateAuthority(new File(getFilesDir(), "ca")); out.write(ca.publicCertificate()); }
                else new CaptureStore(this).export(out);
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) message("已导出"); });
            } catch (Exception e) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) message("导出失败：" + e.getClass().getSimpleName()); }); }
        });
    }
    @Override protected void onStart() {
        super.onStart();
        if (ui != null) { handler.removeCallbacks(refresh); handler.post(refresh); }
    }
    @Override protected void onStop() {
        handler.removeCallbacks(refresh); super.onStop();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh); Shizuku.removeRequestPermissionResultListener(permissionListener); Shizuku.removeBinderReceivedListener(binderListener);
        io.shutdown(); super.onDestroy();
    }
}
