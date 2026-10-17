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
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 导出面板（聊天页悬浮按钮 / 多选栏按钮进入）。
 *
 * 视觉基准 = MIUIX / HyperOS：
 * - 裸 Dialog + 全自绘内容：不再用 AlertDialog，避免继承 QQ 宿主对话框主题的灰面板、
 *   标题区、按钮栏与硬阴影（那正是“安卓 4.4 风”的来源）；
 * - 28dp 大圆角浮层、分段控件切模式、圆形勾选、胶囊按钮、强调色 #3482FF；
 * - 时间选择用自绘滚轮 {@link MiWheel}，不用系统 DatePickerDialog / TimePickerDialog。
 *
 * 三种筛选模式（业务逻辑与原版一致）：
 * <ul>
 *   <li>时间范围：点右侧时间块弹滚轮，精确到分钟；</li>
 *   <li>seq 区间：群用 shmsgseq、C2C 用 uniseq，数字键盘输入；</li>
 *   <li>手动勾选：列表逐条勾选，全不勾时按全部处理。</li>
 * </ul>
 */
public final class ExportDialog {

    private static final SimpleDateFormat IN =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    public static void show(final Activity act) {
        final String key = Store.latest();
        final List<Msg> all = Store.session(key);
        if (all.isEmpty()) {
            toast(act, "尚未捕获消息，先在聊天页上下滚动一次列表");
            return;
        }

        final String fmt = Fmt.get(act);
        final Dialog[] ref = new Dialog[1];
        final int[] mode = {0};
        final Runnable[] sync = new Runnable[1];

        final LinearLayout root = Ui.sheet(act);

        /* ---------------- 标题 ---------------- */
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hcol = Ui.column(act);
        hcol.addView(Ui.text(act, "导出聊天记录", 20, Ui.colorText(act), true));
        TextView sub = Ui.text(act,
                "共 " + all.size() + " 条 · " + (key == null ? "未知会话" : key),
                11, Ui.colorSub(act), false);
        sub.setPadding(0, Ui.dp(act, 4), 0, 0);
        hcol.addView(sub);
        head.addView(hcol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.closeButton(act);
        int cs = Ui.dp(act, 28);
        head.addView(close, new LinearLayout.LayoutParams(cs, cs));
        close.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        root.addView(head);

        /* ---------------- 模式分段控件 ---------------- */
        final MiSegmented seg = new MiSegmented(act,
                new String[]{"时间范围", "seq 区间", "手动勾选"});
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.topMargin = Ui.dp(act, 16);
        root.addView(seg, segLp);

        /* ---------------- 时间面板 ---------------- */
        final LinearLayout timePanel = Ui.panel(act);
        final TextView tFrom = Ui.valuePill(act,
                IN.format(new Date(all.get(0).time * 1000)));
        final TextView tTo = Ui.valuePill(act,
                IN.format(new Date(all.get(all.size() - 1).time * 1000)));
        tFrom.setOnClickListener(v -> pickTime(act, tFrom));
        tTo.setOnClickListener(v -> pickTime(act, tTo));
        timePanel.addView(Ui.textRow(act, "起始时间", "点右侧时间块可调整", tFrom));
        timePanel.addView(Ui.divider(act));
        timePanel.addView(Ui.textRow(act, "结束时间", null, tTo));
        addPanel(root, timePanel);

        /* ---------------- seq 面板 ---------------- */
        final LinearLayout seqPanel = Ui.panel(act);
        final EditText sFrom = Ui.numPill(act, String.valueOf(all.get(0).seq()));
        final EditText sTo = Ui.numPill(act, String.valueOf(all.get(all.size() - 1).seq()));
        seqPanel.addView(Ui.textRow(act, "起始 seq", "群用 shmsgseq，C2C 用 uniseq", sFrom));
        seqPanel.addView(Ui.divider(act));
        seqPanel.addView(Ui.textRow(act, "结束 seq", null, sTo));
        addPanel(root, seqPanel);

        /* ---------------- 手动勾选面板 ---------------- */
        final LinearLayout manualPanel = Ui.panel(act);
        final boolean[] sel = new boolean[all.size()];
        final TextView cnt = Ui.text(act, "", 11, Ui.colorSub(act), false);
        final Runnable refreshCnt = () -> {
            int n = 0;
            for (int i = 0; i < sel.length; i++) if (sel[i]) n++;
            cnt.setText("已选 " + n + " / " + all.size());
        };

        final BaseAdapter ad = new BaseAdapter() {
            @Override
            public int getCount() {
                return all.size();
            }

            @Override
            public Object getItem(int i) {
                return all.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(final int position, View convert, ViewGroup parent) {
                final LinearLayout row;
                final MiCheck ck;
                final TextView label;
                if (convert instanceof LinearLayout && convert.getTag() instanceof View[]) {
                    row = (LinearLayout) convert;
                    View[] t = (View[]) row.getTag();
                    ck = (MiCheck) t[0];
                    label = (TextView) t[1];
                } else {
                    row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    int p = Ui.dp(act, 12);
                    row.setPadding(p, Ui.dp(act, 7), p, Ui.dp(act, 7));
                    ck = new MiCheck(act);
                    row.addView(ck);
                    label = Ui.text(act, "", 12, Ui.colorText(act), false);
                    label.setPadding(Ui.dp(act, 10), 0, 0, 0);
                    row.addView(label, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.setTag(new View[]{ck, label});
                }
                label.setText(Export.line(all.get(position), false));
                ck.setChecked(sel[position]);
                row.setOnClickListener(v -> {
                    sel[position] = !sel[position];
                    ck.setChecked(sel[position]);
                    refreshCnt.run();
                });
                return row;
            }
        };

        ListView lv = new ListView(act);
        lv.setAdapter(ad);
        lv.setDivider(new ColorDrawable(Ui.colorLine(act)));
        lv.setDividerHeight(1);
        lv.setSelector(new ColorDrawable(Color.TRANSPARENT));
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setBackgroundColor(Color.TRANSPARENT);
        lv.setVerticalScrollBarEnabled(false);
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        int listH = Math.min(Ui.dp(act, 240), (int) (screenH * 0.26f));
        manualPanel.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        LinearLayout manBar = new LinearLayout(act);
        manBar.setOrientation(LinearLayout.HORIZONTAL);
        manBar.setGravity(Gravity.CENTER_VERTICAL);
        int mp = Ui.dp(act, 12);
        manBar.setPadding(mp, Ui.dp(act, 6), mp, Ui.dp(act, 10));
        manBar.addView(cnt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        manBar.addView(Ui.textButton(act, "全选", v -> {
            Arrays.fill(sel, true);
            ad.notifyDataSetChanged();
            refreshCnt.run();
        }));
        manBar.addView(Ui.textButton(act, "全不选", v -> {
            Arrays.fill(sel, false);
            ad.notifyDataSetChanged();
            refreshCnt.run();
        }));
        manualPanel.addView(manBar);
        addPanel(root, manualPanel);

        /* ---------------- 面板显隐同步 ---------------- */
        sync[0] = () -> {
            int m = mode[0];
            timePanel.setVisibility(m == 0 ? View.VISIBLE : View.GONE);
            seqPanel.setVisibility(m == 1 ? View.VISIBLE : View.GONE);
            manualPanel.setVisibility(m == 2 ? View.VISIBLE : View.GONE);
        };
        seg.setOnPick(i -> {
            mode[0] = i;
            if (sync[0] != null) sync[0].run();
        });
        sync[0].run();
        refreshCnt.run();

        /* ---------------- 附带 UIN ---------------- */
        LinearLayout uinBox = Ui.panel(act);
        uinBox.addView(Ui.switchRow(act, "附带发送者 UIN",
                "输出形如 昵称(10001): 内容；关闭则只输出昵称",
                Prefs.bool(Prefs.K_UIN, Prefs.D_UIN),
                on -> rememberUin(act, on)));
        addPanel(root, uinBox);

        /* ---------------- 底部按钮 ---------------- */
        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        TextView cancel = Ui.button(act, "取消", false, v -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        TextView copy = Ui.button(act, "复制", false, v -> {
            List<Msg> picked = resolve(mode[0], all, sel, tFrom, tTo, sFrom, sTo);
            if (picked.isEmpty()) {
                toast(act, "该范围没有消息");
                return;
            }
            Export.toClipboard(act, Export.build(key, picked, uin()));
            toast(act, "已复制 " + picked.size() + " 条");
            if (ref[0] != null) ref[0].dismiss();
        });
        TextView ok = Ui.button(act, Fmt.action(fmt), true, v -> {
            List<Msg> picked = resolve(mode[0], all, sel, tFrom, tTo, sFrom, sTo);
            if (picked.isEmpty()) {
                toast(act, "该范围没有消息");
                return;
            }
            if (Fmt.HTML.equals(fmt)) {
                try {
                    Html.Result r = Html.export(act, key, picked, uin());
                    Toast.makeText(act, r.summary() + "\n→ " + r.path(),
                            Toast.LENGTH_LONG).show();
                    if (ref[0] != null) ref[0].dismiss();
                } catch (Throwable t) {
                    Toast.makeText(act, "导出失败: " + t, Toast.LENGTH_LONG).show();
                }
                return;
            }
            if (Fmt.MD.equals(fmt)) {
                try {
                    Md.Result r = Md.export(act, key, picked, uin());
                    Toast.makeText(act, r.summary() + "\n→ " + r.dir, Toast.LENGTH_LONG).show();
                    if (ref[0] != null) ref[0].dismiss();
                } catch (Throwable t) {
                    Toast.makeText(act, "导出失败: " + t, Toast.LENGTH_LONG).show();
                }
                return;
            }
            try {
                String path = Export.toFile(act, key, Export.build(key, picked, uin()));
                Toast.makeText(act, "已导出 " + picked.size() + " 条 → " + path,
                        Toast.LENGTH_LONG).show();
                if (ref[0] != null) ref[0].dismiss();
            } catch (Throwable t) {
                Toast.makeText(act, "写文件失败: " + t, Toast.LENGTH_LONG).show();
            }
        });

        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams lpCp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpCp.leftMargin = Ui.dp(act, 8);
        LinearLayout.LayoutParams lpOk = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f);
        lpOk.leftMargin = Ui.dp(act, 10);
        actions.addView(cancel, lpC);
        actions.addView(copy, lpCp);
        actions.addView(ok, lpOk);

        LinearLayout actionBox = new LinearLayout(act);
        actionBox.setOrientation(LinearLayout.VERTICAL);
        actionBox.setPadding(0, Ui.dp(act, 4), 0, 0);
        actionBox.addView(actions);
        root.addView(actionBox);

        /* ---------------- 弹窗 ---------------- */
        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(true);
        ref[0] = dlg;
        dress(dlg, act, 0.92f);
        dlg.show();
        dress(dlg, act, 0.92f);
    }

    /* ---------------- 内部 ---------------- */

    /** 读取当前的「附带 UIN」开关（设置页与弹窗共用同一份 Prefs） */
    private static boolean uin() {
        return Prefs.bool(Prefs.K_UIN, Prefs.D_UIN);
    }

    private static void rememberUin(Activity act, boolean on) {
        try {
            Prefs.local(act).edit().putBoolean(Prefs.K_UIN, on).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 分组块加 12dp 上间距后挂到根上 */
    private static void addPanel(LinearLayout root, View panel) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(root.getContext(), 12);
        root.addView(panel, lp);
    }

    private static List<Msg> resolve(int mode, List<Msg> all, boolean[] sel,
                                     TextView tFrom, TextView tTo,
                                     EditText sFrom, EditText sTo) {
        try {
            if (mode == 0) {
                long a = IN.parse(tFrom.getText().toString()).getTime();
                long b = IN.parse(tTo.getText().toString()).getTime();
                return Export.byTime(all, Math.min(a, b), Math.max(a, b));
            }
            if (mode == 1) {
                long a = Long.parseLong(sFrom.getText().toString().trim());
                long b = Long.parseLong(sTo.getText().toString().trim());
                return Export.bySeq(all, a, b);
            }
            Set<Long> picked = new HashSet<>();
            for (int i = 0; i < sel.length; i++) if (sel[i]) picked.add(all.get(i).uniseq);
            if (picked.isEmpty()) {
                for (Msg m : all) picked.add(m.uniseq);   // 未勾选时按全部处理
            }
            return Export.byPicked(all, picked);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** 把窗口压成透明背景的浮层，宽度按屏宽比例 */
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

    /* ---------------- 时间滚轮弹窗 ---------------- */

    private static void pickTime(final Activity act, final TextView target) {
        Calendar cur = Calendar.getInstance();
        try {
            Date parsed = IN.parse(target.getText().toString());
            if (parsed != null) cur.setTime(parsed);
        } catch (Throwable ignored) {
        }
        final int y0 = cur.get(Calendar.YEAR);
        final int m0 = cur.get(Calendar.MONTH) + 1;
        final int d0 = cur.get(Calendar.DAY_OF_MONTH);
        final int h0 = cur.get(Calendar.HOUR_OF_DAY);
        final int mi0 = cur.get(Calendar.MINUTE);

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = Ui.sheet(act);

        LinearLayout hcol = Ui.column(act);
        hcol.addView(Ui.text(act, "选择时间", 18, Ui.colorText(act), true));
        TextView hs = Ui.text(act, "上下拖动滚轮，精确到分钟", 11, Ui.colorSub(act), false);
        hs.setPadding(0, Ui.dp(act, 4), 0, 0);
        hcol.addView(hs);
        sheet.addView(hcol);

        final MiWheel wy = new MiWheel(act);
        final MiWheel wm = new MiWheel(act);
        final MiWheel wd = new MiWheel(act);
        final MiWheel wh = new MiWheel(act);
        final MiWheel wi = new MiWheel(act);
        int colorText = Ui.colorText(act);
        wy.setTextColor(colorText);
        wm.setTextColor(colorText);
        wd.setTextColor(colorText);
        wh.setTextColor(colorText);
        wi.setTextColor(colorText);

        int days0 = daysIn(y0, m0);
        wy.setRange(2000, 2100, y0);
        wm.setRange(1, 12, m0);
        wd.setRange(1, days0, Math.min(d0, days0));
        wh.setRange(0, 23, h0);
        wi.setRange(0, 59, mi0);

        wy.setOnPick(v -> {
            int dm = daysIn(v, wm.value());
            wd.setRange(1, dm, Math.min(wd.value(), dm));
        });
        wm.setOnPick(v -> {
            int dm = daysIn(wy.value(), v);
            wd.setRange(1, dm, Math.min(wd.value(), dm));
        });

        LinearLayout wheels = new LinearLayout(act);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        wheels.setGravity(Gravity.CENTER_VERTICAL);
        wheels.addView(wheelCol(act, "年", wy, 1.25f));
        wheels.addView(wheelCol(act, "月", wm, 1f));
        wheels.addView(wheelCol(act, "日", wd, 1f));
        wheels.addView(wheelCol(act, "时", wh, 1f));
        wheels.addView(wheelCol(act, "分", wi, 1f));
        LinearLayout.LayoutParams wlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlLp.topMargin = Ui.dp(act, 12);
        sheet.addView(wheels, wlLp);

        LinearLayout acts = new LinearLayout(act);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.CENTER_VERTICAL);
        TextView c = Ui.button(act, "取消", false, v -> dlg.dismiss());
        TextView ok = Ui.button(act, "确定", true, v -> {
            Calendar r = Calendar.getInstance();
            r.set(wy.value(), wm.value() - 1, wd.value(), wh.value(), wi.value(), 0);
            r.set(Calendar.MILLISECOND, 0);
            target.setText(IN.format(r.getTime()));
            dlg.dismiss();
        });
        acts.addView(c, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lpOk = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpOk.leftMargin = Ui.dp(act, 10);
        acts.addView(ok, lpOk);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = Ui.dp(act, 16);
        sheet.addView(acts, aLp);

        dlg.setContentView(sheet);
        dress(dlg, act, 0.9f);
        dlg.show();
        dress(dlg, act, 0.9f);
    }

    private static LinearLayout wheelCol(Activity act, String label, MiWheel w, float weight) {
        LinearLayout c = Ui.column(act);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(w, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = Ui.text(act, label, 11, Ui.colorSub(act), false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(act, 4), 0, 0);
        c.addView(t);
        c.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        return c;
    }

    private static int daysIn(int y, int m) {
        Calendar c = Calendar.getInstance();
        c.set(y, m - 1, 1, 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    private static void toast(Activity act, String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
