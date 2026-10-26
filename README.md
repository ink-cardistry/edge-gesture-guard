# 边缘手势守卫 · EdgeGestureGuard

LSPosed 模块：**按前台应用 / Activity 动态屏蔽「左边缘返回」「右边缘返回」「底部上滑手势」**。
只 Hook `com.android.systemui`，不碰目标应用进程，不改系统分区，不做 Magisk 模块。

| 项 | 值 |
|---|---|
| 模块包名 | `com.edgeguard.gesture` |
| LSPosed 作用域 | **必需：System UI（`com.android.systemui`）**；可选：Launcher（仅策略 B，默认关闭） |
| Hook 入口类 | `com.edgeguard.gesture.xposed.XposedEntry`（`assets/xposed_init`） |
| 日志 TAG | `EdgeGestureGuard` |
| 已构建产物 | `dist/EdgeGestureGuard-1.5.7-debug.apk`（debug 签名；MD5 见第 16 节构建表）|
| 真机验证 | **LineageOS 24 / Android 17 (SDK 37) 实测通过**：左右返回屏蔽生效 |
| 1.4.0 新增 | 顶部通知栏下拉屏蔽（SystemUI 内）；底部上滑策略 A 坐标判断 + 自检计数；策略 B（Launcher 进程，默认关）；音游快捷预设 |
| **1.5.0 新增** | **游戏模式会话**（寄生通知 / 控制中心磁贴开启，**锁屏后解锁自动退出**）；状态卡改为「摘要 + 可折叠详情」 |

### 0.1 真机实测结论（LineageOS 24 / Android 17）

AOSP 16/17 的手势层与网上多数资料（A12–A15 写法）差别很大，以下是**解包设备 SystemUI.apk 核对 + 真机验证**的结果：

| 环节 | 真实类/方法 | 说明 |
|---|---|---|
| 手势输入 | `InputChannelCompat$InputEventReceiver$1#onInputEvent(InputEvent)` | 继承 `android.view.InputEventReceiver`，持 `val$listener`；**必须**在它上面调 `finishInputEvent(event, true)`，否则输入通道会卡住 |
| 通道过滤 | `val$listener` 类名命中 `DisplayBackGestureHandlerImpl` / `EdgeBackGestureHandler`（1.5.1 起两者都接受） | 同一个 receiver 类也被导航条 home handle 等复用，不能无条件拦 |
| 执行返回 | `EdgeBackGestureHandler$5#triggerBack(boolean)` | `mBackCallback` 字段的类型；A17 里**不是** `onBackInvoked` |
| 左右判定 | `EdgeBackGestureHandler.mIsOnLeftEdge` | 在 callback 里通过 `this$0` 拿到 handler 再读 |
| 旧入口 | `EdgeBackGestureHandler#onMotionEvent` | **A17 已删除**，仅 A12–A15 有效 |
| Context | `ActivityThread.getSystemContext()` → `createPackageContext("com.android.systemui")` | 早期写 `Settings.Global` 必须用包名与 uid 匹配的 context，否则报 `SecurityException: Given calling package android does not match caller's uid` |
| 通知栏下拉 | SystemUI 的 shade 触摸链（`NotificationShadeWindowView` / 面板控制器 / `ShadeTouchEventHandler`） | **在 SystemUI 内**，可 Hook；但类名随 A14/15/16 重构挪过包，本版按多候选探测（见第 13 节） |
| 底部上滑 | `com.android.quickstep.TouchInteractionService`（**Launcher 进程**）+ `InputManager.monitorGestureInput()` | **不在 SystemUI**；SystemUI 侧只能靠"返回手势 gesture monitor 是否也能收到该事件"来赌（第 12 节，本版给出实测判定方法） |

实测日志（LSPosed 模块日志 `modules.log`）：

```
edge DOWN side=LEFT block=true x=… fg=com.android.settings rules=1
consumed input event (action=0)
backInvoked: left=true block=true fg=com.android.settings
blocked triggerBack (EdgeBackGestureHandler$5)
```

> 部署注意：**装完新 APK 后不要立刻重启**。LSPosed 需要先登记新 APK（数十秒），
> 立刻重启会让 SystemUI 加载上一版 dex（日志里会出现旧的 `v=x.y.z`）。
> 正确顺序：装包 → 打开一次 LSPosed Manager → 等约 1 分钟 → 重启。

---

## 0. 一句话原理

返回手势（左/右边缘）**完全在 SystemUI 进程内**处理，但入口在 Android 16/17 变了：

| 版本 | 触摸入口 | 本模块做法 |
|---|---|---|
| A12 ~ A15 | `EdgeBackGestureHandler.onMotionEvent(MotionEvent)` | Hook 它，被屏蔽侧整条手势流不喂原方法 |
| A16 / A17 | `DisplayBackGestureHandlerImpl$inputEventReceiver$1.onInputEvent(InputEvent)`（InputEventReceiver + gesture monitor；`onMotionEvent` 已被 AOSP 删除） | Hook 它，先 `finishInputEvent(event, true)` 再阻止原逻辑 |

两种入口的屏蔽语义相同：**被屏蔽侧那条手势不喂给原处理器**，它的状态机不启动，
返回就不触发；未屏蔽的一侧完全不动参数，行为与原生一致。

底部上滑在 AOSP 里**不在 SystemUI**：由 Launcher3/Quickstep 的
`TouchInteractionService` 通过 `InputManager` 手势通道（`monitorGestureInput`）接收。
因此本模块对底部手势采用「探测到 SystemUI 侧真实入口才启用，否则明确降级」的策略（见第 6 节）。

---

## 1. 目录结构

```
edge-gesture-guard/
├── settings.gradle / build.gradle / gradle.properties
├── README.md
├── dist/EdgeGestureGuard-1.1.0-debug.apk      # 已构建好的 debug 签名 APK
├── tools/
│   ├── build-local.sh                          # 无 Gradle 的离线打包脚本
│   └── verify-apk.py                          # 校验：Xposed 未打包 + arsc 对齐
├── app/
│   ├── build.gradle                            # compileOnly Xposed API
│   ├── proguard-rules.pro
│   ├── libs/api-82.jar                         # 内置，离线可编译
│   └── src/main/
│       ├── AndroidManifest.xml                 # LSPosed 模块声明 + 组件
│       ├── assets/xposed_init
│       ├── java/com/edgeguard/gesture/
│       │   ├── common/                         # App 与 Hook 进程共用
│       │   │   ├── Logx.java                   统一日志（TAG 固定）
│       │   │   ├── ModuleContract.java         包名/action/authority/extra
│       │   │   ├── Rule.java                   规则模型（包名+可选 Activity+3 个布尔）
│       │   │   ├── RuleSet.java                不可变规则表 + 决策（含全局 config）
│       │   │   ├── ModuleConfig.java           顶部/底部区比例、验证模式、Launcher 开关
│       │   │   ├── StatusReport.java           Hook 状态报告
│       │   │   ├── RuleStore.java              App 侧 SharedPreferences 持久化
│       │   │   ├── ModuleBroadcasts.java       IPC 发送封装
│       │   │   └── RulesProvider.java          ContentProvider 兜底拉取
│       │   ├── xposed/                         # 只会加载进 SystemUI 进程
│       │   │   ├── XposedEntry.java            LSPosed 入口（只认系统 UI）
│       │   │   ├── GestureHooks.java           Hook 安装器（异常全捕获）
│       │   │   ├── Probe.java                  类/字段探测（不硬编码类名）
│       │   │   ├── EdgeMetrics.java            边缘宽度（反射字段 + 兜底）
│       │   │   ├── HookLogic.java              屏蔽决策 + 整条事件流吞掉
│       │   │   ├── ForegroundTracker.java      前台应用三档降级
│       │   │   ├── RulesPipe.java              广播 / Provider / 本地缓存
│       │   │   ├── HookRuntime.java            进程内共享状态
│       │   │   ├── HookStatus.java             状态快照
│       │   │   ├── Reflect.java                反射工具箱
│       │   │   ├── Targets.java                Hook 目标候选表（唯一名字清单）
│       │   │   ├── Zones.java                  顶部/底部区域判定（屏幕高度缓存）
│       │   │   ├── InputSink.java              finishInputEvent 收尾（返回/底部共用）
│       │   │   ├── ShadeHooks.java             通知栏下拉 Hook（多候选类 + 探测日志）
│       │   │   └── LauncherBottomHooks.java    策略B：Launcher 进程底部 Hook（默认关）
│       │   └── app/                            # 控制器 UI
│       │       ├── MainActivity.java
│       │       ├── AppListAdapter.java
│       │       ├── AppListLoader.java
│       │       ├── AppEntry.java
│       │       ├── StatusBus.java
│       │       ├── SyncReceiver.java
│       │       ├── StatusReceiver.java
│       │       └── ForegroundAccessibilityService.java（可选前台助手）
│       └── res/                                # layout / values / values-night / xml / drawable
```

---

## 2. 编译

### 2.1 Android Studio / Gradle（推荐）

```bash
# JDK 17+、Android SDK Platform 35
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Xposed API 必须是 `compileOnly`（`app/build.gradle` 已配好）：

```groovy
compileOnly files('libs/api-82.jar')
// 或联网时：compileOnly 'de.robv.android.xposed:api:82'
```

如果误改成 `implementation`，APK 会带上 `de/robv/android/xposed/*`，
**LSPosed 会拒绝加载**。自查：

```bash
python3 tools/verify-apk.py app/build/outputs/apk/debug/app-debug.apk
# OK  : dex 内没有任何 Xposed API 定义（compileOnly 生效）
```

其他：模块不允许混淆（反射依赖类名）；不依赖 AndroidX；`compileSdk 35`。
若本地只有 SDK 36/37，把 `compileSdk`/`targetSdk` 一起调高即可（代码只用到 API ≤ 34 的公开接口）。

### 2.2 无 Gradle 的离线构建（本机验证用）

```bash
ANDROID_JAR=/path/to/android.jar \
AAPT2=/path/to/aapt2 \
D8_JAR=/path/to/build-tools/lib/d8.jar \
UBER_JAR=/path/to/uber-apk-signer.jar \
JAVA=/path/to/java JAVAC=/path/to/javac \
sh tools/build-local.sh
```

### 2.3 本仓库已做过的真实编译验证

| 步骤 | 工具 | 结果 |
|---|---|---|
| 资源编译/链接 | `aapt2 2.20-android-16.0.0_r4` | 通过（9 个资源文件、manifest、themes、string-array 全部合法） |
| Java 编译 | `javac 17.0.20` + `android-35/android.jar` + `api-82.jar` | 通过，**55 个 class，0 error** |
| dex | `d8.jar`（build-tools 35，`--min-api 30`） | 通过，classes.dex 67 KB |
| 打包/签名 | `uber-apk-signer 1.3.0 --skipZipAlign` | v1+v2+v3 签名验证通过 |
| 结构校验 | `tools/verify-apk.py` | PASS（dex 内 0 个 Xposed 类；`resources.arsc` stored + 4 字节对齐） |

产物：`dist/EdgeGestureGuard-1.1.0-debug.apk`（56 个 class，MD5 见 `dist/` 目录旁注）。

> 说明：签名是 **debug 证书**（CN=Android Debug）。模块不涉及与系统 App 的签名关系，
> debug 签名完全可用；如果你要长期使用，自行用 keytool 重新签名即可。

---

## 3. 安装与 LSPosed 配置

> **Android 17 / LineageOS 24 前置要求（重要）**
> Android 17 上 LSPosed 需要对应分支才可能稳定 Hook SystemUI。
> 装本模块之前，请先确认 LSPosed **基础 Hook 能力**正常（任意一个能在 SystemUI 生效的
> 日志型/主题型模块可用）。确认后再装本模块，并用状态卡核对探测结果。

1. 安装 APK（`adb install -r dist/EdgeGestureGuard-1.1.0-debug.apk`）。
2. 打开 LSPosed → 模块 → 启用「边缘手势守卫」。
3. **作用域只勾 `System UI`**（清单里用 `xposedscope` 预填了，一般会自动勾好）。
4. 重启 SystemUI 或重启手机（SystemUI 重启后 Hook 才会注入）。
5. 打开「边缘手势守卫」，顶部状态卡应显示 **「左右返回 Hook：已挂载」**。
   若显示"未挂载" → 见第 9 节排障。

---

## 4. 使用

1. 搜索框输入应用名/包名；勾「包含系统应用」可看到系统组件。
2. 点一行展开，四个复选框：
   - 屏蔽左边缘返回 / 屏蔽右边缘返回（SystemUI 内，可靠性最高）；
   - **屏蔽顶部下拉通知栏**（SystemUI 内，见第 13 节）；
   - **屏蔽底部上滑（实验性）**（见第 12 节：能不能生效由状态卡的 `seen=` 实测计数决定）。
   复选框始终可点 —— 即使当前没探测到入口也允许你先配好规则，真实是否生效看状态卡，不看复选框。
3. 勾选即保存 + 立刻广播给 SystemUI，**下一次触摸即生效**（不用重启应用或 SystemUI）。
4. 四个都不勾 = 删除该应用规则 = 恢复系统默认。
5. 切换应用立即按新应用自身规则生效。
6. 底部工具条：
   - **音游预设（顶+底）**：对"最近前台应用"一键打开顶部下拉 + 底部上滑，**左右返回保持原设置**；
   - **阈值/验证**：调顶部/底部区比例（2%~30%）、打开「验证模式（只记日志不拦截）」、
     打开「策略 B（Launcher 进程拦底部，需手工扩展作用域）」。
7. **游戏模式（1.5.0，会话式）**：不用逐个勾应用，从「常驻通知按钮 / 控制中心磁贴 / 控制器里的
   「游戏模式」按钮」任意一个入口打开，顶部下拉 + 底部上滑就对**所有应用**生效；
   **锁屏后再解锁会自动退出**。详见第 15 节。
8. **状态卡默认折叠**：只显示 3 行摘要（左右/顶部/底部状态 + 游戏模式 + 前台 + 告警数），
   点「详情 ▾」才展开 200dp 的可滚动详情 —— 不会再把你下面的应用列表顶出屏幕。

规则同时支持 Activity 级（Activity 级优先于包级）：

```json
{
  "version": 1,
  "rules": [
    {"package": "com.example.game", "left": true, "right": true, "bottom": false},
    {"package": "com.example.game", "activity": "com.example.game.MainActivity", "left": true}
  ]
}
```

UI 目前编辑包级规则；Activity 级规则模型、IPC、匹配逻辑都已完整实现（后续只在 UI 层加写入口即可）。

---

## 5. 关键 Hook 点清单

| # | 目标 | 探测方式 | Hook 方法 | 屏蔽方式 | 失败降级 |
|---|---|---|---|---|---|---|
| 1a | 左右返回（A12–A15 旧架构） | ① 类名提示 `…gestural.EdgeBackGestureHandler`；② 扫 `NavigationBar/NavigationBarView/…` 字段类型名含 `EdgeBack` | `onMotionEvent` / `onInputEvent` / `handleMotionEvent`…（参数里含 `MotionEvent`/`InputEvent` 即挂，**全部**挂载） | DOWN 判侧 → 整条手势流不调用原方法 + 直接返回真值 | 探测不到 → 不挂载 + 日志 + 状态卡显示 0 |
| 1b | 左右返回（A16/A17 输入层） | ① 直接加载 `com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver$1`；② 从包装类 `InputChannelCompat$InputEventReceiver` 的内部类里找带 `val$listener` 的子类 | `onInputEvent(InputEvent)`，按 `val$listener` 类型名过滤（只处理返回手势通道） | 在该 receiver 实例上调 `finishInputEvent(event,true)` 后再阻止原逻辑 | finish 失败 → 撤销屏蔽、放行（宁可不生效也不卡输入） |
| 1c | 左右返回（A16/A17 执行层，最终兜底） | 读 `EdgeBackGestureHandler.mBackCallback` 的字段类型（`EdgeBackGestureHandler$5`），内部类兜底搜索 | `triggerBack(boolean)`（A17 真实触发点；`onBackInvoked` 仅作兼容候选） | 命中规则 → `param.setResult(null)` 不调用原方法；左右由 `this$0.mIsOnLeftEdge` 判定 | 读不到 `mIsOnLeftEdge` → 不屏蔽（安全默认） |
| 2 | 边缘宽度 / 屏幕宽度 | 反射 `mEdgeWidthLeft/mEdgeWidthRight/mBackGestureInsets/mDisplayWidth/mDisplaySize(Point)`；新架构匿名类再往 `this$0` 找 `displaySize` | 只读 | 新架构改用 gesture monitor 语义（事件本身就在边缘区）按屏幕左右半区判定 | 兜底 `max(24dp, config_backGestureInset, 宽 4%)`，宁窄勿宽 |
| 5 | **顶部下拉通知栏（功能二）** | 多候选类名加载（`shade.NotificationShadeWindowView` / `shade.NotificationShadeWindowViewController` / `shade.NotificationPanelViewController` / `statusbar.phone.NotificationPanelViewController` / `shade.ShadeTouchEventHandler` / `statusbar.phone.PhoneStatusBarView` / `statusbar.phone.StatusBarTouchHandler`），类内扫 `onInterceptTouchEvent` `onTouchEvent` `dispatchTouchEvent` `handleDispatchTouchEvent` `shouldInterceptTouchEvent` `handleTouchEvent` `onQsIntercept` `handleMotionEvent` `onMotionEvent`（含无参 `onQsIntercept()`） | 谁存在挂谁（多个类都挂，互不冲突） | 命中「规则.blockTopShade + 起手 rawY 在顶部区 + 通知栏未展开」→ 整条事件流不喂原方法、`setResult(true)` | 一个候选都没挂上 → `shadeState=unsupported`，UI 明说"不生效"；`shade candidates` 日志列出每个候选的探测结果 |
| 6 | **底部上滑（功能一，策略 A）** | 复用 1b 的返回手势 gesture monitor 通道，额外读 `rawY` 判底部区（`WindowManager.getCurrentWindowMetrics()`，公开 API） | 不新增 Hook 点，只在既有回调里加坐标判断 | 命中「规则.blockBottom + 起手 rawY 在底部区」→ 与左右返回同一套 `finishInputEvent` 吞流 | **输入通道收不到底部事件时 seen=0**：状态卡直接写"策略 A 不可用"，绝不假装生效；此时只能走策略 B |
| 7 | 底部上滑（功能一，策略 B，默认关） | 仅在 Launcher 进程被 LSPosed 注入时：`Targets.LAUNCHER_TOUCH_CLASSES`（`quickstep.TouchInteractionService` 等）+ 其内部类 | `onInputEvent` / `onMotionEvent` / `handleMotionEvent` / `dispatchTouchEvent` / `onTouchEvent` | 命中「`config.launcherBottom` + 规则.blockBottom + 起手在底部区」→ `finishInputEvent` 后吞流 | 拿不到 `finishInputEvent`（入口是 listener）→ 明确放弃并只记一条日志，防止输入卡死 |
| 3 | 底部手势 | ① 类名提示 `HomeHandle`；② 字段类型扫描 | 同上（只接受 SystemUI 自己声明的方法） | 同一套闸门（`blockBottom`） | 探测不到 → `unsupported`，UI 置灰 + 明确提示降级 |
| 4 | 前台应用 | `ActivityTaskManager` 反射 → 无障碍推送 → `UsageStatsManager` | `getFocusedRootTaskInfo` / `getTasks(…)` 多形态 + `queryEvents` | 400ms 缓存 + 失败冷却；拿不到 → 不屏蔽 | 三档全失败 → 安全默认"不屏蔽" |

实现要点：

- **为什么不用 `mAllowGesture=false`？**
  AOSP 的 `onMotionEvent` 在 DOWN 时会自己重算该字段，外部改写可能被覆盖，也可能与
  ROM 的额外手势补丁（如 long swipe affordance）冲突。整条事件流不喂给处理器，
  语义最干净、跨版本最稳，且天然不影响未屏蔽的一侧。
- **A16/17 为什么必须 `finishInputEvent`？**
  新架构的触摸来自 `InputEventReceiver`。事件必须由处理方 finish，否则输入通道一直等不到
  "已处理"，表现就是手势后触摸卡死 / Input dispatching timed out。
  所以屏蔽路径是：`finishInputEvent(event, true)` → 成功才阻止原逻辑；失败就放弃屏蔽。
  （已核对 Android 17 framework：`android.view.InputEventReceiver#finishInputEvent(InputEvent, boolean)` 存在。）
- **事件流 key = `MotionEvent.getDownTime()`**。
  `getDisplayId()` 是 `@hide`（`android.jar` 里取不到），不能直接用；
  5 秒兜底清理防止丢 UP 后"永久屏蔽"。
- **只 Hook ROM 自己声明的方法**：`Reflect.isRomDeclared()` 会跳过 `android.*`/`androidx.*`，
  避免误挂 `View/ViewGroup.onTouchEvent/dispatchTouchEvent` 这类全体控件共享的框架实现
  （这是本模块实现时实际踩到并修掉的坑）。
- **所有回调 `try/catch (Throwable)`**：出异常就不 `setResult`，原方法照常执行 = 恢复系统默认。
- Hook 进程不读目标应用数据、不读窗口内容、不联网。

---

## 6. 已支持的 AOSP 版本范围

| Android | API | 左右返回屏蔽 | 顶部下拉屏蔽 | 底部上滑屏蔽 | 说明 |
|---|---|---|---|---|---|
| 12 / 12L | 31 / 32 | ✅ 预期可用 | ✅ 预期可用 | ⚠️ 走策略 B | `edgeback` 在 `navigationbar.gestural`；shade 类在 `statusbar.phone` |
| 13 | 33 | ✅ 预期可用 | ✅ 预期可用 | ⚠️ 走策略 B | 同上 |
| 14 | 34 | ✅ 预期可用 | ✅ 预期可用 | ⚠️ 走策略 B | shade 已挪到 `com.android.systemui.shade` |
| 15 | 35 | ✅ 预期可用 | ✅ 预期可用 | ⚠️ 走策略 B | 触摸逻辑逐步抽到 `ShadeTouchEventHandler` |
| 16 | 36 | ✅ 已按新架构适配 | ✅ 预期可用（多候选） | ⚠️ 策略 A 待实测 | `onMotionEvent` 已删除，走 InputEventReceiver |
| 17 | 37 | ✅ 已实测确认新架构类存在 | ✅ 已按多候选实现（结果看 `shade=` 日志/状态卡） | ⚠️ 策略 A 由 seen= 计数判定 | 见第 12/13 节 |

> 本表的"✅"只表示**代码路径已实现且探测到入口**；是否能拦住在 A17 上必须用第 14 节的
> "先日志、后拦截"流程实测确认。本模块刻意不把"预期可用"写成"已支持"。

> 判定标准：状态卡里 **「新架构 InputEventReceiver: ≥1 个入口」** 即为挂载成功。
> 本模块不猜、不假装成功；探测不到就明确显示 0。

### 6.1 为什么 1.0.0 在 Android 16/17 上"完全没反应"（已修）

本机（小米 23013RK75C / Android 17 / SDK 37）直接解包
`/system_ext/priv-app/SystemUI/SystemUI.apk` 核对的结果：

```
Lcom/android/systemui/navigationbar/gestural/EdgeBackGestureHandler;
   fields=95  methods=17
   M cancelGesture(view/MotionEvent)V
   M dispatchToBackAnimation(view/MotionEvent)V
   M createDisplayBackGestureHandler(view/Display)DisplayBackGestureHandlerImpl
   ...（**没有任何 onMotionEvent**）

Lcom/android/systemui/navigationbar/gestural/DisplayBackGestureHandlerImpl;
   F inputEventReceiver  com/android/systemui/shared/system/InputChannelCompat$InputEventReceiver
   F inputMonitorCompat  com/android/systemui/shared/system/InputMonitorCompat
   F onInputEvent        kotlin/jvm/functions/Function1
   M createLayoutParams / dispose

Lcom/android/systemui/navigationbar/gestural/DisplayBackGestureHandlerImpl$inputEventReceiver$1;
   M onInputEvent(view/InputEvent)V
```

即：返回手势改成 **每 display 一个 handler，用 `InputMonitorCompat` 拿 gesture monitor，
再用 `InputEventReceiver` 收事件**。本机（LineageOS 24）里这个 handler 叫
`DisplayBackGestureHandlerImpl`；AOSP main 的对应实现是 `EdgeBackGestureHandler`
（注册的 monitor 名是 `edge-swipe`，且**不设区域 = 整屏监视**）。1.5.1 已把 listener 过滤
放宽为同时接受这两个命名，避免换 ROM 后"一个都挂不上"。
1.0.0 只挂 `EdgeBackGestureHandler.onMotionEvent`，该方法在 A17 已不存在 →
`edgeHooked=false` → 表现就是"勾了完全没效果"。1.1.0 已改为双架构同时挂载。

**底部手势（1.3 时期的结论，1.4/1.5 已给出分层方案，见第 12 节）**

AOSP 手势导航的「上滑回桌面 / 上滑悬停进最近任务」由
`com.android.launcher3 / com.android.quickstep.TouchInteractionService` 通过
`InputManager.monitorGestureInput()` 拿到手势通道后自己处理，**不在 SystemUI 进程**。
SystemUI 里只有导航键层与 home handle 小胶囊（`HomeHandle` 的触摸只影响胶囊自身
拖拽/长按动画，不是上滑 Home）。

所以自 1.4.0 起不再靠"猜 SystemUI 里的入口"，而是分三层（细节见第 12 节）：

- **策略 A**：复用返回手势的 gesture monitor 通道 + 按 `rawY` 判底部区。
  AOSP 源码已证实该 monitor 是**整屏监视**，所以底部触摸**会**进来；
  能否真正拦住取决于"消费后 Launcher 的 monitor 是否仍执行 Home"，由状态卡的
  `seen=/blocked=` 与实测定论。
- **策略 B**：可选地把作用域扩展到 Launcher，挂 `InputChannelCompat$InputEventReceiver$1`
  （TIS 自己只是 listener，消费不了事件），**默认关闭、需两个显式开关**。
- **策略 C**：两条都不成立时，状态卡与 UI 直接写"当前架构下底部上滑屏蔽不可用"。

**不做的事**：不会在用户没有"显式勾选 Launcher 作用域 + 打开策略 B/游戏模式"的情况下
去 Hook Launcher 进程，也不会用强制 Hook 赌 SystemUI 崩溃。

---

## 7. 前台应用检测（三档降级）

| 档位 | 实现 | 优点 | 限制 |
|---|---|---|---|
| 1 | `ActivityTaskManager.getService()` 反射（`getFocusedRootTaskInfo` / `getTasks` 多形态） | 同时拿到包名 + Activity，最快 | SystemUI 是否持有 `REAL_GET_TASKS` 等权限因 ROM 而异；失败进 30s 冷却 |
| 2 | 模块内可选 `AccessibilityService` 推送 `TYPE_WINDOW_STATE_CHANGED` 的包名/类名 | 一定拿得到；`canRetrieveWindowContent=false`，不读界面内容 | 需用户在系统设置里手动启用 |
| 3 | `UsageStatsManager.queryEvents` 取最近 `ACTIVITY_RESUMED` | 无需交互 | 只有包名；需 `PACKAGE_USAGE_STATS`（SystemUI 未必有） |

三档都失败 → **默认不屏蔽**（安全默认）。状态卡会显示当前生效的是哪一档。

> 状态卡显示「前台应用来源：未知」时，点「前台检测助手」，在系统设置里启用
> 「边缘手势守卫 · 前台检测」即可（只上报前台包名，不读界面内容、不联网）。

---

## 8. 规则存储与实时生效（IPC 设计）

```
[控制器 UI] 勾选
   ├─ RuleStore 写 SharedPreferences（整份 JSON）
   └─ sendBroadcast(ACTION_RULES_PUSH, setPackage("com.android.systemui"), 规则JSON)
            │
            ▼
[SystemUI 进程] 动态 BroadcastReceiver（RECEIVER_EXPORTED）
   ├─ 校验来源 → RuleSet.fromJson → HookRuntime.rules 整体替换（volatile）
   ├─ 写 SystemUI 私有缓存 files/edgeguard/rules-cache.json（SystemUI 重启后恢复）
   └─ 回推状态报告
```

- 为什么不用 `XSharedPreferences` 直接读模块 App 的 prefs：Android 11+ 之后
  `/data/data/<pkg>` 目录权限 + SELinux 让 SystemUI 基本读不到别人的私有目录，
  这条老路不可靠，所以主通道是广播（payload 自带完整规则）。
- 兜底通道：`RulesProvider`（`content://com.edgeguard.gesture.rules/rules`），
  SystemUI 冷启动时尝试 query 一次；失败只记日志（包可见性/SELinux 都可能拦）。
- 反向补齐：SystemUI 启动后向模块 App 发 `ACTION_RULES_REQUEST`，
  `SyncReceiver` 收到就重推；开机完成、模块被覆盖安装也会重推。

---

## 9. 排障

```bash
adb logcat -s EdgeGestureGuard
```

启动后关键日志（按顺序）：

```
probe: class found com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler
edge metrics: width=(none), displaySize=mDisplaySize, left=mEdgeWidthLeft, right=mEdgeWidthRight
probe: back-gesture impl by return type of createDisplayBackGestureHandler -> ...DisplayBackGestureHandlerImpl
modern back-gesture impl: ...DisplayBackGestureHandlerImpl, declaredClasses=[...]
hooked [EDGE-INPUT] DisplayBackGestureHandlerImpl$inputEventReceiver$1#onInputEvent(InputEvent) returns void
legacy edge hooks on ...EdgeBackGestureHandler: 0
modern back-gesture input hooks: 1
hook install finished: edge=true (legacy=0, modern=1) bottom=unsupported
rules applied from broadcast: 3 条
BLOCK LEFT gesture, foreground=com.xxx/com.xxx.MainActivity [atm:getTasks4]
```

状态卡对应显示：

```
模块状态
· 左右返回 Hook：已挂载
    旧架构 onMotionEvent: 0 个入口 (...EdgeBackGestureHandler)
    新架构 InputEventReceiver: 1 个入口
· 底部手势 Hook：当前环境不支持 → 已降级为仅左右返回屏蔽
· 前台应用来源：ActivityTaskManager 反射查询 / 无障碍助手推送
· Hook 内规则条数：3
```

| 现象 | 排查 |
|---|---|
| 挂载成功但手势无反应 | 看状态卡「前台应用来源」：若为"未知"，启用「前台检测助手」；日志里没有 `BLOCK` 行说明没拿到前台包名 |
| 状态卡「左右返回 Hook：未挂载」 | 作用域没勾 SystemUI / 没重启 SystemUI / `probe:` 日志里类名变了 → 把 `edge handler fields` 与 `probe:` 行贴出来即可加候选名 |
| 状态卡一直「正在请求状态」 | 广播被拦：确认模块未被冻结、SystemUI 已重启；点「重新同步到 SystemUI」 |
| 勾了不生效、log 无 `BLOCK` | 前台检测失败（看「前台应用来源」）；启用「前台检测助手」 |
| 底部复选框置灰 | 当前环境不支持，属预期（第 6 节） |
| SystemUI 反复重启 | 立即在 LSPosed 关闭本模块 / 进安全模式；本模块 Hook 全在 try/catch + 严格探测内，若仍崩溃请贴日志 |
| 3 键导航下测边缘 | 3 键导航没有边缘返回手势，本模块无意义 |

---

## 10. 安全与合规

- 用户自己设备上的合法定制：只影响本机手势，不联网、不上传、无广告。
- Hook 进程不读其它应用数据；无障碍助手只读"哪个包在前台"，显式关闭窗口内容读取。
- 修改规则必须用户手动操作 UI；广播通道校验发送方包名，异常按"放行"处理
  （最坏只是规则被重载，不可能执行外部指令）。
- 不含任何提权 / 绕过授权 / 隐藏行为的代码。

---

## 11. 已知限制（明确列出，不粉饰）

1. **底部上滑**：AOSP/LineageOS 由 Launcher3/Quickstep 处理，SystemUI 单进程 Hook 无法保证拦截。
   本版新增"策略 A：在返回手势 gesture monitor 通道上按 rawY 判底部区"，但**它是否真的能看到
   底部触摸由系统决定**：状态卡的 `seen=` 计数为 0 就说明看不到，此时该功能在本机不可用
   （明确降级，不假装生效）。真正可靠的路径是策略 B（扩展到 Launcher 作用域，默认关闭）。
2. 前台应用检测依赖 SystemUI 进程权限；ActivityTaskManager / UsageStats 都可能被拒，
   此时需启用无障碍助手（第三档）。
3. 多窗口/分屏下"前台"语义模糊：取的是 display 0 最上层任务，可能与直觉不同。
4. 悬浮窗/输入法弹窗等瞬态窗口切换可能让无障碍推送短暂指向非目标包——因为规则默认不屏蔽，
   影响是"少屏蔽一次"，不会错误屏蔽无关应用。
5. Hook 依赖隐藏 API 反射；若 ROM 关闭了 LSPosed 的 hidden API bypass，探测可能失败
   （状态卡显示未挂载），请保持 LSPosed 默认设置。
6. 不做「手势区域视觉提示」（不画额外箭头/高亮）。
7. 只支持触摸屏；不支持外接鼠标/手写笔的特殊手势路径。
8. **顶部下拉**：只在**屏幕顶部 topZoneRatio（默认 12%）内起手**的手势被拦；
   从状态栏以下开始的下拉、以及"通知栏已展开后"的所有触摸都不拦（否则用户点不了通知）。
   通知栏展开状态靠反射读 `isFullyExpanded/isExpanded/mExpanded*`；读不到时按"未展开"处理，
   此时若"已展开 + 目标应用在前台"，顶部 12% 的点击可能被吞（日志里会有一条 WARN 提示）。
9. 底部/顶部的区域比例是**屏幕高度百分比**；横屏、折叠屏、多窗口下"整屏高度"取
   `getCurrentWindowMetrics()`，旋转后最多 60 秒内自动刷新（事件 rawY 超出缓存高度会立即重算）。
10. 3 键导航/无手势导航的机型上，顶部下拉与底部区判断仍有意义，但边缘返回规则无效果。
---

## 6.2 省电优化（1.2.1）

| 项 | 之前 | 现在 | 节省 |
|---|---|---|---|
| 规则兜底轮询 | 每 15s 一次的 `HandlerThread` 轮询，永不停 | 启动 20s 后**只拉一次**，失败再重试一次即停；收到过广播则完全不启动 | 去掉一个常驻定时唤醒源 |
| 热路径写文件 | 每吞一个事件都 `StatusFile.append()`（/sdcard 写入） | 已删除；每事件日志降级为 `Xlog.debug`，默认**完全关闭** | 滑动时不再有磁盘 IO |
| 无规则时的开销 | 仍然解析 MotionEvent、查表 | `gate()` 开头判空直接返回 | 空闲时零开销 |
| 前台应用查询 | 400ms 缓存，一次滑动会多次 Binder | **3s** 缓存 | Binder 调用降到约 1/7 |
| BLOCK 日志 | 每次屏蔽都写 | 同一侧 2s 内最多一条 | 日志量大幅下降 |
| 事件查表 | 每次事件 `synchronized` | `ConcurrentHashMap` 无锁读 | 去掉输入回调里的锁 |

**稳态耗电预期**：不滑动时没有任何定时器/轮询（SystemUI 里只有两个被动 Hook + 一个广播接收器），
滑动时每条事件只做一次规则表引用读取 + 一次哈希查表，不再有文件 IO 或 Binder。
## 6.3 只在目标应用前台时才介入（1.3.0）

之前的实现是「每次手指碰到边缘 → 查一次前台应用（4/3 秒缓存）→ 再判断要不要拦」。
1.3.0 改成**推送式**：

```
SystemUI 启动
  └─ 注册 TaskStackListener（系统推送：任务栈变化时回调）
       └─ 回调里只置脏 + 异步去抖 120ms 刷新一次前台缓存（HandlerThread）

手指触到边缘
  └─ 读两个 volatile 字段：规则表 / 缓存的前台包名
       ├─ 规则为空            → 直接放行（零开销）
       ├─ 前台不是目标应用    → 直接放行（零开销）★ 这就是「不启动」
       └─ 前台是目标应用      → 才进入左右判定与拦截
```

| 项 | 1.2.1 | 1.3.0 |
|---|---|---|
| 触摸回调里的 Binder | 3s 缓存，仍会周期性触发 | **0 次**（只用缓存） |
| 空闲时唤醒 | 无定时器 | 无定时器（纯系统回调驱动） |
| 非目标应用在前台 | 每次触摸都要判一次 | 两个字段读完直接返回 |
| 降级 | — | `TaskStackListener` 注册失败时自动退回「按需查询」，行为同 1.2.1 |

实现细节：`android.app.TaskStackListener` 是 @hide 类，仓库里用 `stubs/` 下的编译桩
（`app/libs/hidden-stub.jar`，**compileOnly，不进 APK**）编译，运行时解析到系统真类。

自检日志（modules.log）会出现：

```
[EdgeGestureGuard] task-stack listener registered
[EdgeGestureGuard] foreground tracking: push mode (触摸路径零 Binder)
```

---

# 12. 底部上滑屏蔽（功能一）：可行性判定与分层策略

## 12.1 架构事实（这是所有结论的前提）

AOSP 16/17 手势导航里，两条手势的执行方**不是同一个进程**：

| 手势 | 谁在执行 | 输入通道 |
|---|---|---|
| 左右边缘返回 | **SystemUI**（`DisplayBackGestureHandlerImpl` → `EdgeBackGestureHandler`） | `InputManager.monitorGestureInput()` 建的 gesture monitor + `InputEventReceiver` |
| 底部上滑（Home / 最近任务） | **Launcher 进程**（`com.android.quickstep.TouchInteractionService`） | 同样是 `monitorGestureInput()`，但在 Launcher 进程里消费 |

**结论 1：SystemUI 进程内没有"底部上滑"的最终执行逻辑。** 这是 AOSP 的分工，不是 LineageOS 的特性；
所以"在 SystemUI 里 Hook 底部手势的最终执行"这条路在 A17 上不存在（旧版的 `EdgeBackGestureHandler.onMotionEvent`
也已在 A17 删除）。

**结论 2：类被复用 ≠ 通道被复用。** 你已有的模块观察到的
"`InputChannelCompat$InputEventReceiver$1` 被导航条 home handle 等复用"说的是**同一个包装类**；
但每个 gesture monitor 注册是**独立实例 + 独立区域**。返回手势的 monitor 只围绕"返回手势区"建立，
它是否也会把底部区域的触摸送进来，**只能由实测回答，不能靠推测**。

**结论 3（AOSP main 源码核对后补）：back 手势的 monitor 是「整屏监视」。**
`EdgeBackGestureHandler` 用 `new InputMonitorCompat("edge-swipe", displayId)` → `monitorGestureInput(name, displayId)`
（**不设区域**），再由 SystemUI 自己在 `onMotionEvent` 里用 `isWithinInsets && isWithinTouchRegion`
过滤出左右边缘条带（并排除 PiP / navbar overlay / exclude region）。
也就是说：**底部 home handle 区域的 ACTION_DOWN 会进入这条 monitor 的判断**，
只是不满足边缘条带 → 不触发返回、事件继续正常分发。

> 对策略 A 的意义：`seen > 0` 属于**预期结果**，不是侥幸。真正还没被源码证实的不确定项
> 只剩一个 —— "SystemUI 消费之后，Launcher 自己的 monitor 是否仍会执行 Home"。

## 12.2 策略 A（在既有 onInputEvent 里加坐标判断）——判定：**条件性可用，必须实测**

实现方式（本版已落地，见 `HookLogic` 的 `KIND_EDGE` 分支）：

```
ACTION_DOWN 时
  rawY = ev.getRawY()                     // 屏幕坐标
  screenHeight = WindowManager.getCurrentWindowMetrics().getBounds().height()
  inBottomZone = rawY >= screenHeight * (1 - bottomZoneRatio)   // 默认 12%
  命中 = 规则.blockBottom && inBottomZone
  命中 -> finishInputEvent(event, true) -> 不调用原方法（整条流按 downTime 吞掉）
```

**它"生效"的三个必要条件**（缺一即不可用）：

1. 返回手势的 gesture monitor 收到了底部区域的触摸事件 —— **AOSP 源码已证实会收到**（整屏 monitor，见 12.1 结论 3）。
   因此状态卡里的 `seen=` 应该 > 0；若仍为 0，说明这个 ROM 把 monitor 收窄了或换了类，看 logcat 再补候选。
2. SystemUI 消费该事件后，Launcher 自己的 gesture monitor **不再执行 Home** ——
   **这是目前唯一还没被源码证实的环节**（monitor 间消费是否互斥取决于 InputDispatcher 语义与 ROM 定制），
   必须靠"上滑还回不回桌面"实测。
3. `blockBottom` 规则 / 游戏模式 + 前台包名判定正常（复用左右返回那一套，无额外 IPC）。

### 12.2.1 真机实测结论（LineageOS 24 / A17，屏幕 3200px）——**策略 A 在本机无效**

| 观测 | 数值 | 含义 |
|---|---|---|
| 底部区事件被看到 | `seen=7` | 条件 1 成立：整屏 monitor 确实送来了底部触摸 |
| 实际吞掉的事件 | `blocked=1` | 模块确实 `finishInputEvent(true)` 并阻止了原逻辑 |
| 上滑效果 | **仍然回桌面** | 条件 2 **不成立**：Launcher 自己的 gesture monitor 不受影响 |

也就是说：**gesture monitor 之间不是互斥的**，在 SystemUI 侧消费一份不会取消 Launcher 那一份。
这不是实现 bug，而是架构决定的 —— 所以：

> **本机要真正屏蔽底部上滑，只能用策略 B（Launcher 作用域）。**

**而且它是有害的**：SystemUI 消费掉那条 DOWN 之后，**游戏窗口也收不到底部 12% 的触摸** ——
对音游就是判定区被吃掉，却仍然回桌面。

所以 1.5.4 起：

| 行为 | 默认 | 开关 |
|---|---|---|
| 策略 A：只观测计数（不吞事件） | ✅ 默认 | 无 |
| 策略 A：真的吞掉底部事件 | ❌ 关闭 | 「阈值/验证」→「策略 A：真的吞掉底部事件」 |
| **策略 B：Launcher 进程拦截** | 需手工扩作用域 | 「阈值/验证」→「策略 B」；**游戏模式开启时自动生效** |

1.5.2 起，**打开游戏模式（armed）后策略 B 会自动生效**，不再要求额外勾第二个开关；
但 LSPosed 作用域里必须手工加上桌面包名（`com.android.launcher3`），这一步模块无法替用户做。

**验证方法（30 秒，不需要改代码）**

```bash
# 先打开验证模式：控制器 UI → 阈值/验证 → 勾"验证模式（只打日志、不拦截）"
adb logcat -s EdgeGestureGuard | grep -E "ZONE |screen metrics"
```

然后在音游里**沿屏幕底部上滑**（起点要在最底部 12% 以内），观察是否出现：

```
ZONE EDGE action=DOWN rawY=2312 screenHeight=2400 inTopZone=false inBottomZone=true fg=com.xxx.game blocked=true -> BOTTOM(返回通道)
```

- **出现 `inBottomZone=true` 的行** → 策略 A 有条件可用：关掉验证模式，实测"上滑还回不回桌面"。
  - 不回桌面 → 策略 A 成立，保持默认（只勾 SystemUI 作用域）即可；
  - 仍回桌面 → 说明"监视器之间不互斥"，策略 A 无效 → 走策略 B。
- **完全没有 ZONE 行 / 永远 `inBottomZone=false`** → 该通道看不到底部触摸，**策略 A 在本机不可用**。

**状态卡上的判定**（读的是真实计数，不是猜测）：

| 状态卡显示 | 含义 |
|---|---|
| `实测计数：seen=0 blocked=0` | 输入通道从未看到底部区域事件 → **策略 A 不可用** |
| `seen>0 blocked=0` | 看得到但没命中（检查：前台包名？blockBottom 勾了吗？验证模式是否还开着？） |
| `seen>0 blocked>0` | 已拦截，请实测上滑是否仍回桌面 |

> 为什么不"强行"写一个看起来能用的 Hook：因为**若通道看不到底部事件，任何坐标判断都是死代码**。
> 本模块的做法是"逻辑先装上（零成本）+ 计数自证 + UI 明说不生效"，让你一眼看出到底卡在哪一环。

## 12.3 策略 B（扩展作用域到 Launcher）——**实验性，默认关闭**

代码：`LauncherBottomHooks.java`（与 SystemUI 路径完全独立，不会互相影响）。

**挂在哪一层（关键，按 AOSP main 核对结果修正）**

| 位置 | 能不能消费事件 | 本模块怎么用 |
|---|---|---|
| `TouchInteractionService#onInputEvent(InputEvent)` | ❌ 它 `extends Service`，只是 `getInputReceiver(looper, choreographer, this::onInputEvent)` 里的 **listener**，没有 `finishInputEvent` | 只作**兜底探测**：拿不到 finish 就明确放弃，绝不卡输入 |
| `InputChannelCompat$InputEventReceiver$1#onInputEvent` | ✅ 真正持有 `finishInputEvent`（Launcher 进程里同样存在） | **主力**：先挂它，命中就 finish + 吞流 |

日志里用 `[LAUNCHER-RCVR]` 前缀区分主力通道，`[LAUNCHER]` 前缀是兜底通道。

生效需要**两个显式条件同时满足**：

1. **你在 LSPosed 里手工把作用域扩展到桌面包名**（清单里只预填了 SystemUI，不会替你扩）；
2. **你在 UI 里勾选「策略 B：在 Launcher 进程拦底部手势」**（`config.launcherBottom`，默认 false）。

步骤：

```
1) 确认桌面包名：adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
       -c android.intent.category.HOME | tail -1        # 例如 com.android.launcher3 / com.miui.home
2) LSPosed → 模块 → 边缘手势守卫 → 作用域 → 额外勾选该桌面包名
3) 模块 UI → 阈值/验证 → 勾「策略 B」→ 保存
4) 重启桌面进程（或重启手机），然后：
   adb logcat -s EdgeGestureGuard | grep LAUNCHER
```

日志判读：

```
[LAUNCHER] dispatched, pkg=com.android.launcher3        # 模块确实被注入桌面进程
hooked [LAUNCHER] TouchInteractionService#onInputEvent(InputEvent) returns void
[LAUNCHER] 底部手势入口已挂载: 1 个
```

- 有 `[LAUNCHER] 入口不是 InputEventReceiver（拿不到 finishInputEvent）` →
  **该 ROM 的入口是 listener，无法安全消费事件**，模块会主动放弃（保护输入通道不卡死）。
  这条路在本机就到此为止，不要硬改。
- `hooks=0` → 类名与候选不同，把 `[LAUNCHER]` 那几行贴出来补 `Targets.LAUNCHER_TOUCH_CLASSES`。

**风险（务必知道）**：

| 风险 | 后果 | 缓解 |
|---|---|---|
| Hook 桌面进程的输入入口 | 极端情况下桌面手势异常 / 桌面进程崩溃重启 | 全部 `try/catch(Throwable)`；出异常不 setResult，原逻辑照常跑；崩溃可在 LSPosed 里取消桌面作用域并从安全模式恢复 |
| 拿不到 `finishInputEvent` | 若强行消费会**触摸卡死** | 拿不到就**放弃拦截**（代码里已强制），只打一条 WARN |
| 拦截范围过宽 | 桌面/最近任务全部不能用 | 只在"前台命中 blockBottom + 起手在底部 12%"时拦；桌面自己在前台时不会命中规则 |
| 规则没送到桌面进程 | 永远放行（等于没开） | `ModuleBroadcasts.pushRules` 会额外向 `ModuleContract.LAUNCHER_PACKAGES` 推一份；Provider 也放行了桌面 uid |

## 12.4 策略 B 到底要勾哪个作用域（操作步骤）

**本机要勾的包名：`com.android.launcher3`**（从状态卡"最近前台"就能确认：
`com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher`）。

```
1) LSPosed → 模块 → 边缘手势守卫 → 作用域
   保持 System UI 已勾，**再勾上 com.android.launcher3**
2) 打开一次 LSPosed Manager 让它登记（约 1 分钟），然后重启手机
3) 打开游戏模式（通知/磁贴/控制器按钮任一）
4) 看诊断文件里是否出现：
      [LAUNCHER] dispatched, pkg=com.android.launcher3
      [LAUNCHER-RCVR] …onInputEvent(InputEvent) returns void   ← 挂上了能 finish 的那一层
      [LAUNCHER] gesture receiver listener = com.android.quickstep.TouchInteractionService$$…
5) 再上滑：应不再回桌面；游戏底部 12% 的触摸不受影响（策略 A 默认不吞事件）
```

| 日志 | 含义 | 处理 |
|---|---|---|
| 没有 `[LAUNCHER] dispatched` | 桌面进程没被注入 | 作用域没勾上 / 没重启 |
| 有 dispatched、没有 `[LAUNCHER-RCVR]` | 该 ROM 的入口不是 `InputChannelCompat` 那层 | 把候选缺失行发我，补 `Targets.LAUNCHER_TOUCH_CLASSES` |
| 有 `[LAUNCHER-RCVR]` 但仍回桌面 | 命中条件没满足（游戏模式没开 / 不在底部 12% / 规则没到桌面进程） | 看诊断里 `rules applied from … in com.android.launcher3` 是否出现 |

> 桌面进程里的规则来源：`ModuleBroadcasts.pushRules` 会额外向 `ModuleContract.LAUNCHER_PACKAGES`
> 各推一份；`RulesProvider` 也放行了桌面 uid。所以只要作用域加上，规则会自动到位。

**1.5.7：ARM/DISARM 广播也会发给桌面进程。**
这是"作用域勾了但底部仍没效果"的根因：两个进程各有自己的 `ArmController` 静态状态，
以前 ARM 只发给 SystemUI，桌面进程 `isArmed()` 恒为 false，而 `"launcher":false` 时
`onLauncherBottomTouch` 直接返回 false → **必然放行**。现在游戏模式会同时驱动两侧。

> 另一个必须注意的点：**加完作用域要重启手机**。桌面是常驻进程，不重启就不会加载新作用域。

**1.5.5：状态卡能直接告诉你策略 B 有没有注入。** 桌面进程挂完后会通过
`ContentResolver.update(RULES_URI, {launcher_status: "hooks=N,v=…"})` 回报一次，
App 显示成「策略 B：已注入（hooks=1）」或「未注入」。不用再去翻 logcat。

## 12.5 策略 C（明确不支持的场景）

- 只想保留单一 SystemUI 作用域、且 `seen=0` → **当前架构下底部上滑屏蔽不可用**，
  状态卡与复选框提示都会这么写，不会给你"看起来能工作"的代码。
- 3 键导航机型：本来就没有上滑手势，无需此功能。

---

# 13. 顶部通知栏下拉屏蔽（功能二）

**作用域：仍然只需要 `com.android.systemui`**（shade 的触摸链全在 SystemUI 进程内）。

## 13.1 拦截点

`ShadeHooks` 会按候选表 `Targets.SHADE_TOUCH_CLASSES` 逐个 `Class.forName`（`findClassIfExists` 等价物），
对存在的类扫 `onInterceptTouchEvent` / `onTouchEvent` / `dispatchTouchEvent` /
`handleDispatchTouchEvent` / `shouldInterceptTouchEvent` / `handleTouchEvent` /
`onQsIntercept` / `handleMotionEvent` / `onMotionEvent`，**谁存在挂谁**：

| 候选类 | 出现版本 | 说明 |
|---|---|---|
| `com.android.systemui.shade.NotificationShadeWindowView` | A14+ | shade 窗口根 View，覆写 dispatch/onIntercept/onTouch，委托嵌套接口 `InteractionEventHandler` |
| `com.android.systemui.shade.NotificationShadeWindowViewController` | A13+ | 实现 `InteractionEventHandler`：`handleDispatchTouchEvent` / `shouldInterceptTouchEvent` / `handleTouchEvent`（**真实逻辑在这层**） |
| `com.android.systemui.shade.NotificationPanelViewController` | A14+ | 面板控制器，`onInterceptTouchEvent(MotionEvent)` / `onTouchEvent(MotionEvent)`（QS 在 `onTouchEvent` 内处理） |
| `com.android.systemui.statusbar.phone.NotificationPanelViewController` | A12/13 | 老位置（commit `8a75e4922a` 于 2022-07 挪包） |
| `com.android.systemui.statusbar.phone.PhoneStatusBarView` | A12+ | 状态栏本体（下拉起手区） |
| `com.android.systemui.statusbar.phone.StatusBarTouchHandler` | — | 纯候选（main 上未证实存在，探测不到就跳过） |

> **两处已核对的纠偏**：
> 1. `com.android.systemui.shade.ShadeTouchEventHandler` **在 AOSP main 里不存在**。
>    存在的是 `com.android.systemui.ambient.touch.ShadeTouchHandler`，属于 glanceable hub 的
>    ambient-touch 体系，**不是** shade 下拉入口 —— 所以本版没有把它当主力候选。
> 2. `onQsIntercept`（A13 时代的面板方法）在 15/16/17 main 上**未证实**仍存在；
>    探测不到会自动跳过，不抛异常、不假装支持。

判定（`beforeHookedMethod`）：

```
规则.blockTopShade && 起手 rawY <= screenHeight * topZoneRatio && 通知栏未展开
  -> setResult(true)（不调用原方法），并按 downTime 吞掉整条流
```

## 13.2 已知限制（明确写出来）

1. **只拦"起手就在顶部 12%"的手势**。从状态栏以下开始的下拉（例如从屏幕中部往上推再下拉）不拦。
   注意：被拦的 DOWN 是**整条消费**的（这正是"不让面板状态机启动"的原因），所以
   "目标应用在前台时，落在顶部 12% 内的**点击**也会被吞掉" —— 这是本方案的固有代价。
   音游里顶部 12% 一般只有暂停/设置按钮，影响可控；介意就把 topZoneRatio 调小（最小 2%）。
2. **通知栏已展开时一律不拦**（否则你点不了通知）。展开状态靠反射读
   `isFullyExpanded/isExpanded/mIsExpanded/mExpandedFraction` 等；都读不到时按"未展开"处理，
   并在日志里给一条 WARN。
3. **不拦音量面板 / 电源菜单 / 快捷设置的下拉**（那些是其它窗口，不是 shade 触摸链）。
4. 若某 ROM 把 shade 改成 scene 容器而没有上述任何触摸方法 → `shadeState=unsupported`，
   UI 会明说"不生效"（此时本功能等同于关闭）。
5. `onQsIntercept()` 无参版本没有 MotionEvent，只能靠"最近一次 DOWN 的判定"（3 秒内有效）。

## 13.3 验证方法

```bash
adb logcat -s EdgeGestureGuard | grep -E "shade candidates|hooked \[SHADE\]|shade hooks|ZONE SHADE"
```

期望看到（示例）：

```
shade candidates on com.android.systemui.shade.NotificationShadeWindowView: 3
hooked [SHADE] NotificationShadeWindowView#onInterceptTouchEvent(MotionEvent) returns boolean (带 MotionEvent)
shade hooks: 3 个入口; 已挂载=NotificationShadeWindowView=3
ZONE SHADE action=DOWN rawY=64 screenHeight=2400 inTopZone=true inBottomZone=false fg=com.xxx.game blocked=true -> TOP-SHADE
```

状态卡对应行：`· 通知栏下拉 Hook：已挂载` + 已挂载/候选缺失清单。
**一个都没挂上**时状态卡写"未挂载（当前 ROM 上此项不生效）"，并把缺失的候选类名列出来。

### 13.4 1.5.2 的"状态栏仍能下拉"排查手段

实测（LineageOS 24 / A17）：`NotificationShadeWindowView=3 / PhoneStatusBarView=3` 已挂载、
下拉控制中心能拦住，**但从状态栏（时钟那条）往下拉仍然能拉开**。
这通常意味着那一下走的是**另一个窗口根**（例如 `StatusBarWindowView` 之类的独立窗口）。

1.5.2 因此加了三件事：

1. 候选补全：`statusbar.phone.StatusBarWindowView`、`shade.NotificationPanelView`、
   `shade.ui.NotificationPanelViewController`、`scene.ui.view.SceneContainerView`；
2. **动态根视图发现**：第一次收到触摸时打一行
   `SHADE touched class=… | windowRoot=… | viewAt=… | viewSize=…`，
   并把真实窗口根的触摸方法**当场补挂**（不再假设类名）；
3. **SHADE-TOUCH 逐次日志**（限流 500ms）：`SHADE-TOUCH <类#方法> rawY=… y=… blocked=…`，
   用来回答"状态栏这一下到底有没有走到 Hook"。

配合**诊断自动导出**（见 15.7），不需要再手工复制 logcat。

---

# 14. 音游场景：推荐配置与调试步骤

## 14.1 推荐配置（打音游的典型值）

| 项 | 推荐 | 理由 |
|---|---|---|
| 左/右边缘返回 | **按需**（音游里有左右滑条时再开） | 有些音游左右滑动是操作，开了会误伤 |
| 顶部下拉 | **开** | 音游判定区在下方，顶部 12% 基本不会用到；下拉会打断连击 |
| 底部上滑 | **开（实验性）** | 拇指托在屏幕下沿时最容易误触 Home；是否能拦住看 `seen=` |
| 顶部区比例 | **10%~12%** | 再大就会碰到游戏 UI 顶栏 |
| 底部区比例 | **10%~12%** | 覆盖系统手势区即可，不要超过 15%（会碰判定线） |
| 验证模式 | **先开，确认后再关** | 只看日志不动手，避免"以为生效了" |

一键做法：切到音游（让它成为前台）→ 打开控制器 → 点 **「音游预设（顶+底）」**。
它只改"最近前台应用"的顶部/底部两项，**不动左右**。

## 14.2 调试步骤（先日志、后拦截）

```
1) 安装 1.4.0 → LSPosed 作用域只勾 System UI → 重启 SystemUI/手机
   （装完新 APK 不要立刻重启：先打开一次 LSPosed Manager 让它登记新包，约 1 分钟后再重启）
2) 打开控制器，确认状态卡：
   · 左右返回 Hook：已挂载
   · 通知栏下拉 Hook：已挂载
   · 底部上滑（策略 A）：… + 实测计数
3) 阈值/验证 → 勾「验证模式」→ 保存
4) 进音游，分别做三种动作，看 logcat 的 ZONE 行：
   - 顶部往下拉      → 期望 inTopZone=true
   - 底部往上滑      → 期望 inBottomZone=true（若从来没有，策略 A 不可用）
   - 左/右边缘返回    → 期望 inTopZone=false inBottomZone=false
5) 三个坐标都对 → 关掉验证模式 → 再试：
   - 顶部下拉是否还被拦下（应被拦）
   - 底部上滑是否仍回桌面（仍回 → 换策略 B）
   - 屏幕中部正常点按是否受影响（应完全不受影响）
6) 若某一步不对，把 ZONE / BLOCK / shade 三组日志行贴出来即可定位（不需要改代码先猜）
```

## 14.3 性能与手感

- 热路径（每次 DOWN）：读 2 个 volatile 字段（规则表 / 配置）+ 1 次 hash 查表 + 2 次浮点比较；
  **没有 Binder、没有 IO、没有锁**（事件流表是 `ConcurrentHashMap` 无锁读）。
- 无规则 / 前台不是目标应用时：`rules.size()==0` 或 `Decision.any()==false` 立即返回，等同于模块没启动。
- 只有存在底部/顶部规则（或验证模式）时才做区域计数，平时连 `getRawY()` 都不读。
- 屏幕高度缓存 60 秒；`rawY` 超出缓存高度（旋转/分辨率变化）时立即重算。
---

# 15. 游戏模式（1.5.0）：寄生通知 / 磁贴 / 锁屏自动退出

## 15.1 为什么换了一套用法

打音游的节奏是**一局一局**的：开局前要「现在开始防误触」，打完这一把就该恢复正常。
让用户每换一个游戏都去列表里勾四个复选框，既慢又容易忘关（忘关的代价是日常完全没法下拉通知栏）。

所以 1.5.0 增加一个**会话式总闸**，与原来的「按应用勾选」**并存**：

| 模式 | 怎么开 | 作用范围 | 何时失效 |
|---|---|---|---|
| 常驻模式（1.3 起） | 应用列表里勾「顶部下拉 / 底部上滑」 | **只对勾选的应用** | 手动取消勾选 |
| **游戏模式（1.5 新增）** | 通知按钮 / 磁贴 / 控制器「游戏模式」按钮 | **全局**（顶部下拉 + 底部上滑，**不含左右返回**） | **锁屏后解锁** / 通知按钮 / 磁贴 / 控制器按钮 / SystemUI 重启 |

判定式：`是否屏蔽 = 该应用规则命中 || 游戏模式已开`

左/右返回**不受游戏模式影响**：它一直是「按应用规则常驻」，老用户用法一个字都不用改。

## 15.2 三个入口（等价，都只是「遥控器」）

| 入口 | 怎么用 | 说明 |
|---|---|---|
| **寄生通知** | 开启后常驻一条低优先级通知，按钮「退出游戏模式」 | 用 `PendingIntent` 指向静态接收器，**App 进程被回收了也能点** |
| **控制中心磁贴** | 下拉 QS → 编辑 → 添加「游戏模式」 | 磁贴标签随状态变：`游戏模式 已开` |
| 控制器按钮 | App 内「游戏模式：已关（点此启用 顶+底）」 | 方便验证与排障 |

> 磁贴需要你在控制中心里手动添加（系统不允许 App 自己加）。

## 15.3 退出：主出口是锁屏（刻意设计）

游戏模式会屏蔽**顶部下拉** —— 而通知栏、控制中心都在下拉里。也就是说：
**开起来之后，通知按钮和磁贴这两条退出路很可能被自己堵住了。**

所以主出口是锁屏：

```
开启游戏模式
  → 打游戏
  → 锁屏（SCREEN_OFF）
  → 解锁（USER_PRESENT）
  → 自动退出，系统手势全部恢复
```

实现要点（`ArmController`，**跑在 SystemUI 进程里**）：

实现要点（`ArmController`，**跑在 SystemUI 进程里**）——**两条路，短路一条也能退出**：

| 路径 | 触发 | 动作 |
|---|---|---|
| 快路径（广播） | `ACTION_SCREEN_OFF` | `sawLocked = true`（不看 armed，先记账） |
| 快路径（广播） | `ACTION_SCREEN_ON` | 1.2s 后复查一次是否已解锁 |
| 快路径（广播） | `ACTION_USER_PRESENT` | `armed && sawLocked` → `disarm(auto: user-present)` |
| **保险丝（自检）** | armed 期间每 **3s** 一次 | `息屏 or keyguard 仍锁着` → 记账；`亮屏且已解锁` → `disarm(auto: unlocked)` |

> **1.5.1 的 bug（已修）**：锁屏监听用的是 `RECEIVER_NOT_EXPORTED` 注册系统广播，
> 与能正常工作的规则接收器（`RECEIVER_EXPORTED`）标志不同 —— 实测在这台机器上
> 系统广播根本没送到，所以"锁屏后解锁不退出"。1.5.3 改为 `RECEIVER_EXPORTED`
> （这三个都是 protected broadcast，只有系统能发，不存在被第三方伪造的风险），
> **并额外加了不依赖任何广播的 3s 自检**，即使 ROM 完全不发 `USER_PRESENT` 也一定能退出。
>
> 3s 自检只在 armed 期间运行，退出即 `removeCallbacks`，不会长期耗电。

**1.5.5：可以不自动退出。** 「阈值/验证」里新增「锁屏→解锁后**保持**游戏模式」（默认关）。
打音游时锁一下屏再回来很常见，自动退出会让人误以为"功能没生效" —— 这个开关就是给这种情况的。
开启后 `GAME MODE kept after unlock (keepOnUnlock=true)` 会出现在诊断里。

**为什么状态放在 SystemUI 而不是 App 的 SharedPreferences**：SystemUI 常驻，锁屏/解锁广播一定收得到；
App 随时可能被冻结或杀死。放 App 会出现「已经锁屏解锁了但 SystemUI 还在拦」的鬼状态。
App 侧只缓存「最近一次已知状态」用来画磁贴/通知。

**会话不跨重启**：SystemUI 重启天然复位；`BOOT_COMPLETED` 时 App 侧也会清掉缓存与通知，
避免磁贴显示「已开」但实际没拦。

## 15.4 三条安全阀（防止把自己锁死）

1. **前台未知 → 不拦**（fail-open）：拿不到前台包名时，游戏模式不作用于任何触摸。
2. **前台是本模块控制器 → 不拦**：保证你总能打开 App 把游戏模式关掉。
3. **锁屏永远可达**：即使通知/磁贴被自己屏蔽了，锁屏→解锁一定能把状态清掉。

## 15.5 权限与可见性

| 项 | 需要什么 | 没有会怎样 |
|---|---|---|
| 常驻通知 | `POST_NOTIFICATIONS`（API 33+ 运行时授权，App 首次启动会请求） | 通知不显示；**磁贴与锁屏退出仍然可用** |
| QS 磁贴 | 无额外权限，需在控制中心手动添加 | — |
| 锁屏退出 | 无 | — |

## 15.7 诊断自动导出（1.5.2，不用再复制 logcat）

链路：

```
SystemUI(Hook) ──日志──> Logx.sink ──> HookRuntime 内存环形缓冲(300 行)
      │                                      │
      └── 状态变化/请求 ──> StatusReport.diag ─┘
                                   │  广播
                                   ▼
                        App: StatusReceiver
                                   │
                                   ▼
              /storage/emulated/0/Android/media/com.edgeguard.gesture/
                  edgeguard-diag-latest.txt        ← 最新的（覆盖写）
                  edgeguard-diag-20260101-120000.txt  ← 历史快照（保留最近 5 个）
```

- **何时自动写**：每次收到 SystemUI 的状态回推（切回 App 会自动请求一次；开关游戏模式、
  改规则也会触发），以及在通知/磁贴上操作之后。
- **手动写**：控制器底部「导出诊断」按钮。
- **文件内容**：完整状态字段 + 规则 JSON + Hook 进程日志尾部 200 行 + 原始状态 JSON。
- **为什么放 `Android/media`**：`/Android/data/<包名>` 对其他 App 是禁止访问的，
  而 `/Android/media/<包名>` 允许 App 无权限写入、且其它有存储权限的工具可以读取 ——
  这样"用户不用复制、AI 直接读文件"才成立。

## 15.8 阈值到底控制什么（1.5.5 起标签带像素）

| 滑杆 | 含义 | 什么时候才有可观察效果 |
|---|---|---|
| 顶部屏蔽区 | 屏幕顶部多高的一条会被吃掉：**起手 `rawY ≤ 屏高 × 比例`** 才算顶部区 | 一直有效（顶部下拉拦截在 SystemUI 内） |
| 底部屏蔽区 | 屏幕底部多高的一条算"底部手势区" | **只在策略 B（Launcher）生效后才参与判定**；策略 A 默认只观测，所以现在调它没变化 |

参考：本机 3200px 高、状态栏约 70~110px、底部手势条约 100~130px。

- 12%（约 384px）是保守默认值；
- 调到 2%（约 64px）时，**从状态栏偏下起手就会漏拦** —— 所以别把顶部调太小；
- 底部同理：策略 B 生效前调它没有任何效果（1.5.5 的状态卡会明确写「未注入 → 底部阈值现在没有效果」）。

## 15.6 排障

| 现象 | 原因 / 处理 |
|---|---|
| 状态卡显示「SystemUI 里仍在运行 v1.2.0」 | **新代码还没进 SystemUI**：装完 APK → 打开一次 LSPosed Manager 让它登记新包 → 约 1 分钟后重启手机（或 root 下 `killall com.android.systemui`） |
| 状态卡「通知栏下拉 Hook：未挂载」且自检记录是旧版本 | 同上：旧版本里根本没有这道 Hook，先让新版本生效再判断 |
| 底部「实测计数：无」 | 旧版本不带该字段；1.5.x 生效后显示 `seen=N blocked=N` |
| 通知不出现 | 检查系统设置里本 App 的通知权限（API 33+ 需允许）；渠道名「游戏模式」 |
| 磁贴找不到 | 下拉控制中心 → 编辑（铅笔）→ 把「游戏模式」拖进可见区域 |
| 游戏模式开了但没效果 | logcat 看两行：`GAME MODE ON` 与 `BLOCK ... armed=true`；只有前者说明顶部/底部 Hook 没挂上（看状态卡） |
| 想确认锁屏退出 | 诊断文件里应有：`game-mode watchdog started` →（锁屏）`ARM-LOCK locked/screen-off detected` →（解锁）`GAME MODE OFF (auto: unlocked)` |
| 锁屏解锁不退出（1.5.1 及更早） | 已知 bug：系统广播用了 NOT_EXPORTED，收不到。1.5.3 已修（EXPORTED + 3s 自检） |
| 不想复制日志 | 用「导出诊断」（见 15.7）：切回 App 会自动导出，或点按钮 |
---

# 16. 1.5.7 构建验证（本机实跑）

| 步骤 | 工具 | 结果 |
|---|---|---|
| 资源编译/链接 | `aapt2 16.0.0.4` | 通过（`versionCode=24` / `versionName=1.5.7`） |
| Java 编译 | `javac 17.0.20` + `android-35.jar` + `api-82.jar` + `hidden-stub.jar` | 通过，**0 error** |
| dex | `d8.jar`（build-tools 35，`--min-api 30`） | 通过 |
| 打包/签名 | `uber-apk-signer 1.3.0` | v3 签名验证通过，0 errors |
| 结构校验 | `tools/verify-apk.py` | **PASS**（dex 内 0 个 Xposed 类；`resources.arsc` stored + 4 字节对齐） |

产物：`dist/EdgeGestureGuard-1.5.7-debug.apk`，
MD5 `f0174e667709ec3bc06c389a7db05112`。

## 16.1 一键构建 + 自动推送

```bash
sh tools/build-and-push.sh
```

做四件事：编译 → 签名 → 复制到 `dist/EdgeGestureGuard-<版本>-debug.apk` → `git commit` →
通过 `tools/push-via-api.py`（api.github.com 的 Git Data API）推进远端 main。

> 为什么不用 `git push`：本机只放行 `api.github.com`，`github.com:443` 直接超时。
> 令牌放在 `~/.eg-git-cred`（600 权限，仓库之外），**用完记得轮换**。

## 16.2 桌面进程日志

策略 B 跑在桌面进程，它的日志以前只存在于 logcat。1.5.6 起：
桌面进程装好后会通过 `ContentResolver.update` 回报
`{launcher_status: "hooks=N,…", launcher_diag: "<日志尾>"}`，
App 导出诊断时会写出独立段落「## 策略 B（Launcher 进程回报）」。

> 顺带修了离线构建脚本：`tools/build-local.sh` 之前没把 `app/libs/hidden-stub.jar` 放进 classpath
>（Gradle 里有、脚本里漏了），会导致本地构建 `TaskStackListener` 找不到符号。现已补上。

