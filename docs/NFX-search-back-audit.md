# NagramXF — "back does not return to the in-chat search results" audit

Repo: `/root/DSH/NFX-Pad/NagramXF` (branch `dev`, HEAD `37a353808818ee4fdc29fe1ce583b3a18f87d10e`).
Read-only audit: tree left unmodified (`git status --porcelain` → empty). No gradle was run.
All paths below are relative to `TMessagesProj/src/main/java/`.

---

## 0. TL;DR (the definitive statement)

The in-chat **search-results list is not a navigation destination** in this fork. It is a *visibility flag*
(`animatorSearchResultAsListVisibility`) on the **same** `ChatActivity` that shows the channel
(`ChatActivity.java:52494`, container created at `8467-8504`, overlay added to the same `contentView` at `8473`).

That `ChatActivity` **does survive** the round trip (comments are pushed on top of it, they do not replace it), and
`onResume` never touches search state. So nothing about the query text, the chat position or the search session is
destroyed by "back" per se. **What kills the results screen is that the fork explicitly closes it at the moment the
user picks a result, and nothing ever re-opens it on return**:

* `ChatActivity.java:8538-8541` — tapping a result in the list (plain search) does
  `getMediaDataController().jumpToSearchedMessage(classGuid, position); showMessagesSearchListView(false);`
  → the results list is gone *before* the user even opens the comments.
* There is **no** `showMessagesSearchListView(true)` in `onResume` / `onBecomeFullyVisible` / `onFragmentResume`.
  All 13 call sites (see §1.4) are user actions, hashtag/feed flows, or `MODE_SEARCH` initialisation.
* On return the user is looking at the *channel chat list*, scrolled to the selected post, with (at most) the search
  field still open — i.e. exactly "it lands on the channel message list".

Two independent aggravating facts explain the "or behaves inconsistently" part:

* **A) the extra `ChatActivity`**: on the hashtag/`MODE_SEARCH` results pages the *same* tap goes through
  `openMessageInOriginalDialog()` (`8505-8518` → `39809-39824`) which does
  `presentFragment(new ChatActivity(args))` with `need_remove_previous_same_chat_activity=false`. On a tablet that
  pushes a **second live `ChatActivity` of the same dialog** onto `rightActionBarLayout`; the first back press lands on
  *that* channel message list, the search page is one press further down.
* **B) the back gesture is form-factor dependent**: on phone `ActionBarLayout.onBackPressed()`
  (`ActionBarLayout.java:1871-1874`) closes the **search field** first; on tablet `LaunchActivity.onBackPressed()`
  (`LaunchActivity.java:9451-9463`) bypasses that and calls the fragment, where
  `isMessagesSearchListVisible()` (`38449`) is checked *before* `actionBar.isSearchFieldVisible()` (`38485`).
  Once the field is closed, `onSearchCollapse()` **irreversibly wipes** the cached results
  (`42517-42606`: `clearFoundMessageObjects()`, `HashtagSearchController.clearSearchResults()`), so the session cannot
  be restored at all without a new server search.

---

## 1. "Search as list" mechanism

### 1.1 Fields and construction

```java
// ChatActivity.java:863-865
private ChatActivitySearchContainer messagesSearchListContainer;
public RecyclerListView messagesSearchListView;
private MessagesSearchAdapter messagesSearchAdapter;
```
```java
// ChatActivity.java:8467-8473
messagesSearchListContainer = new ChatActivitySearchContainer(context);
...
messagesSearchListContainer.setVisibility(View.GONE);
...
contentView.addView(messagesSearchListContainer, LayoutHelper.createFrameMatchParent());
```
The list is a sibling overlay inside the **same** `contentView` (i.e. the same fragment view) as `chatListView`;
`8492-8495` builds a `LinearLayoutManager` (**a local variable, not a field**) and the adapter:
`new MessagesSearchAdapter(context, this, themeDelegate, searchType, dialog_id == getUserConfig().getClientUserId())`.

### 1.2 The flag + toggle

```java
// ChatActivity.java:14713-14728
private void toggleMesagesSearchListView() { ... showMessagesSearchListView(!isMessagesSearchListVisible()); }
private boolean isMessagesSearchListVisible() { return animatorSearchResultAsListVisibility.getValue(); }
private void showMessagesSearchListView(boolean show) {
    if (messagesSearchListContainer == null || animatorSearchResultAsListVisibility.getValue() == show) return;
    animatorSearchResultAsListVisibility.setValue(show, true);
    ...
```
The animator drives alpha/visibility of the container (`52390-52399`, `52455`), hides `chatListView` (`52410-52418`)
and the side controls (`52360-52370`). It is a plain object field on the fragment, so **its value survives
pause/resume** (see §4).

### 1.3 `chatMode == MODE_SEARCH` vs. normal in-chat search — two different models

```java
// ChatActivity.java:874
public static final int MODE_SEARCH = 7;
```
`MODE_SEARCH` is **only** the hashtag / "public posts" / "my messages" search container, created from the pager:

```java
// ChatActivity.java:8605-8645  (searchViewPager adapter)
int getItemCount() { return 3; }
View createView(int viewType) {
    if (viewType == SEARCH_THIS_CHAT) { return new FirstViewPage(context); }
    Bundle args = new Bundle();
    args.putInt("chatMode", ChatActivity.MODE_SEARCH);
    args.putInt("searchType", viewType);
    args.putString("searchHashtag", searchingHashtag);
    ChatActivityContainer container = new ChatActivityContainer(context, getParentLayout(), args) { ... };
    container.chatActivity.parentThemeDelegate = themeDelegate;
    container.chatActivity.parentChatActivity = ChatActivity.this;
```
and validated in `onFragmentCreate`:
```java
// ChatActivity.java:3303-3309
} else if (chatMode == MODE_SEARCH) {
    searchType = arguments.getInt("searchType", 0);
    searchingHashtag = arguments.getString("searchHashtag", null);
    ...
    if (searchType == 0 || (searchingHashtag == null && searchType != 4)) return false;
```

Search UIs present in this fork (all inside `ChatActivity.createView`):

| UI | Field / line | What it is |
|---|---|---|
| action-bar search field | `searchItem` (`610`, created `4949-4953`) | plain in-chat text search; results are shown by **scrolling the chat list** (`26373-26398` → `scrollToMessageId`) |
| "search as list" overlay | `messagesSearchListContainer` / `messagesSearchListView` (`863-865`, `8467-8504`) | optional list of results (toggle "Search as list" text button, `12120-12135`, `14776`) |
| hashtag search pager | `searchViewPager` (`699`, `8571-8666`) | 3 pages: *This chat* / *My messages* / *Public posts*; pages 1-2 are nested `ChatActivity`s with `chatMode == MODE_SEARCH` |
| hashtag tabs | `hashtagSearchTabs` (`694`, `8671-8696`) | tab strip for the pager |
| hashtag history / empty view | `hashtagHistoryView`, `hashtagSearchEmptyView` (`9963-9991`) | shown inside `messagesSearchListContainer` |
| mention container | `mentionContainer` (`12896-12921`) | @mention / user-filter search inside the field, unrelated to result lists |

**So: two search models coexist.**
*Plain text search* (`chatMode == 0`): results are the real chat list (jumped to), plus an optional overlay list that
lives in the same fragment.
*Hashtag search* (`chatMode == MODE_SEARCH`): the results *page* is a `View` (`ChatActivityContainer`) inside
`ViewPagerFixed`, hosting its **own** `ChatActivity` instance (`ChatActivityContainer.java:35-67`,
`chatActivity.isInsideContainer = true`) — **not** a fragment in the navigation stack.

### 1.4 Every `showMessagesSearchListView(false)` / `setValue(false, …)` site, classified

| Line | Trigger | Class |
|---|---|---|
| `8535` | reaction-filtered result tapped, after `scrollToMessageId` | **user picked a result** |
| `8540` | normal result tapped → `jumpToSearchedMessage` | **user picked a result** (this is the killer for the reported flow) |
| `24301` | `processLoadedMessages`, `postponedScroll` branch, `progressDialog != null` | chat (re)loaded / scrolled to a message |
| `27213` | `hashtagSearchUpdated`, feed search with visible items | feed results arrived |
| `38449-38450` | `onBackPressed`, `isMessagesSearchListVisible()` | **back gesture** (consumes the press world) |
| `42622` | `onSearchCollapse()` (`actionBar.closeSearchField`) | **user closed search** (irreversible, see §4) |
| `42748` | `onSearchPressed`, non-hashtag branch | **query submitted** |
| `42753-42755` | `onTextChanged`: `if (searchingHashtag == null) showMessagesSearchListView(false);` | **any keystroke** in the field |
| `52655` | `goToNextOrPrevSearchMessage` (up/down arrows) | user navigated to next result |
| `48552` | feed search reset (`clearChatData`) | chat re-opened / feed reset |
| `3658` (`true`) | `updateSearchingHashtag()` | hashtag changed → **show** |
| `39039` (`true`) | `openHashtagSearch()` | user opened a hashtag → **show** |
| `42488-42497` | `canCollapseSearch()`: re-opens the list (returns false) or closes it | back/close of search field (fork-specific) |
| `42724` / `42767` (`true`) | `onSearchPressed` hashtag branch / `onTextChanged` hashtag-history branch | hashtag flows → **show** |
| `48490`, `49249` (`true`) | feed empty state / `nkbtn_view_history` | NekoX menu actions → **show** |
| `9925` | `createSearchContainer()` when `chatMode == MODE_SEARCH` | `MODE_SEARCH` page initialisation (`setValue(true, false)`) |

**No site is "fragment resumed / re-shown".** That absence is the root cause of the missing screen.

### 1.5 Where the rows come from

```java
// Adapters/MessagesSearchAdapter.java:176-189
searchResultMessages.clear(); messageIds.clear();
if (searchType == 0) searchResults = MediaDataController.getInstance(currentAccount).getFoundMessageObjects();
else if (searchType == 4) searchResults = new ArrayList<>();
else searchResults = HashtagSearchController.getInstance(currentAccount).getMessages(searchType);
```
`getFoundMessageObjects()` is `return searchResultMessages;` (`MediaDataController.java:3890-3892`) — a
**per-account, not per-dialog** field (`3742`).

---

## 2. Opening a search result

### 2.1 The click listener

```java
// ChatActivity.java:8505-8542
messagesSearchListView.setOnItemClickListener((view, position) -> {
    if (chatMode == MODE_SEARCH) {
        Object obj = messagesSearchAdapter.getItem(position);
        if (position == 0 && messagesSearchAdapter.containsStories && ...) { presentFragment(new MediaActivity(...)); }
        else if (obj instanceof MessageObject) {
            openMessageInOriginalDialog((MessageObject) obj);          // 8518  ← new ChatActivity
        }
    } else if (searchingReaction != null) {
        ... setSearchedPosition(position); updateSearchButtons(...);
        AndroidUtilities.runOnUIThread(() -> {
            scrollToMessageId(msg.getId(), 0, true, 0, true, 0, null, () -> {...});
            if (waitingForLoad.isEmpty()) showMessagesSearchListView(false);    // 8535
        });
    } else {
        getMediaDataController().jumpToSearchedMessage(classGuid, position);   // 8539  ← in place
        showMessagesSearchListView(false);                                     // 8540
    }
});
```
**Confirmed: `openMessageInOriginalDialog` creates a NEW `ChatActivity`; the plain-search branch does NOT.**

```java
// ChatActivity.java:39809-39824
private void openMessageInOriginalDialog(MessageObject messageObject) {
    Bundle args = new Bundle();
    if (messageObject.messageOwner.peer_id.channel_id != 0) args.putLong("chat_id", messageObject.messageOwner.peer_id.channel_id);
    else if (...chat_id...) ... else if (...user_id...) ...
    args.putInt("message_id", messageObject.getRealId());
    args.putBoolean("need_remove_previous_same_chat_activity", false);
    if (getMessagesController().checkCanOpenChat(args, ChatActivity.this)) {
        presentFragment(new ChatActivity(args));
    }
}
```
`jumpToSearchedMessage` is purely local (no IO):
```java
// MediaDataController.java:3915-3922
public void jumpToSearchedMessage(int guid, int index) {
    if (index < 0 || index >= searchResultMessages.size()) return;
    lastReturnedNum = index;
    ... postNotificationName(NotificationCenter.chatSearchResultsAvailable, guid, messageObject.getId(), getMask(), ...);
}
```
(latent detail: it is fed the raw adapter position while the adapter offsets by `containsStories`
(`8508`, `206-214`); `containsStories` is hard-forced `false` at `156`, so harmless today.)

### 2.2 Does presenting a same-dialog `ChatActivity` remove the previous one? Exact rules

`BaseFragment.presentFragment` → `parentLayout.presentFragment(...)`
(`ActionBar/BaseFragment.java:812-826`) → `ActionBarLayout.presentFragment()` → delegate hook
`INavigationLayoutDelegate.needPresentFragment(...)` (`ActionBar/ActionBarLayout.java:2231-2250`).

**Tablet (`LaunchActivity.needPresentFragment`, `LaunchActivity.java:9651-9769`):**
```java
// 9691-9712
if (fragment instanceof ChatActivity && !((ChatActivity) fragment).isInScheduleMode() || params.forceRightLayout ||
    layout == rightActionBarLayout && (rightActionBarLayout.getFragmentStack().size() > 1 || !(last instanceof ChatActivity))) {
    if (!tabletFullSize && layout == rightActionBarLayout || tabletFullSize && layout == actionBarLayout) {
        if (layout == rightActionBarLayout) { rightActionBarLayout.getView().setVisibility(VISIBLE); backgroundTablet.setVisibility(GONE); }
        boolean result = !(tabletFullSize && layout == actionBarLayout && actionBarLayout.getFragmentStack().size() == 1);
        ...
        return result;      // ← true ⇒ plain push onto rightActionBarLayout
    } else if (!tabletFullSize && layout != rightActionBarLayout && rightActionBarLayout != null) {
        rightActionBarLayout.removeAllFragments();          // 9723 ← nukes the search screen
        rightActionBarLayout.presentFragment(...);          // 9724
```
**Phone:** `needPresentFragment` returns `true` (no tablet branch) → same push.

Stack insertion itself never removes the old fragment when `removeLast == false`:
```java
// ActionBarLayout.java:1977-2014  presentFragmentInternalRemoveOld(removeLast, fragment)
fragment.onBecomeFullyHidden(); fragment.onPause();
if (removeLast) { fragment.onFragmentDestroy(); fragment.setParentLayout(null); fragmentsStack.remove(fragment); ... }
else { /* only detach fragmentView + actionBar */ }
```

The same-dialog de-duplication lives **in `ChatActivity`**, at the end of the *new* fragment's open animation:
```java
// ChatActivity.java:30171 onTransitionAnimationEnd(boolean isOpen, boolean backward)
// ChatActivity.java:30217-30232
if (!backward && parentLayout != null && needRemovePreviousSameChatActivity) {
    for (int a = 0, N = parentLayout.getFragmentStack().size() - 1; a < N; a++) {
        BaseFragment fragment = parentLayout.getFragmentStack().get(a);
        if (fragment != this && fragment instanceof ChatActivity) {
            ChatActivity chatActivity = (ChatActivity) fragment;
            if (chatActivity.needRemovePreviousSameChatActivity
                && chatActivity.dialog_id == dialog_id && chatActivity.getTopicId() == getTopicId()
                && chatActivity.getChatMode() == getChatMode()
                && chatActivity.threadMessageId == threadMessageId && chatActivity.isReport() == isReport()) {
                fragment.removeSelfFromStack();
                break;
            }
        }
    }
}
```
with
```java
// ChatActivity.java:3165
needRemovePreviousSameChatActivity = arguments.getBoolean("need_remove_previous_same_chat_activity", true);
```

**Consequences (tablet and phone identical here):**
* `openMessageInOriginalDialog` sets the flag to **false** ⇒ the search `ChatActivity` is **kept**. On tablet the right
  layout therefore holds **two live `ChatActivity` instances of the same dialog**: `[ChatA(search), ChatB(post)]`.
* Any *later* `ChatActivity` of the same dialog opened with the **default** flag (`true`) removes the previous one —
  and the test uses the **old** fragment's flag, so the search `ChatActivity` (`flag == true`) is removable, while the
  `openMessageInOriginalDialog` one (`flag == false`) is not. Same pattern is mirrored for `hasChatInBack`
  (`26491`, `29196`).
* Tablet-only difference on the *presenting* side: if the presenter is not in `rightActionBarLayout`
  (`9714-9731`) or `tabletFullSize && layout != actionBarLayout` (`9729-9741`), `removeAllFragments()` /
  "present into the left stack" runs ⇒ the search `ChatActivity` is destroyed.
* Back: `needCloseLastFragment` (`9846-9869`) only special-cases the empty/leftmost stacks; a pop of the right stack
  re-attaches the **same** `fragmentView` object and calls `onResume` (`ActionBarLayout.java:2925-2982`).

---

## 3. Comments / discussion

```java
// ChatActivity.java:46015-46037
public void didPressCommentButton(ChatMessageCell cell) {
    ...
    if (message.messageOwner.replies != null) { maxReadId = message.messageOwner.replies.read_max_id; linkedChatId = message.messageOwner.replies.channel_id; }
    else { maxReadId = -1; linkedChatId = 0; }
    if (isFeedSearch()) openDiscussionMessageChat(-message.getDialogId(), FeedMessageUtils.getForwardingMessageObject(...), ...);
    else openDiscussionMessageChat(currentChat.id, message, message.getId(), linkedChatId, maxReadId, 0, null);
}
```
```java
// ChatActivity.java:39636-39660 (processLoadedDiscussionMessage)
Bundle args = new Bundle();
long dialogId = arrayList.get(0).getDialogId();
args.putLong("chat_id", -dialogId);
args.putInt("message_id", Math.max(1, discussionMessage.read_inbox_max_id));
args.putBoolean("historyPreloaded", historyFinal != null);
ChatActivity chatActivity = new ChatActivity(args);
...
chatActivity.setThreadMessages(arrayList, originalChat, req.msg_id, discussionMessage.read_inbox_max_id, discussionMessage.read_outbox_max_id, null);
...
presentFragment(chatActivity);          // 39681-ish, inside openCommentsChat
```
`setThreadMessages` sets `threadMessageId = threadMessageObject.getId()` (`38586-38593`).

Findings:
* The comment page is a **new `ChatActivity`**, pushed with `presentFragment(...)`; `need_remove_previous_same_chat_activity`
  is **not** set (default `true`), but the de-dup test at `30222` also compares `dialog_id` **and** `threadMessageId`,
  which differ from the search `ChatActivity` (`dialog_id` = linked group, `threadMessageId` = post id) ⇒
  **the search page is NOT removed and is NOT replaced**. On tablet the right stack becomes
  `[ChatA(search), ChatB?(post), ChatC(comments)]`.
* There is **no** `parentLayout.addFragmentToStack` / `backToPreviousFragment` in this path. `backToPreviousFragment`
  exists only for the pull-down gesture (`1412`, set at `30283-30284`, re-inserted at `38492-38494`) and is irrelevant
  to this flow.

---

## 4. Lifecycle — is there anything that resets search when the fragment is re-shown?

**No.**

* `presentFragmentInternalRemoveOld` detaches `fragmentView` and calls `onPause()`; it does not recreate anything
  (`ActionBarLayout.java:1988-2012`).
* `closeLastFragment` re-adds **the same** view object, calls `previousFragment.onResume()` and sets
  `currentActionBar = previousFragment.actionBar` (`ActionBarLayout.java:2931-2982`).
* `ChatActivity.onPause()` (`33128-…`) and `ChatActivity.onResume()` (`32905-…`) contain **zero** search-state code:
  no `actionBar.closeSearchField()`, no `searching = false`, no `chatMode = 0`, no `showMessagesSearchListView(false)`.
* `onBecomeFullyHidden()` (`30081-30103`) and `onBecomeFullyVisible()` (`29935-29960`) are equally clean.
* `ChatActivity` has no `onFragmentPause` / `onFragmentResume` override (grep: none) and no
  `onSaveInstanceState` usage at all — `onFragmentCreate` reads search state only from `arguments` (`3125-3165`),
  and there are no search-related arguments in the fork's `ChatActivity` bundles.
* Visibilities are recomputed from the retained animator value (`52390-52460`), so a re-attached view keeps the
  overlay state consistent with `isMessagesSearchListVisible()`.

**What *does* destroy the session:**
```java
// ChatActivity.java:42517 onSearchCollapse()
searching = false; ... searchingHashtag = null; ...
getMediaDataController().clearFoundMessageObjects();                                   // 42602
HashtagSearchController.getInstance(currentAccount).clearSearchResults(...)            // 42604-42606
...
animatorSearchResultAsListVisibility.setValue(false, true);                            // 42622
```
plus the pager teardown:
```java
// ChatActivity.java:42583-42589
if (searchViewPager != null) {
    if (hashtagSearchTabs.tabs.getCurrentPosition() != 0) { hashtagSearchTabs.tabs.scrollToTab(0, 0); requestClearSearchPages = true; }
    else { searchViewPager.clearViews(); }        // ViewPagerFixed.clearViews() → viewsByType.clear()  (Components/ViewPagerFixed.java:1103-1105)
}
```
`clearFoundMessageObjects()` cancels the in-flight requests and clears every result bucket
(`MediaDataController.java:3894-3905`). **After that, restoring the query requires a brand-new server search.**

---

## 5. What a restore would need — concrete API list, sync vs. server

### 5.1 State to snapshot (all reachable from `ChatActivity`)

| What | Field / accessor (line) |
|---|---|
| search active | `searching` (`840`), `searchItemVisible` (`30946` consumer) |
| query text | `searchingQuery` (`841`); visible text: `searchItem.getSearchField().getText()` |
| field open | `actionBar.isSearchFieldVisible()` (`ActionBar/ActionBar.java:160`), `animatorSearchFieldVisibility` |
| sender filter | `searchingUserMessages` (`823`), `searchingChatMessages` (`824`) |
| tag/reaction filter | `searchingReaction` (`826`), `searchingFiltered` (`839`), `actionBarSearchTags` (`693`) |
| media/type filter | `searchingType` (`843`), `FiltersView.filters` (`Adapters/FiltersView.java:75`), `getFilterAt(int)` (`214`) |
| hashtag state | `searchingHashtag` (`845`), `searchType`, `defaultSearchPage` (`39041-39046`), `hashtagSearchTabs.tabs.getCurrentPosition()` |
| results list visible | `isMessagesSearchListVisible()` (`14719`) |
| list scroll | `((LinearLayoutManager) messagesSearchListView.getLayoutManager()).findFirstVisibleItemPosition()` — note `messagesSearchLayoutManager` is a **local** at `8492`; pattern used at `14731-14736` |
| selected result index | `getMediaDataController().getSearchPosition()` (`3924`), `getSearchCount()` (`3928`), `getMask()` (`29892` consumer) |
| identity guard | `dialog_id`, `mergeDialogId`, `threadMessageId`, `chatMode`, `getTopicId()` |

### 5.2 Restore calls

| Purpose | API (file:line) | Cost |
|---|---|---|
| put text back into the field | `setSearchQuery(String)` → `searchItem.setSearchFieldText(searchingQuery = text, false)` (`10262-10266`) | **offline** |
| re-open the field | `searchItem.openSearch(false)` (`38937`), or `animatorSearchFieldVisibility.setValue(true, true)` (`39030` region) | offline |
| re-apply a tag filter | `setTagFilter(VisibleReaction)` (`10254-10261`) → `actionBarSearchTags.setChosen(reaction, true)` | offline |
| re-show the list overlay | `showMessagesSearchListView(true)` (`14723`), or `animatorSearchResultAsListVisibility.setValue(true, false)` (pattern at `9925`) | offline |
| re-bind rows from the still-valid cache | `messagesSearchAdapter.notifyDataSetChanged()` (`42608` pattern) → reads `MediaDataController.getFoundMessageObjects()` (`3890`) | offline |
| restore list scroll | `messagesSearchListView.getLayoutManager().scrollToPosition(n)` (`27206-27207` pattern) | offline |
| restore selected result / jump | `getMediaDataController().setSearchedPosition(index)` (`3939`), `jumpToSearchedMessage(classGuid, index)` (`3915`) | offline (no-op if index out of range) |
| restore arrows/counter | `updateSearchButtons(mask, num, count)` (`29892`), `updateSearchCountText()` (`29906`) | offline |
| **re-run the query** | `hitSearch()` (`10268-10276`) or `getMediaDataController().searchMessagesInChat(query, dialog_id, mergeDialogId, classGuid, 0, threadMessageId, false, user, chat, jumpToMessage, reaction, filter)` (`3911`, `3964`) | **SERVER**: clears results first (`4024-4034`) then sends `TL_messages_search` (`4121-4131`, sent `4192`); also kicks a deleted-messages history scan (`4040-4080`) |
| hashtag page restore | `updateSearchingHashtag(hashtag)` (`3652-3680`) → `clearChatData` + `firstLoadMessages` → `HashtagSearchController.searchHashtag(...)` (`3673`); controller keeps results per `searchType` and only clears when the hashtag differs (`HashtagSearchController.java:140-160`) | **SERVER** (continues paging; `sendRequest` at `225`) |
| hashtag paging / navigation | `HashtagSearchController.jumpToMessage(guid, index, searchType)` (`290-297`), `getMessages/getCount/isEndReached(searchType)` (`128-138`) | offline |

**Bottom line for a fix:** the *only* restore that is fully offline and reliable is "keep the fragment alive (already
true) and re-apply the overlay list visibility + scroll + selected index". Anything based on re-issuing the query is a
new network round trip and rebuilds the list from scratch (top, no scroll), and is unsafe to key on the global cache
(§6 pitfalls).

---

## 6. Which model applies (item 6) + pitfalls

**Both models apply, and they behave differently:**

* plain in-chat search (`chatMode == 0`): the results page **is** the `ChatActivity` itself + an overlay list; the
  "parent page" that must be restored is a fragment (retained), and only a boolean/scroll needs restoring.
* hashtag search (`chatMode == MODE_SEARCH`): the results page is a **`View` inside `ViewPagerFixed`**
  (`Components/ViewPagerFixed.java:491-534` `updateViewForIndex`, `1103-1105` `clearViews`, `1160-1162`
  `getCurrentView`), hosting a nested `ChatActivity` via `ChatActivityContainer` (`ChatActivityContainer.java:35-67`,
  `isInsideContainer = true`, `initChatActivity()` at `84-112`). Restoring it means *recreating a page*, not popping a
  fragment. `ViewPagerFixed` only keeps `viewPages[0]`/`[1]`; other pages are stashed in `viewsByType`
  (`520-529`) and thrown away by `clearViews()`.

Pitfalls to design around:

1. **Two `ChatActivity` instances of the same dialog.** `openMessageInOriginalDialog` deliberately sets
   `need_remove_previous_same_chat_activity=false` (`39819`), so `[ChatA(search), ChatB(post)]` coexist in
   `rightActionBarLayout`. Any later same-dialog `ChatActivity` with the default flag removes **ChatA** (`30217-30232`)
   — e.g. tapping the channel avatar inside the comments goes through `openChat()` (`43956-43992`) which creates a
   channel `ChatActivity` with default args ⇒ the search screen is destroyed and back no longer lands on it.
2. **Switching dialogs.** `MediaDataController.searchResultMessages`, `lastReturnedNum`, `lastSearchQuery`,
   `lastGuid`, `lastDialogId`, `messagesSearchEndReached` (`3731-3754`, `4138-4143`) are **one global session per
   account**, not keyed by dialog. A search in another chat silently invalidates this activity's cached rows; there is
   no public getter for `lastDialogId`/`lastGuid` (only `getLastSearchQuery()` at `4301`), so a cache-based restore
   would need a new accessor or a private copy of the result ids.
3. **Deleted message.** The adapter keeps `MessageObject` references (`Adapters/MessagesSearchAdapter.java:73`,
   `176-189`); `removeMessageFromResults` mutates the controller list (`3759-3766`); `jumpToSearchedMessage` returns
   silently when the index is stale (`3916-3918`). A restored list may show rows that no longer exist, and a restored
   index may point elsewhere after `updateSearchResults()` re-sorts.
4. **Process death / rebuild.** Nothing is persisted: no `onSaveInstanceState`, search state exists only in fragment
   fields. Rebuilds that recreate the view (`ActionBarLayout.rebuildAllFragmentViews` `3501`) or the activity (tablet
   layout move in `LaunchActivity.checkLayout()` `2077-2120` — `onFragmentDestroy()` + `rebuildFragments`) lose the
   query, the list and the pager pages. A restore that must survive this has to travel in the fragment `arguments`
   (or preferences) — `onFragmentCreate` currently reads only `chat_id/user_id/message_id/chatMode/searchType/searchHashtag`.
5. **Back-order semantics.** The same gesture means three different things: close list (`38449-38450`), close search
   field (`38485-38486`), pop fragment. On phone the layout intercepts first (`ActionBarLayout.java:1871-1874`); on
   tablet `LaunchActivity.java:9451-9463` calls the fragment directly. Any restored list must be placed explicitly in
   that order, otherwise the user's next back destroys the session irreversibly (`42602-42606`).
6. **`onTextChanged` closes the list on every keystroke** for plain queries (`42753-42755`) and `onSearchPressed`
   closes it on submit (`42748`) — so re-populating the field programmatically (`setSearchFieldText` from
   `setSearchQuery`) can itself re-close a restored list depending on listener wiring. Restore ordering matters:
   restore text first, then the list visibility.

---

## 7. Ranked candidate root causes (by evidence strength)

| # | Cause | Evidence | Confidence |
|---|---|---|---|
| 1 | **No restore of the results-list overlay across fragment navigation.** It is closed when a result is picked (`8535`, `8540`) and never re-opened on resume; no `showMessagesSearchListView(true)` exists in any lifecycle path. Back therefore shows the channel chat list with only the search field (+ query) left. | `8505-8541`, `14713-14728`, call-site table §1.4, `32905-33099`, `30081-30103` | **High — this is the primary cause** |
| 2 | **Extra same-dialog `ChatActivity` pushed by the result tap in `MODE_SEARCH` pages** ⇒ one back press short; two live activities of the same dialog in the right pane on tablet. | `8518`, `39809-39824`, `9691-9712`, `30217-30232` | High (applies when the results page is a hashtag/public-posts page) |
| 3 | **Form-factor divergence of the back gesture** (phone closes the search field at layout level; tablet checks the list first inside the fragment) → "inconsistent", and the field's closure wipes the session. | `ActionBarLayout.java:1871-1874`, `LaunchActivity.java:9451-9463`, `38449`, `38485`, `42517-42622` | High |
| 4 | **`onSearchCollapse()` is destructive and irreversible** (`clearFoundMessageObjects`, `clearSearchResults`, list reset) — no history/query snapshot, so any accidental close forces a fresh server search. | `42517-42622`, `MediaDataController.java:3894-3905` | High |
| 5 | **Search screen can be silently removed by same-chat de-duplication** when a later same-dialog `ChatActivity` opens with the default flag (e.g. channel avatar inside comments). | `3165`, `30217-30232`, `43956-43992` | Medium (needs an extra tap outside the reported flow) |
| 6 | **Global, non-dialog-keyed search cache** makes cache-based restore unsafe and makes results disappear if another chat is searched meanwhile. | `MediaDataController.java:3731-3754`, `3890-3905`, `4138-4143` | Medium |
| 7 | **Structural/rebuild loss** (tablet layout switch, theme rebuild, process death) — destroys the activity instead of the state. | `LaunchActivity.java:2077-2120`, `ActionBarLayout.java:3501`, absence of `onSaveInstanceState` | Low for the reported flow, high for "survives nothing" |

### Minimal reliable fix (local, offline)

Snapshot in `ChatActivity` when it is paused/hidden while `searching || isMessagesSearchListVisible()`
(`onPause`/`onBecomeFullyHidden`): `searchingQuery`, `searchingHashtag`, `searchingUserMessages`,
`searchingChatMessages`, `searchingReaction`, `searchingType`, `searchingFiltered`, the field text/visibility,
`isMessagesSearchListVisible()`, the list's first visible position, and `getSearchPosition()`.
Re-apply in `onResume`/`onBecomeFullyVisible`, **text before list**, guarded by `dialog_id/threadMessageId`, and only
when `searching` is still true. No network call is needed; `hitSearch()`/`searchMessagesInChat(...)` should be reserved
for the case where the fragment (or the `MediaDataController` session) really is gone, and then the scroll position and
selected result must be re-established via `jumpToSearchedMessage`/`setSearchedPosition` after the results arrive.
