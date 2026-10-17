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
 *
 * <p><b>v1.9 起只有一份真相：QQ 进程自己的 SharedPreferences（qqexport.xml）。</b></p>
 *
 * 老做法是「模块进程写、QQ 进程用 XSharedPreferences 读」，在 LSPosed + 分区存储上
 * 这条路基本不成立：模块的 /data/data/&lt;模块&gt;/shared_prefs 对 QQ 的 uid 不可读，
 * XSharedPreferences 拿不到值，读取一律回落到默认值——这正是「设置里选了网页，
 * 导出还是 Markdown」的根因。
 *
 * 现在设置面板跑在 QQ 进程里（{@link SettingsPanel}），写的就是 QQ 自己的 prefs，
 * 同进程里的导出逻辑立刻可见，连 reload 都不用。桌面上的模块页只做说明。
 *
 * 兼容：若某个键在 QQ prefs 里不存在，会尝试从旧的模块 prefs 读一次并写回 QQ prefs，
 * 完成一次性迁移；读不到就用默认值。
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
    /** 导出格式：html / md / txt，见 {@link Fmt} */
    public static final String K_FMT = "export_fmt";
    /** 旧开关：只有 true/false 两态，v1.9 起仅在 K_FMT 从未写过时才参考 */
    public static final String K_MD = "export_md";
    /** 导出时把图片/语音/视频/文件复制到 media/ */
    public static final String K_MEDIA = "export_media";
    /** 单个媒体文件的体积上限（MB），超过只留文字，不复制；0 表示不限制 */
    public static final String K_MEDIA_MAX_MB = "media_max_mb";
    public static final boolean D_FLOATING = true;
    public static final boolean D_MULTI = true;
    public static final boolean D_UIN = true;
    public static final boolean D_MD = true;
    /** 默认导出网页：手机上能直接翻图、点开视频 */
    public static final String D_FMT = "html";
    public static final boolean D_MEDIA = true;
    public static final int D_MEDIA_MAX_MB = 50;
    public static final int D_LIMIT = 20000;
    public static final boolean D_LOG = false;

    private Prefs() {
    }

    /* ---------------- 宿主（QQ 进程）上下文 ---------------- */

    /** QQ 进程的应用上下文；由 hook 注入，注入后所有读写都在 QQ 进程内闭环 */
    private static volatile Context host;

    /** hook 侧尽早调用 */
    public static void attach(Context c) {
        if (c == null) return;
        Context app = c;
        try {
            Context a = c.getApplicationContext();
            if (a != null) app = a;
        } catch (Throwable ignored) {
        }
        if (host == null) {
            host = app;
            L.d("prefs: 已绑定宿主上下文 " + app.getPackageName());
        }
    }

    public static boolean attached() {
        return host != null;
    }

    /** 读写入口：QQ 进程里就是 QQ 自己的 qqexport.xml */
    public static SharedPreferences sp(Context c) {
        Context base = c;
        if (base == null) base = host;
        if (base == null) return null;
        try {
            return base.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 旧代码沿用的名字：写的就是传进来的 Context 那一侧（QQ 进程内=QQ prefs） */
    public static SharedPreferences local(Context c) {
        return sp(c);
    }

    private static SharedPreferences hostPrefs() {
        Context h = host;
        if (h == null) return null;
        try {
            return h.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- 兼容旧的模块侧 prefs ---------------- */

    private static Object xsp;
    private static Method mBool;
    private static Method mInt;
    private static Method mStr;
    private static Method mReload;
    private static boolean tried;

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

    /** 老接口保留：宿主已绑定时无需重读盘 */
    public static synchronized void reload() {
        if (host != null) return;
        if (initX()) {
            try {
                mReload.invoke(xsp);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object raw(String key, int type) {
        if (!initX()) return null;
        try {
            if (type == 0) return mBool.invoke(xsp, key, false);
            if (type == 1) return mInt.invoke(xsp, key, 0);
            return mStr.invoke(xsp, key, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ---------------- 读 ---------------- */

    public static boolean bool(String key, boolean def) {
        SharedPreferences h = hostPrefs();
        if (h != null) {
            try {
                if (h.contains(key)) return h.getBoolean(key, def);
                Object v = raw(key, 0);
                if (v instanceof Boolean) {
                    h.edit().putBoolean(key, (Boolean) v).apply();   // 一次性迁移
                    return (Boolean) v;
                }
                return def;
            } catch (Throwable ignored) {
            }
        }
        Object v = raw(key, 0);
        if (v instanceof Boolean) return (Boolean) v;
        String x = xml();
        if (x == null) return def;
        Matcher m = Pattern.compile("<boolean\\s+name=\"" + Pattern.quote(key)
                + "\"\\s+value=\"(true|false)\"").matcher(x);
        return m.find() ? "true".equals(m.group(1)) : def;
    }

    public static int num(String key, int def) {
        SharedPreferences h = hostPrefs();
        if (h != null) {
            try {
                if (h.contains(key)) return h.getInt(key, def);
                Object v = raw(key, 1);
                if (v instanceof Number) {
                    h.edit().putInt(key, ((Number) v).intValue()).apply();
                    return ((Number) v).intValue();
                }
                return def;
            } catch (Throwable ignored) {
            }
        }
        Object v = raw(key, 1);
        if (v instanceof Number) return ((Number) v).intValue();
        String x = xml();
        if (x == null) return def;
        Matcher m = Pattern.compile("<int\\s+name=\"" + Pattern.quote(key)
                + "\"\\s+value=\"(\\d+)\"").matcher(x);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    public static String str(String key, String def) {
        SharedPreferences h = hostPrefs();
        if (h != null) {
            try {
                if (h.contains(key)) return h.getString(key, def);
                Object v = raw(key, 2);
                if (v != null) {
                    h.edit().putString(key, String.valueOf(v)).apply();
                    return String.valueOf(v);
                }
                return def;
            } catch (Throwable ignored) {
            }
        }
        Object v = raw(key, 2);
        if (v != null) return String.valueOf(v);
        return def;
    }

    /** 这个键到底有没有被写过（用来区分「默认值」和「用户选择」） */
    public static boolean has(String key) {
        SharedPreferences h = hostPrefs();
        if (h != null) {
            try {
                if (h.contains(key)) return true;
            } catch (Throwable ignored) {
            }
        }
        if (raw(key, 2) != null) return true;
        String x = xml();
        return x != null && x.contains("name=\"" + key + "\"");
    }

    /* ---------------- 写（QQ 进程内设置面板用） ---------------- */

    public static void putBool(Context c, String key, boolean v) {
        try {
            sp(c).edit().putBoolean(key, v).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void putInt(Context c, String key, int v) {
        try {
            sp(c).edit().putInt(key, v).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void putStr(Context c, String key, String v) {
        try {
            sp(c).edit().putString(key, v).apply();
        } catch (Throwable ignored) {
        }
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