package com.opentools.qqexport;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
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
 * 全部用原生控件 + {@link Ui} 拼装，不依赖 AndroidX；外观与 {@link SettingsActivity} 一致：
 * 圆角卡片、胶囊式模式切换、主按钮亮色实心。
 *
 * 三种筛选模式：
 * <ul>
 *   <li>时间范围：点右侧胶囊弹日期+时间选择器，精确到分钟；</li>
 *   <li>seq 区间：群用 shmsgseq、C2C 用 uniseq，数字键盘输入；</li>
 *   <li>手动勾选：列表逐条勾选，全不勾时按全部处理。</li>
 * </ul>
 */
public final class ExportDialog {

    private static final SimpleDateFormat IN = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    public static void show(final Activity act) {
        final String key = Store.latest();
        final List<Msg> all = Store.session(key);
        if (all.isEmpty()) {
            Toast.makeText(act, "尚未捕获消息，先在聊天页上下滚动一次列表", Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean md = Prefs.bool(Prefs.K_MD, Prefs.D_MD);
        final AlertDialog[] ref = new AlertDialog[1];
        final int[] mode = {0};
        final Runnable[] sync = new Runnable[1];

        final LinearLayout root = Ui.dialogRoot(act);

        /* ---------------- 标题 ---------------- */
        LinearLayout head = Ui.column(act);
        head.addView(Ui.text(act, "导出聊天记录", 18, Ui.colorText(act), true));
        TextView sub = Ui.text(act,
                "共 " + all.size() + " 条 · " + (key == null ? "未知会话" : key),
                11, Ui.colorSub(act), false);
        sub.setPadding(0, Ui.dp(act, 4), 0, 0);
        head.addView(sub);
        root.addView(head);

        /* ---------------- 模式胶囊 ---------------- */
        final String[] names = {"时间范围", "seq 区间", "手动勾选"};
        final TextView[] chips = new TextView[3];
        LinearLayout chipRow = new LinearLayout(act);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, Ui.dp(act, 14), 0, 0);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView chip = modeChip(act, names[i]);
            chips[i] = chip;
            chip.setOnClickListener(v -> {
                mode[0] = idx;
                if (sync[0] != null) sync[0].run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(act, 8);
            chipRow.addView(chip, lp);
        }
        root.addView(chipRow);

        /* ---------------- 时间面板 ---------------- */
        final LinearLayout timePanel = Ui.column(act);
        final TextView tFrom = valuePill(act, IN.format(new Date(all.get(0).time * 1000)));
        final TextView tTo = valuePill(act, IN.format(new Date(all.get(all.size() - 1).time * 1000)));
        tFrom.setOnClickListener(v -> pickTime(act, tFrom));
        tTo.setOnClickListener(v -> pickTime(act, tTo));
        timePanel.addView(Ui.textRow(act, "起始时间", "格式 yyyy-MM-dd HH:mm", tFrom));
        timePanel.addView(Ui.divider(act));
        timePanel.addView(Ui.textRow(act, "结束时间", null, tTo));
        root.addView(timePanel);

        /* ---------------- seq 面板 ---------------- */
        final LinearLayout seqPanel = Ui.column(act);
        final EditText sFrom = numInput(act, String.valueOf(all.get(0).seq()));
        final EditText sTo = numInput(act, String.valueOf(all.get(all.size() - 1).seq()));
        seqPanel.addView(Ui.textRow(act, "起始 seq", "群用 shmsgseq，C2C 用 uniseq", sFrom));
        seqPanel.addView(Ui.divider(act));
        seqPanel.addView(Ui.textRow(act, "结束 seq", null, sTo));
        root.addView(seqPanel);

        /* ---------------- 手动勾选面板 ---------------- */
        final LinearLayout manualPanel = Ui.column(act);
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
            public View getView(final int i, View v, ViewGroup parent) {
                CheckBox cb;
                if (v instanceof CheckBox) {
                    cb = (CheckBox) v;
                } else {
                    cb = new CheckBox(act);
                    cb.setTextSize(11);
                    cb.setTextColor(Ui.colorText(act));
                    cb.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
                    int p = Ui.dp(act, 8);
                    cb.setPadding(p, Ui.dp(act, 6), p, Ui.dp(act, 6));
                }
                final Msg m = all.get(i);
                cb.setOnCheckedChangeListener(null);
                cb.setText(Export.line(m, false));
                cb.setChecked(sel[i]);
                cb.setOnCheckedChangeListener((b, checked) -> {
                    sel[i] = checked;
                    refreshCnt.run();
                });
                return cb;
            }
        };
        ListView lv = new ListView(act);
        lv.setDivider(new ColorDrawable(Ui.colorLine(act)));
        lv.setDividerHeight(1);
        lv.setAdapter(ad);
        lv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 240)));
        manualPanel.addView(lv);

        LinearLayout manBar = new LinearLayout(act);
        manBar.setOrientation(LinearLayout.HORIZONTAL);
        manBar.setGravity(Gravity.CENTER_VERTICAL);
        manBar.setPadding(0, Ui.dp(act, 10), 0, 0);
        manBar.addView(cnt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView allBtn = mini(act, "全选", v -> {
            Arrays.fill(sel, true);
            ad.notifyDataSetChanged();
            refreshCnt.run();
        });
        TextView noneBtn = mini(act, "全不选", v -> {
            Arrays.fill(sel, false);
            ad.notifyDataSetChanged();
            refreshCnt.run();
        });
        LinearLayout.LayoutParams lpAll = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpAll.leftMargin = Ui.dp(act, 8);
        manBar.addView(allBtn, lpAll);
        LinearLayout.LayoutParams lpNone = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpNone.leftMargin = Ui.dp(act, 8);
        manBar.addView(noneBtn, lpNone);
        manualPanel.addView(manBar);
        root.addView(manualPanel);

        /* ---------------- 面板显隐同步 ---------------- */
        sync[0] = () -> {
            for (int i = 0; i < 3; i++) styleChip(chips[i], i == mode[0]);
            timePanel.setVisibility(mode[0] == 0 ? View.VISIBLE : View.GONE);
            seqPanel.setVisibility(mode[0] == 1 ? View.VISIBLE : View.GONE);
            manualPanel.setVisibility(mode[0] == 2 ? View.VISIBLE : View.GONE);
        };
        sync[0].run();
        refreshCnt.run();

        /* ---------------- 附带 UIN ---------------- */
        root.addView(Ui.divider(act));
        root.addView(Ui.switchRow(act, "附带发送者 UIN",
                "输出形如 昵称(10001): 内容；关闭则只输出昵称",
                Prefs.bool(Prefs.K_UIN, Prefs.D_UIN),
                (b, on) -> rememberUin(act, on)));

        /* ---------------- 底部按钮 ---------------- */
        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, Ui.dp(act, 14), 0, 0);

        TextView cancel = Ui.button(act, "取消", false, v -> ref[0].dismiss());
        TextView copy = Ui.button(act, "复制", false, v -> {
            List<Msg> picked = resolve(mode[0], all, sel, tFrom, tTo, sFrom, sTo);
            if (picked.isEmpty()) {
                toast(act, "该范围没有消息");
                return;
            }
            Export.toClipboard(act, Export.build(key, picked, uin()));
            toast(act, "已复制 " + picked.size() + " 条");
            ref[0].dismiss();
        });
        TextView ok = Ui.button(act, md ? "导出 Markdown" : "导出 TXT", true, v -> {
            List<Msg> picked = resolve(mode[0], all, sel, tFrom, tTo, sFrom, sTo);
            if (picked.isEmpty()) {
                toast(act, "该范围没有消息");
                return;
            }
            if (md) {
                try {
                    Md.Result r = Md.export(act, key, picked, uin());
                    Toast.makeText(act, r.summary() + "\n→ " + r.dir, Toast.LENGTH_LONG).show();
                    ref[0].dismiss();
                } catch (Throwable t) {
                    Toast.makeText(act, "导出失败: " + t, Toast.LENGTH_LONG).show();
                }
                return;
            }
            try {
                String path = Export.toFile(act, key, Export.build(key, picked, uin()));
                Toast.makeText(act, "已导出 " + picked.size() + " 条 → " + path,
                        Toast.LENGTH_LONG).show();
                ref[0].dismiss();
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
        lpOk.leftMargin = Ui.dp(act, 8);
        actions.addView(cancel, lpC);
        actions.addView(copy, lpCp);
        actions.addView(ok, lpOk);
        root.addView(actions);

        /* ---------------- 弹窗 ---------------- */
        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setView(root)
                .create();
        ref[0] = dlg;
        Ui.transparentDialog(dlg, act);
        dlg.setOnShowListener(d -> {
            try {
                if (dlg.getWindow() != null) {
                    int w = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.92f);
                    dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            } catch (Throwable ignored) {
            }
        });
        dlg.show();
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

    private static void pickTime(Activity act, final TextView target) {
        final Calendar now = Calendar.getInstance();
        new DatePickerDialog(act, (dp, y, m, d) -> {
            Calendar c2 = Calendar.getInstance();
            new TimePickerDialog(act, (tp, hh, mm) -> {
                c2.set(y, m, d, hh, mm, 0);
                c2.set(Calendar.MILLISECOND, 0);
                target.setText(IN.format(c2.getTime()));
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** 模式胶囊：未选中浅底深字，选中亮色实心白字 */
    private static TextView modeChip(Context c, String s) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(c, 10), Ui.dp(c, 9), Ui.dp(c, 10), Ui.dp(c, 9));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private static void styleChip(TextView tv, boolean on) {
        Context c = tv.getContext();
        if (on) {
            tv.setTextColor(Color.WHITE);
            tv.setBackground(Ui.round(c, Ui.ACCENT, 10));
        } else {
            tv.setTextColor(Ui.colorText(c));
            tv.setBackground(Ui.round(c, Ui.colorCard2(c), 10));
        }
    }

    /** 可点击的取值胶囊（时间） */
    private static TextView valuePill(Context c, String s) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(13);
        tv.setTextColor(Ui.ACCENT);
        tv.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7));
        tv.setBackground(Ui.round(c, Ui.colorCard2(c), 10));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    /** 数字输入框（seq） */
    private static EditText numInput(Context c, String init) {
        EditText et = new EditText(c);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(init);
        et.setTextSize(13);
        et.setGravity(Gravity.END);
        et.setBackground(Ui.round(c, Ui.colorCard2(c), 10));
        et.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
        et.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(c, 128), ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    /** 小号次按钮 */
    private static TextView mini(Context c, String s, View.OnClickListener l) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(c, 12), Ui.dp(c, 7), Ui.dp(c, 12), Ui.dp(c, 7));
        tv.setTextColor(Ui.colorText(c));
        tv.setBackground(Ui.round(c, Ui.colorCard2(c), 8));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    private static void toast(Activity act, String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
