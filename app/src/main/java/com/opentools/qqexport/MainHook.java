package com.opentools.qqexport;

import android.app.Activity;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG = "com.tencent.mobileqq";

    /** 混淆适配点 A：聊天列表 Adapter（JADX 搜 "extends BaseAdapter" + 持有 List<MessageRecord>） */
    private static final String[] ADAPTER_CANDIDATES = {
            "com.tencent.mobileqq.activity.aio.adapters.ChatAdapter",
            "com.tencent.mobileqq.activity.aio.adapters.ChatAdapterFor3rdApp",
    };

    /** 混淆适配点 B：消息代理层，用于读取完整历史记录 */
    private static final String[] HISTORY_CANDIDATES = {
            "com.tencent.imcore.message.BaseMsgProxy",
            "com.tencent.imcore.message.QQMessageFacade",
            "com.tencent.mobileqq.app.message.MsgProxy",
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PKG.equals(lp.packageName)) return;
        XposedBridge.log("[QQExport] injected into " + lp.processName);

        hookAdapters(lp.classLoader);
        hookHistory(lp.classLoader);
        hookAioActivity();
    }

    /** 从 Adapter 双向抓：setList(List) 入参 + getItem(int) 返回值 */
    private void hookAdapters(ClassLoader cl) {
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
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object a = p.args[0];
                            if (a instanceof List) Store.ingest((List<?>) a);
                        }
                    });
                }

                if (ps.length == 1 && ps[0] == int.class && m.getReturnType() == Object.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (p.getResult() != null) Store.ingestSingle(p.getResult());
                        }
                    });
                }
            }
            XposedBridge.log("[QQExport] adapter hooked: " + cn);
        }
    }

    /** 兜底：消息代理层所有返回 List 的方法，结果中凡 MessageRecord 一律入缓存 */
    private void hookHistory(ClassLoader cl) {
        for (String cn : HISTORY_CANDIDATES) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, cl);
            } catch (Throwable t) {
                continue;
            }

            for (Method m : c.getDeclaredMethods()) {
                if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object r = p.getResult();
                            if (r instanceof List) Store.ingest((List<?>) r);
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log("[QQExport] history hooked: " + cn);
        }
    }

    /** 只对聊天页注入悬浮入口 */
    private void hookAioActivity() {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Activity a = (Activity) p.thisObject;
                if (!a.getClass().getName().startsWith(PKG)) return;
                if (!looksLikeChat(a)) return;
                try {
                    FloatingEntry.attach(a);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    private static boolean looksLikeChat(Activity a) {
        if (a.getClass().getName().contains(".activity.aio.")) return true;
        for (java.lang.reflect.Field f : a.getClass().getDeclaredFields()) {
            String t = f.getType().getName();
            if (t.contains("BaseChatPie") || t.contains("ChatPie")) return true;
        }
        return false;
    }
}
