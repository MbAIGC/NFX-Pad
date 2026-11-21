# PiP 往返后群组聊天跳回最新消息：分析

> 复现：群组 A 停在 A-6 → 点视频全屏 → 切到应用内小窗 → 切到别的应用（系统 PiP）→ 返回应用 → 列表在 A-1（最新）。
> 本文只做分析与验证方案，**未改代码**。

---

## 1. 先纠正一个一直误导判断的点：日志里的 `atBottom` 是假的

`ChatActivity.scrollStateForLog()`（`ChatActivity.java:14815`）里：

```java
+ " atBottom=" + (!chatListView.canScrollVertically(1))
```

`canScrollVertically(1)` 在**列表里一个子 View 都没有**时返回 false（没东西可滚），所以
`atBottom` 被算成 **true**。

日志实况（截至 01:42 的 nfxp-diag.log，共 654 行 scroll、171 行 resume(after)）：

| 统计项 | 数量 |
|---|---|
| `chat resume` 且 `first=-1`（一个子 View 都没有） | **324** |
| 其中 `atBottom=true` | **324（100%）** |

也就是说：**每次"布局还没发生"的 resume，日志都在喊"我在最底部"**。
之前几轮"看日志判断不了"不是日志不够，是这一行在说谎。

正确写法应是 `first != -1 && !canScrollVertically(1)`。

---

## 2. 这一次的具体证据（关键 4 行）

```
01:32:54.070  scroll | chat pause  first=11 last=16 top=1997 count=83 atBottom=false   ← 用户在 A-6 一带，不在底部
01:32:59.106  scroll | chat resume(before) first=-1 ... atBottom=true               ← 列表已经没有子上 View
01:33:25.043  scroll | chat resume(before) first=-1 ... count=82                     ← 进系统 PiP 期间，count 82
01:36:39.062  scroll | chat resume(before) first=0 last=4 top=2275 count=83 atBottom=true  ← 回到应用时，已经在 adapter 位置 0
```

两个关键事实：

1. **保存的位置是有的**：`chat pause first=11 atBottom=false`。
2. **回到应用时已经是 0 了**，而且 `resume(before)` 与 `resume(after)` **完全一样**——
   说明不是 `onResume` 里的代码把它滚过去的，是在这之前的一次**重新布局**里丢的。

同时，01:33:25 → 01:36:39 之间**没有任何** `moveScrollToLastMessage` / `scrollToMessageId` 日志。
也就是说：不是某个"滚到底部"的函数被调用，而是 **RecyclerView 重新布局时没有锚点，自己从位置 0 开始**。

---

## 3. 为什么"位置 0"就是"最新消息"

聊天列表是反序布局的：

```java
// ChatActivity.java:7394
chatLayoutManager = new GridLayoutManagerFixed(context, 1000, LinearLayoutManager.VERTICAL, !reversed);
```

`reversed` 默认 false → `reverseLayout = true`。
在 reverseLayout 下，**adapter 位置 0 画在底部**，也就是最新一条消息。
`moveScrollToLastMessage()` 也是 `scrollToPositionWithOffset(0, 0)`（`ChatActivity.java:17878`），
反证了位置 0 = 最新。

所以：**列表一丢锚点，默认落在 0，用户就看到 A-1。**

---

## 4. 锚点为什么会丢：三个候选，按可能性排序

### 候选 A（最可能，但还差一步实证）：系统 PiP 期间把整个 activity 内容设成了 GONE

> 说明：`GONE` 本身不会立刻回收 RecyclerView 的子 View，所以"哪一刻、由谁把子 View 丢掉"
> 还需要一行日志来钉死（见 §6.3）。下面这条链路是现有证据最支持的一条。

进系统 PiP 时：

```java
// SystemHost.java:570
contentVisibility.setHidden(true);
```

它落到：

```java
// LaunchActivity.java:10451
frameLayout.setVisibility(reasonsToHideMainContent > 0 ? View.GONE : View.VISIBLE);
```

GONE 的 View **不参与 measure/layout**。整个聊天页（含 RecyclerView）在 PiP 期间处于"不布局"状态；
期间又来了一条新消息（日志 count 82 → 83），`updateMessages` 对 adapter 做了插入/刷新。
回到应用时 `frameLayout` 变回 VISIBLE，RecyclerView 做第一次布局——
此时如果子 View 已被回收（`notifyDataSetChanged` + 期间没有有效布局），
`LinearLayoutManager` 找不到锚点，只能从 **adapter 位置 0** 开始。

这解释了全部现象：不需要任何"滚到底"的调用、`resume(before)` 就已是 0、count 恰好在 PiP 期间 +1。

### 候选 B：`notifyDataSetChanged` 在 onResume 里把锚点作废

```java
// ChatActivity.java:33195
if (wasPaused) {
    wasPaused = false;
    if (chatAdapter != null) {
        chatAdapter.notifyDataSetChanged(false);
    }
}
```

但这次 `resume(before)` 就已是 0，**早于**这段代码，所以它不是本次主因；
不过它会让候选 A 的后果固定下来（第二次布局也不会回到原位置）。

### 候选 C：`scrollToTopOnResume` 被置位后生效

`ChatActivity.java:24333` / `:29046` 会在 `paused` 时置 `scrollToTopOnResume = true`，
`onResume` 里 `moveScrollToLastMessage(false)`（`:33182`）。
**这次没有 `moveScrollToLastMessage` 日志**，可以排除（但它是同类问题的另一条路径，仍要防）。

---

## 5. 已有的"保存位置"为什么没救回来

`onPause` 确实把位置写进了 prefs：

```java
// ChatActivity.java:33433
editor.putInt("diditem" + key, messageId);
editor.putInt("diditemo" + key, offset);
```

但**读**只在 `onCreate` 加载消息时（`startLoadFromMessageId == 0` 分支，`ChatActivity.java:3522`）。
本次聊天页没有被重建（count 从 82→83 连续，说明是同一个 ChatActivity 实例），
所以 `diditem` 从头到尾没被读过。

**结论：存了，但没人用。**

---

## 6. 修复方案（两层，建议都做）

### 第 1 层：别丢锚点（治因）——已按此实现

进系统 PiP 时不要用 `GONE` 隐藏内容，用 `INVISIBLE`：

- `INVISIBLE` 仍然参与 measure/layout，只是不 draw → RecyclerView 的锚点保住；
- PiP 窗口里已经由 `SystemPipLayout` 铺黑底 + 视频（`SystemPipLayout.onDraw` 里 `canvas.drawColor(0xFF000000)`），
  不会露出聊天内容。

实现：`LaunchActivity` 加了一个平行的计数 `reasonsToHideMainContentKeepLayout` 和
`createPipVisibilityController()`（`keepLayout=true`）。`reasonsToHideMainContent > 0` 仍然是 `GONE`，
只有 PiP 这条路径是 `INVISIBLE`，其它用途（`PhotoViewer`/`Stories` 等 `obtainActivityVisibilityController()`）行为不变。

### 第 1 层为什么是对的：dev.34 的日志已经实证

```
23:06:11.327  content hide(after)   children=6 first=67 h=2614 shown=false
23:06:26.767  content show(before)  children=0 first=-1 h=0    shown=false
23:06:26.821  chat resume(laid)     first=0 ... atBottom=true
```

隐藏那一刻列表还有 6 个子 View、停在 adapter 位置 67；15 秒后恢复前已经变成 0 个子 View、高度 0。
`resume(before)` 与 `resume(after)` 完全相同且都是空的 → 位置是**在隐藏期间**丢的，不是 `onResume` 滚的。
这就是 `GONE` 停掉 measure/layout 的直接后果。

### 第 2 层：丢了也能回来（治果）

在 `ChatActivity` 里显式记住锚点，与上面那套 prefs 解耦：

- `onPause` / `onBecomeFullyHidden`：记录 `(firstVisibleMessageId, offset)` 到字段；
- `onResume` / `onBecomeFullyVisible`：用 `chatListView.post(...)` 等第一次布局完成后检查——
  若当前首条可见是 0（底部）而记住的不是底部，就 `scrollToMessageId(savedId, offset, ...)` 回去。

这样即使根因没修净（厂商 ROM 的 PiP 行为不一），用户也不会看到跳位。

### 6.3 先加 3 行日志，一次钉死"哪一刻丢的"（11-20 15:25 已落地，未推送）

> 你已同意只加日志。实现位置：`LaunchActivity.updateReasonsToHideMainContent`（hide/show before+after）、
> `ChatActivity.onChatListViewLaidOut`（挂在 list 的 layout 回调，只在 empty/refilled 切换时打）、
> `ChatActivity.onResume` 末尾 `chatListView.post(...)`（`chat resume(laid)`）。
> 行内只有计数/下标/偏移/布尔值，已脱敏。顺带把 `atBottom` 改成 `first != -1 && !canScrollVertically(1)`。

不加这三行，下一次还是只能靠推理：

1. `ChatActivity.onPause` 之后、`frameLayout` 被设为 GONE 前后，各记一次
   `scroll | anchor before/after hide first=... childCount=...`
   （`childCount` 用 `chatListView.getChildCount()`，不是 adapter 的 count）。
2. `chatListView` 加 `addOnLayoutChangeListener`，在 `childCount == 0` 时记一行
   `scroll | list laid out empty, top=..., height=...`。
3. `ChatActivity.onResume` 里用 `chatListView.post(...)` 记一次布局完成后的锚点
   （`first`/`top`/`atBottom`），与 `resume(before)` 对照。

有这三行，就能区分四种可能：
- 子 View 在隐藏前就没了 → 是路由/重建，不是 PiP；
- 隐藏后、恢复前变空 → 是 `GONE` 打断布局；
- 恢复后的第一次布局为空 → 是恢复时序；
- 布局时 `childCount > 0` 但 `first == 0` → 是 `notifyDataSetChanged` 锚点作废（候选 B）。

### 顺手：让日志说真话

`scrollStateForLog()` 的 `atBottom` 改成 `first != -1 && !canScrollVertically(1)`，
并补一行"布局完成后的锚点"日志（`post` 一次），下次一眼能看出来。

---

## 7. 验收（拿到新包后）

1. 群组 A 停在 A-6 → 视频 → 应用内小窗 → 切应用 → 系统 PiP → 返回：**应仍在 A-6**。
2. 同上，但期间让群里来 1~3 条新消息：**应仍在 A-6，底部出现"未读/回到底部"按钮**。
3. 返回后日志应出现 `resume(before) first=11 ... atBottom=false`（不是 -1，也不是 0）；
   并对照新增的 `content hide/show(before/after)`、`list layout empty/refilled`、`chat adapter refresh`、
   `chat resume(laid)` 四组行（关键字检索这 4 个词即可，其余区域的行不用看）。
4. 若第 1 层没生效、第 2 层生效：日志里应能看到"restore anchor mid=... "一行。
5. 普通切后台/切回（不进 PiP）不得回归。

---

## 8. 这次和近 20 小时改动的关联

- 近 20 小时的提交（`a56b4acb` … `b1a31158`）都在 PiP 的窗口/按钮/日志上，**没有改动过 ChatActivity 的滚动逻辑**
  （`bf173af8` 只往 ChatActivity 加了 `scroll |` 诊断行）。
- 因此这不是"最近改坏的"，而是**系统 PiP + 隐藏整个 activity 内容**这个组合暴露出来的老问题：
  `GONE` 打断布局 → 锚点丢失 → 反序列表默认回 0。
- 也解释了为什么它只在"应用内小窗 → 切应用 → 系统 PiP → 返回"这条路上稳定复现：
  只有这条路会让整个 activity 内容在 PiP 期间进入 `GONE`。
