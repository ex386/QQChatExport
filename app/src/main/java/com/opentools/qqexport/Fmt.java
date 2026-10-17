package com.opentools.qqexport;

import android.content.Context;

/**
 * 导出格式选项。
 *
 * 三种：
 *   html —— 单文件网页 + 媒体目录（整体 zip），图片/语音/视频/文件在网页里直接点击播放或下载；
 *   md   —— Markdown 正文 + images/ 目录（v1.5 行为）；
 *   txt  —— 纯文本，只含时间、昵称、正文。
 *
 * 默认 html：TXT/Markdown 在手机上看不了图、点不开视频，网页版才是一次导出就能翻看的形态。
 */
public final class Fmt {

    public static final String HTML = "html";
    public static final String MD = "md";
    public static final String TXT = "txt";

    /** 顺序与 {@link #LABELS} 一一对应 */
    public static final String[] KEYS = {HTML, MD, TXT};
    public static final String[] LABELS = {"网页 (zip)", "Markdown", "纯文本"};

    /** QQ 进程内本次选择：改完立刻在同一个进程里生效，不用等 prefs 同步 */
    private static String session;

    private Fmt() {
    }

    public static String get(Context c) {
        if (session != null) return session;
        String v = null;
        try {
            v = Prefs.str(Prefs.K_FMT, null);
        } catch (Throwable ignored) {
        }
        if (v == null || v.isEmpty()) {
            // 老版本只有「导出为 Markdown」开关，没设置过新键时沿用它的语义
            try {
                v = Prefs.bool(Prefs.K_MD, Prefs.D_MD) ? MD : TXT;
            } catch (Throwable t) {
                v = Prefs.D_FMT;
            }
        }
        for (String k : KEYS) if (k.equals(v)) return v;
        return Prefs.D_FMT;
    }

    public static void set(Context c, String v) {
        if (v == null) return;
        session = v;
        try {
            Prefs.local(c).edit().putString(Prefs.K_FMT, v).apply();
        } catch (Throwable ignored) {
        }
    }

    public static int index(String k) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(k)) return i;
        return 0;
    }

    /** 主按钮文案 */
    public static String action(String k) {
        if (MD.equals(k)) return "导出 Markdown";
        if (TXT.equals(k)) return "导出 TXT";
        return "导出网页";
    }
}
