package com.opentools.qqexport;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * 桌面入口（点模块图标进来的页面）。
 *
 * <p><b>v1.9 起这里不再放设置项。</b>设置已经搬进 QQ 进程内部（{@link SettingsPanel}）：
 * 桌面 Activity 属于模块进程，写的是模块自己的 qqexport.xml，而 QQ 进程在分区存储下
 * 读不到那个目录，于是「在设置里选了网页、导出还是 Markdown」。同一进程读写才能保证
 * 「改完立即生效、重启 QQ 依然保留」。</p>
 *
 * <p>本页只做三件事：显示是否已激活、说明设置入口在哪、一键打开 QQ。</p>
 */
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.colorBg(this));
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, Ui.dp(this, 28));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        root.addView(Ui.text(this, "QQ 聊天导出", 24, Ui.colorText(this), true));
        root.addView(Ui.text(this, "v" + version() + " · 设置已移到 QQ 内",
                12, Ui.colorSub(this), false), padTop(6));

        root.addView(Ui.section(this, "设置入口"));
        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.textRow(this, "① 长按右下角「导出」圆钮",
                "聊天页右下角的圆钮，点一下是导出面板，长按直接进设置", null));
        c1.addView(Ui.divider(this));
        c1.addView(Ui.textRow(this, "② 导出面板右上角 ⚙",
                "打开导出面板后点标题右侧的齿轮，同样进设置", null));
        Ui.addCard(root, c1);

        root.addView(Ui.section(this, "为什么搬进 QQ"));
        LinearLayout c2 = Ui.card(this);
        note(c2, "桌面这个页面属于模块自己的进程，写下的设置存在模块的私有目录里；"
                + "QQ 进程在分区存储下读不到那个目录，于是设置永远等于默认值——"
                + "这就是以前「选了网页还是导出 Markdown、视频导不出」的原因。");
        c2.addView(Ui.divider(this));
        note(c2, "现在设置由 QQ 进程自己读写（同一份 qqexport.xml、同一个进程），"
                + "改完立刻生效，杀掉 QQ 再进也还在。");
        Ui.addCard(root, c2);

        root.addView(Ui.section(this, "使用步骤"));
        LinearLayout c3 = Ui.card(this);
        note(c3, "1. LSPosed 里启用本模块，作用域勾选 QQ，然后强行停止 QQ 再打开。");
        c3.addView(Ui.divider(this));
        note(c3, "2. 进任意聊天，上下滑一下列表让消息被采集。");
        c3.addView(Ui.divider(this));
        note(c3, "3. 长按圆钮进设置，选「网页 (zip)」——这样才有 media/ 目录，图片能翻、语音能听、视频能放。");
        c3.addView(Ui.divider(this));
        note(c3, "4. 点圆钮打开导出面板，选范围后导出；成品在 /sdcard/Download/QQChatExport/ 下。");
        Ui.addCard(root, c3);

        root.addView(Ui.section(this, "排错"));
        LinearLayout c4 = Ui.card(this);
        note(c4, "导出为空：先在聊天页滑动列表采集消息；重启 QQ 后第一次进聊天页才有圆钮。");
        c4.addView(Ui.divider(this));
        note(c4, "视频只留占位：设置里把「媒体体积上限」调大（或设 0 表示不限制）。");
        c4.addView(Ui.divider(this));
        note(c4, "看不到圆钮：设置里「聊天页悬浮按钮」是否被关掉了；关掉后需要重进聊天页。");
        Ui.addCard(root, c4);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 20);
        root.addView(Ui.button(this, "打开 QQ", true, v -> openQq()), blp);
    }

    private LinearLayout.LayoutParams padTop(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, dp);
        return lp;
    }

    private void note(LinearLayout card, String s) {
        android.widget.TextView tv = Ui.text(this, s, 12, Ui.colorSub(this), false);
        tv.setLineSpacing(Ui.dp(this, 3), 1f);
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        card.addView(tv);
    }

    private void openQq() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.tencent.mobileqq");
        if (i == null) {
            android.widget.Toast.makeText(this, "未安装 QQ", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(i);
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "启动失败: " + t,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
