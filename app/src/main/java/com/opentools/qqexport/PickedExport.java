package com.opentools.qqexport;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.CheckBox;
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
 * 一条条勾好了，直接按勾选结果导出即可。所以面板只有「附带发送者UIN」一个选项。
 *
 * 勾选项可能跨会话（QQ 多选栏本身不支持跨会话，但万一），这里按 sessionKey
 * 分组：单会话时输出与 ExportDialog 完全一致；多会话时先写总头，再逐会话分段。
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
        int pad = dp(act, 12);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView summary = new TextView(act);
        StringBuilder sb = new StringBuilder("已勾选 " + total + " 条");
        if (groups.size() > 1) sb.append("，来自 ").append(groups.size()).append(" 个会话");
        summary.setText(sb.toString());
        summary.setTextSize(13);
        root.addView(summary);

        TextView hint = new TextView(act);
        hint.setText("按勾选顺序导出，不套用时间/seq 范围");
        hint.setTextSize(11);
        hint.setPadding(0, dp(act, 6), 0, dp(act, 6));
        root.addView(hint);

        final CheckBox withUin = new CheckBox(act);
        withUin.setText("附带发送者UIN");
        withUin.setChecked(Prefs.bool(Prefs.K_UIN, Prefs.D_UIN));
        root.addView(withUin);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("导出勾选的聊天记录")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("导出TXT", null)
                .setNeutralButton("复制到剪切板", null)
                .create();

        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                boolean u = withUin.isChecked();
                remember(act, u);
                String text = buildAll(groups, u);
                String key = fileNameKey(groups);
                try {
                    String path = Export.toFile(act, key, text);
                    Toast.makeText(act, "已导出 " + total + " 条 → " + path, Toast.LENGTH_LONG).show();
                    dlg.dismiss();
                } catch (Throwable t) {
                    Toast.makeText(act, "写文件失败: " + t, Toast.LENGTH_LONG).show();
                }
            });

            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                boolean u = withUin.isChecked();
                remember(act, u);
                Export.toClipboard(act, buildAll(groups, u));
                Toast.makeText(act, "已复制 " + total + " 条", Toast.LENGTH_SHORT).show();
                dlg.dismiss();
            });
        });

        dlg.show();
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

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density);
    }
}
