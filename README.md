# QQChatExport

LSPosed 模块：把 QQ（`com.tencent.mobileqq`）本机聊天记录导出为 **txt** 或写入 **剪切板**，支持三种范围选择：

| 模式 | 依据 | 说明 |
|---|---|---|
| 时间 | `MessageRecord.time` | 起止时间用日期/时间选择器，自动排序 |
| seq | `shmsgseq`（群）/ `uniseq`（C2C） | 输入起始与结束序号，闭区间 |
| 手动勾选 | `uniseq` | 列表逐条勾选，支持全选/全不选；未勾选时按全部处理 |

## 环境

- Android 8.0+（minSdk 21）
- LSPosed / EdXposed（Xposed API 82）
- Root 或 Magisk + LSPosed

## 编译

```bash
./gradlew assembleDebug          # 产物 app/build/outputs/apk/debug/
./gradlew assembleRelease        # 需 keystore.properties，否则不签名
```

## 安装与激活

1. 安装 APK；
2. LSPosed 中启用模块，**作用域勾选 `com.tencent.mobileqq`**；
3. 强制停止 QQ 后重新打开；
4. 进入任意聊天页 → 右下角「导出」按钮 → 选择范围 → 导出TXT / 复制到剪切板。

导出文件位置：`Android/data/com.tencent.mobileqq/files/QQExport/QQ_<frienduin>_<isTroop>_<时间戳>.txt`（App 外部私有目录，无需存储权限）。

## 实现结构

```
MainHook      入口：Adapter / 消息代理层 / 聊天页 Activity 三处 hook
Msg           从 MessageRecord 反射取字段（senderuin/time/msgtype/msg/uniseq/shmsgseq/istroop）
Store         会话级缓存，uniseq 去重，time 排序
Export        范围过滤 + txt/剪切板输出
ExportDialog  范围选择 UI
FloatingEntry 聊天页悬浮入口
```

## 混淆适配

不同 QQ 版本类名会变，改这两处常量即可：

- `MainHook.ADAPTER_CANDIDATES` —— 聊天列表 Adapter（持有 `List<MessageRecord>`）
- `MainHook.HISTORY_CANDIDATES` —— 消息代理层（返回 `List<MessageRecord>`）

定位方法：JADX 打开 QQ，全局搜索 `MessageRecord`，找 `extends BaseAdapter` 且含 `List<MessageRecord>` 字段的类；或找 `getMsg`/`a(String, int, ...)` 返回 `List<MessageRecord>` 的代理类。

首轮验证看日志：`adb logcat | grep QQExport`，出现 `adapter hooked: <类名>` / `history hooked: <类名>` 说明命中。

## 边界

- 仅读取本机当前登录账号已落库的消息，不联网、不上传；
- 图片/语音/文件只输出占位标记，不落地媒体文件；
- 长文本消息（`msg` 被截断为索引）暂未展开 `longMsgIndex`；
- 未登录/未加载的云端历史需先滚动或走 `HISTORY_CANDIDATES` 路径拉取。

## License

MIT
