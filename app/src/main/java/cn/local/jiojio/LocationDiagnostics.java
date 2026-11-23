package cn.local.jiojio;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import java.util.Locale;

/** Read-only evidence. Does not request a fix, alter providers or claim app acceptance. */
final class LocationDiagnostics {
    static String read(Context context) {
        StringBuilder out = new StringBuilder("模拟定位授权：")
                .append(MockLocationService.authorized(context) ? "已授权" : "未授权")
                .append("\n注入服务：").append(MockLocationService.running ? "运行中" : "未运行");
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) return out.append("\n系统定位服务不可用").toString();
        Location expected = null;
        SharedPreferences session = context.getSharedPreferences("session", 0);
        if (MockLocationService.running) {
            try {
                Position target = Position.parse("诊断", session.getString("lat", null), session.getString("lon", null));
                expected = new Location("expected");
                expected.setLatitude(target.latitude); expected.setLongitude(target.longitude);
            } catch (RuntimeException ignored) { out.append("\n无法读取当前注入目标"); }
        }
        for (String provider : new String[]{"gps", "network", "fused", "passive"}) {
            out.append("\n\n").append(provider).append("：");
            try {
                if (!manager.getAllProviders().contains(provider)) { out.append("系统未提供"); continue; }
                out.append(manager.isProviderEnabled(provider) ? "启用" : "关闭");
                Location last = manager.getLastKnownLocation(provider);
                if (last == null) { out.append("，无可读缓存"); continue; }
                long age = SystemClock.elapsedRealtimeNanos() - last.getElapsedRealtimeNanos();
                out.append("\n缓存年龄：").append(age < 0 ? "未知" : String.format(Locale.ROOT, "%.1f 秒", age / 1e9));
                out.append("；模拟标记：").append(last.isFromMockProvider() ? "有" : "无");
                if (expected != null) out.append("\n与注入目标相距：")
                        .append(String.format(Locale.ROOT, "%.1f 米", last.distanceTo(expected)));
            } catch (SecurityException denied) { out.append("，缺少本应用的位置读取权限"); }
            catch (RuntimeException unavailable) { out.append("，读取不可用"); }
        }
        return out.append("\n\n这是系统缓存读回，不代表目标应用已采纳。不同应用可能使用独立定位 SDK、Wi-Fi/基站、缓存或拒绝模拟位置。坐标按 WGS-84 注入；地图显示坐标可能不同。")
                .toString();
    }
}
