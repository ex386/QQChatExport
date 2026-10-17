package com.opentools.qqexport;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置面板——<b>直接跑在 QQ 进程里</b>（聊天页悬浮按钮长按 / 导出面板右上角齿轮进入）。
 *
 * <p>为什么不是桌面那个模块 Activity：模块进程写的是模块自己的 qqexport.xml，QQ 进程
 * 靠 XSharedPreferences 读不到（分区存储下目录不可读），于是设置永远等于默认值。
 * 搬进 QQ 进程后写的就是 QQ 自己的 prefs，和导出逻辑同进程同实例，改完立即生效、
 * 重启 QQ 后依然保留。</p>
 *
 * <p>视觉沿用 {@link Ui}（MIUIX 风浮层），不依赖 AndroidX。</p>
 */
public final class SettingsPanel {

    private SettingsPanel() {
    }

    public static void show(Activity act) {
        show(act, null);
    }

    /**
     * @param afterClose 面板关闭后回调；导出面板用它提示「重新打开后按新格式导出」
     */
    public static void show(final Activity act, final Runnable afterClose) {
        if (act == null || act.isFinishing()) return;
        final Dialog[] holder = new Dialog[1];

        LinearLayout sheet = Ui.sheet(act);

        /* ---------------- 头部 ---------------- */
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hcol = Ui.column(act);
        hcol.addView(Ui.text(act, "导出设置", 19, Ui.colorText(act), true));
        TextView sub = Ui.text(act, "改动立即生效 · 设置保存在 QQ 内", 11, Ui.colorSub(act), false);
        sub.setPadding(0, Ui.dp(act, 4), 0, 0);
        hcol.addView(sub);
        head.addView(hcol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.closeButton(act);
        int cs = Ui.dp(act, 28);
        head.addView(close, new LinearLayout.LayoutParams(cs, cs));
        close.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        sheet.addView(head);

        /* ---------------- 可滚动内容 ---------------- */
        LinearLayout body = Ui.column(act);

        /* 导出格式 */
        body.addView(Ui.section(act, "导出格式"));
        LinearLayout fmtCard = Ui.card(act);
        addFormatRow(act, fmtCard, true);
        addSwitch(act, fmtCard, false, "复制媒体文件",
                "把图片/表情/语音/视频/文件复制进导出目录（网页是 media/，Markdown 是 images/）；"
                        + "关闭后只留文字占位",
                Prefs.K_MEDIA, Prefs.D_MEDIA);
        addNumberRow(act, fmtCard, false, "媒体体积上限",
                "单个文件超过该值只留占位不复制；视频大的话调大或设 0（不限制）",
                Prefs.K_MEDIA_MAX_MB, Prefs.D_MEDIA_MAX_MB, 0, 4096, "MB");
        Ui.addCard(body, fmtCard);

        /* 入口 */
        body.addView(Ui.section(act, "入口"));
        LinearLayout entryCard = Ui.card(act);
        addSwitch(act, entryCard, true, "聊天页悬浮按钮",
                "右下角「导出」圆钮；关掉后重新进聊天页生效",
                Prefs.K_FLOATING, Prefs.D_FLOATING);
        addSwitch(act, entryCard, false, "长按多选栏按钮",
                "长按消息进多选后，多选栏右侧出现「导出」；已勾选时只导出勾选项",
                Prefs.K_MULTI, Prefs.D_MULTI);
        Ui.addCard(body, entryCard);

        /* 显示 */
        body.addView(Ui.section(act, "显示"));
        LinearLayout showCard = Ui.card(act);
        addSwitch(act, showCard, true, "附带发送者 UIN",
                "开启后形如 昵称(10001): 内容；关闭只输出昵称",
                Prefs.K_UIN, Prefs.D_UIN);
        Ui.addCard(body, showCard);

        /* 缓存 */
        body.addView(Ui.section(act, "缓存"));
        LinearLayout cacheCard = Ui.card(act);
        addNumberRow(act, cacheCard, true, "每会话缓存条数",
                "100 ~ 200000，只存内存；调大能导出更长的历史",
                Prefs.K_LIMIT, Prefs.D_LIMIT, 100, 200000, "条");
        Ui.addCard(body, cacheCard);

        /* 调试 */
        body.addView(Ui.section(act, "调试"));
        LinearLayout dbgCard = Ui.card(act);
        addSwitch(act, dbgCard, true, "详细日志",
                "向 LSPosed 日志输出每条采集到的消息，排查用",
                Prefs.K_LOG, Prefs.D_LOG);
        Ui.addCard(body, dbgCard);

        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svp.topMargin = Ui.dp(act, 6);
        sheet.addView(sv, svp);

        /* ---------------- 底部 ---------------- */
        LinearLayout foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView reset = Ui.textButton(act, "恢复默认", v -> {
            Fmt.set(act, Prefs.D_FMT);
            Prefs.putBool(act, Prefs.K_MEDIA, Prefs.D_MEDIA);
            Prefs.putInt(act, Prefs.K_MEDIA_MAX_MB, Prefs.D_MEDIA_MAX_MB);
            Prefs.putBool(act, Prefs.K_FLOATING, Prefs.D_FLOATING);
            Prefs.putBool(act, Prefs.K_MULTI, Prefs.D_MULTI);
            Prefs.putBool(act, Prefs.K_UIN, Prefs.D_UIN);
            Prefs.putInt(act, Prefs.K_LIMIT, Prefs.D_LIMIT);
            Prefs.putBool(act, Prefs.K_LOG, Prefs.D_LOG);
            Store.invalidateLimit();
            toast(act, "已恢复默认");
            if (holder[0] != null) holder[0].dismiss();
            show(act, afterClose);
        });
        foot.addView(reset);
        foot.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView done = Ui.button(act, "完成", true, v -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        foot.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Ui.dp(act, 10);
        sheet.addView(foot, fp);

        Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(sheet);
        dlg.setCanceledOnTouchOutside(true);
        holder[0] = dlg;
        dress(dlg, act);
        dlg.show();
        dress(dlg, act);
        if (afterClose != null) {
            dlg.setOnDismissListener(d -> {
                try {
                    afterClose.run();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    /* ---------------- 行构造 ---------------- */

    private static void addSwitch(final Activity act, LinearLayout card, boolean first,
                                  String title, String subtitle, final String key, boolean def) {
        if (!first) card.addView(Ui.divider(act));
        card.addView(Ui.switchRow(act, title, subtitle,
                Prefs.bool(key, def), on -> {
                    Prefs.putBool(act, key, on);
                    toast(act, title + (on ? " 已开启" : " 已关闭"));
                }));
    }

    /** 导出格式三选一 */
    private static void addFormatRow(final Activity act, LinearLayout card, boolean first) {
        if (!first) card.addView(Ui.divider(act));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        final String[] cur = {Fmt.get(act)};
        final TextView[] btns = new TextView[Fmt.KEYS.length];
        final Runnable paint = () -> {
            for (int i = 0; i < btns.length; i++) {
                TextView b = btns[i];
                if (b == null) continue;
                boolean on = Fmt.KEYS[i].equals(cur[0]);
                b.setTextColor(on ? Ui.colorText(act) : Ui.colorSub(act));
                b.setBackground(Ui.round(act, on ? Ui.colorFill(act) : 0x00000000, 9));
            }
        };
        for (int i = 0; i < Fmt.KEYS.length; i++) {
            final String k = Fmt.KEYS[i];
            TextView b = Ui.textButton(act, Fmt.LABELS[i], v -> {
                cur[0] = k;
                Fmt.set(act, k);
                paint.run();
                toast(act, "导出格式：" + Fmt.LABELS[Fmt.index(k)]);
            });
            btns[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = Ui.dp(act, 6);
            box.addView(b, lp);
        }
        paint.run();
        card.addView(Ui.textRow(act, "导出格式",
                "网页：index.html + media/ 打成 zip，能翻图、听语音、放视频；"
                        + "Markdown：chat.md + images/；纯文本：单个 txt",
                box));
    }

    private static void addNumberRow(final Activity act, LinearLayout card, boolean first,
                                     String title, String subtitle, final String key, int def,
                                     final int min, final int max, final String unit) {
        if (!first) card.addView(Ui.divider(act));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        final EditText et = Ui.numPill(act, String.valueOf(Prefs.num(key, def)));
        box.addView(et);
        TextView ut = Ui.text(act, unit, 12, Ui.colorSub(act), false);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.leftMargin = Ui.dp(act, 4);
        box.addView(ut, ulp);
        TextView save = Ui.textButton(act, "保存", v -> {
            int val;
            try {
                val = Integer.parseInt(et.getText().toString().trim());
            } catch (Throwable t) {
                toast(act, "请输入数字");
                return;
            }
            if (val < min) val = min;
            if (val > max) val = max;
            et.setText(String.valueOf(val));
            Prefs.putInt(act, key, val);
            if (Prefs.K_LIMIT.equals(key)) Store.invalidateLimit();
            toast(act, title + " 已设为 " + val + unit);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(act, 8);
        box.addView(save, lp);
        card.addView(Ui.textRow(act, title, subtitle, box));
    }

    /* ---------------- 杂项 ---------------- */

    private static void dress(Dialog d, Activity act) {
        try {
            Window w = d.getWindow();
            if (w == null) return;
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
            int sw = act.getResources().getDisplayMetrics().widthPixels;
            int sh = act.getResources().getDisplayMetrics().heightPixels;
            w.setLayout((int) (sw * 0.92f), (int) (sh * 0.80f));
        } catch (Throwable ignored) {
        }
    }

    private static void toast(Activity act, String s) {
        try {
            Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
