package com.opentools.qqexport;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块设置页（桌面图标进入）。
 *
 * 这里写的是模块自己进程的 SharedPreferences（qqexport.xml）；
 * QQ 进程里由 {@link Prefs#bool}/{@link Prefs#num} 通过 XSharedPreferences 读同一份文件。
 * 所以改动立即落盘，但要等 QQ 重新走一次注入点（进/出聊天页、或重进多选模式）才读到新值。
 *
 * 不依赖 AndroidX：全部用原生控件拼装，避免为了一个设置页引入 appcompat。
 */
public final class SettingsActivity extends Activity {

    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = Prefs.local(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------- 标题 ---------- */
        TextView title = new TextView(this);
        title.setText("QQ聊天导出");
        title.setTextSize(22);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("版本 " + version() + " · 作用域 com.tencent.mobileqq\n"
                + "需在 LSPosed 中启用本模块并勾选 QQ，然后强制停止 QQ 一次");
        sub.setTextSize(11);
        sub.setPadding(0, dp(4), 0, dp(10));
        root.addView(sub);

        /* ---------- 入口 ---------- */
        header(root, "入口");
        addSwitch(root, "聊天页悬浮按钮", Prefs.K_FLOATING, Prefs.D_FLOATING,
                "聊天界面右下角显示「导出」圆钮，点开是完整筛选面板");
        addSwitch(root, "长按多选栏按钮", Prefs.K_MULTI, Prefs.D_MULTI,
                "长按消息进入多选后，多选栏右侧出现「导出」；已勾选时只导出勾选项");

        /* ---------- 导出格式 ---------- */
        header(root, "导出格式");
        addSwitch(root, "附带发送者 UIN", Prefs.K_UIN, Prefs.D_UIN,
                "开启后形如 昵称(10001): 内容；关闭则只输出昵称");

        /* ---------- 性能 ---------- */
        header(root, "缓存");
        TextView limitDesc = desc("每个会话最多缓存多少条消息（100 ~ 200000）。\n"
                + "缓存只存在内存里，QQ 进程被杀后清空；调小可省内存，调大能导出更长的历史。");
        root.addView(limitDesc);

        LinearLayout limitRow = new LinearLayout(this);
        limitRow.setOrientation(LinearLayout.HORIZONTAL);
        limitRow.setGravity(Gravity.CENTER_VERTICAL);
        limitRow.setPadding(0, dp(4), 0, 0);

        final EditText limitBox = new EditText(this);
        limitBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        limitBox.setText(String.valueOf(sp.getInt(Prefs.K_LIMIT, Prefs.D_LIMIT)));
        limitBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        limitRow.addView(limitBox);

        Button saveLimit = new Button(this);
        saveLimit.setText("保存");
        saveLimit.setOnClickListener(v -> {
            int v2;
            try {
                v2 = Integer.parseInt(limitBox.getText().toString().trim());
            } catch (Throwable t) {
                toast("请输入数字");
                return;
            }
            if (v2 < 100) v2 = 100;
            if (v2 > 200000) v2 = 200000;
            limitBox.setText(String.valueOf(v2));
            sp.edit().putInt(Prefs.K_LIMIT, v2).apply();
            toast("上限已设为 " + v2 + " 条，重进聊天页生效");
        });
        limitRow.addView(saveLimit);
        root.addView(limitRow);

        /* ---------- 调试 ---------- */
        header(root, "调试");
        addSwitch(root, "详细日志", Prefs.K_LOG, Prefs.D_LOG,
                "向 LSPosed 日志输出每条采集到的消息，排查用，平时关掉");

        /* ---------- 说明 ---------- */
        header(root, "说明");
        root.addView(desc(
                "· 消息从 QQ 的 NT 消息实体（AIOMsgItem / MsgRecord）读取，只在 QQ 进程内做内存缓存。\n"
                        + "· 导出文件写到本模块的外部私有目录：\n"
                        + "  Android/data/com.opentools.qqexport/files/QQExport/\n"
                        + "· 不读取本地数据库、不上传任何数据、不需要联网。\n"
                        + "· 只在本人设备上导出本人可见的聊天内容。"));

        Button openQq = new Button(this);
        openQq.setText("打开 QQ");
        openQq.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.tencent.mobileqq");
            if (i == null) {
                toast("未安装 QQ");
                return;
            }
            try {
                startActivity(i);
            } catch (Throwable t) {
                toast("启动失败: " + t);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(openQq, lp);

        setContentView(sv);
    }

    /* ---------------- 小工具 ---------------- */

    private void addSwitch(LinearLayout parent, String label, String key, boolean def, String description) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(sp.getBoolean(key, def));
        cb.setOnCheckedChangeListener((v, checked) -> {
            sp.edit().putBoolean(key, checked).apply();
            toast(label + (checked ? " 已开启" : " 已关闭"));
        });
        parent.addView(cb);
        if (description != null) parent.addView(desc(description));
    }

    private void header(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#3B7CF6"));
        tv.setPadding(0, dp(16), 0, dp(2));
        parent.addView(tv);
    }

    private TextView desc(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(Color.parseColor("#666666"));
        tv.setPadding(0, 0, 0, dp(2));
        return tv;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}