package com.autojump.app.update;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

final class UpdateClient {
    interface Progress { void report(int percent); }
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile HttpsURLConnection active;
    void cancel() { cancelled.set(true); HttpsURLConnection c=active; if(c!=null)c.disconnect(); }
    private void check() throws IOException { if(cancelled.get() || Thread.currentThread().isInterrupted())throw new IOException("操作已取消"); }
    private HttpsURLConnection open(String address) throws IOException {
        URL url=new URL(address);
        for(int i=0;i<6;i++) {
            check();
            if(!"https".equals(url.getProtocol()) || url.getUserInfo()!=null)throw new IOException("拒绝不安全的下载地址");
            HttpsURLConnection c=(HttpsURLConnection)url.openConnection(); active=c;
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent","Toolkit-Updater"); c.setRequestProperty("Accept-Encoding","identity");
            int status;
            try {status=c.getResponseCode();} catch(IOException e) {c.disconnect();active=null;throw e;}
            if(status==200)return c;
            String location=c.getHeaderField("Location");c.disconnect();
            if(status>=300 && status<=399 && location!=null) {url=new URL(url,location);continue;}
            throw new IOException(status==403 || status==429 ? "服务暂时限流，请稍后重试" : "网络请求失败（HTTP "+status+"）");
        }
        throw new IOException("下载重定向次数过多");
    }
    static void cleanInstalledPackages(Context context, long installedCode) {
        File dir=new File(context.getCacheDir(),"updates");File[] files=dir.listFiles();if(files==null)return;
        for(File file:files) {
            String name=file.getName();
            if(name.matches("toolkit-0\\.3\\.[1-9][0-9]{0,6}\\.apk")) {
                long code=1000+Long.parseLong(name.substring("toolkit-0.3.".length(),name.length()-4));
                if(code<=installedCode)file.delete();
            }
        }
    }
    ReleaseInfo latest() throws IOException {
        HttpsURLConnection c=open("https://api.github.com/repos/scorpionfree98/toolkit/releases/latest");
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1) { check(); if(out.size()+n>2*1024*1024)throw new IOException("发布信息过大");out.write(buf,0,n); }
            return ReleaseInfo.parse(out.toString(StandardCharsets.UTF_8.name()));
        } finally { c.disconnect(); active=null; }
    }
    File download(Context context, ReleaseInfo release, int source, Progress progress) throws Exception {
        File dir=new File(context.getCacheDir(),"updates");
        if(!dir.isDirectory() && !dir.mkdirs())throw new IOException("无法创建更新缓存");
        // Unique staging file avoids an exiting Activity's worker touching a new download.
        File part=File.createTempFile("download-",".part",dir);
        File apk=new File(dir,"toolkit-"+release.version+".apk");
        try {
            if(apk.exists()) {try {verify(context,apk,release);return apk;} catch(Exception ignored){if(!apk.delete())throw new IOException("无法清理旧安装包");}}
            if(dir.getUsableSpace()<release.size+16*1024*1024)throw new IOException("存储空间不足");
            HttpsURLConnection c=open(release.downloadUrl(source));
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part)) {
                long total=0;int n,last=-1;byte[] buf=new byte[65536];
                while((n=in.read(buf))!=-1) {
                    check();total+=n;if(total>release.size)throw new IOException("安装包大小不符");out.write(buf,0,n);
                    int percent=(int)(total*100/release.size);if(percent!=last){progress.report(percent);last=percent;}
                }
                out.getFD().sync();if(total!=release.size)throw new IOException("下载不完整，请重试");
            } finally {c.disconnect();active=null;}
            check();verify(context,part,release);check();
            if(!part.renameTo(apk))throw new IOException("无法保存安装包");
            return apk;
        } finally {part.delete();}
    }
    @SuppressWarnings("deprecation")
    static long version(PackageInfo p) {return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    @SuppressWarnings("deprecation")
    static void verify(Context context,File apk,ReleaseInfo release) throws Exception {
        if(apk.length()!=release.size)throw new IOException("安装包大小不符");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(apk)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
        if(!release.sha256.equals(hex.toString()))throw new IOException("安装包校验失败，请切换线路重新下载");
        PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo installed=pm.getPackageInfo(context.getPackageName(),flags), candidate=pm.getPackageArchiveInfo(apk.getPath(),flags);
        if(candidate==null || !context.getPackageName().equals(candidate.packageName)
            || version(candidate)!=release.code || !release.version.equals(candidate.versionName)
            || version(candidate)<=version(installed))throw new IOException("安装包包名或版本不符，拒绝覆盖");
        Signature[] old=Build.VERSION.SDK_INT>=28?installed.signingInfo.getApkContentsSigners():installed.signatures;
        Signature[] next=Build.VERSION.SDK_INT>=28?candidate.signingInfo.getApkContentsSigners():candidate.signatures;
        if(old==null || next==null || old.length==0 || !new HashSet<>(Arrays.asList(old)).equals(new HashSet<>(Arrays.asList(next))))
            throw new IOException("签名不一致，不能覆盖当前应用");
    }
}
