package com.opentools.qqexport;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Export {

    static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
    static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);

    public static String line(Msg m, boolean withUin) {
        String who = m.nick;
        if (withUin && m.isSend) who = "我";
        else if (withUin && m.senderUin != null) who = m.nick + "(" + m.senderUin + ")";
        return "[" + FMT.format(new Date(m.time * 1000)) + "] " + who + ": " + m.text;
    }

    /* ---------- 范围过滤 ---------- */

    public static List<Msg> byTime(List<Msg> all, long fromMs, long toMs) {
        List<Msg> out = new ArrayList<>();
        for (Msg m : all) {
            long ms = m.time * 1000;
            if (ms >= fromMs && ms <= toMs) out.add(m);
        }
        return out;
    }

    public static List<Msg> bySeq(List<Msg> all, long from, long to) {
        List<Msg> out = new ArrayList<>();
        long lo = Math.min(from, to), hi = Math.max(from, to);
        for (Msg m : all) {
            long s = m.seq();
            if (s >= lo && s <= hi) out.add(m);
        }
        return out;
    }

    public static List<Msg> byPicked(List<Msg> all, Set<Long> picked) {
        List<Msg> out = new ArrayList<>();
        for (Msg m : all) if (picked.contains(m.uniseq)) out.add(m);
        return out;
    }

    /* ---------- 输出 ---------- */

    public static String build(String sessionKey, List<Msg> list, boolean withUin) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 会话: ").append(sessionKey).append('\n');
        sb.append("# 条数: ").append(list.size())
          .append("  导出时间: ").append(FMT.format(new Date())).append("\n\n");
        String day = "";
        for (Msg m : list) {
            String d = DAY.format(new Date(m.time * 1000));
            if (!d.equals(day)) {
                day = d;
                sb.append("\n===== ").append(d).append(" =====\n");
            }
            sb.append(line(m, withUin)).append('\n');
        }
        return sb.toString();
    }

    public static void toClipboard(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("qq-export", text));
    }

    /** 写到 App 外部私有目录，免存储权限；返回绝对路径 */
    public static String toFile(Context ctx, String sessionKey, String text) throws Exception {
        File dir = ctx.getExternalFilesDir("QQExport");
        if (dir == null) dir = new File(ctx.getFilesDir(), "QQExport");
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("mkdirs failed: " + dir);
        String name = "QQ_" + sessionKey.replace('|', '_') + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".txt";
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return f.getAbsolutePath();
    }
}