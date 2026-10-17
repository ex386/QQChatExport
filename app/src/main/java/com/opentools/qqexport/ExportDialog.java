package com.opentools.qqexport;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

public final class ExportDialog {

    private static final SimpleDateFormat IN = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    public static void show(final Activity act) {
        final String key = Store.latest();
        final List<Msg> all = Store.session(key);
        if (all.isEmpty()) {
            Toast.makeText(act, "尚未捕获消息，先在聊天页上下滚动一次列表", Toast.LENGTH_SHORT).show();
            return;
        }

        int pad = dp(act, 12);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        /* ---- 模式选择 ---- */
        final RadioGroup rg = new RadioGroup(act);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        String[] modes = {"时间", "seq", "手动勾选"};
        for (int i = 0; i < modes.length; i++) {
            RadioButton rb = new RadioButton(act);
            rb.setId(1000 + i);
            rb.setText(modes[i]);
            rg.addView(rb);
        }
        ((RadioButton) rg.getChildAt(0)).setChecked(true);
        root.addView(rg);

        /* ---- 时间面板 ---- */
        final LinearLayout timePanel = new LinearLayout(act);
        timePanel.setOrientation(LinearLayout.VERTICAL);
        final EditText tFrom = new EditText(act);
        final EditText tTo = new EditText(act);
        tFrom.setText(IN.format(new Date(all.get(0).time * 1000)));
        tTo.setText(IN.format(new Date(all.get(all.size() - 1).time * 1000)));
        timePanel.addView(hint(act, "起始时间 (yyyy-MM-dd HH:mm)"));
        timePanel.addView(tFrom);
        timePanel.addView(hint(act, "结束时间"));
        timePanel.addView(tTo);
        LinearLayout tBtns = new LinearLayout(act);
        Button pickFrom = new Button(act);
        pickFrom.setText("选起始");
        Button pickTo = new Button(act);
        pickTo.setText("选结束");
        pickFrom.setOnClickListener(v -> pickTime(act, tFrom));
        pickTo.setOnClickListener(v -> pickTime(act, tTo));
        tBtns.addView(pickFrom);
        tBtns.addView(pickTo);
        timePanel.addView(tBtns);
        root.addView(timePanel);

        /* ---- seq 面板 ---- */
        final LinearLayout seqPanel = new LinearLayout(act);
        seqPanel.setOrientation(LinearLayout.VERTICAL);
        seqPanel.setVisibility(View.GONE);
        final EditText sFrom = new EditText(act);
        final EditText sTo = new EditText(act);
        sFrom.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        sTo.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        sFrom.setText(String.valueOf(all.get(0).seq()));
        sTo.setText(String.valueOf(all.get(all.size() - 1).seq()));
        seqPanel.addView(hint(act, "起始 seq（群用 shmsgseq，C2C 用 uniseq）"));
        seqPanel.addView(sFrom);
        seqPanel.addView(hint(act, "结束 seq"));
        seqPanel.addView(sTo);
        root.addView(seqPanel);

        /* ---- 手动勾选面板 ---- */
        final LinearLayout manualPanel = new LinearLayout(act);
        manualPanel.setOrientation(LinearLayout.VERTICAL);
        manualPanel.setVisibility(View.GONE);
        final boolean[] sel = new boolean[all.size()];

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
                CheckBox cb = (v instanceof CheckBox) ? (CheckBox) v : new CheckBox(act);
                Msg m = all.get(i);
                cb.setText(Export.line(m, false));
                cb.setTextSize(11);
                cb.setChecked(sel[i]);
                cb.setOnCheckedChangeListener((b, checked) -> sel[i] = checked);
                return cb;
            }
        };

        ListView lv = new ListView(act);
        lv.setAdapter(ad);
        lv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 260)));
        manualPanel.addView(lv);

        LinearLayout mBtn = new LinearLayout(act);
        Button allBtn = new Button(act);
        allBtn.setText("全选");
        Button noneBtn = new Button(act);
        noneBtn.setText("全不选");
        allBtn.setOnClickListener(v -> {
            Arrays.fill(sel, true);
            ad.notifyDataSetChanged();
        });
        noneBtn.setOnClickListener(v -> {
            Arrays.fill(sel, false);
            ad.notifyDataSetChanged();
        });
        mBtn.addView(allBtn);
        mBtn.addView(noneBtn);
        manualPanel.addView(mBtn);
        root.addView(manualPanel);

        rg.setOnCheckedChangeListener((group, id) -> {
            timePanel.setVisibility(id == 1000 ? View.VISIBLE : View.GONE);
            seqPanel.setVisibility(id == 1001 ? View.VISIBLE : View.GONE);
            manualPanel.setVisibility(id == 1002 ? View.VISIBLE : View.GONE);
        });

        final CheckBox withUin = new CheckBox(act);
        withUin.setText("附带发送者UIN");
        withUin.setChecked(Prefs.bool(Prefs.K_UIN, Prefs.D_UIN));
        root.addView(withUin);

        final boolean md = Prefs.bool(Prefs.K_MD, Prefs.D_MD);
        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("导出聊天记录 · 共 " + all.size() + " 条 · " + key)
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton(md ? "导出Markdown" : "导出TXT", null)
                .setNeutralButton("复制到剪切板", null)
                .create();

        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                List<Msg> picked = resolve(rg.getCheckedRadioButtonId(), all, sel, tFrom, tTo, sFrom, sTo);
                if (picked.isEmpty()) {
                    Toast.makeText(act, "该范围没有消息", Toast.LENGTH_SHORT).show();
                    return;
                }
                rememberUin(act, withUin.isChecked());
                if (md) {
                    try {
                        Md.Result r = Md.export(act, key, picked, withUin.isChecked());
                        Toast.makeText(act, r.summary() + "\n→ " + r.dir, Toast.LENGTH_LONG).show();
                        dlg.dismiss();
                    } catch (Throwable t) {
                        Toast.makeText(act, "导出失败: " + t, Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                String text = Export.build(key, picked, withUin.isChecked());
                try {
                    String path = Export.toFile(act, key, text);
                    Toast.makeText(act, "已导出 " + picked.size() + " 条 → " + path, Toast.LENGTH_LONG).show();
                    dlg.dismiss();
                } catch (Throwable t) {
                    Toast.makeText(act, "写文件失败: " + t, Toast.LENGTH_LONG).show();
                }
            });

            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                List<Msg> picked = resolve(rg.getCheckedRadioButtonId(), all, sel, tFrom, tTo, sFrom, sTo);
                if (picked.isEmpty()) {
                    Toast.makeText(act, "该范围没有消息", Toast.LENGTH_SHORT).show();
                    return;
                }
                rememberUin(act, withUin.isChecked());
                Export.toClipboard(act, Export.build(key, picked, withUin.isChecked()));
                Toast.makeText(act, "已复制 " + picked.size() + " 条", Toast.LENGTH_SHORT).show();
                dlg.dismiss();
            });
        });

        dlg.show();
    }

    /** 记住这次的选择，下次打开面板沿用（并同步给设置页） */
    private static void rememberUin(Activity act, boolean withUin) {
        try {
            Prefs.local(act).edit().putBoolean(Prefs.K_UIN, withUin).apply();
        } catch (Throwable ignored) {
        }
    }

    private static List<Msg> resolve(int modeId, List<Msg> all, boolean[] sel,
                                     EditText tFrom, EditText tTo,
                                     EditText sFrom, EditText sTo) {
        try {
            if (modeId == 1000) {
                long a = IN.parse(tFrom.getText().toString()).getTime();
                long b = IN.parse(tTo.getText().toString()).getTime();
                return Export.byTime(all, Math.min(a, b), Math.max(a, b));
            }
            if (modeId == 1001) {
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

    private static void pickTime(Activity act, EditText target) {
        final Calendar c = Calendar.getInstance();
        new DatePickerDialog(act, (dp, y, m, d) -> {
            Calendar c2 = Calendar.getInstance();
            new TimePickerDialog(act, (tp, hh, mm) -> {
                c2.set(y, m, d, hh, mm, 0);
                c2.set(Calendar.MILLISECOND, 0);
                target.setText(IN.format(c2.getTime()));
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private static TextView hint(Activity act, String s) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(11);
        tv.setPadding(0, dp(act, 6), 0, 0);
        return tv;
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density);
    }
}