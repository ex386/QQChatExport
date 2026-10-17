package com.opentools.qqexport;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * QQ9.x（NT 内核）注入入口。
 *
 * 采集链路说明：旧版的 ChatAdapter / BaseMsgProxy / MsgProxy 在 9.2.35 里已经全部不存在
 * （在 25 万个类名里命中数为 0），所以整条采集链路改为挂在 NT 的实体层：
 *
 *   AIOMsgItem(列表项) ──构造参数──> MsgRecord(字段名未混淆)
 *
 * 只要能拿到 MsgRecord，msgId / peerUin / senderUin / elements 全都是明文字段，
 * 不再需要猜混淆名。
 *
 * 注入点：
 *   1) AIOMsgItem       → 采集消息进 Store
 *   2) MultiSelectEvent → 旁听多选勾选状态进 Selection（不改它行为）
 *   3) MultiSelectBarVB → 在多选栏右侧插入“导出”按钮
 *   4) AIO Activity     → 聊天页悬浮导出按钮（受设置开关控制）
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG = "com.tencent.mobileqq";

    /** NT 列表项实体 */
    private static final String CLS_ITEM = "com.tencent.mobileqq.aio.msg.AIOMsgItem";
    /** NT 消息记录（字段名未混淆） */
    private static final String CLS_RECORD = "com.tencent.qqnt.kernel.nativeinterface.MsgRecord";

    /** 旧版兼容（9.2.35 命中为 0，保留只为极少数旧包） */
    private static final String[] ADAPTER_CANDIDATES = {
            "com.tencent.mobileqq.activity.aio.adapters.ChatAdapter",
            "com.tencent.mobileqq.activity.aio.adapters.ChatAdapterFor3rdApp",
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PKG.equals(lp.packageName)) return;
        /* v1.9：把 Prefs 绑到 QQ 自己的 Context 上。
           这样设置面板与导出逻辑读写的是同一份 com.tencent.mobileqq 的 qqexport.xml，
           改完同进程立即可见，也不再依赖 XSharedPreferences 跨进程读模块目录（分区存储下读不到）。 */
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app != null) Prefs.attach(app);
        } catch (Throwable t) {
            L.e(t);
        }
        L.setVerbose(Prefs.bool(Prefs.K_LOG, Prefs.D_LOG));
        XposedBridge.log("[QQExport] injected: " + lp.processName);

        try {
            hookMsgItems(lp.classLoader);
        } catch (Throwable t) {
            L.e(t);
        }
        try {
            hookLegacyAdapters(lp.classLoader);
        } catch (Throwable t) {
            L.e(t);
        }
        try {
            MultiSelectHook.install(lp.classLoader);
        } catch (Throwable t) {
            L.e(t);
        }
        try {
            hookAioActivity();
        } catch (Throwable t) {
            L.e(t);
        }
    }

    /* ---------------- 1) NT 消息采集 ---------------- */

    private void hookMsgItems(ClassLoader cl) {
        Class<?> item;
        try {
            item = XposedHelpers.findClass(CLS_ITEM, cl);
        } catch (Throwable t) {
            L.e("AIOMsgItem 不存在: " + t);
            return;
        }

        // 1.1 构造器：参数里带 MsgRecord 的，构造完成后把 AIOMsgItem 自己丢进 Store
        //     （MsgItem 会按“字段类型 = MsgRecord”找到那个被混淆的字段）
        int ctors = 0;
        for (Constructor<?> c : item.getDeclaredConstructors()) {
            boolean has = false;
            for (Class<?> p : c.getParameterTypes()) {
                if (CLS_RECORD.equals(p.getName())) has = true;
            }
            if (!has) continue;
            try {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Store.ingestSingle(p.thisObject);
                        } catch (Throwable t) {
                            L.e(t);
                        }
                    }
                });
                ctors++;
            } catch (Throwable ignored) {
            }
        }

        // 1.2 兜底：任何返回 MsgRecord 的方法（有些版本 record 是后赋值的）
        int getters = 0;
        for (Method m : item.getDeclaredMethods()) {
            if (!CLS_RECORD.equals(m.getReturnType().getName())) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Object r = p.getResult();
                        if (r == null) return;
                        try {
                            Store.ingestSingle(r);
                        } catch (Throwable t) {
                            L.e(t);
                        }
                    }
                });
                getters++;
            } catch (Throwable ignored) {
            }
        }

        XposedBridge.log("[QQExport] AIOMsgItem hooked: ctors=" + ctors + " getters=" + getters);
    }

    /* ---------------- 2) 旧版 Adapter（兼容） ---------------- */

    private void hookLegacyAdapters(ClassLoader cl) {
        for (String cn : ADAPTER_CANDIDATES) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, cl);
            } catch (Throwable t) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && List.class.isAssignableFrom(ps[0])) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                Object a = p.args[0];
                                if (a instanceof List) Store.ingest((List<?>) a);
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
            XposedBridge.log("[QQExport] legacy adapter hooked: " + cn);
        }
    }

    /* ---------------- 3) 聊天页悬浮入口 ---------------- */

    private void hookAioActivity() {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Activity a = (Activity) p.thisObject;
                try {
                    if (!a.getClass().getName().startsWith(PKG)) return;
                    if (!looksLikeChat(a)) return;

                    Prefs.attach(a.getApplicationContext()); // 兜底：handleLoadPackage 时取不到 Application 也不会写错地方
                    Prefs.reload();                 // 让设置页的改动立刻生效
                    L.setVerbose(Prefs.bool(Prefs.K_LOG, Prefs.D_LOG));
                    Store.invalidateLimit();

                    if (Prefs.bool(Prefs.K_FLOATING, Prefs.D_FLOATING)) {
                        FloatingEntry.attach(a);
                    }
                } catch (Throwable t) {
                    L.e(t);
                }
            }
        });
    }

    /** 粗判聊天页：包名路径或字段类型里能看出 ChatPie / AIOContext */
    private static boolean looksLikeChat(Activity a) {
        String n = a.getClass().getName();
        if (n.contains(".activity.aio.") || n.contains("AIOActivity")) return true;
        for (Field f : a.getClass().getDeclaredFields()) {
            String t = f.getType().getName();
            if (t.contains("BaseChatPie") || t.contains("ChatPie") || t.contains("AIOContext")) return true;
        }
        return false;
    }
}
