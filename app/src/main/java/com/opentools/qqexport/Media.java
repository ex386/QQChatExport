package com.opentools.qqexport;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 媒体文件定位。
 *
 * 背景：QQ 9.x 的 PicElement 是 native 层直接填的数据结构，很多消息里的
 * sourcePath / thumbPath 都是空的（内核只有在图片真正下载完才回填），
 * 所以不能只信一个字段，这里做多路兜底：
 *
 * <ol>
 *   <li>消息元素自带的候选路径（sourcePath / originPath / localPath / 各档缩略图）</li>
 *   <li>QQ 图片缓存目录（chatpic/chatimg、chatraw、chatthumb）里按 size + md5 回查</li>
 *   <li>按原始文件名在缓存目录里找同名文件</li>
 * </ol>
 *
 * 缓存目录的文件名形如 Cache_1afc574b30531001（没有扩展名），
 * 只能靠内容特征判断，回查时按大小先过滤再算 md5，避免扫全盘。
 */
public final class Media {

    private Media() {
    }

    /** 缓存目录索引条目 */
    private static final class Hit {
        final long len;
        final File file;

        Hit(long len, File file) {
            this.len = len;
            this.file = file;
        }
    }

    private static volatile List<Hit> INDEX = null;
    private static volatile long INDEX_AT = 0L;
    /** 各兜底策略的命中计数，导出结束后打日志用 */
    private static int CNT_PATH, CNT_UUID, CNT_MD5, CNT_SIZE, CNT_NAME, CNT_CACHE;
    /** 索引最多收这么多文件，防止极端情况下卡死（本机实测约有 4.3 万个缓存文件） */
    private static final int INDEX_CAP = 120000;
    private static final int MAX_DEPTH = 2;
    /** “同大小唯一”兜底的最小体积门槛：小文件大小极易撞车，不能靠它认人 */
    private static final long SIZE_ONLY_MIN = 16L * 1024L;

    /* ---------------- 目录 ---------------- */

    /** QQ 在外部存储里的数据根目录（.../Android/data/com.tencent.mobileqq） */
    private static File qqBase(Context ctx) {
        try {
            File ext = ctx.getExternalFilesDir(null);   // .../files
            if (ext != null) {
                File base = ext.getParentFile();
                if (base != null && base.isDirectory()) return base;
            }
        } catch (Throwable ignored) {
        }
        File guess = new File("/sdcard/Android/data/com.tencent.mobileqq");
        return guess.isDirectory() ? guess : null;
    }

    /**
     * 媒体缓存目录列表，按优先级排（图片在前，视频/文件在后）。
     *
     * 目录是照 QQ 9.2.x NT 内核的真实落盘布局写的，实测于
     * /sdcard/Android/data/com.tencent.mobileqq：
     *   Tencent/MobileQQ/chatpic/{chatraw,chatimg,chatthumb}  聊天图
     *   Tencent/QQ_Images                                     QQ 图片（注意不在 MobileQQ 下）
     *   Tencent/MobileQQ/photo                                聊天原图另一处落盘
     *   cache/superplayer/<uin>/<md5>_1.mp4                   视频本体
     *   Tencent/MobileQQ/shortvideo/<hash>/                    短视频
     *   cache/shortvideo/thumbs                                视频封面
     *   Tencent/QQfile_recv/<uuid>/                            文件/视频传输
     */
    private static List<File> cacheRoots(Context ctx) {
        List<File> out = new ArrayList<>(12);
        File base = qqBase(ctx);
        if (base == null) return out;
        File pic = new File(base, "Tencent/MobileQQ/chatpic");
        addDir(out, new File(pic, "chatraw"));
        addDir(out, new File(pic, "chatimg"));
        addDir(out, new File(pic, "chatthumb"));
        // QQ_Images 实际在 Tencent/ 下，旧代码写成 Tencent/MobileQQ/QQ_Images，永远命中不到
        addDir(out, new File(base, "Tencent/QQ_Images"));
        addDir(out, new File(base, "Tencent/QQ_Images/QQEditPic"));
        addDir(out, new File(base, "Tencent/MobileQQ/photo"));
        // 视频：本体在 cache/superplayer/<uin>/，文件名形如 <md5>_1.mp4
        addDir(out, new File(base, "cache/superplayer"));
        addDir(out, new File(base, "Tencent/MobileQQ/shortvideo"));
        addDir(out, new File(base, "cache/shortvideo"));
        addDir(out, new File(base, "Tencent/QQfile_recv"));
        return out;
    }

    private static void addDir(List<File> out, File f) {
        try {
            if (f.isDirectory()) out.add(f);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 解析 ---------------- */

    /**
     * 按候选路径 + md5 + 大小找本地文件，找不到返回 null。
     *
     * @param cands 候选路径（可为空串/null，顺序即优先级）
     * @param md5   消息元素上的 md5（用于缓存回查，可为空）
     * @param size  文件大小（同上，可为 0）
     * @param name  原始文件名（同上，可为空）
     */
    public static File resolve(Context ctx, List<String> cands, String md5, long size, String name) {
        return resolve(ctx, cands, md5, size, name, null);
    }

    /**
     * 同上，额外支持按 fileUuid 命中。
     *
     * <p>视频走这条：QQ 把视频缓存命名成 {@code <uuid>_1.mp4} 或 {@code <uuid>.mp4}，
     * 消息里带的正是 fileUuid，两者前缀一致。文件名前缀<b>有时</b>也是内容 md5，
     * 但并不可靠（实测同一目录下两种命名混存），所以 uuid 只做前缀匹配，
     * md5 仍然独立判断。</p>
     */
    public static File resolve(Context ctx, List<String> cands, String md5, long size, String name,
                               String uuid) {
        if (cands != null) {
            for (String c : cands) {
                File f = exist(c);
                if (f != null) {
                    CNT_PATH++;
                    return f;
                }
            }
        }
        String m = (md5 == null || md5.length() != 32) ? null : md5.toLowerCase(java.util.Locale.US);
        String u = (uuid == null || uuid.length() < 16) ? null : uuid.toLowerCase(java.util.Locale.US);
        if (m == null && u == null && size <= 0 && (name == null || name.isEmpty())) return null;

        List<Hit> idx = index(ctx);
        if (idx.isEmpty()) return null;

        // 0) 按 fileUuid 前缀命中（视频最可靠的一把钥匙）
        if (u != null) {
            for (Hit h : idx) {
                String fn = h.file.getName().toLowerCase(java.util.Locale.US);
                // 同目录下有同名 .property 标签文件，前缀一样，绝不能当成视频本体
                if (isSidecar(fn)) continue;
                if (fn.startsWith(u + '_') || fn.startsWith(u + '.')) {
                    CNT_UUID++;
                    L.i("media: 按 uuid 命中 " + h.file);
                    return h.file;
                }
            }
        }

        // 1) 同大小 + md5 相同
        if (m != null && size > 0) {
            for (Hit h : idx) {
                if (h.len != size) continue;
                if (m.equals(md5Of(h.file))) {
                    CNT_MD5++;
                    L.i("media: 按 md5 命中 " + h.file);
                    return h.file;
                }
            }
        }
        // 2) 同大小且只有一个候选（md5 可能是原图，缓存里是重压过的）
        if (size >= SIZE_ONLY_MIN) {
            File only = null;
            int n = 0;
            for (Hit h : idx) {
                if (h.len != size) continue;
                only = h.file;
                if (++n > 1) break;
            }
            if (n == 1 && only != null) {
                CNT_SIZE++;
                L.i("media: 按大小唯一命中 " + only);
                return only;
            }
        }
        // 3) 同名文件
        if (name != null && name.length() > 3) {
            for (Hit h : idx) {
                if (name.equalsIgnoreCase(h.file.getName())) {
                    CNT_NAME++;
                    L.i("media: 按文件名命中 " + h.file);
                    return h.file;
                }
            }
        }
        return null;
    }

    /**
     * QQ 会在媒体文件旁放同名的标签文件（.property / .tmp …），
     * 它们和本体共享同一段前缀，参与匹配会把几百字节的标签当成视频，必须排除。
     */
    private static boolean isSidecar(String lowName) {
        return lowName.endsWith(".property") || lowName.endsWith(".tmp")
            || lowName.endsWith(".nomedia") || lowName.endsWith(".db");
    }

    private static File exist(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty()) return null;
        if (p.startsWith("file://")) p = p.substring(7);
        try {
            File f = new File(p);
            if (!f.isFile()) return null;
            if (f.length() <= 0) return null;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- 索引 ---------------- */

    /** 缓存目录索引，5 分钟内复用 */
    private static List<Hit> index(Context ctx) {
        List<Hit> cur = INDEX;
        if (cur != null && System.currentTimeMillis() - INDEX_AT < 300000L) return cur;
        List<Hit> out = new ArrayList<>(4096);
        try {
            for (File root : cacheRoots(ctx)) {
                walk(root, out, 0);
                if (out.size() >= INDEX_CAP) break;
            }
        } catch (Throwable t) {
            L.e(t);
        }
        INDEX = out;
        INDEX_AT = System.currentTimeMillis();
        CNT_CACHE = out.size();
        L.d("media: 缓存索引 " + out.size() + " 个文件");
        return out;
    }

    /** 导出结束后打印各策略命中情况，真机排查用 */
    public static String summary() {
        return "path=" + CNT_PATH + " uuid=" + CNT_UUID + " md5=" + CNT_MD5 + " size=" + CNT_SIZE
            + " name=" + CNT_NAME + " cache=" + CNT_CACHE;
    }

    private static void walk(File dir, List<Hit> out, int depth) {
        if (depth > MAX_DEPTH || out.size() >= INDEX_CAP) return;
        File[] fs;
        try {
            fs = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (fs == null) return;
        for (File f : fs) {
            if (out.size() >= INDEX_CAP) return;
            try {
                if (f.isDirectory()) {
                    walk(f, out, depth + 1);
                } else {
                    long len = f.length();
                    if (len <= 0) continue;
                    if (isSidecar(f.getName().toLowerCase(java.util.Locale.US))) continue;
                    out.add(new Hit(len, f));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 导出前重置索引，避免用到很久以前的目录快照 */
    public static void reset() {
        INDEX = null;
        INDEX_AT = 0L;
        CNT_PATH = CNT_UUID = CNT_MD5 = CNT_SIZE = CNT_NAME = CNT_CACHE = 0;
    }

    /* ---------------- md5 ---------------- */

    private static String md5Of(File f) {
        java.io.InputStream in = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                int v = b & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}