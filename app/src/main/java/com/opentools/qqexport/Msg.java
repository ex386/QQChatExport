package com.opentools.qqexport;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * 统一的消息模型。
 *
 * 两条来源链路：
 *  1) QQ9.x 新内核（NT）：AIOMsgItem 里包着 MsgRecord，字段名未混淆，元素在 elements 里；
 *  2) 旧版：MessageRecord（保留兼容，真机上一般不会再走到）。
 *
 * 注意：NT 的 msgId 取代了旧的 uniseq 作为唯一键，否则去重会失效。
 */
public class Msg {
    public String selfUin, friendUin, senderUin, nick;
    public long time;       // 秒
    public int type;        // msgtype
    public long uniseq;     // NT 下等于 msgId
    public long shmsgseq;   // 群序号
    public boolean isTroop, isSend;
    public String text;

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

        m.text = renderElements(Reflect.get(r, "elements"));
        if (m.text == null) m.text = "";
        if (m.friendUin == null || m.friendUin.isEmpty()) return null;
        return m;
    }

    /** 元素类型在 NT 里没有稳定的编号语义，所以按“哪个字段非空”来判定，最稳 */
    private static String renderElements(Object elements) {
        if (!(elements instanceof List)) return "";
        List<?> list = (List<?>) elements;
        StringBuilder sb = new StringBuilder();
        for (Object el : list) {
            if (el == null) continue;
            String piece = renderElement(el);
            if (piece == null || piece.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(piece);
        }
        return sb.toString();
    }

    /**
     * MsgElement 里同时存在 elementId/elementType/extBufForUI 这类非内容字段，
     * 而且它们排在 textElement 之前。旧实现“取第一个非空引用字段”会把
     * extBufForUI 当成正文（describe 返回空串），直接导致正文丢失。
     *
     * 所以这里改成显式按内容字段名依次尝试：命中就出文案，没命中继续下一个。
     * 字段表见 classes.dex 里 Lcom/tencent/qqnt/kernel/nativeinterface/MsgElement;。
     */
    private static final String[] ELEMENT_ORDER = {
            "textElement",
            "picElement",
            "pttElement",
            "videoElement",
            "fileElement",
            "faceElement",
            "marketFaceElement",
            "arkElement",
            "structMsgElement",
            "structLongMsgElement",
            "grayTipElement",
            "replyElement",
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

    private static String renderElement(Object el) {
        Class<?> c = el.getClass();
        for (String name : ELEMENT_ORDER) {
            Object v = field(c, el, name);
            if (v == null) continue;
            String s = describe(name, v);
            if (s != null && !s.isEmpty()) return s;
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
            String s = describe(n, v);
            if (s != null && !s.isEmpty()) return s;
        }
        return "";
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

    private static String describe(String field, Object v) {
        switch (field) {
            case "textElement":
                return nl(str(v, "content"));
            case "picElement":
                return "[图片]";
            case "pttElement": {
                long d = Reflect.L(v, "duration");
                return d > 0 ? "[语音 " + d + "\"]" : "[语音]";
            }
            case "videoElement":
                return "[视频]";
            case "fileElement": {
                String name = str(v, "fileName");
                return name == null || name.isEmpty() ? "[文件]" : "[文件] " + name;
            }
            case "faceElement":
                return "[表情]";
            case "marketFaceElement": {
                String name = str(v, "faceName");
                return name == null || name.isEmpty() ? "[表情]" : "[表情 " + name + "]";
            }
            case "arkElement": {
                String app = str(v, "appName");
                return app == null || app.isEmpty() ? "[卡片]" : "[卡片 " + app + "]";
            }
            case "structMsgElement":
                return "[卡片]";
            case "grayTipElement":
                return "[提示]";
            case "replyElement":
                return "[回复]";
            case "multiForwardMsgElement":
                return "[聊天记录]";
            case "walletElement":
                return "[红包/转账]";
            case "markdownElement":
                return "[图文]";
            case "giphyElement":
                return "[动图]";
            case "calendarElement":
                return "[日程]";
            case "shareLocationElement":
                return "[位置]";
            case "liveGiftElement":
                return "[礼物]";
            case "tofuRecordElement":
                return "[待办]";
            /* 纯 UI 元素，不进正文 */
            case "inlineKeyboardElement":
            case "actionBarElement":
            case "recommendedMsgElement":
            case "prohibitedMsgElement":
                return "";
            default:
                return "";
        }
    }

    private static String str(Object o, String... names) {
        Object v = Reflect.get(o, names);
        return v == null ? null : String.valueOf(v);
    }

    private static String nl(String s) {
        if (s == null) return "";
        return s.replace("\r\n", " ↵ ").replace('\n', ' ').replace('\r', ' ');
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