package com.autojump.app.update;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.concurrent.*;

/** User-initiated update. No work is scheduled by Launcher or rule execution. */
public final class UpdateActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private UpdateClient client;
    private ReleaseInfo release;
    private File apk;
    private TextView status;
    private Button check,download,install,cancel;
    private Spinner source;
    private ProgressBar progress;
    private boolean busy, awaitingPermission;
    private long installedCode;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setTitle("应用更新");
        if(getActionBar()!=null)getActionBar().setDisplayHomeAsUpEnabled(true);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*getResources().getDisplayMetrics().density);page.setPadding(pad,pad,pad,pad);page.setBackgroundColor(Color.rgb(246,247,242));
        ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
        String current="未知";
        try {android.content.pm.PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0);current=p.versionName;installedCode=UpdateClient.version(p);}catch(Exception ignored){}
        text(page,"应用更新",26);text(page,"当前版本："+current,16);
        text(page,"从 GitHub 官方 Release 检查正式版。覆盖升级保留规则和设置，无需恢复备份。",14);
        status=text(page,"点击检查更新",15);
        check=button(page,"检查更新",this::checkLatest);
        text(page,"安装包下载线路",15);
        source=new Spinner(this);source.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"GitHub 官方（默认）","GHFast 加速（第三方）","GH-Proxy 加速（第三方）"}));page.addView(source);
        text(page,"加速服务可用性因网络而异。仅代理公开安装包，不接收你的规则；版本和 SHA-256 从 GitHub 官方获取。官方查询失败时请更换网络后重试。",13);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);page.addView(progress);
        download=button(page,"下载更新",this::downloadUpdate);install=button(page,"安装更新",this::installUpdate);
        cancel=button(page,"取消当前操作",()->{if(client!=null)client.cancel();status.setText("正在取消…");cancel.setEnabled(false);});
        button(page,"查看 GitHub 发布页",()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(ReleaseInfo.REPO+"/releases/latest")));}catch(Exception e){status.setText("未找到浏览器");}});
        text(page,"下载可取消；退出页面会停止当前操作。安装需要系统确认，首次使用需允许本应用安装更新。",13);refresh();
    }
    private TextView text(LinearLayout page,String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(Color.rgb(27,43,49));v.setPadding(0,12,0,12);page.addView(v);return v;}
    private Button button(LinearLayout page,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextColor(Color.rgb(20,91,76));b.setOnClickListener(v->action.run());page.addView(b);return b;}
    private void refresh(){check.setEnabled(!busy);source.setEnabled(!busy);download.setEnabled(!busy&&release!=null&&release.code>installedCode);install.setEnabled(!busy&&apk!=null);cancel.setEnabled(busy);}
    private void ui(Runnable r){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())r.run();});}
    private interface Work {void run() throws Exception;}
    private void work(Work task){busy=true;client=new UpdateClient();refresh();worker.execute(()->{try{task.run();}catch(Exception e){ui(()->status.setText("操作未完成："+explain(e)));}finally{ui(()->{busy=false;refresh();});}});}
    private String explain(Exception e){if(e instanceof java.net.SocketTimeoutException)return "网络超时，请重试或切换下载线路";if(e instanceof java.net.UnknownHostException)return "无法连接服务器，请检查网络";return e.getMessage()==null?"请检查网络后重试":e.getMessage();}
    private void checkLatest(){
        release=null;apk=null;status.setText("正在查询 GitHub 官方 Release…");progress.setProgress(0);
        work(()->{UpdateClient.cleanInstalledPackages(this,installedCode);ReleaseInfo info=client.latest();ui(()->{release=info;status.setText(info.code>installedCode?"发现新版本 "+info.version+" · "+(info.size/1024/1024)+" MiB\n请选择线路并下载":"当前已是最新正式版（GitHub："+info.version+"）");});});
    }
    private void downloadUpdate(){
        if(release==null)return;
        new AlertDialog.Builder(this).setTitle("下载 "+release.version).setMessage("安装包约 "+release.size/1024/1024+" MiB，可能使用移动数据。下载后校验文件和签名，再由你确认安装。")
            .setNegativeButton("取消",null).setPositiveButton("下载",(d,w)->{
                final ReleaseInfo selected=release;final int route=source.getSelectedItemPosition();apk=null;progress.setProgress(0);status.setText("正在下载…");
                work(()->{File result=client.download(this,selected,route,percent->ui(()->{progress.setProgress(percent);status.setText("正在下载："+percent+"%");}));ui(()->{apk=result;status.setText("校验通过，点击安装更新");});});
            }).show();
    }
    private void installUpdate(){
        if(apk==null || release==null)return;
        if(!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("允许安装更新").setMessage("请在系统页面允许“工具合集”安装应用，返回后继续安装。")
                .setNegativeButton("取消",null).setPositiveButton("前往设置",(d,w)->{try{awaitingPermission=true;startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}catch(Exception e){awaitingPermission=false;status.setText("无法打开安装授权设置");}}).show();return;
        }
        File selected=apk;ReleaseInfo info=release;status.setText("正在复核安装包…");
        work(()->{UpdateClient.verify(this,selected,info);ui(()->{try{
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",selected);
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("update",uri));startActivity(intent);status.setText("已交给系统安装器，请确认更新；取消后可再次点击安装");
        }catch(Exception e){status.setText("无法打开系统安装器，请检查安装权限");}});});
    }
    @Override protected void onResume(){super.onResume();if(awaitingPermission){awaitingPermission=false;if(getPackageManager().canRequestPackageInstalls())installUpdate();else status.setText("尚未允许安装更新，可再次点击安装");}}
    @Override public boolean onOptionsItemSelected(android.view.MenuItem item){if(item.getItemId()==android.R.id.home){onBackPressed();return true;}return super.onOptionsItemSelected(item);}
    @Override public void onBackPressed(){if(busy)new AlertDialog.Builder(this).setTitle("停止并退出？").setMessage("当前检查或下载将取消，现有应用和配置不受影响。").setNegativeButton("继续",null).setPositiveButton("停止并退出",(d,w)->finish()).show();else super.onBackPressed();}
    @Override protected void onDestroy(){if(client!=null)client.cancel();worker.shutdownNow();super.onDestroy();}
}
