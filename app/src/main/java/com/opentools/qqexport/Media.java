package com.opentools.qqexport;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>缓存目录的文件名形如 {@code Cache_5f913ab6e4cb9688} / {@code Cache_-36796858d28ab5fa}
 * （没有扩展名，长度 15~16，可带负号）。这个 hex 不是内容 md5，而是
 * {@code "Cache_" + Long.toString(Utils.Crc64Long(key), 16)}：QQ 9.2.35 的
 * {@code com.tencent.mobileqq.transfile.AbsDownloader#getFileName(String)} 反汇编结果为
 * {@code "Cache_" + Crc64String(key)}，其中 {@code Crc64String = Long.toString(Crc64Long(s), 16)}，
 * {@code Crc64Long} 用的是 CRC-64/Jones 反射多项式 {@code 0x95AC9329AC4BC9B5}（空串返回 0）。
 * 因此只要拿到消息里的 key（视频是 fileUuid，图片是原图直链/下载标识），
 * 就能<b>直接算出</b>缓存文件名去精确命中，不必扫全盘算 md5。</p>
 *
 * <p>实测结论：用文件内容 md5 去算 CRC64 <b>对不上</b>文件名，所以 {@code key} 不是内容 md5；
 * 这里按「uuid → 直链 → md5 → 内容」逐级试，任一级命中即返回。</p>
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
    /** 文件名 -> 条目，Cache_ 预测命中与同名匹配都靠它做 O(1) 查询 */
    private static volatile Map<String, Hit> BY_NAME = null;
    private static volatile long INDEX_AT = 0L;
    /** 各兜底策略的命中计数，导出结束后打日志用 */
    private static int CNT_PATH, CNT_UUID, CNT_PRED, CNT_MD5, CNT_SIZE, CNT_NAME, CNT_CACHE;
    /** 索引最多收这么多文件，防止极端情况下卡死（本机实测约有 4.3 万个缓存文件） */
    private static final int INDEX_CAP = 120000;
    private static final int MAX_DEPTH = 2;
    /** “同大小唯一”兜底的最小体积门槛：小文件大小极易撞车，不能靠它认人 */
    private static final long SIZE_ONLY_MIN = 16L * 1024L;

    /* ---------------- Cache_ 文件名预测 ---------------- */
    /** CRC-64/Jones 反射多项式，取自 QQ Utils.Crc64Long 里的 const-wide 常量 */
    private static final long CRC64_POLY = 0x95AC9329AC4BC9B5L;
    private static final long[] CRC64_TAB = new long[256];
    static {
        for (int i = 0; i < 256; i++) {
            long c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 1L) != 0L ? (c >>> 1) ^ CRC64_POLY : c >>> 1;
            }
            CRC64_TAB[i] = c;
        }
    }
    /** 和 QQ 一致：初值全 F，末尾不再取反 */
    private static long crc64(byte[] b, int len) {
        long c = -1L;
        for (int i = 0; i < len; i++) {
            c = CRC64_TAB[(int) ((c ^ b[i]) & 0xffL)] ^ (c >>> 8);
        }
        return c;
    }
    private static long crc64(String s) {
        if (s == null || s.isEmpty()) return 0L;       // QQ 空串直接返回 0
        try {
            byte[] b = s.getBytes("UTF-8");
            return crc64(b, b.length);
        } catch (Throwable t) {
            return crc64(s.toCharArray());             // 退化路径：按 char 低位算
        }
    }
    /** 兜底：按 UTF-16 char 的字节流算一遍（key 里出现非 ASCII 时两种结果会不同） */
    private static long crc64(char[] cs) {
        long c = -1L;
        for (char ch : cs) {
            c = CRC64_TAB[(int) ((c ^ (ch & 0xff)) & 0xffL)] ^ (c >>> 8);
            c = CRC64_TAB[(int) ((c ^ ((ch >> 8) & 0xff)) & 0xffL)] ^ (c >>> 8);
        }
        return c;
    }
    /** 复刻 Java 的 Long.toString(v, 16)：小写、有符号，负数前面带 '-' */
    private static String hexLikeJava(long v) {
        if (v >= 0L) return Long.toString(v, 16);
        if (v == Long.MIN_VALUE) return "-8000000000000000";
        return "-" + Long.toString(-v, 16);
    }
    /**
     * 把候选 key 批量转成 QQ 缓存文件名，放进 out。
     * 图片带 {@code pubaccountimage:} 前缀时 QQ 会先剥掉再算，这里两种都生成。
     */
    private static void predictCacheNames(String key, List<String> out) {
        if (key == null) return;
        String k = key.trim();
        if (k.isEmpty()) return;
        addPredicted(k, out);
        if (k.startsWith("pubaccountimage:")) {
            addPredicted(k.substring("pubaccountimage:".length()), out);
        } else if (k.startsWith("pubaccountimage")) {
            addPredicted(k.replace("pubaccountimage", ""), out);
        }
    }
    private static void addPredicted(String k, List<String> out) {
        String name = "Cache_" + hexLikeJava(crc64(k));
        if (!out.contains(name)) out.add(name);
        // chatthumb 里的高清图多带一个 _hd 后缀
        if (!out.contains(name + "_hd")) out.add(name + "_hd");
    }
    /**
     * 按 key 直接拼出 {@code chatpic} 的完整路径去 {@code stat}。
     *
     * <p>实测 QQ 9.2.35 的布局是 {@code <root>/<桶>/Cache_<crc64hex>}，**桶名就是这个十六进制串
     * 的末 3 位**（chatimg 下 3701 个桶目录、9533 个文件），文件名<b>没有扩展名</b>。
     * 既然桶名可由 crc64 推出，命中时只需几次 stat，不必先把两万个文件收进索引——
     * 这是本模块最省时的一条路，也是视频/文件类唯一能用的路（NT 内核不给它们 URL）。</p>
     */
    private static File probeBuckets(Context ctx, String... keys) {
        List<String> names = new ArrayList<>(8);
        for (int i = 0; i < keys.length; i++) predictCacheNames(keys[i], names);
        if (names.isEmpty()) return null;
        List<File> roots = cacheRoots(ctx);
        for (int i = 0; i < names.size(); i++) {
            String nm = names.get(i);
            // 桶名取的是主体名末 3 位，_hd 变体要先把后缀剥掉
            String base = nm.endsWith("_hd") ? nm.substring(0, nm.length() - 3) : nm;
            if (base.length() <= 3) continue;
            String bucket = base.substring(base.length() - 3);
            for (int j = 0; j < roots.size(); j++) {
                File f = new File(new File(roots.get(j), bucket), nm);
                if (f.isFile() && f.length() > 0) {
                    CNT_PRED++;
                    L.i("media: 按 Cache_ 路径命中 " + f);
                    return f;
                }
            }
        }
        return null;
    }

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
        return resolve(ctx, cands, md5, size, name, null, null);
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
        return resolve(ctx, cands, md5, size, name, uuid, null);
    }

    /**
     * 完整版：额外用 {@code url}（图片原图直链）参与 {@code Cache_} 文件名预测。
     *
     * <p>图片缓存名 = {@code "Cache_" + Crc64String(key)}，key 实测不是内容 md5，
     * 最可能就是下载用的 key（原图直链或 fileUuid）。这里把能拿到的 key 全算一遍去查，
     * 命中的是<b>精确文件名</b>，比按大小/md5 回查快几个数量级，也不会误伤。</p>
     */
    public static File resolve(Context ctx, List<String> cands, String md5, long size, String name,
                               String uuid, String url) {
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
        if (m == null && u == null && size <= 0 && (name == null || name.isEmpty())
            && (url == null || url.isEmpty())) return null;
        // 0.4) 用 key 算出桶名+文件名，直接拼路径探测（不建索引，代价最低）
        File direct = probeBuckets(ctx, url, u, m);
        if (direct != null) return direct;
        List<Hit> idx = index(ctx);
        if (idx.isEmpty()) return null;

        // 0) 按 fileUuid 前缀命中（视频最可靠的一把钥匙）
        if (u != null) {
            for (Hit h : idx) {
                String fn = h.file.getName().toLowerCase(java.util.Locale.US);
                // 同目录下有同名 .property 标签文件，前缀一样，绝不能当成视频本体
                if (isSidecar(fn)) continue;
                // 视频真名实测是 <md5>_<19位fileUuid>_1.mp4，uuid 落在中间，
                // 只判前缀会永远落空，所以补一个包含判断
                if (fn.startsWith(u + '_') || fn.startsWith(u + '.') || fn.contains(u)) {
                    CNT_UUID++;
                    L.i("media: 按 uuid 命中 " + h.file);
                    return h.file;
                }
            }
        }

        // 0.5) 用 key 直接算出 Cache_<crc64> 文件名去精确命中（QQ 9.2.35 的命名规则）
        Map<String, Hit> byName = BY_NAME;
        if (byName != null) {
            List<String> pred = new ArrayList<>(8);
            predictCacheNames(url, pred);
            predictCacheNames(u, pred);
            predictCacheNames(m, pred);
            for (int i = 0; i < pred.size(); i++) {
                String pn = pred.get(i);
                Hit h = byName.get(pn.toLowerCase(java.util.Locale.US));
                if (h == null) continue;
                CNT_PRED++;
                L.i("media: 按 Cache_ 预测命中(" + pn + ") " + h.file);
                return h.file;
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
        // 3) 同名文件（走文件名表，O(1)）
        if (name != null && name.length() > 3 && byName != null) {
            Hit h = byName.get(name.toLowerCase(java.util.Locale.US));
            if (h != null) {
                CNT_NAME++;
                L.i("media: 按文件名命中 " + h.file);
                return h.file;
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
            || lowName.endsWith(".nomedia") || lowName.endsWith(".db")
            || lowName.endsWith(".cfg") || lowName.endsWith(".json");
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
        Map<String, Hit> names = new HashMap<>(out.size() * 2 + 16);
        for (Hit h : out) {
            String n = h.file.getName().toLowerCase(java.util.Locale.US);
            if (!names.containsKey(n)) names.put(n, h);
        }
        BY_NAME = names;
        INDEX_AT = System.currentTimeMillis();
        CNT_CACHE = out.size();
        L.d("media: 缓存索引 " + out.size() + " 个文件, 目录 " + cacheRoots(ctx).size() + " 个");
        return out;
    }

    /** 导出结束后打印各策略命中情况，真机排查用 */
    public static String summary() {
        return "path=" + CNT_PATH + " uuid=" + CNT_UUID + " pred=" + CNT_PRED + " md5=" + CNT_MD5
            + " size=" + CNT_SIZE + " name=" + CNT_NAME + " cache=" + CNT_CACHE;
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
        BY_NAME = null;
        INDEX_AT = 0L;
        CNT_PATH = CNT_UUID = CNT_PRED = CNT_MD5 = CNT_SIZE = CNT_NAME = CNT_CACHE = 0;
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