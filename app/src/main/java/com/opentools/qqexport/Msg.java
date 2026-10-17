package com.opentools.qqexport;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一的消息模型。
 *
 * 两条来源链路：
 *  1) QQ9.x 新内核（NT）：AIOMsgItem 里包着 MsgRecord，字段名未混淆，元素在 elements 里；
 *  2) 旧版：MessageRecord（保留兼容，真机上一般不会再走到）。
 *
 * 注意：NT 的 msgId 取代了旧的 uniseq 作为唯一键，否则去重会失效。
 *
 * v1.5 起正文不再是「一个字符串」，而是按元素拆成 {@link Part} 列表：
 * 图片/表情包会把本地文件路径一起带出来，导出时据此把媒体文件复制到同目录，
 * 文字则原样保留换行（Markdown 里要还原成真正的段落）。
 */
public class Msg {

    /* ---------------- 元素类型 ---------------- */
    public static final int P_TEXT = 0;
    public static final int P_PIC = 1;
    public static final int P_FACE = 2;
    public static final int P_VOICE = 3;
    public static final int P_VIDEO = 4;
    public static final int P_FILE = 5;
    public static final int P_CARD = 6;
    public static final int P_TIP = 7;
    public static final int P_REPLY = 8;
    public static final int P_FORWARD = 9;
    public static final int P_OTHER = 10;

    /** 正文片段 */
    public static final class Part {
        public int kind;
        /** 展示文案（图片里是 [图片] 这类占位） */
        public String text = "";
        /** 本地文件路径（图片/表情包/语音/文件），可能为空 */
        public String path;
        /** 缩略图 / 备用路径 */
        public String thumb;
        /** 原始文件名或表情名 */
        public String name;
        public String md5;
        /** 视频的 fileUuid：QQ 用它给 cache/superplayer 里的文件命名（<uuid>_<ts>_1.mp4） */
        public String uuid;
        public long size;
        public int w, h;
        /** 其它可能的本地路径（QQ 把多档缩略图塞在 HashMap 里，这里全收） */
        public final java.util.List<String> alts = new java.util.ArrayList<>();

        /** 是不是可以落盘成媒体文件的片段 */
        public boolean media() {
            return kind == P_PIC || kind == P_FACE || kind == P_VIDEO || kind == P_FILE;
        }
    }

    public String selfUin, friendUin, senderUin, nick;
    public long time;       // 秒
    public int type;        // msgtype
    public long uniseq;     // NT 下等于 msgId
    public long shmsgseq;   // 群序号
    public boolean isTroop, isSend;
    public String text = "";
    public final List<Part> parts = new ArrayList<>();

    /** 学习到的本机 UIN：单聊里 senderUin != peerUin 即自己发的，据此反推 */
    private static volatile long SELF = 0L;

    /** 过滤用的统一序号：优先群 seq，退化为 uniseq */
    public long seq() {
        return shmsgseq > 0 ? shmsgseq : uniseq;
    }

    public String sessionKey() {
        return friendUin + "|" + (isTroop ? 1 : 0);
    }

    public static Msg from(Object o) {
        if (o == null) return null;
        if (o instanceof Msg) return (Msg) o;

        Object rec = MsgItem.record(o);          // 新内核：AIOMsgItem -> MsgRecord
        if (rec != null) return fromNT(rec);
        String cn = o.getClass().getName();
        if (cn.contains("MessageRecord")) return fromLegacy(o);
        return null;
    }

    /* ---------------- QQ9.x / NT ---------------- */

    private static Msg fromNT(Object r) {
        Msg m = new Msg();
        long msgId = Reflect.L(r, "msgId");
        if (msgId == 0L) return null;
        long t = Reflect.L(r, "msgTime");
        if (t > 100000000000L) t /= 1000L;       // 有些回调给的是毫秒
        int chatType = (int) Reflect.L(r, "chatType");
        long peerUin = Reflect.L(r, "peerUin");
        long senderUin = Reflect.L(r, "senderUin");
        m.uniseq = msgId;
        m.shmsgseq = Reflect.L(r, "msgSeq");
        m.time = t;
        m.type = (int) Reflect.L(r, "msgType");
        m.friendUin = peerUin > 0 ? String.valueOf(peerUin) : Reflect.S(r, "peerUid");
        m.senderUin = senderUin > 0 ? String.valueOf(senderUin) : Reflect.S(r, "senderUid");
        m.isTroop = chatType == 2;

        // 发送者显示名：群用群名片优先，单聊用备注/昵称
        String member = Reflect.S(r, "sendMemberName");
        String nick = Reflect.S(r, "sendNickName");
        if (m.isTroop) {
            m.nick = (member != null && !member.isEmpty()) ? member : nick;
        } else {
            m.nick = (nick != null && !nick.isEmpty()) ? nick : member;
        }
        if (m.nick == null || m.nick.isEmpty()) m.nick = m.senderUin;

        // 自己发的判定：单聊可以精确判断并顺带学到 SELF；群聊用学到的 SELF 比对
        if (peerUin > 0 && senderUin > 0) {
            if (!m.isTroop) {
                m.isSend = senderUin != peerUin;
                if (m.isSend) SELF = senderUin;
            } else {
                if (SELF == 0L) {
                    long su = Reflect.L(r, "sendType");
                    if (su == 1L) {                  // 兜底：部分版本 sendType=1 表示自己发出
                        m.isSend = true;
                    }
                } else {
                    m.isSend = senderUin == SELF;
                }
            }
        }
        fillParts(m, Reflect.get(r, "elements"));
        if (m.friendUin == null || m.friendUin.isEmpty()) return null;
        return m;
    }

    private static void fillParts(Msg m, Object elements) {
        if (!(elements instanceof List)) return;
        StringBuilder sb = new StringBuilder();
        for (Object el : (List<?>) elements) {
            if (el == null) continue;
            Part p = partOf(el);
            if (p == null) continue;
            m.parts.add(p);
            String one = flatten(p.text);
            if (one.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(one);
        }
        m.text = sb.toString();
    }

    /**
     * MsgElement 里同时存在 elementId/elementType/extBufForUI 这类非内容字段，
     * 而且它们排在 textElement 之前。旧实现「取第一个非空引用字段」会把
     * extBufForUI 当成正文（describe 返回空串），直接导致正文丢失。
     *
     * 所以这里改成显式按内容字段名依次尝试：命中就出片段，没命中继续下一个。
     * 字段表来自 base.apk（9.2.35）classes.dex 的
     * Lcom/tencent/qqnt/kernel/nativeinterface/MsgElement;。
     */
    private static final String[] ELEMENT_ORDER = {
            "textElement",
            "picElement",
            "pttElement",
            "videoElement",
            "fileElement",
            "faceElement",
            "marketFaceElement",
            "replyElement",
            "arkElement",
            "structMsgElement",
            "structLongMsgElement",
            "grayTipElement",
            "multiForwardMsgElement",
            "walletElement",
            "markdownElement",
            "giphyElement",
            "calendarElement",
            "shareLocationElement",
            "liveGiftElement",
            "tofuRecordElement",
            "avRecordElement",
            "faceBubbleElement",
            "textGiftElement",
            "yoloGameResultElement",
            "taskTopMsgElement",
            "filterMsgElement",
            "prologueMsgElement",
    };

    private static Part partOf(Object el) {
        Class<?> c = el.getClass();
        for (String name : ELEMENT_ORDER) {
            Object v = field(c, el, name);
            if (v == null) continue;
            Part p = build(name, v);
            if (p != null) return p;
        }
        // 兜底：按类型扫一遍，跳过 extBufForUI 之类的原始数据字段
        for (Field f : c.getDeclaredFields()) {
            String n = f.getName();
            if (n.startsWith("$") || Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType().isPrimitive()) continue;
            if (!n.endsWith("Element")) continue;
            if (f.getType() == byte[].class) continue;
            Object v = field(c, el, n);
            if (v == null) continue;
            Part p = build(n, v);
            if (p != null) return p;
        }
        return null;
    }

    /** 逐层往上找同名字段，取到返回值 */
    private static Object field(Class<?> c, Object target, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                continue;
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Part text(int kind, String s) {
        Part p = new Part();
        p.kind = kind;
        p.text = s == null ? "" : s;
        return p;
    }

    /**
     * 单个元素 -> 片段。返回 null 表示「这不是内容元素，继续看下一个」。
     * 注意 textElement 内容为空时也要继续往后找（QQ 会给纯图片消息塞一个空文本元素）。
     */
    private static Part build(String fname, Object v) {
        switch (fname) {
            case "textElement": {
                String s = str(v, "content");
                if (s == null || s.trim().isEmpty()) return null;
                return text(P_TEXT, s);
            }
            case "picElement": {
                Part p = text(P_PIC, "[图片]");
                p.path = firstStr(v, "sourcePath", "originPath", "localPath");
                p.md5 = firstStr(v, "md5HexStr", "originImageMd5");
                p.name = firstStr(v, "fileName", "summary");
                p.size = Reflect.L(v, "fileSize");
                p.w = (int) Reflect.L(v, "picWidth");
                p.h = (int) Reflect.L(v, "picHeight");
                p.thumb = firstMapValue(Reflect.get(v, "thumbPath"));
                collect(p, v, "sourcePath", "originPath", "localPath");
                collectMap(p, Reflect.get(v, "thumbPath"));
                return p;
            }
            case "pttElement": {
                long d = Reflect.L(v, "duration");
                Part p = text(P_VOICE, d > 0 ? "[语音 " + d + "s]" : "[语音]");
                p.path = firstStr(v, "filePath");
                p.size = Reflect.L(v, "fileSize");
                String t = str(v, "text");                 // QQ 自带的语音转文字
                if (t != null && !t.trim().isEmpty()) p.text = p.text + " " + t.trim();
                collect(p, v, "filePath");
                return p;
            }
            case "videoElement": {
                Part p = text(P_VIDEO, "[视频]");
                p.path = firstStr(v, "filePath");
                p.thumb = firstMapValue(Reflect.get(v, "thumbPath"));
                p.size = Reflect.L(v, "fileSize");
                p.w = (int) Reflect.L(v, "thumbWidth");
                p.h = (int) Reflect.L(v, "thumbHeight");
                // 视频本体在 cache/superplayer 里以 fileUuid 命名，md5 用于内容校验
                p.uuid = firstStr(v, "fileUuid", "fileUuidStr");
                p.md5 = firstStr(v, "fileMd5", "md5HexStr", "md5");
                p.name = firstStr(v, "fileName");
                collect(p, v, "filePath", "fileDownloadPath", "localPath");
                collectMap(p, Reflect.get(v, "thumbPath"));
                return p;
            }
            case "fileElement": {
                String n = firstStr(v, "fileName");
                Part p = text(P_FILE, n == null || n.isEmpty() ? "[文件]" : "[文件] " + n);
                p.path = firstStr(v, "filePath");
                p.name = n;
                p.size = Reflect.L(v, "fileSize");
                collect(p, v, "filePath", "fileMediaPath", "localPath");
                return p;
            }
            case "faceElement": {
                // 系统小黄脸：faceText 形如 /微笑
                String t = firstStr(v, "faceText");
                if (t == null || t.trim().isEmpty()) t = "[表情]";
                return text(P_FACE, t.trim());
            }
            case "marketFaceElement": {
                String n = firstStr(v, "faceName");
                Part p = text(P_FACE, n == null || n.isEmpty() ? "[表情包]" : n);
                p.path = firstStr(v, "staticFacePath", "dynamicFacePath");
                p.name = n;
                p.w = (int) Reflect.L(v, "imageWidth");
                p.h = (int) Reflect.L(v, "imageHeight");
                collect(p, v, "staticFacePath", "dynamicFacePath");
                return p;
            }
            case "replyElement": {
                String s = firstStr(v, "sourceMsgText");
                s = s == null ? "" : s.replace('\n', ' ').trim();
                if (s.length() > 80) s = s.substring(0, 80) + "…";
                return text(P_REPLY, s.isEmpty() ? "[回复]" : s);
            }
            case "arkElement": {
                String app = firstStr(v, "appName");
                return text(P_CARD, app == null || app.isEmpty() ? "[卡片]" : "[卡片] " + app);
            }
            case "structMsgElement":
            case "structLongMsgElement":
                return text(P_CARD, "[卡片]");
            case "grayTipElement":
                return text(P_TIP, "[提示]");
            case "multiForwardMsgElement":
                return text(P_FORWARD, "[聊天记录]");
            case "walletElement":
                return text(P_CARD, "[红包/转账]");
            case "markdownElement":
                return text(P_CARD, "[图文]");
            case "giphyElement":
                return text(P_PIC, "[动图]");
            case "calendarElement":
                return text(P_CARD, "[日程]");
            case "shareLocationElement":
                return text(P_CARD, "[位置]");
            case "liveGiftElement":
                return text(P_CARD, "[礼物]");
            case "tofuRecordElement":
                return text(P_CARD, "[待办]");
            case "filterMsgElement":
                return null;                     // 被过滤的消息（如敏感词替换），不进正文
            default:
                return null;
        }
    }

    private static String firstStr(Object o, String... names) {
        Object v = Reflect.get(o, names);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private static String firstMapValue(Object map) {
        if (!(map instanceof Map)) return null;
        for (Object k : ((Map<?, ?>) map).keySet()) {
            Object v = ((Map<?, ?>) map).get(k);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        }
        return null;
    }

    /** 把元素上若干个「可能装路径」的字段都收进候选列表，去重且跳过空值 */
    private static void collect(Part p, Object o, String... names) {
        for (String n : names) {
            Object v = Reflect.get(o, n);
            if (!(v instanceof String)) continue;
            add(p, (String) v);
        }
    }

    /** HashMap 形态的路径桶（QQ 的 thumbPath 是「宽度 -> 文件」）全收 */
    private static void collectMap(Part p, Object map) {
        if (!(map instanceof Map)) return;
        for (Object k : ((Map<?, ?>) map).keySet()) {
            Object v = ((Map<?, ?>) map).get(k);
            if (v instanceof String) add(p, (String) v);
        }
    }

    private static void add(Part p, String s) {
        if (s == null) return;
        String t = s.trim();
        if (t.isEmpty()) return;
        if (t.equals(p.path) || t.equals(p.thumb)) return;
        if (!p.alts.contains(t)) p.alts.add(t);
    }

    private static String str(Object o, String... names) {
        Object v = Reflect.get(o, names);
        return v == null ? null : String.valueOf(v);
    }

    /** 压成单行，给 TXT 导出与列表预览用 */
    public static String flatten(String s) {
        if (s == null) return "";
        return s.replace("\r\n", " ↵ ").replace('\n', ' ').replace('\r', ' ').trim();
    }

    /* ---------------- 旧版 MessageRecord（兼容保留） ---------------- */

    private static Msg fromLegacy(Object rec) {
        Msg m = new Msg();
        m.selfUin = Reflect.S(rec, "selfuin");
        m.friendUin = Reflect.S(rec, "frienduin");
        m.senderUin = Reflect.S(rec, "senderuin");
        m.nick = Reflect.S(rec, "friendNickName", "nickName", "senderNick", "sendNickName");
        m.time = Reflect.L(rec, "time");
        m.type = (int) Reflect.L(rec, "msgtype", "msgType");
        m.uniseq = Reflect.L(rec, "uniseq");
        m.shmsgseq = Reflect.L(rec, "shmsgseq", "shMsgSeq");
        m.isTroop = Reflect.L(rec, "istroop") == 1;
        m.isSend = m.senderUin != null && m.senderUin.equals(m.selfUin);
        m.text = renderLegacy(m.type, Reflect.S(rec, "msg"), Reflect.S(rec, "extStr"));
        m.parts.add(text(P_TEXT, m.text));
        if (m.nick == null || m.nick.isEmpty()) m.nick = m.senderUin;
        if (m.friendUin == null) return null;
        return m;
    }

    private static String renderLegacy(int type, String body, String ext) {
        switch (type) {
            case 1:     return body == null ? "" : body;
            case 2:     return "[图片]";
            case 3:     return "[语音]";
            case 6:     return "[表情]";
            case 17:    return "[文件] " + (body == null ? "" : body);
            case -1000: return "[系统] " + (body == null ? "" : body);
            case -2000: return "[图文] " + (body == null ? "" : body);
            case -2005: return "[分享] " + (body == null ? "" : body);
            case -2011: return "[卡片] " + (body == null ? "" : body);
            case -2014: return "[红包/转账]";
            default:    return (body == null || body.isEmpty()) ? "[type=" + type + "]" : body;
        }
    }
}