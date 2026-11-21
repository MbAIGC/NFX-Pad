# Enhanced Tablet Mode —— 第一轮实施说明（基于 `@37a3538`）

## 0. 先说限制：**这批代码我无法在这里编译**

本环境没有 JDK、没有 Android SDK：

```
$ java -version            → command not found
$ echo $ANDROID_HOME       → <unset>
$ du -sh ~/.gradle         → (不存在)
```

所以下面三个提交**只做了静态自查**（括号配对、符号存在性、锚点唯一性、调用路径），
**没有经过一次真实的 `javac` / gradle 编译**。请在你本地先编译再谈行为验证。
若报错，把错误贴给我，我按编译错误逐条修——这比我在这里猜要快得多。

静态自查做了什么：

| 检查 | 结果 |
|---|---|
| 每处改动锚点在 `@37a3538` 上唯一匹配 | ✅ 全部 `assert count == 1` 通过 |
| 改动前后花括号增量平衡 | ✅ `ChatActivity` +4/+4、`PhotoViewer` +12/+12、新文件 12/12 |
| 新引用的每个 API 在该快照中存在且可访问 | ✅ `isTabletInternal()` / `isSmallTablet()` / `isInMultiwindow` / `ApplicationLoader.applicationContext` / `ConfigItem#Bool()` / `ConfigCellTextCheck(ConfigItem,String,CharSequence)` / `AndroidUtilities.dp` 静态导入 |
| 恢复时机确实被调用 | ✅ `ActionBarLayout` 在关闭动画结束处调用 `previousFragment.onBecomeFullyVisible()`（`:3050`，无动画分支 `:3017`） |

## 1. 产物

**工程已移到工作区根目录**（`/root/DSH/NFX-Pad` 就是仓库根，原来的 `NagramXF/` 子目录已不存在），
**应用显示名已改为 `NFXP`**（只改 launcher label / beta label / 版本字符串，`applicationId` 保持不变，
否则会和已安装的旧包签名、更新链冲突）。

分支：`feature/enhanced-tablet-mode`（从 `dev@37a3538` 切出，`dev` 未改动）

```
10 commits，12 个文件，+327 / −27
17bc5b0e  fix(tablet): rebuild the hashtag search pager after a round trip
8220808d  feat(tablet): let the tablet panes run the predictive back animation
17e47a16  fix(tablet): restore the search overlay at the picked message, not at its old row
984778de  chore: rename the displayed app name to NFXP
9df880bb  fix: do not close two fragments for one back press in two step verification
e0317724  fix(tablet): run the right pane back press through the same guards as the main pane
85f4107b  build: allow a signed CI build without repository secrets
416db05c  fix(tablet): restore the search results overlay after comments or media
5b77412d  fix(tablet): keep fullscreen video controls out of the bottom gesture strip
befc43ed  feat(tablet): add Enhanced Tablet Mode switch and feature gate
37a35380  (dev 基线)
```

> 自查补记：第一次提交时 `strings_naxf.xml` 被我的编辑脚本从 CRLF 改成了 LF，导致整个文件 311 行全变。
> 已恢复 CRLF 并用 `git rebase --autosquash` 把它压回 0001，现在该文件只有 +3 行。

补丁序列：`patches/0001..0003*.patch`，可直接 `git am patches/*.patch` 应用到干净的 `dev`。

## 2. 每个提交做了什么

### 0001 开关与门控（计划 M1）

- 新增 `org/telegram/ui/tablet/TabletModeManager.java`
  - `isEnabled()` —— 读 `NekoConfig.tabletOptimizedMode`，默认 `false`
  - `isTabletSplitLayout()` —— **镜像** `LaunchActivity#isTabletSplitMode()`，而不是 `AndroidUtilities.isTablet()`
  - `shouldApplyEnhancedMode()` —— 二者同时成立
- `NekoConfig` 新增一行配置项（prefs 文件 `nkmrcfg`，全局，自动进云同步/JSON 备份）
- `strings_naxf.xml` 新增 2 条英文文案
- `NekoAppearanceSettingsActivity` 新增一行开关，并在"改动需重启"的提示分支里登记该 key

> 为什么不用 `AndroidUtilities.isTablet()`：本 fork 允许用户通过 `NekoConfig.tabletMode` 在手机上强制平板、或在平板上强制关闭（`isTabletForce()`），所以它描述的是"像不像平板"，不是"当前是否真的双栏"。计划的 §4.2 有同样的直觉，但没给出替代判据，这里补上了。

### 0002 全屏视频控制栏避让底部手势区（计划 M4 / 问题三）

- 在**已有的** `containerView` Insets 监听器里计算偏移（`:4895`），并包在开关里
- 计算用**增量**而不是绝对值：

```java
int gestureBottom = max(insets(mandatorySystemGestures).bottom, insets(systemGestures).bottom);
int delta  = gestureBottom - insets.bottom;      // insets.bottom 已在容器高度里扣除过
int offset = delta > 0 ? delta + dp(8) : 0;      // 没有重叠时严格为 0
```

- 应用到 `videoPlayerControlFrameLayout` 和 `videoPreviewFrame` 的 **`bottomMargin`**
  （两者的 `translationY` 都被显隐动画占用，用 translation 会被覆盖）
- 不改 `PhotoViewerWindowView#onMeasure` 的 `heightSize -= insets.bottom`，保留"横屏视频可用导航条空间"的既有设计
- 不改 `VideoPlayerSeekBar`：它的触摸带本来就是这个 48dp 带宽度的全部

### 0003 搜索结果覆盖层恢复（计划 M3 / 问题一）

- 点结果时记录 `position` 并置 `searchListRestorePending`
- 本页再次 `onBecomeFullyVisible()` 时：`scrollToPosition(position)` + `showMessagesSearchListView(true)`
- 用户自己折叠搜索（`onSearchCollapse()`）时丢弃快照，避免"复活用户已关闭的搜索"
- 只在开关打开时生效；只覆盖普通搜索覆盖层，**不含** hashtag / `MODE_SEARCH` 的 pager 页（那是 `View`，恢复要重建页面，超出第一版）

## 3. 上手顺序（重要）

### 第 1 步：先编译，不要先跑

```bash
cd NagramXF
./gradlew TMessagesProj:assembleNormalStaging     # 或 assembleStaging，和你平时一致
```

### 第 2 步：**先埋点被测数值，再谈修复**

0002 的修复是否有效，完全取决于 `delta` 是否 > 0。请在 `updateVideoControlsGestureOffset` 里临时加一行：

```java
android.util.Log.d("NFX-Pad", "insets.bottom=" + reportedBottom
        + " mandatory=" + compat.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
        + " systemGestures=" + compat.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
        + " -> offset=" + newOffset
        + " gestureNav=" + tw.nekomimi.nekogram.utils.AndroidUtil.isGestureNavigation(containerView.getContext()));
```

在**横屏全屏播放**时抓一次 logcat：

- `offset > 0` → 修复生效，继续第 3 步
- `offset == 0` → **底部手势区不是元凶**，问题三需要重新定位（候选：左右边缘 back 手势条、OEM 定制手势区、或根本不是手势冲突而是控制栏自身触摸路由）。此时 0002 是空操作、不会伤害任何东西，但也没解决问题。

### 第 3 步：验收清单

| # | 场景 | 期望 |
|---|---|---|
| 1 | 手机（非平板）全屏视频 | 控制栏位置**与改动前逐像素一致** |
| 2 | 平板 · 开关关闭 | 同上（完全走原逻辑） |
| 3 | 平板 · 开关开启 · 横屏全屏拖进度条 | 不触发 Home / Quick Switch |
| 4 | 同上 · 竖屏全屏 | 不触发；控制栏不悬空（这是原计划公式会犯的错） |
| 5 | 同上 · 系统上滑回桌面 / 切换应用 | 仍然正常 |
| 6 | 同上 · 旋转屏幕 / 退出全屏 | 偏移重算，布局恢复 |
| 7 | 平板 · 开关开启 · 搜索 → 点结果 → 返回 | 回到结果列表且停在原位置，关键词仍在 |
| 8 | 同上 · 打开评论 → 返回 | 同上 |
| 9 | 同上 · 播放评论里的视频 → 返回 → 返回 | 先回评论，再回结果列表 |
| 10 | 同上 · 用户主动关闭搜索后再返回 | **不得**复活搜索 |
| 11 | 同上 · 结果列表里滚动后再点结果并返回 | 位置正确 |
| 12 | 平板 · 折叠/展开、分屏、旋转后重复 7~9 | 不崩、不重复 Fragment |

## 4. 明确**没有**做的部分（以及为什么）

| 计划项 | 状态 | 原因 |
|---|---|---|
| M2 统一返回分发 / 新建 `TabletBackDispatcher` | 🚫 不做（改变方案） | 判定为伪需求的主要部分；改为**复用** `ActionBarLayout` 的检查（`onBackPressedInternal()`），不新建平行 dispatcher。见 0005 |
| 右侧分支补 `startedTracking` 等守卫 | ✅ 已完成 | 0005：右侧分支改走 `onBackPressedInternal()`，守卫 / GroupCallPip / 搜索框 / simpleActionBar / sheet 全部与主栏一致，关闭动作仍走 `finishFragment()` |
| `TwoStepVerificationSetupActivity` 双关 bug | ✅ 已完成 | 0006：仍然自己 `finishFragment()`（覆写里有动画记账），但改为返回 `false` 表示已消费 |
| hashtag / `MODE_SEARCH` 搜索结果恢复 | ✅ 已完成（0010） | 由于页面会被 `clearViews()` 销毁，改为「记住会话 → 返回时 `openHashtagSearch()` 重建 → 按消息 id 滚回」；代价是会重新发起一次哈希搜索（走缓存，通常很快） |
| 平板预测性返回动画 | ✅ 已完成（0009） | 不再短路；按手势解析目标 pane，右栏可跟手滑动，覆盖层保持 alpha 动画旧路径 |
| 开关的**实时**生效 | ❌ 用"需重启"代替 | `LaunchActivity#checkLayout()` / `#invalidateTabletMode()` 都是 `private`，fork 既有平板开关也是重启进程 |

## 5. 已知风险（未编译，按可能性排序）

1. **`ConfigCellTextCheck(ConfigItem, String, CharSequence)` 的 `subtitle` 参数**：我传了 `getString(R.string.TabletOptimizedModeInfo)`。若该构造器对 `subtitle` 有长度/非空假设，界面可能显示异常（不会崩溃）。
2. **`NekoConfig` 新增配置项的加载时机**：`ConfigItem` 在 `loadConfig()` 里按类型读 prefs；新 key 首次不存在时走 `defaultValue=false`，符合预期，但请确认 `tabletOptimizedMode` 的静态字段初始化顺序在 `init()` 之前（我插在 `tabletMode` 之后，与既有写法一致）。
3. **`setLayoutParams` 在 Insets 回调里触发 `requestLayout`**：已用"值变化才写"做守卫；若真机出现控制栏抖动，把 `applyVideoControlsGestureOffset()` 改成 `post(new Runnable(...))`。
4. **`onBecomeFullyVisible` 的恢复时机**：若真机上发现它在"结果列表本来就没关"的情况下反复触发，加一个"恢复后 300ms 内不再恢复"的节流即可（当前用一次性 flag，理论上不会重复）。
5. **`scrollToPosition(position)` 用 adapter 位置**：结果集若在期间变化，位置可能偏移。更稳的做法是记录 `MessageObject#getId()` 再按 id 找位置——第一版先用 position（简单、可预测），验收第 11 项若不过就换成 id 方案。


---

## 6. GitHub Actions 构建（要配哪些密钥）

### 6.1 仓库里只有两个 workflow，且都不监听特性分支

| 文件 | 触发 | 构建命令 | 产物目录 |
|---|---|---|---|
| `.github/workflows/staging.yml` | push 到 `dev`、`workflow_dispatch` | `./gradlew TMessagesProj:assembleStaging` | `normal/staging/` + `plugin/staging/` |
| `.github/workflows/canary.yml` | push 到 `canary`、`workflow_dispatch` | `./gradlew TMessagesProj:assembleNormalStaging` | `normal/staging/` |

两份都有 `paths-ignore: '**.md'`、`'.github/**'`。**`feature/enhanced-tablet-mode` 不会被自动触发**，要么用 `workflow_dispatch` 手动选分支，要么把分支推成 `dev`，要么加一份临时 workflow。

### 6.2 密钥清单（逐条核对过 `build.gradle` / README）

| Secret / 配置 | 是否必需 | 依据 | 缺了会怎样 |
|---|---|---|---|
| `LOCAL_PROPERTIES`（base64 的 `local.properties`） | **原来必需，现在可省**（见 6.3） | `TMessagesProj/build.gradle:58`、`staging.yml`/`canary.yml` 的 `export LOCAL_PROPERTIES=…` | 原先 `storePassword/keyPassword == null`，`packageXxxStaging` 签名阶段失败 |
| `TELEGRAM_APP_ID` / `TELEGRAM_APP_HASH` | 可选 | `build.gradle:189-195`，默认值 `6` / `eb06d4ab…` | 能编译能登录；但**建议自己到 my.telegram.org 注册**，共用他人 api_id 有被限流/风控风险 |
| `GITHUB_TOKEN` | 不需要配 | 只在**被注释掉**的 upload 步骤里出现 | 无影响（产物走 `upload-artifact`） |
| `HELPER_BOT_TOKEN` / `HELPER_BOT_TARGET` / `HELPER_BOT_CANARY_TARGET` / `APP_ID` / `APP_HASH` | 不需要 | 全部位于注释块 | 无影响 |
| FCM：`TMessagesProj/google-services.json` | 不需要 | **已提交在仓库里**，`google-services` 插件在 `build.gradle:14` 无条件应用 | 用作者自己的 FCM 项目，能编译 |
| Google Maps key | 不需要 | `AndroidManifest.xml:866` 已有值 | 能编译，地图可能不可用 |
| `BaseRemoteHelper.CHANNEL_METADATA_ID` | 不需要 | `:22` 已有值 | 远程配置拉不到，不影响编译 |
| 签名密钥库 | 不需要 | `TMessagesProj/release.keystore` **已提交** | 用作者的公钥签名 |

**结论：编译本身不需要任何密钥；只有"签出可安装 APK"这一环原先需要 `LOCAL_PROPERTIES`。**

### 6.3 我加了第 4 个提交，让 CI 零密钥也能出可安装包

`gradle.properties` 里本来就有（但没人读）：

```properties
RELEASE_STORE_PASSWORD=android
RELEASE_KEY_ALIAS=androidkey
RELEASE_KEY_PASSWORD=android
```

`build.gradle` 现在把它们作为**最后兜底**读入，`LOCAL_PROPERTIES` / 环境变量仍然优先：

```groovy
keystorePwd = keystorePwd ?: findProperty('RELEASE_STORE_PASSWORD')
alias       = alias       ?: findProperty('RELEASE_KEY_ALIAS')
pwd         = pwd         ?: findProperty('RELEASE_KEY_PASSWORD')
```

- 若这三个值与仓库里那份 `release.keystore` 的真实口令一致 → **不用配任何 secret 就能出带签名的 staging APK**；
- 若不一致 → 报错点会从"密码为 null"变成"Keystore was tampered with / password incorrect"，此时把正确的三行 base64 成 `LOCAL_PROPERTIES` secret 即可（口令仍优先）。

> ⚠️ 提醒：`release.keystore` 是**公开提交**在仓库里的，用它签出来的 APK 与官方包签名相同、任何人都能伪造。自用没问题；若要分发给别人，请换自己的 keystore 并删掉这个提交。

### 6.4 三种让 CI 构建本分支的方法

1. **手动触发（最省事）**：fork → Actions → `Staging build` → Run workflow → 选 `feature/enhanced-tablet-mode`。
2. **推成 dev**：`git push fork feature/enhanced-tablet-mode:dev`（会触发 staging.yml，两个 flavor 都出包）。
3. **加临时 workflow**：`on: push: branches: ['feature/**']`，产物同名 artifact。

产物：`TMessagesProj/build/outputs/apk/normal/staging/*.apk`（`assembleStaging` 另外还有 `plugin/staging/`）。


---

## 7. 四个问题现在到底解决了没有（逐条给结论）

| # | 问题 | 结论 | 说明 |
|---|---|---|---|
| 一 | 搜索结果 → 评论 → 视频 逐级返回 | **✅ 已解决（两条路径都覆盖）** | 普通搜索：重显覆盖层；hashtag/公开帖：重建 pager 页（0010） |
| 二 | 多栏返回分发不一致 / 一次手势关两页 | **✅ 已解决（代码层面）** | 见 7.2 |
| 三 | 全屏进度条撞底部手势区 | **⚠️ 条件性解决** | 代码就绪，有效性 = `delta > 0`，必须实测 |
| 四 | 隔离 / 可回退 / 不破坏手机与原版平板 | **✅ 已解决** | 见 7.4 |

### 7.1 问题一为什么是"已解决"（含一条我之前说错的话）

我先前在分析报告里写过"会多一跳同会话 ChatActivity"。**对普通频道内搜索这条路径，这句是错的**，我更正：

`messagesSearchListView` 的点击回调里，普通搜索（`chatMode == MODE_DEFAULT`）走的是：

```java
getMediaDataController().jumpToSearchedMessage(classGuid, position);   // 就在当前这个 chat 里滚到那条消息
showMessagesSearchListView(false);                                      // 覆盖层收起
```

也就是说**不会新建 Activity**。`openMessageInOriginalDialog()`（新建同会话 ChatActivity）只在 `chatMode == MODE_SEARCH` 的 hashatg / 公开帖分页里走，不是"频道内搜索关键词"这条路径。

所以修复后的真实链路是：

```
频道 → 搜索 → 结果覆盖层
   ↓ 点结果（原地滚动 + 收起覆盖层，pending = true）
搜索结果所在的消息位置（同一个 ChatActivity）
   ↓ 点评论（新建 ChatActivity）
评论页
   ↓ 播放视频（PhotoViewer，独立窗口）
全屏视频
   ↓ 返回 → PhotoViewer 关闭（这一层本来就没问题）
评论页
   ↓ 返回 → 评论出栈，频道 ChatActivity 重新 onBecomeFullyVisible
搜索结果覆盖层（关键词 + 原位置都已恢复）  ← 本次修复补上的就是这一步
   ↓ 返回 → 关覆盖层（38449 既有逻辑）
频道消息列表
   ↓ 返回 → 聊天列表
```

**仍然不覆盖的**：hashtag / 公开帖的"搜索即列表"分页（`ChatActivityContainer` 里的子 `ChatActivity`，`onSearchCollapse` 会 `clearViews()` 销毁，恢复＝重建页面）。如果用户的"搜索"其实是 hashtag 搜索，那这条修复对他无效——这点必须用真机确认他是哪种搜索。

### 7.2 问题二：已补的两个提交

**0005** 把 `ActionBarLayout#onBackPressed()` 的前置检查抽成 `onBackPressedInternal()`（返回 `true` 表示"调用方自己关"），
`onBackPressed()` 自身改为 `if (onBackPressedInternal() && !fragmentsStack.isEmpty()) closeLastFragment(true);`，
平板右侧分支改为：

```java
if (rightActionBarLayout.onBackPressedInternal()) {
    BaseFragment lastFragment = rightActionBarLayout.getFragmentStack().get(size - 1);
    lastFragment.finishFragment();          // 保留 parentDialog / previewDelegate 语义
}
```

于是右侧栏一次性获得：`startedTracking` / 转场动画守卫、`GroupCallPip`、顶部搜索框、`SimpleActionBar`、sheet。
**没有**新建 dispatcher，也没把平板改走左栏 `onBackPressed()`（那会触发 `needCloseLastFragment` 在左栏空栈时直接结束 Activity）。

**0006** 修 `TwoStepVerificationSetupActivity#onBackPressed(boolean)`：它先 `finishFragment()` 再 `return true`，
而两个调用方看到 `true` 会**再关一次**。现改为返回 `false`（已消费）。之所以不删掉它自己的 `finishFragment()`，
是因为该类覆写了 `finishFragment()` / `finishFragment(boolean)` 做兄弟页面动画记账，交给调用方走
`closeLastFragment()` 会跳过这段。副作用：该类页面不再触发预测性返回预览（`onBackPressed(false)` 现在返回 `false`），属于可接受的收紧。

### 7.3 问题三为什么只能算"条件性"

`offset = max(mandatory, systemGestures) − insets.bottom`。若真机实测 `offset == 0`，说明底部强制手势区与已扣除的导航条 inset 完全重合 —— 那么"拖动进度条误触发 Home"就**不是**底部 inset 造成的，需要换方向排查。**在拿到那行 logcat 之前，谁都不能说它解决了。**

### 7.4 问题四为什么算解决

- 开关默认关；关闭时 `shouldApplyEnhancedMode()` 恒 false，两处修复的代码路径完全不执行；
- 手机（非平板）即使误开开关，`isTabletSplitLayout()` 为 false ⇒ 同样不执行；
- 即使门控全开，`delta <= 0` 时偏移严格为 0 ⇒ 控制栏像素级不变；
- 代价：开关需重启生效（`LaunchActivity#checkLayout` / `#invalidateTabletMode` 都是 private，fork 既有平板开关同样走重启）。


---

## 8. 第三轮补充（0007~0009）

### 0007 工程搬迁 + 改名 NFXP

- 仓库根目录 = 工作区根目录（`/root/DSH/NFX-Pad`），`NagramXF/` 子目录已消失；
- `strings.xml`：`AppName` → `NFXP`、`AppNameBeta` → `NFXP Beta`；`AndroidUtil#getVersionText()` → `"NFXP v…"`；
- **`applicationId` 故意不动**：改了会变成另一个应用，旧安装无法覆盖升级、签名身份也断掉。要改包名请单独提。

### 0008 恢复定位改为消息 ID

```java
final int restoredPosition = findSearchResultPositionById(messageId);
final int target = restoredPosition >= 0 ? restoredPosition : position;   // 消息不在了才退回旧行号
```

`findSearchResultPositionById()` 遍历 `MessagesSearchAdapter#getItem`（∓ stories 偏移）比对 `MessageObject#getId()`。
解决"评论/视频打开期间结果集增减导致滚到无关消息"。

### 0009 平板预测性返回

`onBackStarted/Progressed/Cancelled` 原来对平板直接 `return`，所以平板上永远没有跟手动画。现在：

- 新增 `getBackGestureLayout()`：按 `onBackPressed()` 的分支顺序解析目标 pane（覆盖层 → 右栏 → 主栏）；
- **覆盖层显式返回 `null`**：它用 alpha 动画而不是滑动，强行跟手会很怪，保险起见留在旧路径；
- 新增 `backGestureLayout` 字段：只有 `onBackStarted()` 真的返回 true（即该 pane 确实能滑）才记录，`onBackInvoked()` 优先用它；
- 没开始手势时，`onBackInvoked()` 的平板分支**完全保持原样**（仍走 `onBackPressed()`），所以覆盖层/单层栈等场景零变化；
- 手机路径不变：`getBackGestureLayout()` 在手机上就返回 `actionBarLayout`，与原先无条件使用的一致。

**这是本轮风险最高的一处**：它改的是返回输入主路径。验收时必须覆盖"手机三键返回""平板右栏滑回中途松手取消""覆盖层打开时返回"三类。


### 0010 hashtag / 公开帖搜索页重建

| 阶段 | 做什么 |
|---|---|
| 被覆盖时（`onBecomeFullyHidden`） | 记录 `searchingHashtag`、当前 tab(`hashtagSearchTabs.tabs.getCurrentPosition()`)、当前子页首个可见消息 id |
| 返回时（`onBecomeFullyVisible`） | 若 `searchViewPager.getCurrentView() == null`（页面确已被销毁）→ `openHashtagSearch(hashtag)` 重建 → 切回原 tab → 300ms 后按消息 id 滚回 |
| 用户主动折叠搜索 | 丢弃快照 |

边界：
- 页面**没被销毁**时（`getCurrentView() != null`）直接返回，不重建、不重发请求；
- 重建会**重新发起一次哈希搜索**（你已确认接受）；
- 子页里跨 `ChatActivity` 实例访问 `messagesSearchAdapter` 是合法的：Java 的 `private` 以"同一个顶层类"为界，两个实例都是 `ChatActivity`。

---

## 9. 上游同步指南（改动清单 + 冲突预判）

> 写给几个月后的自己：这份清单的目的是让 `git merge origin/dev` 时知道看哪、怎么判、怎么退。
> 基线：`Keeperorowner/NagramXF dev@37a3538`，本分支 = 36 个提交，25 个文件，+1016 / −48。

### 9.1 三类改动，性质完全不同

| 类别 | 文件 | 上游冲突风险 | 处理方式 |
|---|---|---|---|
| **A. 纯新增**（上游不会碰） | `org/telegram/ui/tablet/`（3 个文件）、`.github/workflows/nfxp.yml`、`publish.yml`、`TMessagesProj/nfxp.keystore` | **几乎为零** | 直接保留 |
| **B. 小块插入**（在上游方法内加几行） | `PhotoViewer` `ChatActivity` `LaunchActivity` `ActionBarLayout` `BaseFragment` `INavigationLayout` | **中～高** | 逐个 hunk 看语义，冲突时保留两边 |
| **C. 配置/文案/构建** | `build.gradle` `NekoConfig` `NekoAppearanceSettingsActivity` `strings*.xml` `settings.gradle` `AndroidUtil` `SessionsActivity` | **低**（文案文件除外） | 文案冲突按行拼接 |

### 9.2 逐个文件：改了什么、能不能单独回退

**新增文件（可整目录删除，功能自动消失）**

| 文件 | 作用 | 删除后果 |
|---|---|---|
| `ui/tablet/TabletModeManager.java` | 开关读取 + "是否平板双栏"判断 + 视频额外间距换算 | 所有调用点编译失败 |
| `ui/tablet/TabletDebugLog.java` | 诊断日志（logcat + 可导出文件，已脱敏） | 同上 |
| `TMessagesProj/nfxp.keystore` | 自用签名证书（PKCS12，alias `nfxp`） | CI 无法出可安装包 |

**上游文件的改动（按冲突概率从高到低）**

| 文件 | hunk 数 | 改了什么 | 冲突预判 |
|---|---|---|---|
| `LaunchActivity.java` | 10 | ①右栏返回走 `onBackPressedInternal()`，开关关闭时回原路径 ②平板路由 wipe→push ③预测性返回按 pane 解析 ④右栏栈上限 8 ⑤诊断日志 | **最高**。上游最活跃的文件；10 个 hunk 里 ③⑤ 纯新增易冲突，①②④ 需读上下文 |
| `ChatActivity.java` | 9 | ①搜索结果覆盖层恢复（普通搜索）②hashtag 分页重建（记住会话→`openHashtagSearch` 重建）③`findSearchResultPositionById` ④诊断日志 | **高**。上游经常动搜索/评论相关代码；本改动集中在 `onBecomeFullyVisible/Hidden`、搜索列表点击回调、`onSearchCollapse` 四处 |
| `PhotoViewer.java` | 9 | ①Insets 监听里算底部安全距离 ②`updateVideoControlsGestureOffset`/`applyVideoControlsGestureOffset` ③拖动时诊断 ④`videoControlsGestureLogged` 字段 | **中高**。视频控制栏上游也常改；核心只有"监听器里加一次调用 + 两个新方法" |
| `ActionBarLayout.java` | 2 | `onBackPressed()` 抽出 `onBackPressedInternal()`（行为等价改写） | **中**。若上游重写了 `onBackPressed()`，直接按新实现重建 `onBackPressedInternal()` 的分支顺序即可 |
| `BaseFragment.java` | 1 | 3 个 `presentFragment` 重载改为自建 `NavigationParams` 并标记发起者 | **中**。上游若改这几个重载，注意保留 `setFromFragment(this)` |
| `INavigationLayout.java` | 2 | `NavigationParams` 新增 `fromFragment` / `presenterIsChat` + `setFromFragment()` | **低**。纯字段新增 |
| `NekoConfig.java` | 1 | 3 个配置项：`tabletOptimizedMode` / `tabletVideoExtraBottomInset` / `tabletDiagnostics` | **低** |
| `NekoAppearanceSettingsActivity.java` | 3 | 4 行设置项 + 重启提示的 key 列表 | **低**，但**行号敏感**（字段初始化顺序 = 显示顺序） |
| `ConfigCellSelectBox.java` | 2 | 下标钳制（修负数越界崩溃） | **低**。上游若重写此 cell，冲突时保留钳制逻辑 |
| `strings_naxf.xml` 及 zh 两份 | 各 1 | 8 条文案 | **低**，纯文本，手工拼接即可 |
| `strings.xml` | 1 | `AppName`/`AppNameBeta` → NFXP | **低**，但**上游改版本号时可能同时动这里** |
| `build.gradle` | 7 | 签名 keystore 兜底、`applicationIdSuffix .nfxp`、`appId` 读环境变量、版本号 `.dev.NN`、APK 文件名 | **中**。上游改签名/版本逻辑时必冲突，都是小块 |
| `settings.gradle` / `README.md` / `AndroidUtil.java` / `SessionsActivity.java` | 各 1 | 项目名 NFX-Pad / 版本串 / 当前会话显示名 | **极低** |

### 9.3 同步步骤

```bash
git remote add upstream https://github.com/Keeperorowner/NagramXF.git   # 只需一次
git fetch upstream dev
git checkout feature/enhanced-tablet-mode
git merge upstream/dev          # 或 git rebase upstream/dev
```

冲突处理顺序建议：**先 `LaunchActivity` → 再 `ChatActivity` → 再 `PhotoViewer`**，其余都是机械拼接。

合并后必须回归的三条（这三处最容易"编译过但行为错"）：

1. 平板右栏：左列表切换（替换）vs 点链接打开（压栈）
2. 平板：A 群 → 链接到 B → 返回是否回到 A
3. 开关关闭时，导航是否与官方原版一致（用 `dev` 打一个包对比）

### 9.4 如何整体摘除这套功能

按依赖顺序，四步即可回到纯上游：

1. 删 `TMessagesProj/src/main/java/org/telegram/ui/tablet/`（3 个文件）
2. 删 `LaunchActivity` / `ChatActivity` / `PhotoViewer` 里所有 `TabletModeManager`、`TabletDebugLog` 引用（编译器会精确报出全部位置）
3. 删 `NekoConfig` 的 3 个配置项、`NekoAppearanceSettingsActivity` 的 4 行
4. 删三份 `strings_naxf.xml` 里的 8 条文案

`build.gradle` / `settings.gradle` / 应用名 / 包名后缀属于**工程身份**，与功能无关，可独立保留或还原。

### 9.5 关键行为契约（合并后必须仍然成立）

| 场景 | 开关开 | 开关关 |
|---|---|---|
| 全屏视频控制栏 | 离屏底 8dp（可调到 32dp） | 与上游完全一致 |
| 搜索 → 评论 → 返回 | 恢复搜索覆盖层/分页 | 上游行为 |
| 右栏打开聊天 | 链接压栈、列表替换 | 一律替换（上游 wipe） |
| 右栏返回 | 复用主栏守卫 | 上游 `onBackPressed(true)+finishFragment()` |
| 平板预测性返回动画 | 有 | 无（上游短路） |
| 右栏栈上限 | 8 | 无上限（上游） |
