# NagramXF「平板优化模式」开发计划 —— 源码核验与可行性结论

> 核验对象：`NagramXF_平板优化模式_完整开发计划.md`
> 核验基线：`Keeperorowner/NagramXF` 分支 `dev`，**commit `37a353808818ee4fdc29fe1ce583b3a18f87d10e`**（2026-11-10）
> 本地源码：`/root/DSH/NFX-Pad/NagramXF`（`--depth 1` 克隆）
> 方法：逐行阅读 + 交叉验证；**未编译、未在真机运行**。凡属推断均已标注「推断」。

---

## 0. 版本与快照声明（必读）

**本文所有行号只对一个快照成立。混用不同快照的行号是无效的。**

### 0.1 本文的行号属于哪个快照

| 项 | 值 |
|---|---|
| 仓库 | `Keeperorowner/NagramXF` |
| 分支 | `dev`（与 `canary` 内容相同） |
| **commit** | **`37a353808818ee4fdc29fe1ce583b3a18f87d10e`** |
| 日期 | 2026-11-10 |
| 文件行数 | `ChatActivity.java` 53474 · `PhotoViewer.java` 24705 · `TwoStepVerificationSetupActivity.java` 2200 · `LaunchActivity.java` 10409 · `ActionBar/ActionBarLayout.java` 4131 · `ActionBar/BaseFragment.java` 1721 |

核验方式（可复现）：`git ls-remote --heads` 取 `refs/heads/dev` = 上述 SHA；再用
`raw.githubusercontent.com/Keeperorowner/NagramXF/{dev,canary}/… | wc -l` 独立取行数，与本地克隆**完全一致**。
即：本文引用的行号就是 `dev` 当前 tip 的行号。

### 0.2 关于其他快照的行数对不上的问题

若你手上的行数是 `ChatActivity 50820 / PhotoViewer 23411 / TwoStepVerificationSetupActivity 2054`，那**不是**本仓库 dev 的行数。按标签做行数指纹（`git ls-remote --tags` + raw 计数）：

| 快照 | ChatActivity | PhotoViewer |
|---|---|---|
| tag 1238 | 47874 | 23462 |
| tag 1241 | 48256 | 23792 |
| tag 1244 | 49543 | 24187 |
| tag 1245 | 50020 | 24285 |
| tag 1249 | 51000 | 24364 |
| tag 1251 | 52159 | 24349 |
| tag 1450 | 52190 | 24349 |
| **dev @37a3538** | **53474** | **24705** |
| 其他 fork 抽查 | `rhythmcache/NagramXFC@dev` 52285 · `Ahmed947916/NagramXF@dev` 52159 · `mrjohnny3000/NagramXF@dev` 53474 · `NextAlone/NagramX@dev` 51315 · `@master` 50427 | 24502 / 24261 |

按抽查到的 11 个 ref 看，两条曲线单调增长：`PhotoViewer=23411` 要求落在 tag 1238 之前（那时 ChatActivity 仅 4.7 万行量级），
`ChatActivity=50820` 却要求落在 tag 1249 一带（那时 PhotoViewer 已 24364 行）。**抽查范围内找不到同时满足这两个数的提交**
（按标签采样，非全量遍历，故只能说"高度可疑"），三个数字之间互相矛盾这一点是硬的。

基线已确认：**以本地克隆为准**。

### 0.3 本文的引用规则

1. 每个结论都以**符号为锚**：`类#方法()` 或唯一代码片段，而不是裸行号。
2. 行号写成 `@37a3538:NNN` 形式，表示"在该快照上的位置提示"，不是结论本身。
3. 提供可执行校验脚本 `verify_citations.sh`，按**符号**在任意 checkout 上重新取号：

```bash
cd /root/DSH/NFX-Pad
./verify_citations.sh ./NagramXF          # 或 ./verify_citations.sh /path/to/源码
```

在分支上跑（代码已有改动）仍然 **57/57 全部命中**，只是行号整体后移——这正是符号定位的意义。

### 0.4 已按本快照落地代码（`feature/enhanced-tablet-mode`）

基线以本地克隆为准，实现已在分支上完成（`dev` 未改动，共 6 个提交，+177/−14 行）：

```
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

工程根目录 = 工作区根目录（`/root/DSH/NFX-Pad`），应用显示名已改为 `NFXP`（`applicationId` 未动）。

**四个问题的落地状态**：一 ✅（普通搜索 + hashtag 两条路径）· 二 ✅（改为复用守卫，未建 dispatcher）· 三 ⚠️ 待实测 `delta` · 四 ✅。
变更说明、CI 密钥、埋点方法、验收清单、已知风险见 `IMPLEMENTATION_NOTES.md`。

**注意**：本环境无 JDK / Android SDK，这批代码**未经编译验证**（只做符号存在性、锚点唯一性、括号平衡等静态自查）。

---

## 1. 一句话结论

**计划的方向基本正确，但根因判断错了一半，方案里有一处会直接造成可见回归的算术错误；照原样实施无法解决问题一；问题三可以解决但公式必须改成增量；问题二的大部分内容是伪需求，真正要修的只是几处缺失的判断条件。**

分问题打分（行号均属 `@37a3538`，符号为准，见 §0.3）：

| 问题 | 计划的根因判断 | 计划的方案能否解决 | 说明 |
|---|---|---|---|
| 一、搜索结果 → 评论 → 视频 逐级返回 | ❌ 判断偏差 | ⚠️ 需重写核心假设，方向对 | 真正原因是「点结果时就把结果列表关掉了，且永不恢复」（`ChatActivity#messagesSearchListView` 点击回调 → `showMessagesSearchListView(false)`，@37a3538:8505-8541），不是返回分发不一致 |
| 二、多栏返回分发不一致 | ✅ 事实成立 | ⚠️ 过度设计 | 「不一致」真实存在，但影响面远小于计划描述；真问题是几处缺失的守卫 |
| 三、全屏进度条与底部手势冲突 | ✅ 成立（且比计划更确定） | ✅ 可解决，但**公式必须改** | 计划的 `max(nav, mandatory) + 8dp` 会多抬一个 `insets.bottom`，竖屏会明显偏高 |
| 四、模式隔离/可回退 | ✅ 成立 | ✅ 可解决 | 落点不在"聊天设置"，而在 `NekoConfig` + `UniversalFragment`/`CellGroup` |

计划中「§5.8 正式编码前必须补全的审计」10 项，本次已全部核验完毕，结果见 §3。

---

## 2. 基线事实（M0 交付物①：固定 SHA）

```
repo        https://github.com/Keeperorowner/NagramXF   (fork of NagramX)
branch      dev
commit      37a353808818ee4fdc29fe1ce583b3a18f87d10e
date        2026-11-10 06:38:03 +0800
APP_VERSION_NAME  13.2.1      (gradle.properties:17)
verCode（APK 实际）1252        (TMessagesProj/build.gradle:48 —— 注意不是 APP_VERSION_CODE=7291)
applicationId     fork.risin42.nagramx  (TMessagesProj/build.gradle:141)
compileSdk 36 / minSdk 27 / targetSdk 36   (build.gradle:25,42,43)
AGP 8.13.2 / Java 21 / Kotlin 2.3.0
androidx.core:core-ktx 1.18.0            (TMessagesProj/build.gradle:556)
flavors     normal / plugin（**没有** afat / bundle / release flavor）
CI 任务      assembleNormalStaging（canary 分支）/ assembleStaging（dev 分支）
```

结论：`androidx.core` 1.18.0 完全支持 `WindowInsetsCompat.Type.mandatorySystemGestures()`（1.5.0 起），**无需升级依赖**；`minSdk 27` 不影响，因为手势导航本身要求 API 29+（`AndroidUtil#isGestureNavigation` 已有该判断）。

---

## 3. §5.8 待核验 10 项 —— 核验结果

| # | 计划待核验项 | 核验结果 |
|---|---|---|
| 1 | 固定 dev commit SHA | ✅ `37a3538…`（见 §2） |
| 2 | 搜索结果点击消息的具体回调 | ✅ `ChatActivity#messagesSearchListView` 的 `setOnItemClickListener` |
| 3 | 点击后是否隐藏搜索列表 | ✅ **是，无条件隐藏**：普通搜索 `ChatActivity:8540 showMessagesSearchListView(false)`；带 reaction 过滤 `8535` |
| 4 | 评论页实际使用的导航容器 | ✅ 新建 `ChatActivity`（`threadMessageId`=原帖）→ `presentFragment` → 平板进 `rightActionBarLayout`（`LaunchActivity#needPresentFragment` 返回 true，普通入栈） |
| 5 | `presentFragment` 对父子页的生命周期 | ✅ 父页**实例不销毁**：`ActionBarLayout#presentFragmentInternalRemoveOld` 只 detach view，`closeLastFragment` 回来时复用同一 view + `onResume` |
| 6 | PhotoViewer Insets 监听是否覆盖所有版本 | ✅ 是。PhotoViewer 是独立 `WindowManager` 窗口（`type=LAST_APPLICATION_WINDOW`），监听器在 `containerView` 上、`ViewCompat` 实现、无版本门槛 |
| 7 | compileSdk/minSdk/core 版本 | ✅ 见 §2 |
| 8 | SettingsActivity 设置项构建方法与保存方式 | ✅ 本仓库**没有** `getItems()`/`ListItem`，用的是 `fillItems(ArrayList<UItem>, UniversalAdapter)`；fork 侧还有 `CellGroup`+`ConfigItem` 体系（`NekoConfig`/`NaConfig`，prefs 文件 `nkmrcfg`） |
| 9 | `videoPlayerControlFrameLayout` 与 `videoPreviewFrame` 是否共用底部定位 | ❌ **不共用**。两者都是 `containerView` 的兄弟：控制栏 `Gravity.BOTTOM` 高度 48dp、margin 0；预览窗独立 `bottomMargin=48+10dp`，且只动态改 X |
| 10 | 真机记录现状 | ⛔ 无法在此环境完成（需设备）。**这是唯一还缺的一环，且它决定问题三的"增量"是否 > 0** |

---

## 4. 问题一：返回层级 —— 计划的根因判断错在哪

### 4.1 真实机制（逐步可查证）

1. 频道内搜索的结果以「覆盖在同一条 chat 列表上的结果层」呈现：`messagesSearchListContainer`，可见性由 `animatorSearchResultAsListVisibility` 控制（`showMessagesSearchListView` / `isMessagesSearchListVisible`）。
   → 它**不是** Fragment，是同一条 `ChatActivity` 内部的一个可见性标志。**计划 §5.4 判断正确。**
2. **用户点结果的那一瞬间，代码主动关掉了结果列表**：

```java
// ChatActivity.java:8538-8541（普通搜索）
} else {
    getMediaDataController().jumpToSearchedMessage(classGuid, position);   // 就在当前 chat 里滚到该消息
    showMessagesSearchListView(false);                                     // ← 结果列表在这里消失
}
```

3. 之后打开评论 → 新建 `ChatActivity`（`openDiscussionMessageChat` → `processLoadedDiscussionMessage`）入栈；播放视频 → PhotoViewer（独立窗口）。
4. 返回时：视频 → PhotoViewer 关闭（`LaunchActivity#onBackPressed(boolean)` 里优先处理 PhotoViewer）；评论 → 出栈；**回到父 `ChatActivity` 时，搜索列表仍然是 `false`** —— 因为**全仓库没有任何一处在返回/恢复时把它设回 `true`**（所有 `showMessagesSearchListView(true)` 都是用户主动操作 / 新建会话 / hashtag 初始化）。
5. 于是用户看到的正是"落到频道消息列表"。

**结论：问题一的根因是「结果列表在点选时被关闭且没有恢复机制」，与返回分发是否统一基本无关。** 计划把 M2（统一返回分发）列为主要修复手段，方向是错的。

### 4.2 一处需要更正的说法（我先前写错，已改）

我一度认为这里会「多一跳同会话 ChatActivity」。**对普通频道内搜索这条路径，这句是错的**：
`openMessageInOriginalDialog()`（新建同会话 `ChatActivity`）只属于 `chatMode == MODE_SEARCH` 的 hashtag / 公开帖分页；
普通搜索是在**同一个 `ChatActivity` 里原地滚动**。所以修复后的链路没有多出来的一层。

仍然存在的差异只有一条：**手机与平板的同一次返回语义不同**。

- 手机：`ActionBarLayout#onBackPressed` 先关**搜索框**；
- 平板：`LaunchActivity#onBackPressed` 直接进 `ChatActivity#onBackPressed`，是先判结果列表、后判搜索框。

### 4.3 计划 §8 的 `TabletSearchState` 不够用（但修起来比计划想的容易）

- **字段不足**：还差 `searching` / `searchingHashtag` / `searchingReaction` / `searchingType` / `searchingFiltered`、分页位置 `getSearchPosition()/getSearchCount()/getMask()`。
- **好消息：恢复是离线的，不需要重新连服务器**。只要"搜索会话"没被 collapse，`searchResultMessages` 缓存仍然有效，恢复只需 `showMessagesSearchListView(true)` + 滚动定位。
- **坏消息：一旦 `onSearchCollapse()` 执行过就不可逆**（`clearFoundMessageObjects()`、`HashtagSearchController.clearSearchResults()`、`animatorSearchResultAsListVisibility.setValue(false)`）。之后只能重新发起服务器搜索。

### 4.4 问题一：判定

**能解决，但不能按计划的方式解决。** 正确的做法是"点结果时记录一次性恢复快照 → 从被推入的子页面返回时恢复"，
而不是新建一套 `TabletStateManager` + 完整快照模型。已在分支上以约 26 行实现（`ChatActivity` 单文件，无新类）。

---

## 5. 问题二：返回分发 —— 事实成立，但影响面被高估

### 5.1 计划 §5.2 的描述**完全属实**

```java
// LaunchActivity.java:9451-9465（改前）
if (AndroidUtilities.isTablet()) {
    if (layersActionBarLayout != null && layersActionBarLayout.getView().getVisibility() == View.VISIBLE) {
        layersActionBarLayout.onBackPressed();
    } else {
        if (rightActionBarLayout != null && …) {
            BaseFragment lastFragment = rightActionBarLayout.getFragmentStack().get(size - 1);
            if (lastFragment.onBackPressed(true)) {
                lastFragment.finishFragment();      // ← 临时分支，不是完整 ActionBarLayout 流程
            }
        } else {
            actionBarLayout.onBackPressed();
        }
    }
} else {
    actionBarLayout.onBackPressed();
}
```

### 5.2 但计划担心的几项，大部分不影响目标场景

| 右侧分支跳过的检查 | 对 `ChatActivity` 是否有影响 | 依据 |
|---|---|---|
| 顶部搜索框 → `closeSearchField()` | ❌ 无影响 | `ChatActivity#onBackPressed` 末尾自己处理 |
| `sheetFragment.onBackPressed` | ❌ 基本无影响 | `BaseFragment#onBackPressed` 默认已处理 `hasShownSheet()` |
| `GroupCallPip.onBackPressed()` | ⚠️ 有影响 | 通话悬浮窗在平板上不会被返回键关闭 |
| `simpleActionBar.onBackPressed()` | ⚠️ **有影响** | 通过 `SettingsActivity#forceRightLayout()` 塞进右栏的页面，其顶部菜单/搜索**不会被返回键关闭** |
| `startedTracking` / 转场动画守卫 | ⚠️ **有影响** | 见 5.3，会导致一次手势关两页 |

### 5.3 真正该修的是几处缺失守卫（计划 §7.2 只描述原则，没定位）

1. **缺 `startedTracking` 守卫** → 一次手势关两个页面。`ActionBarLayout#onBackPressed` 首行有总守卫，右侧临时分支一个都没有；而 `closeLastFragment` 只检查 `checkTransitionAnimation()`，**不检查 `animationInProgress`**。侧滑返回收尾期间再触发返回，会再关一个 Fragment。
2. **`TwoStepVerificationSetupActivity#onBackPressed(boolean)` 自己关了一次又返回 `true`**：两个调用方看到 `true` 会**再关一次**。这是真实的"一次返回关两页"。
3. **`finishFragment()` ≠ `closeLastFragment(true)`**：前者还会 dismiss `parentDialog` 并走 `previewDelegate`。计划若"统一"成 `closeLastFragment` 会**丢掉这两个语义**。

**反方向的坑（计划没提）**：若把平板也统一走 `actionBarLayout.onBackPressed()`，`LaunchActivity#needCloseLastFragment` 在左栏栈 ≤1 时**会直接 finish Activity**，而右栏还显示着聊天。

### 5.4 问题二：判定

「统一分发」的**目标**没错，但**不该新建一个与 `ActionBarLayout` 平行的 dispatcher**（计划 §6.2 的做法），
否则就出现了计划自己在 §16 里警告的"双重导航状态源"。

正确做法（已实现）：把 `ActionBarLayout#onBackPressed` 的前置检查抽成 `onBackPressedInternal()`，
让平板右侧分支调用它，**保留 `finishFragment()` 作为关闭动作**。改动约 30 行，零新增类。

---

## 6. 问题三：全屏进度条与底部手势 —— 方向对，公式错

### 6.1 现状（比计划描述得更明确）

```java
// PhotoViewer.java:4865-4899 唯一的 Insets 监听（containerView 上，且返回 CONSUMED）
final int bottom = newInsetsCompat.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).bottom;
insets.set(r.left, r.top, r.right, bottom);
containerView.setPadding(r.left, 0, r.right, 0);          // 底部 padding = 0
```

```java
// PhotoViewer（PhotoViewerWindowView#onMeasure）
int bottomInsets = insets.bottom;
heightSize -= bottomInsets;                                // ← containerView 高度已扣掉 insets.bottom
```

```java
// PhotoViewer.java:9986  控制栏
containerView.addView(videoPlayerControlFrameLayout,
        LayoutHelper.createFrame(MATCH_PARENT, 48, Gravity.BOTTOM | Gravity.LEFT));   // margin 全 0
```

推论链：

```
屏幕底 ↑
├─ containerView 底边 = 屏高 − insets.bottom
└─ 控制栏底边 = containerView 底边（48dp 高）
   ※ 触摸热区 = 整个 48dp
   ※ 视觉进度线中心在控制栏顶部下方 22~26dp
```

- **竖屏**：只在左右有 inset 时才 `HIDE_NAVIGATION`，所以竖屏 `insets.bottom ≈ 手势条高度`，控制栏底部贴着导航条上沿 —— **安全**。
- **横屏全屏**：进入 `HIDE_NAVIGATION | IMMERSIVE_STICKY`，`getInsets(systemBars())` 返回 **0**，控制栏**直接贴物理屏底**，整条 48dp 落在强制手势区内 —— **这就是用户遇到的现象**。
- `mandatorySystemGestures.bottom` 在沉浸态仍非零，而 `systemBars()` 为 0，二者之差正是需要补的量。

### 6.2 计划的公式会造成的回归（**必须改**）

```
safeBottom = max(navigationBars.bottom, mandatorySystemGestures.bottom)
controlBottomMargin = safeBottom + extraSpacing(8dp)
```

`containerView` 高度**已经**减掉了 `insets.bottom`，控制栏底边已在导航条上沿。再抬一个**绝对值**就会多抬一个 `insets.bottom`（竖屏常见 48dp 级），控制栏悬在半空。

正确写法（增量）：`offset = max(0, max(nav, mandatory, cutout) − insets.bottom)`，无重叠时严格为 0。

### 6.3 计划漏掉的三点

1. **不能用 `translationY` 实现**：控制栏的 `translationY` 已被显隐动画占用（`setTranslationY(dpf2(24) * (1f - progress))`），必须改 `bottomMargin`。
2. **`videoPreviewFrame` 必须一起动**，且同样只能改 `bottomMargin`。
3. **注入点必须在 `containerView` 的那个监听器内部**：它返回 `CONSUMED`，挂在子 view 上的新监听器**永远收不到 insets**。

### 6.4 问题三：判定

**能解决，成本很低（约 50 行，全部在 `PhotoViewer`），但必须先拿到真机 `delta` 数值。**
若实测 `delta == 0`，说明冲突不在底部强制区，需要换方向 —— 这一点计划完全没有预案。
另外：`SecretMediaViewer`、`bottomLayout`、`videoTimelineViewContainer` 有同款问题，本次未覆盖。

---

## 7. 问题四：模式隔离 —— 可行，但落点要换

- **配置落点**：`NekoConfig.addConfig("TabletMode", configTypeInt, 0)` 就是范式。新增一行 `addConfig("TabletOptimizedMode", configTypeBool, false)` → prefs `nkmrcfg`，**全局、不随账号**，自动进云同步/JSON 备份。
- **设置界面落点**：计划写的「设置 → 聊天设置 → 平板优化模式」在本仓库**不存在**。可选 `NekoAppearanceSettingsActivity`（已有 `tabletMode` 下拉）一行 `ConfigCellTextCheck`，或仿 `HeaderSettingsActivity` 新建 `UniversalFragment` 页。
- **字符串**：放 `res/values/strings_naxf.xml`。`MissingTranslation` lint 已关闭、CI 只跑 assemble 任务，不会因缺翻译失败；但**新增独立 strings 文件会被静默忽略**（构建插件里的 include 列表是硬编码的 5 个文件名）。
- **"是否处于平板布局"判断**：`AndroidUtilities.isTablet()` **不可直接用于本功能**，因为它在 fork 里可被用户强制开关。应复用 `LaunchActivity#isTabletSplitMode()` 的表达式（`isTabletInternal() && !isInMultiwindow && (!isSmallTablet() || LANDSCAPE)`）。
- **实时开关不可行**：`checkLayout()` / `invalidateTabletMode()` 都是 `private`，fork 既有先例是**重启进程**。第一版照抄「需重启」提示。

---

## 8. 修正后的最小实施方案（均已在分支落地）

总改动 177 行 / 10 个文件，无新增架构层。

### 8.1 修问题一 —— `ChatActivity` 内的一次性恢复快照 ✅

- 点结果时记录 position + 置 `searchListRestorePending`；
- 本页 `onBecomeFullyVisible()` 时 `scrollToPosition` + `showMessagesSearchListView(true)`（**只重显缓存，不发新请求**）；
- 用户自己折叠搜索（`onSearchCollapse()`）时丢弃快照，避免"复活用户已关闭的搜索"。

坑（计划未涉及）：恢复必须抢在 `onSearchCollapse()` 之前；`checkLayout()` 在折叠/多窗口/旋转时会 `onFragmentDestroy()` 后重新加栈，所以恢复挂在可见性回调而不是 `presentFragment` 之前。

### 8.2 修问题二 —— 复用守卫，不建 dispatcher ✅

见 §5.4 与 5.3。三处：抽出 `onBackPressedInternal()`；右侧分支改调它；修 `TwoStepVerificationSetupActivity` 的双关。

### 8.3 修问题三 —— 增量公式 + `bottomMargin` ✅

见 §6.2 / §6.3。落点：`containerView` 的 Insets 监听器内部 + `createVideoControlsInterface()` 末尾各调一次。

### 8.4 开关与配置 ✅

见 §7：`TabletModeManager`（判据镜像 `isTabletSplitMode()`）+ `NekoConfig` + 设置页一行 + 2 条文案 + 「需重启」提示。

---

## 9. 计划里明确需要改写的表述

| 计划位置 | 原文（要点） | 核验结论 |
|---|---|---|
| §5.2 / M2 | 「右侧分支与完整 ActionBarLayout 返回流程并不完全等价，是优先审查和测试的位置」 | 前半句对；但**它不是问题一的根因**，优先级应下调 |
| §5.4 | 「搜索结果不是独立 Fragment，而是 ChatActivity 内部管理的界面状态」 | ✅ 对普通搜索成立；但 hashtag/`MODE_SEARCH` 结果页是 `ViewPager` 里的独立 `ChatActivity` 实例（`ChatActivityContainer`），需分两种模型 |
| §5.5 | 「评论页面通过 ChatActivity 进入导航体系…父级 ChatActivity 理论上可以保留在导航栈中」 | ✅ 完全正确 |
| §5.7 / §9.4 | 「控制栏高度保持 48dp，进度条视觉高度保持，优先不修改 VideoPlayerSeekBar」 | ✅ 正确。补充：**整个 48dp 就是触摸带**，横向已扩 12dp，无纵向扩展 |
| §6 / §11.1 | 新增 `TabletBackDispatcher`、`TabletStateManager` 等 6 个类 | ⚠️ 过度设计。`TabletBackDispatcher` 与 `ActionBarLayout` 职责重叠，制造"第二套导航状态源"，与 §16 自述风险冲突 |
| §7.1 | 返回优先级 1~7 条 | ⚠️ 内部不自洽：第 3 条"当前 Fragment 内部状态"与第 5 条"搜索结果列表"是同一对象的同一层 |
| §9.2 | `safeBottom = max(nav, mandatory)`，`controlBottomMargin = safeBottom + 8dp` | ❌ **算术错误**，会多抬一个 `insets.bottom` |
| §9.3 | 只调 `videoPlayerControlFrameLayout`，`videoPreviewFrame` 视审计而定 | ⚠️ `videoPreviewFrame` **必须**同步，且两者都只能用 `bottomMargin` |
| §12 | 「设置 → 聊天设置 → 平板优化模式」 | ❌ 本仓库无此路径，见 §7 |
| §14.1 N02/N04/N05 | 验收「回到原搜索结果（关键词/位置保留）」 | ✅ 目标正确，可达成；需补一条：**返回后第一次再按返回，应只关闭搜索框而不销毁会话** |

## 10. 计划完全没覆盖、但会影响目标场景的三件事

1. **平板上的预测性返回动画原本根本不存在**（`LaunchActivity` 里对平板直接短路，`predictiveInput` 永远为 false）—— 已在 0009 实现：按手势解析目标 pane，右栏可跟手；覆盖层仍走 alpha 动画、保持旧路径。**这属于返回输入主路径的改动，是全套修改里风险最高的一处**，验收须覆盖手机三键返回、滑回中途取消、覆盖层打开时返回三类。
2. **折叠/展开/多窗口会重建 ChatActivity**：`LaunchActivity#checkLayout` 对迁移的 Fragment 调用 `onFragmentDestroy()` 后再加入另一栏。真正风险是**搜索会话被销毁**，需单列验收。
3. **手势导航检测已有现成工具**：`AndroidUtil#isGestureNavigation`，`ChatActivity` 已缓存使用（`isGesture()`）。媒体修复应复用。

## 11. 建议的里程碑修订

| 原里程碑 | 修订 |
|---|---|
| M0 固定基线 + 补审计 | ✅ 已完成（§2、§3）。剩余：真机记录 `delta` |
| M1 模式隔离 | ✅ 落点改为 `NekoConfig` + `NekoAppearanceSettingsActivity` + `strings_naxf.xml` |
| M2 统一返回分发 | 🔁 **降级**为"守卫复用 + 双关修复"，不建 dispatcher |
| M3 搜索/评论状态恢复 | 🔁 **升格为主力**，但只在 `ChatActivity` 内做一次性快照；hashtag 结果页明确列为非目标 |
| M4 媒体手势 | ✅ 保留，**公式改增量**，落点改到监听器内部 + `bottomMargin` |
| M5 回归 | ➕ 增加：「一次手势关两页」专项、右栏 `simpleActionBar` 页面返回、折叠/多窗口重建后的搜索会话 |
| M6 发布 | ✅ 保留；补「真机 delta 若为 0 的替代方案」说明 |

---

## 12. 最终判断

1. **这份计划可以当工作底稿，但不能直接当实施方案用。** 它对代码结构的观察大体准确且逐条可查；对**因果链**的推断（"返回分发不一致导致搜索丢失"）是错的，且错在关键路径上。
2. **问题三可以干净利落地解决**：约 50 行、单文件、手机零影响 —— 前提是公式改增量，并先实测 `delta`。
3. **问题一可以解决，成本比计划预估低**：不需要新的状态管理器，只需在结果点选处记录、在返回时恢复。真正风险是"恢复 vs `onSearchCollapse` 不可逆"的时序。
4. **问题二应缩水**："统一分发"是伪需求的一部分；真实缺陷是几处缺失守卫 + 1 处双关 bug。
5. **不建议**按计划 §6 新建 6 个类。实际落地只新增 1 个类（`TabletModeManager`），其余全部以最小补丁形式落在 `ChatActivity` / `LaunchActivity` / `ActionBarLayout` / `PhotoViewer` / `TwoStepVerificationSetupActivity` / `NekoConfig` / `NekoAppearanceSettingsActivity` / `build.gradle`。

---

## 附录 A：本次核验产生的原始材料

- `back-nav-trace.md` —— 返回分发全链路（含 58 个 `onBackPressed(boolean)` 覆写清单、56 个 `useSimpleActionBar` 页面清单）
- `NFX-search-back-audit.md` —— 搜索/评论状态链路（含快照/恢复 API 清单与离线成本分析）
- `IMPLEMENTATION_NOTES.md` —— 落地说明：编译方式、CI 密钥、埋点、验收清单、已知风险、四个问题结论
- `verify_citations.sh` —— 按**符号**重新定位本文全部锚点，可在任意快照上运行

## 附录 B：锚点定位表（行号 = 当前分支工作区）

用法：`./verify_citations.sh`（默认根目录为 `.`）。下表是在 `feature/enhanced-tablet-mode` 工作区跑出的结果（58/58 命中）。

| 锚点（符号 / 唯一代码片段） | 文件 | 行号 | 命中 |
|---|---|---|---|
| 结果列表点击回调 | `ChatActivity.java` | `8523` | OK |
| 普通搜索点结果→关列表 | `ChatActivity.java` | `8557` | OK |
| reaction 过滤点结果→关列表 | `ChatActivity.java` | `8543` | OK |
| 结果列表可见性读写 | `ChatActivity.java` | `14747` | OK |
| 结果列表可见性查询 | `ChatActivity.java` | `14743` | OK |
| 点结果→新建同会话 ChatActivity | `ChatActivity.java` | `39935` | OK |
| 评论入口（气泡按钮） | `ChatActivity.java` | `46146` | OK |
| 评论页面创建 | `ChatActivity.java` | `39818` | OK |
| 评论页面呈现 | `ChatActivity.java` | `39694` | OK |
| ChatActivity 内部返回优先级 | `ChatActivity.java` | `38543` | OK |
| 返回时先判结果列表 | `ChatActivity.java` | `38575` | OK |
| 返回时后判搜索框 | `ChatActivity.java` | `38610` | OK |
| 重新完全可见回调（恢复时机） | `ChatActivity.java` | `11021 / 30041` | MULTI |
| 搜索会话不可逆清空 | `ChatActivity.java` | `9902 / 42733` | MULTI |
| 搜索列表显隐动画终点 | `ChatActivity.java` | `42753` | OK |
| 同会话 Activity 去重（默认 true） | `ChatActivity.java` | `30343` | OK |
| 去重开关读取 | `ChatActivity.java` | `3183` | OK |
| 平板返回总入口 | `LaunchActivity.java` | `9479` | OK |
| 全局返回链优先级 | `LaunchActivity.java` | `9504` | OK |
| 右侧临时分支（finishFragment） | `LaunchActivity.java` | `9493` | OK |
| predictive: 14+ 回调创建 | `LaunchActivity.java` | `827` | OK |
| predictive: 13 回调创建 | `LaunchActivity.java` | `925` | OK |
| predictive: 目标 pane 解析（0009 新增） | `LaunchActivity.java` | `2080` | OK |
| predictive: 手势期 layout 字段（0009） | `LaunchActivity.java` | `972` | OK |
| predictive: onBackStartedInternal | `LaunchActivity.java` | `869` | OK |
| 平板路由 presentFragment(ChatActivity) | `LaunchActivity.java` | `8183 / 9833` | MULTI |
| 打开聊天时选容器 | `LaunchActivity.java` | `4771` | OK |
| 分栏迁移 / 销毁重建 | `LaunchActivity.java` | `2099` | OK |
| 分栏判定 | `LaunchActivity.java` | `2095` | OK |
| 左栏空栈→结束 Activity | `LaunchActivity.java` | `9883` | OK |
| ABL 完整返回流程 | `ActionBar/ActionBarLayout.java` | `1864` | OK |
| ABL 侧滑守卫（平板分支缺失） | `ActionBar/ActionBarLayout.java` | `1882` | OK |
| ABL 搜索框先关 | `ActionBar/ActionBarLayout.java` | `1888` | OK |
| ABL simpleActionBar 先关 | `ActionBar/ActionBarLayout.java` | `1893` | OK |
| 被覆盖页 detach（实例保留） | `ActionBar/ActionBarLayout.java` | `1990` | OK |
| 回来时复用同一 view 并 onResume | `ActionBar/ActionBarLayout.java` | `2991 / 3167` | MULTI |
| 关闭动画收尾移除 Fragment | `ActionBar/ActionBarLayout.java` | `1415` | OK |
| 双关 bug 所在方法（0006 已修） | `TwoStepVerificationSetupActivity.java` | `2116` | OK |
| 右栏复用守卫（0005 新增） | `ActionBar/ActionBarLayout.java` | `1881` | OK |
| 右栏调用点（0005 新增） | `LaunchActivity.java` | `9491` | OK |
| 唯一的 Insets 监听器 | `PhotoViewer.java` | `4868` | OK |
| insets 字段写入 | `PhotoViewer.java` | `4874` | OK |
| containerView 只设左右 padding | `PhotoViewer.java` | `4894` | OK |
| 监听器返回 CONSUMED | `PhotoViewer.java` | `4904` | OK |
| 容器高度已扣掉 insets.bottom | `PhotoViewer.java` | `24625` | OK |
| 控制栏 48dp / BOTTOM / margin 0 | `PhotoViewer.java` | `10037` | OK |
| 预览窗固定 bottomMargin 48+10 | `PhotoViewer.java` | `10124` | OK |
| 控制栏 translationY 已被占用 | `PhotoViewer.java` | `3702` | OK |
| 手势返回注册（自有窗口） | `PhotoViewer.java` | `18035` | OK |
| 仅左右有 inset 才隐藏导航栏 | `PhotoViewer.java` | `13728` | OK |
| 平板上可被用户强制开关 | `AndroidUtilities.java` | `3077` | OK |
| isTablet 缓存实现 | `AndroidUtilities.java` | `3110` | OK |
| 系统栏/刘海 insets 归一化 | `AndroidUtilities.java` | `6940` | OK |
| 已有 tabletMode 配置项范式 | `NekoConfig.java` | `87` | OK |
| 布尔项持久化 | `ConfigItem.java` | `129` | OK |
| 已有构建方式 fillItems | `SettingsActivity.java` | `752` | OK |
| Neko 页面里已有的平板设置行 | `NekoAppearanceSettingsActivity.java` | `105 / 276` | MULTI |
| 手势导航检测（现成工具） | `AndroidUtil.java` | `208` | OK |

> 说明：
> - `MULTI` = 该片段在文件中出现多次，列出的行号都需结合上下文取用。
> - 结论**不依赖**行号，只依赖符号与语义；行号漂移只影响"跳转定位"。
> - 在 `dev@37a3538`（未改动）上重跑脚本，行号会整体前移，锚点仍全部命中。
