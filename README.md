# 边缘手势守卫 · EdgeGestureGuard

LSPosed 模块：**按前台应用 / Activity 动态屏蔽「左边缘返回」「右边缘返回」「底部上滑手势」**。
只 Hook `com.android.systemui`，不碰目标应用进程，不改系统分区，不做 Magisk 模块。

| 项 | 值 |
|---|---|
| 模块包名 | `com.edgeguard.gesture` |
| LSPosed 作用域 | **只需要 System UI（`com.android.systemui`）** |
| Hook 入口类 | `com.edgeguard.gesture.xposed.XposedEntry`（`assets/xposed_init`） |
| 日志 TAG | `EdgeGestureGuard` |
| 已构建产物 | `dist/EdgeGestureGuard-1.2.0-debug.apk`（debug 签名，61 KB，MD5 `3342fe761017832942df4f5c4f848e31`）|
| 真机验证 | **LineageOS 24 / Android 17 (SDK 37) 实测通过**：左右返回屏蔽生效 |

### 0.1 真机实测结论（LineageOS 24 / Android 17）

AOSP 16/17 的手势层与网上多数资料（A12–A15 写法）差别很大，以下是**解包设备 SystemUI.apk 核对 + 真机验证**的结果：

| 环节 | 真实类/方法 | 说明 |
|---|---|---|
| 手势输入 | `InputChannelCompat$InputEventReceiver$1#onInputEvent(InputEvent)` | 继承 `android.view.InputEventReceiver`，持 `val$listener`；**必须**在它上面调 `finishInputEvent(event, true)`，否则输入通道会卡住 |
| 通道过滤 | `val$listener` 的类名含 `DisplayBackGestureHandlerImpl` | 同一个 receiver 类也被导航条 home handle 等复用，不能无条件拦 |
| 执行返回 | `EdgeBackGestureHandler$5#triggerBack(boolean)` | `mBackCallback` 字段的类型；A17 里**不是** `onBackInvoked` |
| 左右判定 | `EdgeBackGestureHandler.mIsOnLeftEdge` | 在 callback 里通过 `this$0` 拿到 handler 再读 |
| 旧入口 | `EdgeBackGestureHandler#onMotionEvent` | **A17 已删除**，仅 A12–A15 有效 |
| Context | `ActivityThread.getSystemContext()` → `createPackageContext("com.android.systemui")` | 早期写 `Settings.Global` 必须用包名与 uid 匹配的 context，否则报 `SecurityException: Given calling package android does not match caller's uid` |

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
│       │   │   ├── RuleSet.java                不可变规则表 + 决策
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
│       │   │   └── Reflect.java                反射工具箱
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
2. 点一行展开，三个复选框：屏蔽左边缘返回 / 屏蔽右边缘返回 / 屏蔽底部上滑手势
   （当前环境不支持底部时会置灰并提示）。
3. 勾选即保存 + 立刻广播给 SystemUI，**下一次触摸即生效**（不用重启应用或 SystemUI）。
4. 三个都不勾 = 删除该应用规则 = 恢复系统默认。
5. 切换应用立即按新应用自身规则生效。

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

| Android | API | 左右返回屏蔽 | 底部手势屏蔽 | 说明 |
|---|---|---|---|---|
| 12 / 12L | 31 / 32 | ✅ 预期可用 | ⚠️ 基本不可用 | `edgeback` 在 `navigationbar.gestural` |
| 13 | 33 | ✅ 预期可用 | ⚠️ 基本不可用 | 同上 |
| 14 | 34 | ✅ 预期可用 | ⚠️ 基本不可用 | 同上 |
| 15 | 35 | ✅ 预期可用 | ⚠️ 基本不可用 | 同上 |
| 16 | 36 | ✅ 已按新架构适配 | ⚠️ 基本不可用 | `onMotionEvent` 已删除，走 InputEventReceiver |
| 17 | 37 | ✅ 已实测确认新架构类存在 | ⚠️ 基本不可用 | 见下节 |

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

即：AOSP 16/17 把返回手势改成 **每 display 一个 `DisplayBackGestureHandlerImpl`，
用 `InputMonitorCompat` 拿 gesture monitor，再用 `InputEventReceiver` 收事件**。
1.0.0 只挂 `EdgeBackGestureHandler.onMotionEvent`，这个方法在本版已不存在 →
`edgeHooked=false` → 表现就是"勾了完全没效果"。1.1.0 已改为双架构同时挂载。

**底部手势为什么多半不可用（重要，不忽悠）**

AOSP 手势导航的「上滑回桌面 / 上滑悬停进最近任务」由
`com.android.launcher3 / com.android.quickstep.TouchInteractionService` 通过
`InputManager.monitorGestureInput()` 拿到手势通道后自己处理，**不在 SystemUI 进程**。
SystemUI 里只有导航键层与 home handle 小胶囊（`HomeHandle` 的触摸只影响胶囊自身
拖拽/长按动画，不是上滑 Home）。

因此：

- 若 ROM 把底部手势做进了 SystemUI（部分 OEM/定制 ROM），探测到入口就挂载，状态卡显示「已挂载」；
- stock AOSP / LineageOS 上探测不到 → 状态卡显示
  **「当前环境不支持底部手势屏蔽 → 已降级为仅左右返回屏蔽」**，对应复选框置灰。

**不做的事**：不会为了"看起来支持"去 Hook Launcher 进程（违反作用域约束），
也不会用强制 Hook 赌 SystemUI 崩溃。

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

1. **底部上滑**：AOSP/LineageOS 由 Launcher3 处理，SystemUI 单进程 Hook 无法保证拦截；
   默认降级为仅左右返回（状态卡会明确显示）。
2. 前台应用检测依赖 SystemUI 进程权限；ActivityTaskManager / UsageStats 都可能被拒，
   此时需启用无障碍助手（第三档）。
3. 多窗口/分屏下"前台"语义模糊：取的是 display 0 最上层任务，可能与直觉不同。
4. 悬浮窗/输入法弹窗等瞬态窗口切换可能让无障碍推送短暂指向非目标包——因为规则默认不屏蔽，
   影响是"少屏蔽一次"，不会错误屏蔽无关应用。
5. Hook 依赖隐藏 API 反射；若 ROM 关闭了 LSPosed 的 hidden API bypass，探测可能失败
   （状态卡显示未挂载），请保持 LSPosed 默认设置。
6. 不做「手势区域视觉提示」（不画额外箭头/高亮）。
7. 只支持触摸屏；不支持外接鼠标/手写笔的特殊手势路径。
