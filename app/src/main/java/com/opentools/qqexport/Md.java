package com.opentools.qqexport;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v1.5 主导出器：Markdown 正文 + 同目录媒体文件。
 *
 * 产物结构：
 *   QQExport/&lt;会话名&gt;_&lt;时间戳&gt;/
 *       chat.md
 *       images/
 *           img_1.jpg  face_2.png  voice_3.amr  video_4.mp4  file_5_报告.pdf
 *
 * 媒体来源全部是 QQ 已经落盘的本地文件（PicElement.sourcePath、
 * PttElement.filePath、VideoElement.filePath、FileElement.filePath、
 * MarketFaceElement.staticFacePath），不需要联网下载。
 * 同一个文件在聊天里出现多次时只复制一份，md 里复用同一个相对路径。
 */
public final class Md {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private static final SimpleDateFormat HMS = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);

    public static final String MD_NAME = "chat.md";
    public static final String IMG_DIR = "images";

    private Md() {
    }

    /** 导出结果，给 UI 拼提示用 */
    public static final class Result {
        public String dir;
        public String mdPath;
        public int msgs;
        public int media;
        public int missing;
        public int skipped;
        public String summary() {
            StringBuilder sb = new StringBuilder("已导出 ").append(msgs).append(" 条消息");
            if (media > 0) sb.append("、").append(media).append(" 个媒体文件");
            if (missing > 0) sb.append("（").append(missing).append(" 个媒体源文件缺失）");
            if (skipped > 0) sb.append("（").append(skipped).append(" 个超出体积上限未导出）");
            return sb.toString();
        }
    }

    /** 复制过程用的临时状态 */
    private static final class Scope {
        final File dir;
        final Map<String, String> seen = new HashMap<>();
        public int media;
        public int missing;
        /** 因为超过体积上限而没复制的媒体数量 */
        public int skipped;
        int n;
        /** 是否复制媒体文件 */
        boolean on = true;
        /** 单文件体积上限，0 表示不限制 */
        long maxBytes;
        Scope(File dir) {
            this.dir = dir;
        }
    }

    /* ================= 入口 ================= */

    public static Result export(Context ctx, String sessionKey, List<Msg> list, boolean withUin) throws Exception {
        Result r = new Result();
        File root = root(ctx);
        String title = title(sessionKey, list);
        File dir = unique(root, safe(title) + "_" + STAMP.format(new Date()));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建导出目录: " + dir);
        }
        File imgDir = new File(dir, IMG_DIR);
        if (!imgDir.isDirectory() && !imgDir.mkdirs()) {
            throw new IOException("无法创建媒体目录: " + imgDir);
        }
        Scope sc = new Scope(imgDir);
        sc.on = Prefs.bool(Prefs.K_MEDIA, Prefs.D_MEDIA);
        int maxMb = Prefs.num(Prefs.K_MEDIA_MAX_MB, Prefs.D_MEDIA_MAX_MB);
        sc.maxBytes = maxMb > 0 ? maxMb * 1024L * 1024L : 0L;
        StringBuilder sb = head(sessionKey, title, list, withUin);
        String day = "";
        int idx = 0;
        for (Msg m : list) {
            String d = DAY.format(new Date(m.time * 1000));
            if (!d.equals(day)) {
                if (!day.isEmpty()) sb.append("\n---\n\n");
                day = d;
                sb.append("\n## ").append(d).append("\n\n");
            }
            who(sb, m, withUin);
            body(sb, sc, m, ++idx);
        }
        sb.append("\n---\n\n> 由 QQExport v").append(BuildConfig.VERSION_NAME)
          .append(" 生成，共 ").append(list.size()).append(" 条消息。\n");
        File md = new File(dir, MD_NAME);
        write(md, sb.toString());
        r.dir = dir.getAbsolutePath();
        r.mdPath = md.getAbsolutePath();
        r.msgs = list.size();
        r.media = sc.media;
        r.missing = sc.missing;
        r.skipped = sc.skipped;
        return r;
    }

    /* ================= 文件骨架 ================= */

    private static File root(Context ctx) {
        File dir = ctx.getExternalFilesDir("QQExport");
        if (dir == null) dir = new File(ctx.getFilesDir(), "QQExport");
        if (!dir.exists() && !dir.mkdirs()) {
            // 外层建不出来时退到内存目录，至少不要抛
            File alt = new File(ctx.getFilesDir(), "QQExport");
            alt.mkdirs();
            return alt;
        }
        return dir;
    }

    private static File unique(File parent, String name) {
        File f = new File(parent, name);
        if (!f.exists()) return f;
        for (int i = 2; i < 500; i++) {
            File g = new File(parent, name + "_" + i);
            if (!g.exists()) return g;
        }
        return new File(parent, name + "_" + System.currentTimeMillis());
    }

    private static void write(File f, String text) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes("UTF-8"));
        }
    }

    private static StringBuilder head(String key, String title, List<Msg> list, boolean withUin) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# ").append(esc(title)).append("\n\n");
        sb.append("| 项 | 值 |\n");
        sb.append("| --- | --- |\n");
        sb.append("| 会话 | `").append(key).append("` |\n");
        sb.append("| 消息数 | ").append(list.size()).append(" |\n");
        if (!list.isEmpty()) {
            sb.append("| 时间范围 | ").append(FMT.format(new Date(list.get(0).time * 1000)))
              .append(" ~ ").append(FMT.format(new Date(list.get(list.size() - 1).time * 1000))).append(" |\n");
        }
        sb.append("| 导出时间 | ").append(FMT.format(new Date())).append(" |\n");
        sb.append("| 发送者 UIN | ").append(withUin ? "显示" : "隐藏").append(" |\n");
        sb.append("| 导出工具 | QQExport v").append(BuildConfig.VERSION_NAME).append(" |\n");
        return sb;
    }

    private static void who(StringBuilder sb, Msg m, boolean withUin) {
        String name = m.isSend ? "我" : (m.nick == null || m.nick.isEmpty() ? "未知" : m.nick);
        sb.append("**").append(esc(name)).append("**");
        if (withUin && m.senderUin != null && !m.senderUin.isEmpty()) {
            sb.append("（").append(m.senderUin).append("）");
        }
        sb.append(" · ").append(HMS.format(new Date(m.time * 1000))).append("\n\n");
    }

    /* ================= 正文 ================= */

    private static void body(StringBuilder sb, Scope sc, Msg m, int idx) {
        if (m.parts.isEmpty()) {
            String t = esc(m.text);
            if (!t.isEmpty()) sb.append(t.replace("\n", "  \n")).append("\n\n");
            return;
        }
        for (Msg.Part p : m.parts) {
            switch (p.kind) {
                case Msg.P_TEXT: {
                    String t = esc(p.text);
                    if (t.trim().isEmpty()) continue;
                    sb.append(t.replace("\n", "  \n")).append("\n\n");
                    break;
                }
                case Msg.P_PIC: {
                    String name = media(sc, "img", p, null);
                    if (name != null) {
                        sb.append("![](").append(IMG_DIR).append('/').append(name).append(")\n\n");
                    } else {
                        sb.append("*[图片]*\n\n");
                    }
                    break;
                }
                case Msg.P_FACE: {
                    String name = media(sc, "face", p, null);
                    if (name != null) {
                        String alt = (p.name != null && !p.name.isEmpty()) ? p.name : stripBracket(p.text);
                        sb.append("![").append(esc(alt)).append("](")
                          .append(IMG_DIR).append('/').append(name).append(")\n\n");
                    } else {
                        sb.append(esc(p.text)).append("\n\n");
                    }
                    break;
                }
                case Msg.P_VOICE: {
                    String name = media(sc, "voice", p, null);
                    String label = stripBracket(p.text);
                    if (name != null) {
                        sb.append("[🔊 ").append(esc(label)).append("](")
                          .append(IMG_DIR).append('/').append(name).append(")\n\n");
                    } else {
                        sb.append(esc(p.text)).append("\n\n");
                    }
                    break;
                }
                case Msg.P_VIDEO: {
                    // 视频本体是链接，封面单独落盘插进去
                    String vname = media(sc, "video", p, null);
                    String tname = media(sc, "thumb", p, p.thumb);
                    if (tname != null) {
                        sb.append("![](").append(IMG_DIR).append('/').append(tname).append(")\n\n");
                    }
                    if (vname != null) {
                        sb.append("[▶ 视频").append(size(p.size)).append("](")
                          .append(IMG_DIR).append('/').append(vname).append(")\n\n");
                    } else if (tname == null) {
                        sb.append("*[视频]*\n\n");
                    }
                    break;
                }
                case Msg.P_FILE: {
                    String name = media(sc, "file", p, null);
                    String label = (p.name != null && !p.name.isEmpty()) ? p.name : stripBracket(p.text);
                    if (name != null) {
                        sb.append("[📎 ").append(esc(label)).append(size(p.size)).append("](")
                          .append(IMG_DIR).append('/').append(name).append(")\n\n");
                    } else {
                        sb.append(esc(p.text)).append(size(p.size)).append("\n\n");
                    }
                    break;
                }
                case Msg.P_REPLY: {
                    sb.append("> 回复：").append(esc(p.text)).append("\n\n");
                    break;
                }
                default: {
                    String t = p.text;
                    if (t == null || t.trim().isEmpty()) continue;
                    sb.append(esc(t)).append("\n\n");
                    break;
                }
            }
        }
    }

    /* ================= 媒体落盘 ================= */

    /**
     * 把一个片段的媒体文件复制到 images/，返回文件名（不含目录）。
     * withPath 非空时优先用它（视频封面这种同一个 Part 里要取两份文件的情况）。
     */
    private static String media(Scope sc, String kind, Msg.Part p, String withPath) {
        if (!sc.on) return null;
        File src;
        if (withPath != null) {
            src = exist(withPath);
        } else {
            src = exist(p.path);
            if (src == null) src = exist(p.thumb);
        }
        if (src == null) {
            sc.missing++;
            return null;
        }
        if (sc.maxBytes > 0 && src.length() > sc.maxBytes) {
            sc.skipped++;
            return null;
        }
        String md5 = (p.md5 == null || p.md5.length() < 8) ? null : p.md5.toLowerCase(Locale.US);
        String key = md5 != null ? "md5:" + md5 + ':' + kind : "p:" + src.getAbsolutePath() + ':' + kind;
        String had = sc.seen.get(key);
        if (had != null) return had;

        String ext = ext(src, p.name);
        String base = kind + "_" + (++sc.n);
        if ("file".equals(kind) && p.name != null && !p.name.isEmpty()) {
            String raw = safe(trimExt(p.name));
            if (!raw.isEmpty()) base = base + "_" + raw;
        }
        String name = base + '.' + ext;
        File dst = new File(sc.dir, name);
        if (!copy(src, dst)) {
            dst.delete();
            sc.missing++;
            return null;
        }
        sc.seen.put(key, name);
        sc.media++;
        return name;
    }

    private static boolean copy(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
            close(out);
        }
    }

    private static void close(Object o) {
        try {
            if (o instanceof InputStream) ((InputStream) o).close();
            else if (o instanceof OutputStream) ((OutputStream) o).close();
        } catch (Throwable ignored) {
        }
    }

    private static File exist(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty()) return null;
        try {
            File f = new File(p);
            if (!f.isFile()) return null;
            if (f.length() <= 0) return null;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** QQ 缓存里 chatimg 里的文件没有扩展名，所以按文件头判断 */
    private static String ext(File f, String nameHint) {
        String fromName = extOf(f.getName());
        if (fromName == null || fromName.isEmpty()) fromName = extOf(nameHint);
        if (fromName != null && !fromName.isEmpty() && !"tmp".equals(fromName)) return fromName;
        String magic = sniff(f);
        return magic != null ? magic : "bin";
    }

    private static String extOf(String n) {
        if (n == null) return null;
        int i = n.lastIndexOf('.');
        if (i <= 0 || i >= n.length() - 1) return null;
        String e = n.substring(i + 1).toLowerCase(Locale.US);
        if (e.length() > 5) return null;
        for (int k = 0; k < e.length(); k++) {
            char c = e.charAt(k);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return null;
        }
        return e;
    }

    private static String sniff(File f) {
        InputStream in = null;
        try {
            byte[] b = new byte[16];
            in = new FileInputStream(f);
            int n = in.read(b);
            if (n < 4) return null;
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff, b2 = b[2] & 0xff, b3 = b[3] & 0xff;
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "jpg";
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "png";
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return "gif";
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 && n >= 12) {
                if (b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
                return "webp";
            }
            if (b0 == '#' && b1 == '!' && b2 == 'A' && b3 == 'M') return "amr";
            if (b0 == '#' && b1 == '!' && b2 == 'S' && b3 == 'I') return "silk";
            if (b0 == '%' && b1 == 'P' && b2 == 'D' && b3 == 'F') return "pdf";
            if (b0 == 'P' && b1 == 'K' && b2 == 0x03 && b3 == 0x04) return "zip";
            if (n >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "mp4";
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    /* ================= 小工具 ================= */

    /** 去掉目录名里的非法字符，并限制长度（含 emoji 的昵称也不会炸） */
    private static String safe(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 48; i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 0x20) {
                b.append('_');
            } else {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    private static String trimExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static String stripBracket(String s) {
        if (s == null) return "";
        return s.replace("[", "").replace("]", "").trim();
    }

    private static String size(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return " (" + bytes + " B)";
        if (bytes < 1024L * 1024) return " (" + (bytes / 1024) + " KB)";
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, " (%.1f MB)", bytes / 1048576.0);
        return String.format(Locale.US, " (%.2f GB)", bytes / 1073741824.0);
    }

    /** Markdown 转义：只处理会破坏结构的符号，正文尽量原样可读 */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                case '`':
                case '*':
                case '_':
                case '[':
                case ']':
                case '<':
                case '>':
                    b.append('\\').append(c);
                    break;
                default:
                    b.append(c);
            }
        }
        return b.toString();
    }

    /** 单聊拿对方昵称，群聊只有群号可用 */
    private static String title(String key, List<Msg> list) {
        String uin = key;
        int bar = key.indexOf('|');
        if (bar > 0) uin = key.substring(0, bar);
        boolean troop = key.endsWith("|1");
        if (troop) return "群" + uin;
        String nick = null;
        if (list != null) {
            for (Msg m : list) {
                if (m.isSend) continue;
                String s = m.nick;
                if (s == null || s.isEmpty()) continue;
                if (s.equals(m.senderUin)) continue;
                nick = s;
                break;
            }
        }
        return (nick != null ? nick + "_" : "QQ_") + uin;
    }
}
