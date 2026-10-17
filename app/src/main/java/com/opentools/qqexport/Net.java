package com.opentools.qqexport;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * v2.0 联网兜底下载器。
 *
 * <p>背景：v1.x 的假设是「媒体一定能从 QQ 本地缓存里捞到」，所以 {@link Media} 是纯本地检索器。
 * 实际使用中大量图片根本没落盘（未点开过、已过期的临时文件、换设备后重新同步的聊天记录），
 * 于是 HTML/Markdown 里只剩一个 {@code [图片]} 占位。v2.0 在这里补上第二来源。</p>
 *
 * <p>可下载范围由元素字段决定，不是想拉就能拉：</p>
 * <ul>
 *   <li>{@code PicElement.originImageUrl} —— 原图直链（gchat.qpic.cn，免鉴权），主力路径；</li>
 *   <li>{@code VideoElement / PttElement / FileElement} —— NT 内核<b>完全不暴露 URL</b>，
 *       只能走 {@code IKernelMsgService.downloadRichMedia} 的异步回调，本类不处理；</li>
 *   <li>{@code MarketFaceElement} —— 没有 URL 字段（只有 staticFacePath/dynamicFacePath），
 *       同样只能靠本地缓存。</li>
 * </ul>
 *
 * <p>缓存设计：磁盘缓存放在 {@code QQExport/netcache/<url的md5>.<真实扩展名>}，
 * 扩展名由 {@link Md#sniff(File)} 按文件头判定后重命名得到——这一点很关键，
 * 若把文件存成 {@code <md5>} 或 {@code <md5>.bin}，{@link Md#extOf(String)} 会把它
 * 当成 {@code bin} 扩展名，导出的图片就成了打不开的 .bin 文件。</p>
 *
 * <p>第一次调用会扫描一遍缓存目录建立「url 指纹 -&gt; 文件」索引，之后只查内存，
 * 因此同一张图重复导出不会重新下载。</p>
 */
final class Net {

    /** 缓存子目录名，位于 QQExport/ 下 */
    static final String CACHE = "netcache";

    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 20000;
    private static final int THREADS = 4;
    /** 单次预取最多处理的 URL 数，防止一次性几万条的会话把导出拖到不可用 */
    private static final int MAX_URLS = 3000;
    /** 单文件上限：QQ 原图最大也就十几 MB，40MB 足够挡住异常响应 */
    private static final long MAX_BYTES = 40L * 1024L * 1024L;
    /** 预取总预算，超时后直接进入正文生成（没下完的会在 media() 里再试一次） */
    private static final long BUDGET_SEC = 120L;

    /** key = url 的 md5，value = 已落盘文件 */
    private static final Map<String, File> MEM = new ConcurrentHashMap<>();
    private static volatile boolean scanned = false;

    private static int cntHit, cntGet, cntFail;

    private Net() {
    }

    static void reset() {
        cntHit = 0;
        cntGet = 0;
        cntFail = 0;
    }

    static String summary() {
        return "net hit=" + cntHit + " get=" + cntGet + " fail=" + cntFail;
    }

    static File dir(Context ctx) {
        File base = ctx.getExternalFilesDir("QQExport");
        if (base == null) base = new File(ctx.getFilesDir(), "QQExport");
        File d = new File(base, CACHE);
        if (!d.isDirectory() && !d.mkdirs()) {
            L.d("net: 缓存目录建不出来 " + d);
            return null;
        }
        return d;
    }

    /** 只有 http(s) 才算可下载地址；PicElement.summary 这类字段可能是普通描述文本 */
    static boolean usable(String url) {
        if (url == null) return false;
        String t = url.trim();
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /**
     * 把缓存目录里已有的文件登记进内存索引。
     *
     * <p>只做一次；文件名格式固定为 {@code <32位md5>.<ext>}，用第一个点切分即可还原指纹。</p>
     */
    private static void scan(Context ctx) {
        if (scanned) return;
        scanned = true;
        File d = dir(ctx);
        if (d == null) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        int n = 0;
        for (File f : fs) {
            String name = f.getName();
            if (name.endsWith(".part")) continue;
            int i = name.indexOf('.');
            if (i != 32) continue;
            MEM.put(name.substring(0, i), f);
            n++;
        }
        if (n > 0) L.d("net: 已有缓存 " + n + " 个");
    }

    /** 只查不打网络：命中返回本地文件，未命中返回 null */
    static File cached(Context ctx, String url) {
        if (!usable(url)) return null;
        scan(ctx);
        String key = md5(url);
        File f = MEM.get(key);
        if (f != null && f.isFile() && f.length() > 0L) {
            cntHit++;
            return f;
        }
        return null;
    }

    /**
     * 取 URL 对应的本地文件：缓存优先，未命中就下载。
     *
     * <p>任何异常都只记日志返回 null——导出不能因为一张图挂掉。</p>
     */
    static File get(Context ctx, String url) {
        File hit = cached(ctx, url);
        if (hit != null) return hit;
        if (!usable(url)) return null;
        File d = dir(ctx);
        if (d == null) return null;

        String key = md5(url);
        File part = new File(d, key + ".part");
        HttpURLConnection c = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            c = (HttpURLConnection) new URL(url.trim()).openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) QQExport/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept", "image/*,*/*;q=0.8");
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                L.d("net: HTTP " + code + " " + url);
                cntFail++;
                return null;
            }
            long len = c.getContentLength();
            if (len > MAX_BYTES) {
                L.d("net: 响应过大 " + len + " " + url);
                cntFail++;
                return null;
            }
            in = c.getInputStream();
            out = new FileOutputStream(part);
            byte[] buf = new byte[64 * 1024];
            long total = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new java.io.IOException("超过单文件上限");
                out.write(buf, 0, n);
            }
            out.flush();
            Md.close(out);
            out = null;
            Md.close(in);
            in = null;
            if (total <= 0L) {
                part.delete();
                cntFail++;
                return null;
            }
            String ext = Md.sniff(part);
            if (ext == null) ext = "jpg";            // 无 magic 时按 QQ 原图最常见的格式兜
            File dst = new File(d, key + '.' + ext);
            if (!part.renameTo(dst)) {
                if (!Md.copy(part, dst)) {
                    part.delete();
                    cntFail++;
                    return null;
                }
                part.delete();
            }
            MEM.put(key, dst);
            cntGet++;
            return dst;
        } catch (Throwable t) {
            L.d("net: 下载失败 " + t + " " + url);
            cntFail++;
            return null;
        } finally {
            Md.close(out);
            Md.close(in);
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
            if (part.exists() && !MEM.containsKey(key)) part.delete();
        }
    }

    /**
     * 导出前把整批消息里能下载的图片一次性预取下来。
     *
     * <p>之所以要预取而不是在 {@link Md#media} 里懒加载：正文生成是单线程顺序跑的，
     * 几百张图串行下载会让一次导出卡上几分钟；这里用 4 线程并发把这些网络等待重叠掉，
     * 之后 {@code media()} 阶段全部走本地缓存命中。</p>
     */
    static void prefetch(Context ctx, List<Msg> list) {
        if (list == null || list.isEmpty()) return;
        scan(ctx);
        final Context app = ctx;
        Set<String> urls = new HashSet<>();
        for (Msg m : list) {
            if (m == null || m.parts == null) continue;
            for (Msg.Part p : m.parts) {
                if (p == null || !usable(p.url)) continue;
                String k = md5(p.url);
                if (MEM.containsKey(k)) {
                    cntHit++;
                    continue;
                }
                urls.add(p.url);
            }
            if (urls.size() >= MAX_URLS) break;
        }
        if (urls.isEmpty()) return;

        final List<String> todo = new ArrayList<>(urls);
        L.d("net: 预取 " + todo.size() + " 个图片资源（" + THREADS + " 线程）");
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (final String u : todo) {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            get(app, u);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(BUDGET_SEC, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }
            if (!pool.isTerminated()) pool.shutdownNow();
        }
        L.d("net: 预取结束 " + summary());
    }

    /** 取 URL 的 md5 作缓存指纹（URL 本身可能很长且含特殊字符，不适合做文件名） */
    static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.trim().getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xff);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString().toLowerCase(Locale.US);
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
