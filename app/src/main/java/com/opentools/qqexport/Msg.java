package com.opentools.qqexport;

public class Msg {
    public String selfUin, friendUin, senderUin, nick;
    public long time;       // 秒
    public int type;        // msgtype
    public long uniseq;
    public long shmsgseq;   // 群序号，C2C 常为 0
    public boolean isTroop, isSend;
    public String text;

    /** 过滤用的统一序号：优先群 seq，退化为 uniseq */
    public long seq() {
        return shmsgseq > 0 ? shmsgseq : uniseq;
    }

    public String sessionKey() {
        return friendUin + "|" + (isTroop ? 1 : 0);
    }

    public static Msg from(Object rec) {
        if (rec == null) return null;
        String cn = rec.getClass().getName();
        if (!cn.contains("MessageRecord")) return null;   // 只认消息实体

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
        m.text = render(m.type, Reflect.S(rec, "msg"), Reflect.S(rec, "extStr"));
        if (m.nick == null || m.nick.isEmpty()) m.nick = m.senderUin;
        if (m.friendUin == null) return null;
        return m;
    }

    private static String render(int type, String body, String ext) {
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
