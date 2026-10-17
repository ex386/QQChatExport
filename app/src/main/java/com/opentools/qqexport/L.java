package com.opentools.qqexport;

/**
 * 日志出口。用反射调用 XposedBridge.log，避免模块自身进程（没有 Xposed 类）加载时崩掉。
 */
public final class L {

    private static volatile boolean verbose = false;

    private L() {
    }

    public static void setVerbose(boolean v) {
        verbose = v;
    }

    public static void i(String msg) {
        if (verbose) out(false, msg);
    }

    public static void e(String msg) {
        out(true, msg);
    }

    public static void e(Throwable t) {
        if (t == null) return;
        out(true, android.util.Log.getStackTraceString(t));
    }

    private static void out(boolean err, String msg) {
        android.util.Log.println(err ? android.util.Log.ERROR : android.util.Log.INFO, "QQExport", msg);
        try {
            Class<?> xb = Class.forName("de.robv.android.xposed.XposedBridge");
            xb.getMethod("log", String.class).invoke(null, "[QQExport]" + (err ? "[E] " : "[I] ") + msg);
        } catch (Throwable ignored) {
        }
    }
}
