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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多选栏「导出」的落地面板。
 *
 * 与 {@link ExportDialog} 的区别：这里不做范围筛选——用户已经在 QQ 的多选模式里
 * 一条条勾好了，直接按勾选结果导出即可。所以面板只有「附带发送者 UIN」一个选项。
 *
 * 勾选项可能跨会话（QQ 多选栏本身不支持跨会话，但万一），这里按 sessionKey
 * 分组：单会话时输出与 ExportDialog 完全一致；多会话时先写总头，再逐会话分段。
 *
 * 外观同为 MIUIX：裸 Dialog + 全自绘，不用 AlertDialog / CheckBox。
 */
public final class PickedExport {

    private PickedExport() {
    }

    public static void show(final Activity act, List<Msg> picked) {
        if (picked == null || picked.isEmpty()) {
            Toast.makeText(act, "没有勾选消息", Toast.LENGTH_SHORT).show();
            return;
        }

        /* ---------- 按会话分组，保持勾选顺序 ---------- */
        final Map<String, List<Msg>> groups = new LinkedHashMap<>();
        for (Msg m : picked) {
            if (m == null) continue;
            String k = m.sessionKey();
            List<Msg> bucket = groups.get(k);
            if (bucket == null) {
                bucket = new ArrayList<>();
                groups.put(k, bucket);
            }
            bucket.add(m);
        }
        if (groups.isEmpty()) {
            Toast.makeText(act, "没有可导出的消息", Toast.LENGTH_SHORT).show();
            return;
        }

        final int total = total(groups);
        final boolean multi = groups.size() > 1;
        final String fmt = Fmt.get(act);
        final boolean[] uin = {Prefs.bool(Prefs.K_UIN, Prefs.D_UIN)};
        final Dialog[] ref = new Dialog[1];

        final LinearLayout sheet = Ui.sheet(act);

        /* ---------- 标题 ---------- */
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hcol = Ui.column(act);
        hcol.addView(Ui.text(act, "导出勾选消息", 20, Ui.colorText(act), true));
        TextView hs = Ui.text(act, "按勾选顺序导出，不套用范围筛选",
                11, Ui.colorSub(act), false);
        hs.setPadding(0, Ui.dp(act, 4), 0, 0);
        hcol.addView(hs);
        head.addView(hcol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.closeButton(act);
        int cs = Ui.dp(act, 28);
        head.addView(close, new LinearLayout.LayoutParams(cs, cs));
        close.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        sheet.addView(head);

        /* ---------- 摘要 ---------- */
        LinearLayout info = Ui.panel(act);
        info.addView(Ui.textRow(act, "已勾选",
                multi ? "分属 " + groups.size() + " 个会话，各自一个目录" : null,
                Ui.valuePill(act, total + " 条")));
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iLp.topMargin = Ui.dp(act, 16);
        sheet.addView(info, iLp);

        /* ---------- 附带 UIN ---------- */
        LinearLayout uinBox = Ui.panel(act);
        uinBox.addView(Ui.switchRow(act, "附带发送者 UIN",
                "输出形如 昵称(10001): 内容；关闭则只输出昵称",
                uin[0],
                on -> {
                    uin[0] = on;
                    remember(act, on);
                }));
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uLp.topMargin = Ui.dp(act, 12);
        sheet.addView(uinBox, uLp);

        /* ---------- 按钮 ---------- */
        TextView cancel = Ui.button(act, "取消", false, v -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        TextView copy = Ui.button(act, "复制", false, v -> {
            Export.toClipboard(act, buildAll(groups, uin[0]));
            Toast.makeText(act, "已复制 " + total + " 条", Toast.LENGTH_SHORT).show();
            if (ref[0] != null) ref[0].dismiss();
        });
        TextView ok = Ui.button(act, Fmt.action(fmt), true, v -> {
            final boolean u = uin[0];
            remember(act, u);
            if (ref[0] != null) ref[0].dismiss();
            Toast.makeText(act, "开始导出 " + total + " 条，请稍候…",
                    Toast.LENGTH_SHORT).show();
            // v2.0：导出可能联网补全图片，必须放到后台线程，否则主线程等网络 → ANR
            new Thread(() -> {
                String msg;
                try {
                    if (Fmt.HTML.equals(fmt)) {
                        int n = 0;
                        String lastPath = null;
                        for (Map.Entry<String, List<Msg>> e : groups.entrySet()) {
                            Html.Result r = Html.export(act, e.getKey(), e.getValue(), u);
                            n += r.msgs;
                            lastPath = r.path();
                        }
                        msg = multi
                                ? "已导出 " + n + " 条，分属 " + groups.size() + " 个会话，各自一个 zip"
                                : "已导出 " + n + " 条 → " + lastPath;
                    } else if (Fmt.MD.equals(fmt)) {
                        int n = 0;
                        String lastDir = null;
                        for (Map.Entry<String, List<Msg>> e : groups.entrySet()) {
                            Md.Result r = Md.export(act, e.getKey(), e.getValue(), u);
                            n += r.msgs;
                            lastDir = r.dir;
                        }
                        msg = multi
                                ? "已导出 " + n + " 条，分属 " + groups.size() + " 个会话，各自一个目录"
                                : "已导出 " + n + " 条 → " + lastDir;
                    } else {
                        String path = Export.toFile(act, fileNameKey(groups), buildAll(groups, u));
                        msg = "已导出 " + total + " 条 → " + path;
                    }
                    L.d("PickedExport: done total=" + total);
                } catch (Throwable t) {
                    L.e(t);
                    msg = "导出失败: " + t;
                }
                final String m = msg;
                try {
                    act.runOnUiThread(() -> Toast.makeText(act, m, Toast.LENGTH_LONG).show());
                } catch (Throwable t) {
                    L.e(t);
                }
            }, "qqexport-picked").start();
        });

        LinearLayout acts = new LinearLayout(act);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.CENTER_VERTICAL);
        acts.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lpCp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpCp.leftMargin = Ui.dp(act, 8);
        acts.addView(copy, lpCp);
        LinearLayout.LayoutParams lpOk = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f);
        lpOk.leftMargin = Ui.dp(act, 10);
        acts.addView(ok, lpOk);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = Ui.dp(act, 18);
        sheet.addView(acts, aLp);

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(sheet);
        dlg.setCanceledOnTouchOutside(true);
        ref[0] = dlg;
        dress(dlg, act, 0.9f);
        dlg.show();
        dress(dlg, act, 0.9f);
    }

    /** 透明背景浮层，宽度按屏宽比例 */
    private static void dress(Dialog d, Activity act, float widthFrac) {
        try {
            Window w = d.getWindow();
            if (w == null) return;
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
            int sw = act.getResources().getDisplayMetrics().widthPixels;
            w.setLayout((int) (sw * widthFrac), ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) {
        }
    }

    /** 记住这次的选择，下次打开面板沿用 */
    private static void remember(Activity act, boolean withUin) {
        try {
            Prefs.local(act).edit().putBoolean(Prefs.K_UIN, withUin).apply();
        } catch (Throwable ignored) {
        }
    }

    private static int total(Map<String, List<Msg>> groups) {
        int n = 0;
        for (List<Msg> l : groups.values()) n += l.size();
        return n;
    }

    private static String fileNameKey(Map<String, List<Msg>> groups) {
        if (groups.size() == 1) return groups.keySet().iterator().next();
        return "勾选_" + groups.size() + "会话";
    }

    /** 单会话走 Export.build；多会话先写总头再逐段拼接 */
    private static String buildAll(Map<String, List<Msg>> groups, boolean withUin) {
        if (groups.size() == 1) {
            Map.Entry<String, List<Msg>> only = groups.entrySet().iterator().next();
            return Export.build(only.getKey(), only.getValue(), withUin);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 勾选导出 · 会话数: ").append(groups.size())
                .append("  总条数: ").append(total(groups))
                .append("  导出时间: ").append(Export.FMT.format(new Date())).append('\n');
        for (Map.Entry<String, List<Msg>> e : groups.entrySet()) {
            sb.append("\n\n");
            sb.append(Export.build(e.getKey(), e.getValue(), withUin));
        }
        return sb.toString();
    }
}
