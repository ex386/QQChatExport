package com.opentools.qqexport;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v1.6 主导出器：单页 HTML 加 media/ 目录，再整体打成一个 zip。
 *
 * 产物结构：
 *   QQExport/会话名_时间戳/
 *       index.html   正文全部内联，样式内置，深/浅色自适应
 *       media/       图片、表情、语音、视频、视频封面、文件
 *   QQExport/会话名_时间戳.zip   上面整个目录，方便一次拷走
 *
 * 为什么换成网页：手机上 TXT 看不了图，Markdown 里的图片/语音也要靠别的 App 渲染，
 * 视频更是只有一个链接。HTML 里 img/audio/video 都是浏览器原生控件，
 * 解压后点开 index.html 就能翻图、听语音、放视频、下载文件。
 */
public final class Html {

    public static final String HTML_NAME = "index.html";
    public static final String MEDIA_DIR = "media";

    /** 生成 HTML 时用的换行与引号，避免在源码里堆转义字符 */
    static final String NL = String.valueOf((char) 10);
    static final String DQ = String.valueOf((char) 34);

    private Html() {
    }

    public static final class Result {
        /** 解压后的目录 */
        public String dir;
        /** index.html 绝对路径 */
        public String htmlPath;
        /** 打包出的 zip；打包失败时为 null */
        public String zipPath;
        public int msgs;
        public int media;
        public int missing;
        public int skipped;

        /** 提示里优先给能整体拷走的 zip */
        public String path() {
            return zipPath != null ? zipPath : htmlPath;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder("已导出 ").append(msgs).append(" 条消息");
            if (media > 0) sb.append("、").append(media).append(" 个媒体文件");
            if (missing > 0) sb.append("（").append(missing).append(" 个媒体源文件缺失）");
            if (skipped > 0) sb.append("（").append(skipped).append(" 个超出体积上限未导出）");
            return sb.toString();
        }
    }

    /* ================= 入口 ================= */

    public static Result export(Context ctx, String sessionKey, List<Msg> list, boolean withUin)
            throws Exception {
        Result r = new Result();
        File root = Md.root(ctx);
        String title = Md.title(sessionKey, list);
        File dir = Md.unique(root, Md.safe(title) + "_" + Md.STAMP.format(new Date()));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建导出目录: " + dir);
        }
        File mediaDir = new File(dir, MEDIA_DIR);
        if (!mediaDir.isDirectory() && !mediaDir.mkdirs()) {
            throw new IOException("无法创建媒体目录: " + mediaDir);
        }

        Media.reset();
        Md.Scope sc = new Md.Scope(ctx, mediaDir);
        sc.on = Prefs.bool(Prefs.K_MEDIA, Prefs.D_MEDIA);
        int maxMb = Prefs.num(Prefs.K_MEDIA_MAX_MB, Prefs.D_MEDIA_MAX_MB);
        sc.maxBytes = maxMb > 0 ? maxMb * 1024L * 1024L : 0L;

        StringBuilder sb = new StringBuilder(16384);
        head(sb, sessionKey, title, list, withUin);

        String day = "";
        int idx = 0;
        for (Msg m : list) {
            String d = Md.DAY.format(new Date(m.time * 1000));
            if (!d.equals(day)) {
                day = d;
                sb.append("<div class='day'><span>").append(h(d)).append("</span></div>").append(NL);
            }
            row(sb, sc, m, ++idx, withUin);
        }
        sb.append("</main>").append(NL);
        sb.append("<footer>由 QQExport v").append(BuildConfig.VERSION_NAME)
          .append(" 生成，共 ").append(list.size()).append(" 条消息。")
          .append("图片/语音/视频/文件都在 ").append(MEDIA_DIR)
          .append("/ 目录里，请与 index.html 同级解压。</footer>").append(NL)
          .append("</body>").append(NL).append("</html>").append(NL);

        File html = new File(dir, HTML_NAME);
        Md.write(html, sb.toString());
        r.dir = dir.getAbsolutePath();
        r.htmlPath = html.getAbsolutePath();
        r.msgs = list.size();
        r.media = sc.media;
        r.missing = sc.missing;
        r.skipped = sc.skipped;

        File zip = new File(root, dir.getName() + ".zip");
        try {
            pack(dir, zip);
            r.zipPath = zip.getAbsolutePath();
        } catch (Throwable t) {
            L.d("html: 打包失败 " + t);
            r.zipPath = null;
        }
        L.d("html: " + r.summary() + " zip=" + r.zipPath + " " + Media.summary());
        return r;
    }

    /* ================= 骨架 ================= */

    static void head(StringBuilder sb, String key, String title, List<Msg> list, boolean withUin) {
        sb.append("<!DOCTYPE html>").append(NL);
        sb.append("<html lang='zh-CN'>").append(NL).append("<head>").append(NL);
        sb.append("<meta charset='utf-8'>").append(NL);
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>").append(NL);
        sb.append("<title>").append(h(title)).append("</title>").append(NL);
        sb.append("<style>").append(NL).append(CSS).append(NL).append("</style>").append(NL);
        sb.append("</head>").append(NL).append("<body>").append(NL);
        sb.append("<header>").append(NL).append("<h1>").append(h(title)).append("</h1>").append(NL);
        sb.append("<div class='meta'>").append(NL);
        sb.append("<span>会话 ").append(h(key)).append("</span>");
        sb.append("<span>").append(list.size()).append(" 条消息</span>");
        if (!list.isEmpty()) {
            sb.append("<span>").append(Md.FMT.format(new Date(list.get(0).time * 1000)))
              .append(" ~ ")
              .append(Md.FMT.format(new Date(list.get(list.size() - 1).time * 1000)))
              .append("</span>");
        }
        sb.append("<span>导出 ").append(Md.FMT.format(new Date())).append("</span>");
        sb.append("<span>QQExport v").append(BuildConfig.VERSION_NAME).append("</span>");
        sb.append("<span>UIN ").append(withUin ? "显示" : "隐藏").append("</span>").append(NL);
        sb.append("</div>").append(NL).append("</header>").append(NL).append("<main>").append(NL);
    }

    static void row(StringBuilder sb, Md.Scope sc, Msg m, int idx, boolean withUin) {
        String name = m.isSend ? "我"
                : (m.nick == null || m.nick.isEmpty() ? "未知" : m.nick);
        sb.append("<div class='m").append(m.isSend ? " me" : "").append("'>").append(NL);
        sb.append("<div class='who'><b>").append(h(name)).append("</b>");
        if (withUin && m.senderUin != null && !m.senderUin.isEmpty()) {
            sb.append("<i>").append(h(m.senderUin)).append("</i>");
        }
        sb.append("<time>").append(Md.HMS.format(new Date(m.time * 1000)))
          .append("</time></div>").append(NL);
        sb.append("<div class='bubble'>").append(NL);
        body(sb, sc, m, idx);
        sb.append("</div>").append(NL).append("</div>").append(NL);
    }

    /* ================= 正文 ================= */

    static void body(StringBuilder sb, Md.Scope sc, Msg m, int idx) {
        if (m.parts.isEmpty()) {
            if (m.text != null && !m.text.trim().isEmpty()) {
                sb.append("<p>").append(br(m.text)).append("</p>").append(NL);
            }
            return;
        }
        for (Msg.Part p : m.parts) {
            switch (p.kind) {
                case Msg.P_TEXT: {
                    if (p.text == null || p.text.trim().isEmpty()) continue;
                    sb.append("<p>").append(br(p.text)).append("</p>").append(NL);
                    break;
                }
                case Msg.P_PIC: {
                    String n = Md.media(sc, "img", p, null);
                    if (n != null) {
                        String src = u(n);
                        sb.append("<a class='pic' href='").append(src)
                          .append("' target='_blank' rel='noreferrer'><img loading='lazy' src='")
                          .append(src).append("' alt='图片'></a>").append(NL);
                    } else {
                        sb.append("<span class='miss'>[图片]</span>").append(NL);
                    }
                    break;
                }
                case Msg.P_FACE: {
                    String n = Md.media(sc, "face", p, null);
                    String alt = (p.name != null && !p.name.isEmpty())
                            ? p.name : Md.stripBracket(p.text);
                    if (n != null) {
                        sb.append("<img class='face' src='").append(u(n))
                          .append("' alt='").append(h(alt)).append("'>").append(NL);
                    } else {
                        sb.append("<span class='miss'>").append(h(p.text))
                          .append("</span>").append(NL);
                    }
                    break;
                }
                case Msg.P_VOICE: {
                    String n = Md.media(sc, "voice", p, null);
                    if (n != null) {
                        sb.append("<div class='cap'>🔊 ").append(h(Md.stripBracket(p.text)))
                          .append("</div>").append(NL);
                        sb.append("<audio controls preload='none' src='").append(u(n))
                          .append("'></audio>").append(NL);
                    } else {
                        sb.append("<span class='miss'>").append(h(p.text))
                          .append("</span>").append(NL);
                    }
                    break;
                }
                case Msg.P_VIDEO: {
                    // 本体可能落在 superplayer 缓存里，封面走缩略图路径，两者互不回落
                    String v = Md.media(sc, "video", p, null);
                    String t = Md.media(sc, "thumb", p, p.thumb);
                    if (v != null) {
                        String vs = u(v);
                        sb.append("<video controls preload='metadata'");
                        if (t != null) sb.append(" poster='").append(u(t)).append("'");
                        sb.append(" src='").append(vs).append("'></video>").append(NL);
                        sb.append("<div class='cap'><a href='").append(vs)
                          .append("' download>下载视频").append(h(Md.size(p.size)))
                          .append("</a></div>").append(NL);
                    } else if (t != null) {
                        String ts = u(t);
                        sb.append("<a class='pic' href='").append(ts)
                          .append("' target='_blank' rel='noreferrer'><img src='")
                          .append(ts).append("' alt='视频封面'></a>").append(NL);
                        sb.append("<span class='miss'>[视频文件未找到，仅封面]</span>").append(NL);
                    } else {
                        sb.append("<span class='miss'>[视频]</span>").append(NL);
                    }
                    break;
                }
                case Msg.P_FILE: {
                    String n = Md.media(sc, "file", p, null);
                    String label = (p.name != null && !p.name.isEmpty())
                            ? p.name : Md.stripBracket(p.text);
                    if (n != null) {
                        sb.append("<a class='file' href='").append(u(n))
                          .append("' download><span>📎</span><span>").append(h(label))
                          .append("</span><em>").append(h(Md.size(p.size).trim()))
                          .append("</em></a>").append(NL);
                    } else {
                        sb.append("<span class='miss'>📎 ").append(h(label))
                          .append(h(Md.size(p.size))).append("</span>").append(NL);
                    }
                    break;
                }
                case Msg.P_REPLY: {
                    sb.append("<blockquote>回复：").append(br(p.text))
                      .append("</blockquote>").append(NL);
                    break;
                }
                default: {
                    if (p.text == null || p.text.trim().isEmpty()) continue;
                    sb.append("<p>").append(br(p.text)).append("</p>").append(NL);
                    break;
                }
            }
        }
    }

    /* ================= 打包 ================= */

    /** 把整个目录打成一个 zip，里面带一层目录名，解压不会散落一地 */
    static void pack(File dir, File zip) throws Exception {
        ZipOutputStream zo = null;
        try {
            zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip), 65536));
            zo.setLevel(6);
            add(zo, dir, dir.getName());
            zo.finish();
        } finally {
            if (zo != null) {
                try {
                    zo.close();
                } catch (Throwable t) {
                    L.d("html: 关闭 zip 失败 " + t);
                }
            }
        }
    }

    static void add(ZipOutputStream zo, File f, String prefix) throws Exception {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                Arrays.sort(kids, new Comparator<File>() {
                    public int compare(File a, File b) {
                        return a.getName().compareTo(b.getName());
                    }
                });
                for (File k : kids) {
                    add(zo, k, prefix + "/" + k.getName());
                }
            }
            return;
        }
        put(zo, f, prefix);
    }

    static void put(ZipOutputStream zo, File f, String name) throws Exception {
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        zo.putNextEntry(e);
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(f), 65536);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                zo.write(buf, 0, n);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable t) {
                    L.d("html: 关闭源文件失败 " + t);
                }
            }
            zo.closeEntry();
        }
    }

    /* ================= 转义 ================= */

    static String h(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append('&').append("quot;"); break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 转义正文，同时把换行还原成 <br>，聊天里手打的分行不会糊成一段 */
    static String br(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s.replace("\r\n", NL).replace('\r', '\n');
        String[] lines = t.split(NL, -1);
        StringBuilder sb = new StringBuilder(t.length() + 32);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("<br>");
            sb.append(h(lines[i]));
        }
        return sb.toString();
    }

    /** media/ 下的文件名转成 html 里的相对地址 */
    static String u(String name) {
        if (name == null || name.isEmpty()) return "";
        String s = Uri.encode(name, "/");
        if (s == null) s = name;
        return MEDIA_DIR + "/" + s.replace("&", "&amp;");
    }

    /* ================= 样式 ================= */

    static final String CSS =
            ":root{color-scheme:light dark}"
          + "*{box-sizing:border-box}"
          + "body{margin:0;padding:0 12px 40px;background:#f2f3f5;color:#1b1b1f;"
          + "font:15px/1.65 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;"
          + "-webkit-text-size-adjust:100%}"
          + "header,main{max-width:820px;margin:0 auto}"
          + "header{padding:18px 4px 10px}"
          + "h1{font-size:19px;margin:0 0 8px;word-break:break-all}"
          + ".meta{display:flex;flex-wrap:wrap;gap:6px;font-size:12px;color:#8a8f99}"
          + ".meta span{background:#e4e6ea;border-radius:999px;padding:2px 9px}"
          + ".day{text-align:center;margin:22px 0 12px}"
          + ".day span{font-size:12px;color:#8a8f99;background:#e4e6ea;border-radius:999px;"
          + "padding:2px 10px}"
          + ".m{display:flex;flex-direction:column;align-items:flex-start;margin:10px 0}"
          + ".m.me{align-items:flex-end}"
          + ".m .who{display:flex;align-items:baseline;gap:6px;font-size:11px;"
          + "color:#8a8f99;margin:0 4px 3px}"
          + ".m .who b{font-weight:600;color:#6b7280}"
          + ".m .who i{font-style:normal;opacity:.72}"
          + ".m .who time{opacity:.72}"
          + ".bubble{max-width:78%;background:#fff;border-radius:12px;padding:8px 12px;"
          + "box-shadow:0 1px 2px rgba(0,0,0,.06);word-break:break-word;overflow-wrap:anywhere}"
          + ".m.me .bubble{background:#d7f0c9}"
          + ".bubble p{margin:0 0 6px}"
          + ".bubble p:last-child{margin-bottom:0}"
          + ".bubble a{color:#2563eb;text-decoration:none}"
          + ".bubble a:hover{text-decoration:underline}"
          + ".pic{display:block;margin:4px 0}"
          + ".pic img{display:block;max-width:240px;max-height:240px;border-radius:8px;"
          + "background:#0000000d}"
          + "img.face{width:28px;height:28px;vertical-align:middle;margin:2px 0}"
          + ".cap{font-size:12px;color:#8a8f99;margin:2px 0 4px}"
          + "audio{width:220px;max-width:100%;height:34px;margin:2px 0}"
          + "video{display:block;max-width:280px;max-height:320px;border-radius:8px;"
          + "background:#000;margin:4px 0}"
          + ".file{display:inline-flex;align-items:center;gap:6px;margin:4px 0;padding:6px 10px;"
          + "background:#0000000a;border-radius:8px;max-width:100%}"
          + ".file em{font-style:normal;font-size:12px;color:#8a8f99}"
          + "blockquote{margin:0 0 6px;padding:4px 8px;border-left:3px solid #c7ccd4;"
          + "color:#6b7280;font-size:13px;background:#0000000a;border-radius:0 6px 6px 0}"
          + ".miss{color:#c0392b;font-size:13px;opacity:.85}"
          + "footer{max-width:820px;margin:26px auto 0;font-size:11px;color:#9aa0aa;"
          + "text-align:center;line-height:1.7}"
          + "@media (prefers-color-scheme:dark){"
          + "body{background:#111214;color:#e8e8ea}"
          + ".meta span,.day span{background:#26282c;color:#9aa0aa}"
          + ".bubble{background:#1d1f23;box-shadow:none}"
          + ".m.me .bubble{background:#2c4a33}"
          + ".m .who b{color:#9aa0aa}"
          + "blockquote{border-left-color:#3a3f47;color:#9aa0aa;background:#00000030}"
          + ".file{background:#ffffff12}"
          + ".pic img{background:#ffffff0f}"
          + ".bubble a{color:#7aa2f7}"
          + "}}";
}
