package com.opentools.qqexport;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设置项读写。
 * 设置页（模块自身进程）用 SharedPreferences 写；QQ 进程内用 XSharedPreferences 读。
 * 两边指向同一个 qqexport.xml，所以改完设置只要让 QQ 重新走一次注入点就生效，不用重启。
 */
public final class Prefs {

    public static final String SP_NAME = "qqexport";
    public static final String PKG = "com.opentools.qqexport";

    /** 聊天页悬浮导出按钮 */
    public static final String K_FLOATING = "entry_floating";
    /** 多选栏导出按钮 */
    public static final String K_MULTI = "entry_multiselect";
    /** 导出时附带发送者 UIN */
    public static final String K_UIN = "with_uin";
    /** 每个会话最多缓存多少条 */
    public static final String K_LIMIT = "limit_per_session";
    /** 详细日志 */
    public static final String K_LOG = "verbose_log";
    /** 导出为 Markdown + 媒体文件（关闭则回退成单个 TXT） */
    public static final String K_MD = "export_md";
    /** 导出时把图片/语音/视频/文件复制到 images/ */
    public static final String K_MEDIA = "export_media";
    /** 单个媒体文件的体积上限（MB），超过只留文字，不复制；0 表示不限制 */
    public static final String K_MEDIA_MAX_MB = "media_max_mb";
    public static final boolean D_FLOATING = true;
    public static final boolean D_MULTI = true;
    public static final boolean D_UIN = true;
    public static final boolean D_MD = true;
    public static final boolean D_MEDIA = true;
    public static final int D_MEDIA_MAX_MB = 50;
    public static final int D_LIMIT = 20000;
    public static final boolean D_LOG = false;

    private Prefs() {
    }

    /** 设置页用：直接写模块自己的 prefs */
    public static SharedPreferences local(Context c) {
        return c.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    /* ---------------- 宿主进程（hook 侧）读取 ---------------- */

    private static Object xsp;
    private static Method mBool;
    private static Method mInt;
    private static Method mStr;
    private static Method mReload;
    private static boolean tried;
    private static volatile long lastSync;

    private static synchronized boolean initX() {
        if (xsp != null) return true;
        if (tried) return false;
        tried = true;
        try {
            Class<?> c = Class.forName("de.robv.android.xposed.XSharedPreferences");
            xsp = c.getConstructor(String.class, String.class).newInstance(PKG, SP_NAME);
            mBool = c.getMethod("getBoolean", String.class, boolean.class);
            mInt = c.getMethod("getInt", String.class, int.class);
            mStr = c.getMethod("getString", String.class, String.class);
            mReload = c.getMethod("reload");
            return true;
        } catch (Throwable t) {
            xsp = null;
            return false;
        }
    }

    /** 重新读盘，设置页改完立即生效 */
    public static synchronized void reload() {
        if (initX()) {
            try {
                mReload.invoke(xsp);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 读设置前轻量同步一次，设置页改完最多 1 秒生效 */
    private static void sync() {
        long now = System.currentTimeMillis();
        if (now - lastSync < 1000L) return;
        lastSync = now;
        reload();
    }

    public static boolean bool(String key, boolean def) {
        sync();
        if (initX()) {
            try {
                Object v = mBool.invoke(xsp, key, def);
                if (v instanceof Boolean) return (Boolean) v;
            } catch (Throwable ignored) {
            }
        }
        String x = xml();
        if (x == null) return def;
        Matcher m = Pattern.compile("<boolean\\s+name=\"" + Pattern.quote(key)
                + "\"\\s+value=\"(true|false)\"").matcher(x);
        return m.find() ? "true".equals(m.group(1)) : def;
    }

    public static int num(String key, int def) {
        sync();
        if (initX()) {
            try {
                Object v = mInt.invoke(xsp, key, def);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {
            }
        }
        String x = xml();
        if (x == null) return def;
        Matcher m = Pattern.compile("<int\\s+name=\"" + Pattern.quote(key)
                + "\"\\s+value=\"(\\d+)\"").matcher(x);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    public static String str(String key, String def) {
        sync();
        if (initX()) {
            try {
                Object v = mStr.invoke(xsp, key, def);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    /** 兜底：老框架把 prefs 设成 world-readable 时可直接读文件 */
    private static String cachedXml;
    private static long cachedAt;

    private static synchronized String xml() {
        try {
            File f = new File("/data/data/" + PKG + "/shared_prefs/" + SP_NAME + ".xml");
            long t = f.lastModified();
            if (cachedXml != null && t == cachedAt) return cachedXml;
            if (!f.canRead()) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            cachedXml = bo.toString("UTF-8");
            cachedAt = t;
            return cachedXml;
        } catch (Throwable t) {
            return null;
        }
    }
}
