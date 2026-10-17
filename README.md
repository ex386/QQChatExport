# QQChatExport

LSPosed 模块：把 QQ（`com.tencent.mobileqq`）本机聊天记录导出为 **网页（HTML + 媒体，整体打包成 zip）**、**Markdown + 媒体文件**，或单个 **TXT**，支持三种范围选择：

| 模式 | 依据 | 说明 |
|---|---|---|
| 时间 | `MessageRecord.time` | 起止时间用日期/时间选择器，自动排序 |
| seq | `shmsgseq`（群）/ `uniseq`（C2C） | 输入起始与结束序号，闭区间 |
| 手动勾选 | `uniseq` | 列表逐条勾选，支持全选/全不选；跨会话勾选时按会话分组各自导出 |

## 环境

- Android 8.0+（minSdk 21）
- LSPosed / EdXposed（Xposed API 82）
- Root 或 Magisk + LSPosed

## 编译

```bash
./gradlew assembleDebug          # 产物 app/build/outputs/apk/debug/
./gradlew assembleRelease        # 需 keystore.properties，否则不签名
```

CI 在 `.github/workflows/build.yml`：push 到 `main` 出 artifact，打 `v*` tag 自动发 Release。

## 安装与激活

1. 安装 APK；
2. LSPosed 中启用模块，**作用域勾选 `com.tencent.mobileqq`**；
3. 强制停止 QQ 后重新打开；
4. 桌面图标进入模块设置页，按需开关导出入口与格式；
5. 聊天页右下角「导出」悬浮钮（或长按消息进多选后点「导出」）→ 选范围 → 导出。

## 导出产物

默认网页版：每个会话一个独立目录，并整体打包成同名 zip

```
Android/data/com.tencent.mobileqq/files/QQExport/<会话>_<时间>/
├── index.html   正文全部内联（气泡布局，我/对方左右区分）
├── media/       图片、表情、语音、视频、文件
└── <会话>_<时间>.zip   上面整个目录的打包（分享/发给别人就传这个）
```

`index.html` 里媒体用相对路径 `media/xxx` 引用，解压即可离线打开；图片点开原图、语音 `<audio>` 直接播、视频 `<video>` 可播放（带封面）。找不到的媒体留 `[图片]`/`[视频文件未找到，仅封面]` 之类占位。

另外两种格式：

- **Markdown**：`chat.md` + `images/`
- **纯文本**：单文件 `QQ_<frienduin>_<isTroop>_<时间戳>.txt`（不含媒体）

均为 App 外部私有目录，无需存储权限；但 **Android 11+ 系统自带文件管理器看不到 `Android/data`**，需用支持该目录的第三方文件管理器。

## 设置项

| 分组 | 项 | 默认 | 说明 |
|---|---|---|---|
| 入口 | 聊天页悬浮按钮 | 开 | 聊天界面右下角「导出」圆钮 |
| 入口 | 长按多选栏按钮 | 开 | 多选栏右侧「导出」，已勾选时只导出勾选项 |
| 导出格式 | 导出格式 | 网页 | 三选一：网页（index.html + media/ 打成 zip）/ Markdown / 纯文本 |
| 导出格式 | 复制媒体文件 | 开 | 关闭后媒体只留文字占位 |
| 导出格式 | 媒体体积上限 | 50 MB | 单文件超限不复制，0 表示不限制 |
| 显示 | 附带发送者 UIN | 开 | 形如 `昵称(10001): 内容` |
| 缓存 | 每会话缓存条数 | 20000 | 仅内存，QQ 进程结束即清空 |
| 调试 | 详细日志 | 关 | 向 LSPosed 日志逐条打印采集结果 |

设置写入模块自身进程的 `qqexport.xml`，QQ 进程侧通过 `XSharedPreferences` 读取，并做了节流 reload（≤1s）与缓存上限 3s 重读，**改完设置不用重启 QQ**。

## 实现结构

```
MainHook         入口：Adapter / 消息代理层 / 聊天页 Activity 三处 hook
Msg              从 MessageRecord 反射取字段（senderuin/time/msgtype/msg/uniseq/shmsgseq/istroop）
Store            会话级缓存，uniseq 去重，time 排序，缓存上限热更新
Export           范围过滤 + txt/剪切板输出
ExportDialog     单会话范围选择 UI（时间/seq/手动）
PickedExport     多选结果直出，跨会话分组
MultiSelectHook  多选栏右侧注入「导出」，旁听 MultiSelectEvent 同步勾选
FloatingEntry    聊天页悬浮入口
Html             导出网页（v1.6 默认）：文字内联 index.html + media/，整体打包 zip
Md               导出 Markdown：正文渲染 + 媒体复制（判型/去重/体积上限/缺失计数）
Fmt              导出格式 HTML/MD/TXT 的统一读写（设置页与各入口共用）
Prefs            跨进程设置读写（XSharedPreferences + 节流 reload）
SettingsActivity 模块设置页（卡片式 UI，无 AndroidX）
Ui               原生控件拼装的轻量 UI 工具（卡片/分组/开关行/按钮/圆角）
```

## 混淆适配

不同 QQ 版本类名会变，改这两处常量即可：

- `MainHook.ADAPTER_CANDIDATES` —— 聊天列表 Adapter（持有 `List<MessageRecord>`）
- `MainHook.HISTORY_CANDIDATES` —— 消息代理层（返回 `List<MessageRecord>`）

定位方法：JADX 打开 QQ，全局搜索 `MessageRecord`，找 `extends BaseAdapter` 且含 `List<MessageRecord>` 字段的类；或找 `getMsg`/`a(String, int, ...)` 返回 `List<MessageRecord>` 的代理类。

首轮验证看日志：`adb logcat | grep QQExport`，出现 `adapter hooked: <类名>` / `history hooked: <类名>` 说明命中。

## 边界

- 仅读取本机当前登录账号已落库（或已加载）的消息，不读数据库、不联网、不上传；
- 媒体文件来自 QQ 本地缓存，缓存被清理时只输出 `*[图片]*` / `*[视频]*` 之类占位并计入「缺失」统计；
- 媒体匹配顺序：元素里的本地路径 → `fileUuid` 文件名前缀（视频主力，`cache/superplayer` 里就是 `<uuid>_<ts>_1.mp4`）→ 同体积且 md5 相同 → 同体积唯一 → 同名文件；同名 `.property` 标签文件一律跳过；
- 长文本消息（`msg` 被截断为索引）暂未展开 `longMsgIndex`；
- 未登录/未加载的云端历史需先滚动或走 `HISTORY_CANDIDATES` 路径拉取；
- 单个会话缓存有上限（默认 20000 条），超出部分靠滚动补采。
