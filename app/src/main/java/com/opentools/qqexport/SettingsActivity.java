package com.opentools.qqexport;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块设置页（桌面图标进入）。
 *
 * 写的是模块自己进程的 SharedPreferences（qqexport.xml）；QQ 进程里由
 * {@link Prefs#bool}/{@link Prefs#num} 通过 XSharedPreferences 读同一份文件。
 * Prefs 侧做了节流 reload，所以改完设置最多一秒就对 QQ 进程生效，不用重启。
 *
 * 不依赖 AndroidX：全部用原生控件 + {@link Ui} 拼装。
 */
public final class SettingsActivity extends Activity {

    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = Prefs.local(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.colorBg(this));
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, Ui.dp(this, 28));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------------- 标题 ---------------- */
        root.addView(Ui.text(this, "QQ聊天导出", 22, Ui.colorText(this), true));
        TextView ver = Ui.text(this, "v" + version() + " · 作用域 com.tencent.mobileqq · 本地运行",
                11, Ui.colorSub(this), false);
        ver.setPadding(0, Ui.dp(this, 5), 0, 0);
        root.addView(ver);
        TextView tip = Ui.text(this, "在 LSPosed 中启用本模块并勾选 QQ；设置改完即时生效",
                11, Ui.colorSub(this), false);
        tip.setPadding(0, Ui.dp(this, 3), 0, 0);
        root.addView(tip);

        /* ---------------- 入口 ---------------- */
        root.addView(Ui.section(this, "入口"));
        LinearLayout entry = Ui.card(this);
        addSwitch(entry, true, "聊天页悬浮按钮",
                "聊天界面右下角显示「导出」圆钮，点开是完整筛选面板",
                Prefs.K_FLOATING, Prefs.D_FLOATING);
        addSwitch(entry, false, "长按多选栏按钮",
                "长按消息进入多选后，多选栏右侧出现「导出」；已勾选时只导出勾选项",
                Prefs.K_MULTI, Prefs.D_MULTI);
        Ui.addCard(root, entry);

        /* ---------------- 导出格式 ---------------- */
        root.addView(Ui.section(this, "导出格式"));
        LinearLayout fmt = Ui.card(this);
        addSwitch(fmt, true, "导出为 Markdown",
                "输出 chat.md + images/ 目录，图片、语音、视频、文件一并打包；关闭则退回单个 TXT",
                Prefs.K_MD, Prefs.D_MD);
        addSwitch(fmt, false, "复制媒体文件",
                "把聊天里的图片/表情/语音/视频/文件复制进 images/；关闭后只保留文字占位",
                Prefs.K_MEDIA, Prefs.D_MEDIA);
        addNumberRow(fmt, false, "媒体体积上限",
                "单个文件超过该值时不复制，只留文字占位。0 表示不限制",
                Prefs.K_MEDIA_MAX_MB, Prefs.D_MEDIA_MAX_MB, 0, 4096, "MB");
        Ui.addCard(root, fmt);

        /* ---------------- 显示 ---------------- */
        root.addView(Ui.section(this, "显示"));
        LinearLayout show = Ui.card(this);
        addSwitch(show, true, "附带发送者 UIN",
                "开启后形如 昵称(10001): 内容；关闭则只输出昵称",
                Prefs.K_UIN, Prefs.D_UIN);
        Ui.addCard(root, show);

        /* ---------------- 缓存 ---------------- */
        root.addView(Ui.section(this, "缓存"));
        LinearLayout cache = Ui.card(this);
        addNumberRow(cache, true, "每会话缓存条数",
                "100 ~ 200000，只存在内存里；QQ 进程被杀后清空。调大能导出更长的历史",
                Prefs.K_LIMIT, Prefs.D_LIMIT, 100, 200000, "条");
        Ui.addCard(root, cache);

        /* ---------------- 调试 ---------------- */
        root.addView(Ui.section(this, "调试"));
        LinearLayout dbg = Ui.card(this);
        addSwitch(dbg, true, "详细日志",
                "向 LSPosed 日志输出每条采集到的消息，排查用，平时关掉",
                Prefs.K_LOG, Prefs.D_LOG);
        Ui.addCard(root, dbg);

        /* ---------------- 说明 ---------------- */
        root.addView(Ui.section(this, "说明"));
        LinearLayout doc = Ui.card(this);
        addNote(doc, "· 消息从 QQ 的 NT 消息实体读取，只在 QQ 进程内做内存缓存，不读数据库、不联网、不上传。");
        addNote(doc, "· 导出落在 QQ 自己的外部私有目录，每个会话一个子目录：");
        addNote(doc, "   Android/data/com.tencent.mobileqq/files/QQExport/<会话>_<时间>/");
        addNote(doc, "   chat.md 是正文，images/ 里是图片、表情、语音、视频、文件。");
        addNote(doc, "· 关闭「导出为 Markdown」时退回单个 TXT：QQ_<会话>_<时间>.txt（不含媒体）。");
        addNote(doc, "· Android 11+ 系统自带文件管理器看不到 Android/data，请用支持该目录的文件管理器打开。");
        Ui.addCard(root, doc);

        root.addView(Ui.button(this, "打开 QQ", true, v -> openQq()));

        setContentView(sv);
    }

    /* ---------------- 行构造 ---------------- */

    private void addSwitch(LinearLayout card, boolean first, String title, String sub,
                           final String key, boolean def) {
        if (!first) card.addView(Ui.divider(this));
        card.addView(Ui.switchRow(this, title, sub, sp.getBoolean(key, def), on -> {
            sp.edit().putBoolean(key, on).apply();
            toast(title + (on ? " 已开启" : " 已关闭"));
        }));
    }

    private void addNumberRow(LinearLayout card, boolean first, String title, String sub,
                              final String key, int def, final int min, final int max,
                              final String unit) {
        if (!first) card.addView(Ui.divider(this));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        final EditText et = Ui.numPill(this, String.valueOf(sp.getInt(key, def)));
        box.addView(et);
        TextView ut = Ui.text(this, unit, 12, Ui.colorSub(this), false);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.leftMargin = Ui.dp(this, 4);
        box.addView(ut, ulp);
        TextView save = Ui.textButton(this, "保存", v -> {
            int val;
            try {
                val = Integer.parseInt(et.getText().toString().trim());
            } catch (Throwable t) {
                toast("请输入数字");
                return;
            }
            if (val < min) val = min;
            if (val > max) val = max;
            et.setText(String.valueOf(val));
            sp.edit().putInt(key, val).apply();
            toast(title + " 已设为 " + val + unit);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        box.addView(save, lp);
        card.addView(Ui.textRow(this, title, sub, box));
    }

    private void addNote(LinearLayout card, String s) {
        TextView tv = Ui.text(this, s, 12, Ui.colorSub(this), false);
        tv.setLineSpacing(Ui.dp(this, 3), 1f);
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        card.addView(tv);
    }

    /* ---------------- 杂项 ---------------- */

    private void openQq() {
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
}
