# TABLET back-navigation dispatch trace — NagramXF (dev @ 37a353808818ee4fdc29fe1ce583b3a18f87d10e)

Read-only audit. All paths relative to `TMessagesProj/src/main/java/org/telegram/`.
No files were modified, no gradle was run.

---

## 0. Layout topology (needed to read everything below)

Three `ActionBarLayout` instances exist on tablet; on phone only one.

`ui/LaunchActivity.java:361-363`
```java
361:    public ActionBarLayout actionBarLayout;
362:    private ActionBarLayout layersActionBarLayout;
363:    public ActionBarLayout rightActionBarLayout;
```
Stacks are plain `ArrayList`s owned by the Activity and shared with the layouts:

`ui/LaunchActivity.java:346-348`
```java
346:    private final ArrayList<BaseFragment> mainFragmentsStack = new ArrayList<>();
347:    private final ArrayList<BaseFragment> layerFragmentsStack = new ArrayList<>();
348:    private final ArrayList<BaseFragment> rightFragmentsStack = new ArrayList<>();
```

`ui/LaunchActivity.java:533` — the "main" layout is created first, before it is known whether we are tablet:
```java
533:        actionBarLayout = new ActionBarLayout(this, true);
```

Tablet branch `ui/LaunchActivity.java:1000` (`if (AndroidUtilities.isTablet()) { … }`) builds `launchLayout` and re-parents the main layout into it, then creates the other two:

`ui/LaunchActivity.java:1128-1138`
```java
1128:            ViewGroup parent = (ViewGroup) actionBarLayout.getView().getParent();
1129:            if (parent != null) {
1130:                parent.removeView(actionBarLayout.getView());
1131:            }
1132:            launchLayout.addView(actionBarLayout.getView());
1133:
1134:            rightActionBarLayout = new ActionBarLayout(this, false);
1135:            rightActionBarLayout.setIsRightLayout();
1136:            rightActionBarLayout.setFragmentStack(rightFragmentsStack);
1137:            rightActionBarLayout.setDelegate(this);
1138:            launchLayout.addView(rightActionBarLayout.getView());
```

`ui/LaunchActivity.java:1173-1184`
```java
1173:            layersActionBarLayout = new ActionBarLayout(this, false);
1174:            layersActionBarLayout.setIsLayersLayout();
1175:            layersActionBarLayout.setRemoveActionBarExtraHeight(true);
1176:            layersActionBarLayout.setBackgroundView(shadowTablet);
1177:            layersActionBarLayout.setUseAlphaAnimations(true);
1178:            layersActionBarLayout.setFragmentStack(layerFragmentsStack);
1179:            layersActionBarLayout.setDelegate(this);
1180:            layersActionBarLayout.setDrawerLayoutContainer(drawerLayoutContainer);
1181:
1182:            final View layersView = layersActionBarLayout.getView();
1183:            layersView.setVisibility(layerFragmentsStack.isEmpty() ? View.GONE : View.VISIBLE);
1184:            launchLayout.addView(layersView);
```

Phone branch `ui/LaunchActivity.java:1185-1196` — the main layout goes straight into `drawerLayoutContainer`; `rightActionBarLayout`/`layersActionBarLayout` stay `null`.

Note `useAlphaAnimations` is set **only** on the layers layout (`LaunchActivity.java:1177`, `ExternalActionActivity.java:161`); the right layout keeps the default `false` (`ActionBarLayout.java:3651`). This decides which `closeLastFragment` branch runs (see §2.3).

`import`-level fact: `rightActionBarLayout` is `public` while `layersActionBarLayout` is `private` — the split-pane layout leaks to the outside world, which is exactly how `SettingsActivity` pushes into it:

`ui/SettingsActivity.java:913-927`
```java
913:    private void presentSettingFragment(BaseFragment fragment) {
914:        if (AndroidUtilities.isTablet() && LaunchActivity.instance != null && LaunchActivity.instance.getRightActionBarLayout() != null) {
915:            final INavigationLayout layout = LaunchActivity.instance.getRightActionBarLayout();
916:            if (!layout.getFragmentStack().isEmpty()) {
917:                for (int a = 0; a < layout.getFragmentStack().size() - 1; a++) {
918:                    layout.removeFragmentFromStack(layout.getFragmentStack().get(0));
919:                    a--;
920:                }
921:                layout.closeLastFragment(false);
922:            }
923:            layout.presentFragment(new INavigationLayout.NavigationParams(fragment).setNoAnimation(true).forceRightLayout());
```

---

## 1. `ui/LaunchActivity.java`

### 1.1 `onBackPressed()` — line 9446-9467

```java
9446:    @Override
9447:    public void onBackPressed() {
9448:        if (!onBackPressed(true)) {
9449:            return;
9450:        }
9451:        if (AndroidUtilities.isTablet()) {
9452:            if (layersActionBarLayout != null && layersActionBarLayout.getView().getVisibility() == View.VISIBLE) {
9453:                layersActionBarLayout.onBackPressed();
9454:            } else {
9455:                if (rightActionBarLayout != null && rightActionBarLayout.getView().getVisibility() == View.VISIBLE && !rightActionBarLayout.getFragmentStack().isEmpty()) {
9456:                    BaseFragment lastFragment = rightActionBarLayout.getFragmentStack().get(rightActionBarLayout.getFragmentStack().size() - 1);
9457:                    if (lastFragment.onBackPressed(true)) {
9458:                        lastFragment.finishFragment();
9459:                    }
9460:                } else {
9461:                    actionBarLayout.onBackPressed();
9462:                }
9463:            }
9464:        } else {
9465:            actionBarLayout.onBackPressed();
9466:        }
9467:    }
```

Early-return / branch order:

1. `9448-9450` — `onBackPressed(true)` returned `false` ⇒ **return** (the global chain consumed it; nothing layout-related happens).
2. `9451` — not tablet ⇒ `actionBarLayout.onBackPressed()` (9465) and done.
3. tablet, `9452` — layers layout exists **and its view is `VISIBLE`** ⇒ `layersActionBarLayout.onBackPressed()` (9453). No fall-through to the right or main layout.
4. tablet, `9455` — right layout non-null, **view `VISIBLE`**, **stack non-empty** ⇒ ad-hoc path (9456-9459): `lastFragment.onBackPressed(true)`; if it returns `true`, `lastFragment.finishFragment()`.
5. tablet, otherwise ⇒ `actionBarLayout.onBackPressed()` (9461).

Note the ad-hoc path at 9457-9459 is **inside** `onBackPressed()` only; the `onBackPressed(boolean)` overload never reaches it. It is the *only* place in the app that calls `finishFragment()` on a layout's last fragment from a back event.

### 1.2 `onBackPressed(boolean)` — line 9469-9503 (the "global" chain)

```java
9469:    public boolean onBackPressed(boolean invoked) {
9470:        if (FloatingDebugController.onBackPressed(invoked)) {
9471:            return false;
9472:        }
9473:        if (drawerLayoutContainer != null && drawerLayoutContainer.isDrawerOpened()) {
9474:            if (invoked) {
9475:                drawerLayoutContainer.closeDrawer(true);
9476:            }
9477:            return false;
9478:        }
9479:        if (passcodeDialog != null && passcodeDialog.passcodeView.getVisibility() == View.VISIBLE) {
9480:            if (invoked) finish();
9481:            return false;
9482:        }
9483:        if (bottomSheetTabsOverlay != null && bottomSheetTabsOverlay.isOpen) {
9484:            if (invoked) bottomSheetTabsOverlay.onBackPressed();
9485:            return false;
9486:        }
9487:        if (!SearchTagsList.onBackPressedRenameTagAlert(invoked)) {
9488:            return false;
9489:        } else if (ContentPreviewViewer.hasInstance() && ContentPreviewViewer.getInstance().isVisible()) {
9490:            if (invoked) ContentPreviewViewer.getInstance().closeWithMenu();
9491:            return false;
9492:        } else if (SecretMediaViewer.hasInstance() && SecretMediaViewer.getInstance().isVisible()) {
9493:            if (invoked) SecretMediaViewer.getInstance().closePhoto(true, false);
9494:            return false;
9495:        } else if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
9496:            if (invoked) PhotoViewer.getInstance().closePhoto(true, false);
9497:            return false;
9498:        } else if (ArticleViewer.hasInstance() && ArticleViewer.getInstance().isVisible()) {
9499:            if (invoked) ArticleViewer.getInstance().close(true, false);
9500:            return false;
9501:        }
9502:        return true;
9503:    }
```

Ordered early-returns (all `false` = "consumed, do not touch layouts"):

| # | line | condition | action on `invoked` |
|---|------|-----------|---------------------|
| 1 | 9470 | `FloatingDebugController.onBackPressed(invoked)` | delegated (`Components/FloatingDebug/FloatingDebugController.java:18-20`) |
| 2 | 9473 | drawer opened | `drawerLayoutContainer.closeDrawer(true)` |
| 3 | 9479 | passcode dialog visible | `finish()` the **activity** |
| 4 | 9483 | `bottomSheetTabsOverlay.isOpen` | `bottomSheetTabsOverlay.onBackPressed()` (`ActionBar/BottomSheetTabsOverlay.java:587-593` → `closeTabsView()`) |
| 5 | 9487 | `!SearchTagsList.onBackPressedRenameTagAlert(invoked)` — i.e. the rename-tag alert exists | dismiss it (`Components/SearchTagsList.java:377-386` returns `false` when it dismissed one, `true` when there was none) |
| 6 | 9489 | ContentPreviewViewer visible | `closeWithMenu()` |
| 7 | 9492 | SecretMediaViewer visible | `closePhoto(true,false)` |
| 8 | 9495 | PhotoViewer visible | `closePhoto(true,false)` |
| 9 | 9498 | ArticleViewer visible | `close(true,false)` |

`return true` at 9502 means "layout may act".

Two facts that matter for a refactor:

* Note the **inverted** boolean of `SearchTagsList` (9487) vs. the `hasInstance()/isVisible()` chain (9489-9500) — the same `if/else-if` ladder mixes both conventions.
* `ArticleViewer.hasInstance()` only covers the **singleton** viewer: `Instance` is assigned exclusively inside `ArticleViewer.getInstance()` (`ui/ArticleViewer.java:395-408`); `ArticleViewer.makeSheet()` (`ui/ArticleViewer.java:411-413`) creates a `new ArticleViewer(fragment)` that never touches `Instance`. So an article viewer hosted as an attached sheet is **not** caught here — it is only caught by `ActionBarLayout.onBackPressed()`'s `sheetFragment` branch (see §2.2 / §(c)).

### 1.3 Predictive-back registration — lines 824-939

```java
824:        RestrictedLanguagesSelectActivity.checkRestrictedLanguages(false);
825:        if (Build.VERSION.SDK_INT >= 34 && NaConfig.INSTANCE.getBackAnimationStyle().Int() == ActionBarLayout.BACK_ANIMATION_PREDICTIVE) {
826:            if (onBackAnimationCallback == null) {
827:                onBackAnimationCallback =  new OnBackAnimationCallback() {
828:                    private AnimationNotificationsLocker locker = new AnimationNotificationsLocker();
829:                    private boolean locked;
830:
831:                    private boolean started = false;
832:                    private boolean invoked = false;
833:
834:                    @Override
835:                    public void onBackInvoked() {
836:                        invoked = true;
837:                        if (locked) {
838:                            locker.unlock();
839:                            locked = false;
840:                        }
841:
842:                        if (AndroidUtilities.isTablet()) {
843:                            onBackPressed();
844:                            return;
845:                        }
846:                        if (!onBackPressed(true))
847:                            return;
848:                        if (actionBarLayout != null) {
849:                            actionBarLayout.onBackInvoked();
850:                        } else {
851:                            onBackPressed();
852:                        }
853:                    }
854:
855:                    @Override
856:                    public void onBackStarted(@NonNull BackEvent backEvent) {
857:                        started = true;
858:                        invoked = false;
859:                        predictiveBackStarted = false;
860:                    }
861:
862:                    private void onBackStartedInternal(BackEvent backEvent) {
863:                        if (AndroidUtilities.isTablet()) return;
864:                        if (!onBackPressed(false)) return;
865:                        if (actionBarLayout != null) {
866:                            boolean started = actionBarLayout.onBackStarted(backEvent.getTouchX(), backEvent.getTouchY());
867:                            if (started && !locked) {
868:                                locker.lock();
869:                                locked = true;
870:                            }
871:                        }
872:                    }
873:
874:                    private static final float LAZY_START = 0.015f;
875:                    private boolean predictiveBackStarted;
876:                    private boolean predictiveBackInvoked;
877:
878:                    @Override
879:                    public void onBackProgressed(@NonNull BackEvent backEvent) {
880:                        if (started && invoked) return;
881:
882:                        final float progress = backEvent.getProgress();
883:                        if (!predictiveBackStarted && progress > LAZY_START) {
884:                            predictiveBackStarted = true;
885:                            onBackStartedInternal(backEvent);
886:                        }
887:
888:                        final float fixedProgress = Math.max(0, progress - LAZY_START) / (1 - LAZY_START);
889:
890:                        if (AndroidUtilities.isTablet()) return;
891:                        if (actionBarLayout != null) {
892:                            actionBarLayout.onBackProgress(fixedProgress);
893:                        }
894:                    }
895:
896:                    @Override
897:                    public void onBackCancelled() {
898:                        started = false;
899:                        invoked = false;
900:                        if (locked) {
901:                            locker.unlock();
902:                            locked = false;
903:                        }
904:
905:                        if (AndroidUtilities.isTablet()) return;
906:                        if (actionBarLayout != null) {
907:                            actionBarLayout.onBackCancelled();
908:                        }
909:                    }
910:                };
911:            }
912:            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
913:                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
914:                (OnBackAnimationCallback) onBackAnimationCallback
915:            );
916:        } else if (Build.VERSION.SDK_INT >= 33) {
917:            if (onBackInvokedCallback == null) {
918:                onBackInvokedCallback = new OnBackInvokedCallback() {
919:                    @Override
920:                    public void onBackInvoked() {
921:                        if (AndroidUtilities.isTablet()) {
922:                            onBackPressed();
923:                            return;
924:                        }
925:                        if (!onBackPressed(true))
926:                            return;
927:                        if (actionBarLayout != null) {
928:                            actionBarLayout.onBackInvoked();
929:                        } else {
930:                            onBackPressed();
931:                        }
932:                    }
933:                };
934:            }
935:            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
936:                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
937:                (OnBackInvokedCallback) onBackInvokedCallback
938:            );
939:        }
```
Registration is unregistered in `onDestroy` at `LaunchActivity.java:7932-7943`.

Tablet vs phone, callback by callback:

| callback | phone | tablet |
|---|---|---|
| `onBackStarted` (856-860) | sets `started=true`, `invoked=false`, `predictiveBackStarted=false` (no side effect) | same (the `isTablet()` check is not here) |
| `onBackStartedInternal` (862-872) | `onBackPressed(false)` dry-run, then `actionBarLayout.onBackStarted(x,y)`; on `true` locks `AnimationNotificationsLocker` | **`return` immediately at 863** — no dry-run, no `ActionBarLayout.onBackStarted`, never locks |
| `onBackProgressed` (878-894) | drives `actionBarLayout.onBackProgress(fixedProgress)` (891-893) | `predictiveBackStarted=true` is still set at 884-885, `onBackStartedInternal` is a no-op, then **`return` at 890** — no progress at all |
| `onBackCancelled` (896-909) | unlocks (900-903) then `actionBarLayout.onBackCancelled()` (906-908) | unlocks (900-903) then **`return` at 905** |
| `onBackInvoked` (834-853) | unlock, `onBackPressed(true)`; if `true` → `actionBarLayout.onBackInvoked()`; if `actionBarLayout == null` → `onBackPressed()` (the whole Activity path) | unlock (837-840) then **`onBackPressed()` at 843** — the full Activity path, never `actionBarLayout.onBackInvoked()` |
| `onBackInvoked` (33-only variant, 918-933) | `onBackPressed(true)` → `actionBarLayout.onBackInvoked()` (928) | `onBackPressed()` at 922 |

Consequences of "tablet returns early" (factual, derived from the code above):

1. **No predictive animation is possible on tablet.** `ActionBarLayout.onBackStarted` (1667) is the only predictive entry; it is what sets `predictiveInput`/`predictiveBackInProgress` and calls `currentFragment.onBeginSlide()` (1708) (it is also one of the three callers of `prepareForMoving()` — 1704, plus the touch paths 1578/1617). With 863 returning early, `predictiveBackInProgress`/`predictiveInput` are never set on tablet, so `newBackTransitions()` (1735-1737) stays `false` and the `drawChild` predictive branch at `ActionBarLayout.java:1239-1257` is never taken.
2. **The system back-scroll/scale preview is not suppressed either** — `AnimationNotificationsLocker` (`messenger/AnimationNotificationsLocker.java`, used at 828/838/868/901) is never locked on tablet, so the platform keeps its own cross-task animation.
3. **The only tablet effect of a back gesture is the final `onBackInvoked`**, and it is routed through `onBackPressed()` (843/922) — i.e. the *whole* `LaunchActivity.onBackPressed(boolean)` chain runs again from scratch. On phone the pre-check at 846/925 already ran `onBackPressed(true)` and then hands off to `ActionBarLayout.onBackInvoked()`, which either finishes the predictive animation (`1726-1733`) or falls back to `ActionBarLayout.onBackPressed()`.
4. **Cancel semantics are identical on tablet** — `onBackCancelled` still clears `started`/`invoked`/`locked` before returning, so no state leak, just no visual.
5. Because the tablet branch is *before* `onBackPressed(true)`, on tablet a back gesture that the global chain would consume (PhotoViewer, drawer, passcode…) still results in exactly one `onBackPressed()` call; on phone the same gesture calls `onBackPressed(true)` once at 846 and returns.

### 1.4 How the layouts are created and which one hosts `ChatActivity`

Creation: §0 above (`533`, `1134-1138`, `1173-1184`). Hosting decision is made in `needPresentFragment`, which is the delegate hook consulted from `ActionBarLayout.presentFragment`:

`ui/ActionBar/ActionBarLayout.java:2248`
```java
2248:        if (fragment == null || checkTransitionAnimation() || delegate != null && check && !delegate.needPresentFragment(this, params) || !fragment.onFragmentCreate()) {
2249:            return false;
2250:        }
```

`ui/LaunchActivity.java:9651` + `9691-9714`
```java
9651:    public boolean needPresentFragment(INavigationLayout layout, INavigationLayout.NavigationParams params) {
...
9691:            if (
9692:                fragment instanceof ChatActivity && !((ChatActivity) fragment).isInScheduleMode() || params.forceRightLayout ||
9693:                layout == rightActionBarLayout && (rightActionBarLayout.getFragmentStack().size() > 1 || !(rightActionBarLayout.getLastFragment() instanceof ChatActivity))
9694:            ) {
9695:                if (!tabletFullSize && layout == rightActionBarLayout || tabletFullSize && layout == actionBarLayout) {
9696:                    if (layout == rightActionBarLayout) {
9697:                        if (rightActionBarLayout.getView() != null) {
9698:                            rightActionBarLayout.getView().setVisibility(View.VISIBLE);
9699:                        }
9700:                        backgroundTablet.setVisibility(View.GONE);
9701:                    }
9702:                    boolean result = !(tabletFullSize && layout == actionBarLayout && actionBarLayout.getFragmentStack().size() == 1);
9703:                    if (!layersActionBarLayout.getFragmentStack().isEmpty()) {
9704:                        for (int a = 0; a < layersActionBarLayout.getFragmentStack().size() - 1; a++) {
9705:                            layersActionBarLayout.removeFragmentFromStack(layersActionBarLayout.getFragmentStack().get(0));
9706:                            a--;
9707:                        }
9708:                        layersActionBarLayout.closeLastFragment(!forceWithoutAnimation);
9709:                    }
9710:                    if (!result) {
9711:                        getActionBarLayout().presentFragment(params.setNoAnimation(forceWithoutAnimation).setCheckPresentFromDelegate(false));
9712:                    }
9713:                    return result;
9714:                } else if (!tabletFullSize && layout != rightActionBarLayout && rightActionBarLayout != null) {
```
and it ends with `return true` at `9771` (i.e. "let the layout that was called do the presentation").

Reading the routing rules concretely:

* Any non-schedule `ChatActivity` (9692) → right pane in split mode (9714-9728: `rightActionBarLayout.removeAllFragments(); rightActionBarLayout.presentFragment(...)`), main layout in `tabletFullSize` mode (9729-9738).
* Anything presented **while the right layout is the current layout** keeps stacking in the right pane as long as the right stack has >1 fragment or its last fragment is not a `ChatActivity` (9693). Consequence: chat → profile → media → … all live in `rightFragmentsStack`.
* Everything else (9691 condition false) → layers pane: `9750-9768`
  ```java
  9750:            } else if (layersActionBarLayout != null && layout != layersActionBarLayout) {
  9751:                layersActionBarLayout.getView().setVisibility(View.VISIBLE);
  ...
  9767:                layersActionBarLayout.presentFragment(params.setRemoveLast(removeLast).setNoAnimation(forceWithoutAnimation).setCheckPresentFromDelegate(false));
  9768:                return false;
  ```
* `MainTabsActivity`/`DialogsActivity` reset the whole tablet state (`9660-9690`).

The three call sites the task mentions:

`ui/LaunchActivity.java:4739` (openMessage — link/deep-link chat open):
```java
4739:                if (!(AndroidUtilities.isTablet() ? rightActionBarLayout : getActionBarLayout()).presentFragment(chatActivity) && dialogId < 0) {
```

`ui/LaunchActivity.java:4338-4344` (openShare/showDraft — deliberately the *main* layout):
```java
4338:        final boolean removeLast;
4339:        if (AndroidUtilities.isTablet() && layersActionBarLayout != null) {
4340:            removeLast = !layersActionBarLayout.getFragmentStack().isEmpty() && layersActionBarLayout.getFragmentStack().get(layersActionBarLayout.getFragmentStack().size() - 1) instanceof MainTabsActivity;
4341:        } else {
4342:            removeLast = actionBarLayout.getFragmentStack().size() > 1 && actionBarLayout.getFragmentStack().get(actionBarLayout.getFragmentStack().size() - 1) instanceof MainTabsActivity;
4343:        }
4344:        getActionBarLayout().presentFragment(fragment, removeLast, !animated, true, false);
```

`ui/LaunchActivity.java:4644-4657` (exportChat / openDialogsToSend):
```java
4651:                    final boolean removeLast;
4652:                    if (AndroidUtilities.isTablet()) {
4653:                        removeLast = !layersActionBarLayout.getFragmentStack().isEmpty() && layersActionBarLayout.getFragmentStack().get(layersActionBarLayout.getFragmentStack().size() - 1) instanceof MainTabsActivity;
4654:                    } else {
4655:                        removeLast = actionBarLayout.getFragmentStack().size() > 1 && actionBarLayout.getFragmentStack().get(actionBarLayout.getFragmentStack().size() - 1) instanceof MainTabsActivity;
4656:                    }
4657:                    getActionBarLayout().presentFragment(fragment, removeLast, false, true, false);
```

Important trap for a refactor: `getActionBarLayout()` is **not** `actionBarLayout`:

`ui/LaunchActivity.java:7561-7567`
```java
7561:    public INavigationLayout getActionBarLayout() {
7562:        INavigationLayout currentLayout = actionBarLayout;
7563:        if (!sheetFragmentsStack.isEmpty()) {
7564:            currentLayout = sheetFragmentsStack.get(sheetFragmentsStack.size() - 1);
7565:        }
7566:        return currentLayout;
7567:    }
```
So `isTablet() ? rightActionBarLayout : getActionBarLayout()` (4739) can be `rightActionBarLayout` while a `BottomSheet`-hosted layout is on top — the chat then goes into the right pane *behind* the open sheet.

Split/full-size mode is decided in `checkLayout()` which physically moves `ChatActivity` fragments between `mainFragmentsStack` and `rightFragmentsStack` and toggles the panes' visibility:

`ui/LaunchActivity.java:2063-2095`
```java
2063:    private boolean isTabletSplitMode() {
2064:        return !AndroidUtilities.isInMultiwindow && (!AndroidUtilities.isSmallTablet() || getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
2065:    }
2066:
2067:    private void checkLayout() {
...
2072:        if (isTabletSplitMode()) {
2073:            tabletFullSize = false;
...
2086:                    rightActionBarLayout.addFragmentToStack(chatFragment);
...
2094:            rightActionBarLayout.getView().setVisibility(rightActionBarLayout.getFragmentStack().isEmpty() ? View.GONE : View.VISIBLE);
2095:            backgroundTablet.setVisibility(rightActionBarLayout.getFragmentStack().isEmpty() ? View.VISIBLE : View.GONE);
2096:        } else {
2097:            tabletFullSize = true;
...
2117:            rightActionBarLayout.getView().setVisibility(View.GONE);
```
i.e. in `tabletFullSize` the right pane is always `GONE`, so `onBackPressed()` 9455 is false and chats are popped from `mainFragmentsStack` through `actionBarLayout.onBackPressed()`.

### 1.5 Does anything else route back on tablet?

* **`dispatchKeyEvent`** (`9581-9625`) does *not* touch `KEYCODE_BACK`; it only intercepts volume keys for story viewers and video playback, then `super.dispatchKeyEvent(event)` (9620). So on API < 33 the framework's `Activity.onBackPressed()` reaches the override at 9447 in the normal way.
* **`onKeyUp`** (`9628-9648`) routes `KEYCODE_MENU` (drawer/menu key) to the visible layout — 9635-9642 duplicates the same tablet precedence (layers → right → main). Not a back route, but the same ordering bug surface.
* **`onPreIme`** (`9566-9578`) — reached from `ActionBarLayout.dispatchKeyEventPreIme`:
  `ui/ActionBar/ActionBarLayout.java:1174-1178`
  ```java
  1174:    public boolean dispatchKeyEventPreIme(KeyEvent event) {
  1175:        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
  1176:            return delegate != null && delegate.onPreIme() || super.dispatchKeyEventPreIme(event);
  1177:        }
  1178:        return super.dispatchKeyEventPreIme(event);
  ```
  It handles only SecretMediaViewer / PhotoViewer / ArticleViewer — **no fragment/layout logic**, so a back that arrives through the IME path does *not* pop a fragment (it is consumed only if a viewer is open). This is identical on tablet and phone.
* **Dialog/sheet fragments** have their own, tablet-independent route:
  `ui/ActionBar/BaseFragment.java:1224-1230`
  ```java
  1224:            public void onBackPressed() {
  1225:                if (actionBarLayout[0] == null || actionBarLayout[0].getFragmentStack().size() <= 1) {
  1226:                    super.onBackPressed();
  1227:                } else {
  1228:                    actionBarLayout[0].onBackPressed();
  1229:                }
  1230:            }
  ```
  i.e. a `BottomSheet`-hosted layout answers back itself (`super.onBackPressed()` = dismiss the sheet when it holds ≤1 fragment). Note this bypasses `needCloseLastFragment` / the Activity entirely.
* **`shadowTablet` tap** (`1140-1167`) closes the layers pane without any back event:
  ```java
  1156:                        if (!layersActionBarLayout.getFragmentStack().isEmpty()) {
  1157:                            for (int a = 0; a < layersActionBarLayout.getFragmentStack().size() - 1; a++) {
  1158:                                layersActionBarLayout.removeFragmentFromStack(layersActionBarLayout.getFragmentStack().get(0));
  1159:                                a--;
  1160:                            }
  1161:                            layersActionBarLayout.closeLastFragment(true);
  ```
* `ExternalActionActivity` (a second Activity, used for external intents) has the same tablet split but **no** right-pane ad-hoc path:
  `ui/ExternalActionActivity.java:609-626`
  ```java
  609:    public void onBackPressed() {
  ...
  617:        } else if (AndroidUtilities.isTablet() && layersActionBarLayout != null) {
  618:            if (layersActionBarLayout.getView().getVisibility() == View.VISIBLE) {
  619:                layersActionBarLayout.onBackPressed();
  620:            } else {
  621:                actionBarLayout.onBackPressed();
  622:            }
  ```

---

## 2. `ui/ActionBar/ActionBarLayout.java`

### 2.1 Predictive-back API — lines 1662-1737

```java
1662:    private boolean predictiveInput;
1663:    private boolean predictiveBackInProgress;
1664:    private boolean predictiveBackHasProgress;
1665:    private float predictiveBackY;
1666:    private boolean predictiveBackLeft;
1667:    public boolean onBackStarted(float touchX, float touchY) {
1668:        if (animationInProgress) {
1669:            if (backAnimator != null) {
1670:                backAnimator.end();
1671:                backAnimator = null;
1672:            } else {
1673:                return false;
1674:            }
1675:            if (animationInProgress) return false;
1676:        }
1677:        if (predictiveBackInProgress || predictiveInput) {
1678:            return false;
1679:        }
1680:        if (transitionAnimationPreviewMode || startedTracking || checkTransitionAnimation() || fragmentsStack.size() <= 1) {
1681:            return false;
1682:        }
1683:        if (isInPreviewMode()) {
1684:            return false;
1685:        }
1686:        if (sheetFragment != null && sheetFragment.hasShownSheet()) {
1687:            return false;
1688:        }
1689:        final BaseFragment currentFragment = fragmentsStack.get(fragmentsStack.size() - 1);
1690:        if (!currentFragment.onBackPressed(false)) {
1691:            return false;
1692:        }
1693:        if (currentFragment.simpleActionBar != null && currentFragment.simpleActionBar.hasBackAction()) {
1694:            return false; // the back closes the menu or the search of the header, see onBackInvoked
1695:        }
1696:        if (currentFragment.hasShownSheet() || !currentFragment.canBeginSlide()) {
1697:            return false;
1698:        }
1699:        predictiveBackHasProgress = false;
1700:        predictiveBackInProgress = true;
1701:        predictiveInput = true;
1702:        predictiveBackLeft = touchX < AndroidUtilities.displaySize.x / 2f;
1703:        predictiveBackY = touchY;
1704:        prepareForMoving();
1705:        if (parentActivity != null && parentActivity.getCurrentFocus() != null) {
1706:            AndroidUtilities.hideKeyboard(parentActivity.getCurrentFocus());
1707:        }
1708:        currentFragment.onBeginSlide();
1709:        return true;
1710:    }
1711:
1712:    public void onBackProgress(float t) {
1713:        if (!predictiveInput) return;
1714:        final float dx = dp(56) * CubicBezierInterpolator.StandardDecelerate.getInterpolation(t);
1715:        predictiveBackHasProgress = t > 0;
1716:        containerView.setTranslationX(dx);
1717:        setInnerTranslationX(dx);
1718:    }
1719:
1720:    public void onBackCancelled() {
1721:        if (!predictiveInput) return;
1722:        predictiveInput = false;
1723:        animateBackEndAnimation(true, 0f);
1724:    }
1725:
1726:    public void onBackInvoked() {
1727:        if (!predictiveInput) {
1728:            onBackPressed();
1729:            return;
1730:        }
1731:        predictiveInput = false;
1732:        animateBackEndAnimation(false, 0f);
1733:    }
1734:
1735:    private boolean newBackTransitions() {
1736:        return predictiveBackInProgress && predictiveBackHasProgress;
1737:    }
```

`onBackStarted`'s early returns, in order (all "no predictive animation"): `animationInProgress` and no `backAnimator` (1673/1675) → already predictive (1677) → preview mode/tracking/transition/`size<=1` (1680) → `inPreviewMode()` (1683) → layout-level sheet shown (1686) → **the fragment itself said "I consume it"** (1690) → **the fragment has a `simpleActionBar` with a back action** (1693 — comment says the back will close header menu/search, handled by `onBackInvoked`→`onBackPressed`) → fragment-level sheet / `!canBeginSlide()` (1696).

`onBackInvoked` (1726-1733) is the *contract bridge*: when there was no predictive input it explicitly falls back to `ActionBarLayout.onBackPressed()` (1728). This is why fragments returning `false` from `onBackPressed(false)` still see `onBackPressed(true)` later — via `onBackInvoked`.

### 2.2 `onBackPressed()` — lines 1863-1888

```java
1863:    @Override
1864:    public void onBackPressed() {
1865:        if (transitionAnimationPreviewMode || startedTracking || checkTransitionAnimation() || fragmentsStack.isEmpty()) {
1866:            return;
1867:        }
1868:        if (GroupCallPip.onBackPressed()) {
1869:            return;
1870:        }
1871:        if (!storyViewerAttached() && currentActionBar != null && !currentActionBar.isActionModeShowed() && currentActionBar.isSearchFieldVisible) {
1872:            currentActionBar.closeSearchField();
1873:            return;
1874:        }
1875:        final BaseFragment lastFragmentForBar = fragmentsStack.get(fragmentsStack.size() - 1);
1876:        if (!storyViewerAttached() && lastFragmentForBar.simpleActionBar != null && lastFragmentForBar.simpleActionBar.onBackPressed()) {
1877:            return;
1878:        }
1879:        if (sheetFragment != null && !sheetFragment.onBackPressed(true)) {
1880:            return;
1881:        }
1882:        BaseFragment lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
1883:        if (lastFragment.onBackPressed(true)) {
1884:            if (!fragmentsStack.isEmpty()) {
1885:                closeLastFragment(true);
1886:            }
1887:        }
1888:    }
```

Exact order:

1. `1865` guard: preview-transition / swipe in progress / transition animation / empty stack ⇒ no-op.
2. `1868` `GroupCallPip.onBackPressed()` ⇒ no-op.
3. `1871` regular `ActionBar` search field visible (and no story viewer attached, no action mode) ⇒ `closeSearchField()`, return.
4. `1876` `simpleActionBar.onBackPressed()` ⇒ returns `true` if it closed the header menu overlay or its search field (`ActionBar/SimpleActionBar.java:1160-1170`), return.
5. `1879` **layout-level `sheetFragment`** ⇒ `sheetFragment.onBackPressed(true)` returns `false` = consumed (it closes the top attached sheet via `BaseFragment.onBackPressed`→`closeSheet`→`AttachedSheet.onAttachedBackPressed`), return.
6. `1883` the fragment's own `onBackPressed(true)`; if `true` ⇒ `closeLastFragment(true)` (1885), guarded by a redundant `!fragmentsStack.isEmpty()`.

`storyViewerAttached()` (`114-120`) is a pre-condition for steps 3 and 4 only.

### 2.3 `closeLastFragment` and the real removal point

`ui/ActionBar/ActionBarLayout.java:2902-2913`
```java
2902:    public void closeLastFragment(boolean animated) {
2903:        closeLastFragment(animated, false);
2904:    }
2905:
2906:    public void closeLastFragment(boolean animated, boolean forceNoAnimation) {
2907:        BaseFragment fragment = getLastFragment();
2908:        if (fragment != null && fragment.closeLastFragment()) {
2909:            return;
2910:        }
2911:        if (delegate != null && !delegate.needCloseLastFragment(this) || checkTransitionAnimation() || fragmentsStack.isEmpty()) {
2912:            return;
2913:        }
```

Three gates before anything happens:

* `2908` — **fragment-level veto/redirect**: `BaseFragment.closeLastFragment()` defaults to `false` (`BaseFragment.java:1401-1403`); `DialogsActivity` overrides it:
  `ui/DialogsActivity.java:12969-12978`
  ```java
  12969:    public boolean closeLastFragment() {
  12970:        if (rightSlidingDialogContainer.hasFragment()) {
  12971:            rightSlidingDialogContainer.finishPreview();
  12972:            if (searchViewPager != null) {
  12973:                searchViewPager.updateTabs();
  12974:            }
  12975:            return true;
  12976:        }
  12977:        return super.closeLastFragment();
  12978:    }
  ```
* `2911` — `delegate.needCloseLastFragment(this)`, then `checkTransitionAnimation()`, then empty-stack.
* `LaunchActivity.needCloseLastFragment` (`9848-9868`) is where the Activity decides to close itself instead:
  ```java
  9848:    public boolean needCloseLastFragment(INavigationLayout layout) {
  9849:        if (AndroidUtilities.isTablet()) {
  9850:            if (layout == actionBarLayout && layout.getFragmentStack().size() <= 1 && !switchingAccount) {
  9851:                super.onBackPressed();
  9852:                return false;
  9853:            } else if (layout == rightActionBarLayout) {
  9854:                if (!tabletFullSize) {
  9855:                    backgroundTablet.setVisibility(View.VISIBLE);
  9856:                }
  9857:            } else if (layout == layersActionBarLayout && actionBarLayout.getFragmentStack().isEmpty() && layersActionBarLayout.getFragmentStack().size() == 1) {
  9858:                super.onBackPressed();
  9859:                return false;
  9860:            }
  9861:        } else {
  9862:            if (layout.getFragmentStack().size() <= 1) {
  9863:                super.onBackPressed();
  9864:                return false;
  9865:            }
  9866:        }
  9867:        return true;
  9868:    }
  ```
  ⇒ semantics per pane: **main** layout: last fragment gone ⇒ finish the activity. **right** layout: never finit; shows the empty-pane background and closes the fragment. **layers**: closes the last layer only if the main stack is non-empty; otherwise finishes the activity.

Then two removal implementations:

* animated / two-fragment path, `2918`-`3051`, with the actual removal deferred to `onCloseAnimationEndRunnable` (`2993-3018` → `3013 closeLastFragmentInternalRemoveOld(currentFragment)`), or immediate at `3047` when `needAnimation == false`;
* single-fragment path `3052-3097`: `3059 removeFragmentFromStackInternal(currentFragment, false)` (alpha-animated, layers layout) or `3091` (immediate, right/main layout).

`ui/ActionBar/ActionBarLayout.java:2768-2781` — the real destruction:
```java
2768:    private void closeLastFragmentInternalRemoveOld(BaseFragment fragment) {
2769:        fragment.finishing = true;
2770:        fragment.onPause();
2771:        fragment.onFragmentDestroy();
2772:        fragment.setParentLayout(null);
2773:        fragmentsStack.remove(fragment);
2774:        containerViewBack.setVisibility(View.INVISIBLE);
2775:        containerViewBack.setTranslationY(0);
2776:        bringChildToFront(containerView);
2777:        if (sheetContainer != null) {
2778:            bringChildToFront(sheetContainer);
2779:        }
2780:        onFragmentStackChanged("closeLastFragmentInternalRemoveOld");
2781:    }
```

`ui/ActionBar/ActionBarLayout.java:3169-3186` — the other removal path:
```java
3169:    private void removeFragmentFromStackInternal(BaseFragment fragment, boolean allowFinishFragment) {
3170:        if (!fragmentsStack.contains(fragment)) {
3171:            return;
3172:        }
3173:        if (allowFinishFragment && fragmentsStack.get(fragmentsStack.size() - 1) == fragment) {
3174:            fragment.finishFragment();
3175:        } else {
3176:            if (fragmentsStack.get(fragmentsStack.size() - 1) == fragment && fragmentsStack.size() > 1) {
3177:                fragment.finishFragment(false);
3178:            } else {
3179:                fragment.onPause();
3180:                fragment.onFragmentDestroy();
3181:                fragment.setParentLayout(null);
3182:                fragmentsStack.remove(fragment);
3183:                onFragmentStackChanged("removeFragmentFromStackInternal " + allowFinishFragment);
3184:            }
3185:        }
3186:    }
```

Last line of every close: `3098 currentFragment.onFragmentClosed();` (`onFragmentClosed` default empty, `BaseFragment.java:1421-1423`; note: the task said `onFragmentClose` — no such method exists in this tree, `grep -rn "onFragmentClose"` only matches `onFragmentClosed`).

`BaseFragment.closeLastFragment()`/`finishFragment` chain is in §3.

### 2.4 Swipe-back gesture tracking

State/entry points:

* `startedTracking` set in `prepareForMoving()` (`1485-1488`), cleared in `onSlideAnimationEnd` (`1474`) and on touch cancel (`1646`);
* `maybeStartTracking` set on `ACTION_DOWN` (`1561`);
* `ActionBarLayout.onTouchEvent` `1546-1660`:
  ```java
  1548:        if (!checkTransitionAnimation() && !inActionMode && !animationInProgress && !predictiveBackInProgress) {
  1549:            if (fragmentsStack.size() > 1 && allowSwipe()) {
  1550:                if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN) {
  1551:                    BaseFragment currentFragment = fragmentsStack.get(fragmentsStack.size() - 1);
  1552:                    if (!currentFragment.isSwipeBackEnabled(ev)) {
  ...
  1574:                    if (!transitionAnimationInProgress && !inPreviewMode && maybeStartTracking && !startedTracking && dx >= AndroidUtilities.getPixelsInCM(0.4f, true) && Math.abs(dx) / 3 > dy) {
  1575:                        BaseFragment currentFragment = fragmentsStack.get(fragmentsStack.size() - 1);
  1576:                        if (currentFragment.canBeginSlide() && findScrollingChild(this, ev.getX(), ev.getY()) == null) {
  1577:                            startedTrackingX = (int) ev.getX();
  1578:                            prepareForMoving();
  ...
  1612:                    if (!inPreviewMode && !transitionAnimationPreviewMode && !startedTracking && currentFragment.isSwipeBackEnabled(ev)) {
  1613:                        float velX = velocityTracker.getXVelocity();
  1614:                        float velY = velocityTracker.getYVelocity();
  1615:                        if (velX >= 3500 && velX > Math.abs(velY) && currentFragment.canBeginSlide()) {
  1616:                            startedTrackingX = (int) ev.getX();
  1617:                            prepareForMoving();
  ...
  1626:                    if (startedTracking) {
  1627:                        float x = containerView.getX();
  ...
  1630:                        final boolean backAnimation = (newBackTransitions() ? x < dp(56) / 2 || velX < -1000 : x < containerView.getMeasuredWidth() / 3.0f) && (velX < 3500 || Math.abs(velX) < Math.abs(velY));
  1631:                        animateBackEndAnimation(backAnimation, velX);
  ```
* `allowSwipe()` (`575-578`) only refuses when a layout-level sheet is shown:
  ```java
  575:    @Override
  576:    public boolean allowSwipe() {
  577:        return (sheetFragment == null || sheetFragment.getLastSheet() == null || !sheetFragment.getLastSheet().isShown());
  578:    }
  ```
* `prepareForMoving()` (`1485-1544`) inflates the *previous* fragment into `containerViewBack` (`1492-1529`) and calls `prepareFragmentToSlide` on both.
* `shouldOverrideSlideTransition` is declared at `BaseFragment.java:1150-1152` returning `false` and is consumed once at `ActionBarLayout.java:1748`:
  ```java
  1150:    protected boolean shouldOverrideSlideTransition(boolean topFragment, boolean backAnimation) {
  1151:        return false;
  1152:    }
  ```
  **`grep -rn "shouldOverrideSlideTransition"` finds only the declaration and that one use — there is no override anywhere, so the hook is dead code (always `false`).**

Conditions on tablet: `isLayersLayout`/`isRightLayout` (`789-790`, set at `824-830`) are **not** consulted anywhere in the swipe path; `grep -n "isTablet" ui/ActionBar/ActionBarLayout.java` yields only `3195`, `3198`, `4054`, `4094`, `4100` — none in the touch/back code. So swipe-back is enabled on tablet exactly like on phone, gated only by `fragmentsStack.size() > 1` (1549) plus the fragment's `isSwipeBackEnabled` / `canBeginSlide`. In the right pane that means swipe-back works only once a second fragment is stacked (chat → profile); in the main pane it needs >1 fragment (usually only `MainTabsActivity`, so effectively off).

`isRightLayout` is used only for corner rounding when the pane slides (`1268-1274`) and for insets (`3922-3947`).

Why it matters for the refactor: `startedTracking` is the very first guard in `ActionBarLayout.onBackPressed` (`1865`) — the swipe and the hardware back cannot interleave **through the layout**. See §(e).

### 2.5 Can one `onBackPressed()` on a layout close >1 fragment?

Within `ActionBarLayout.onBackPressed()` itself: **at most one `closeLastFragment` call** (1885), and `closeLastFragment` is guarded against re-entry by `checkTransitionAnimation()` (2911). Two ways more than one page can still disappear per user action:

1. **A fragment that closes itself and returns `true`.** `ActionBarLayout:1883-1886` then calls `closeLastFragment` again on whatever is last now. Concrete instance: `TwoStepVerificationSetupActivity` (§(e)).
2. **`closeLastFragment` interleaved with a running swipe animation** — `checkTransitionAnimation()` checks `transitionAnimationInProgress` only (`1951-1960`), **not** `animationInProgress`/`startedTracking`; so once `ActionBarLayout.onBackPressed`'s own guard at 1865 has been bypassed (which is exactly what the tablet ad-hoc path does), the slide-end handler removes another fragment (`1415-1428`). §(e).

Also note `removeFragmentFromStackInternal(..., true)` (`3173-3174`) and `removeFragmentFromStack` (`3189-3203`) can call `fragment.finishFragment()` — i.e. removal APIs deliberately funnel back into `closeLastFragment`, so any code that both removes a fragment and calls `finishFragment()` closes two.

---

## 3. `ui/ActionBar/BaseFragment.java`

```java
595:    public void finishFragment() {
596:        if (parentDialog != null) {
597:            parentDialog.dismiss();
598:            return;
599:        }
600:        if (inPreviewMode && previewDelegate != null) {
601:            previewDelegate.finishFragment();
602:        } else {
603:            finishFragment(true);
604:        }
605:    }
606:
607:    public void setFinishing(boolean finishing) {
608:        this.finishing = finishing;
609:    }
610:
611:    public boolean finishFragment(boolean animated) {
612:        if (isFinished || parentLayout == null) {
613:            return false;
614:        }
615:        finishing = true;
616:        parentLayout.closeLastFragment(animated);
617:        return true;
618:    }
```

Key semantics: `finishFragment()` **closes whatever is last in the layout**, not necessarily `this`. It never checks `this == parentLayout.getLastFragment()`. Guard is `isFinished` (`612`), which is only set in `onFragmentDestroy()` (`651 isFinished = true;`), i.e. *after* the close completed — so during the close animation a second `finishFragment()` on the same fragment is **not** short-circuited. `finishing` (615) is a different flag and is not consulted at 612.

`setParentLayout` — `457-508` (relevant excerpt):
```java
457:    public void setParentLayout(INavigationLayout layout) {
458:        if (parentLayout != layout) {
459:            parentLayout = layout;
...
492:            if (simpleActionBar != null && parentLayout != null && parentLayout.getView().getContext() != simpleActionBar.getContext()) {
493:                AndroidUtilities.removeFromParent(simpleActionBar);
494:                simpleActionBar = null;
495:            }
496:            if (parentLayout != null && useSimpleActionBar()) {
497:                if (simpleActionBar == null) {
498:                    simpleActionBar = createSimpleActionBar(parentLayout.getView().getContext());
499:                    simpleActionBar.setMenuOverlayProvider(this::getSimpleMenuOverlay);
500:                }
501:            } else if (parentLayout != null && actionBar == null) {
502:                actionBar = createActionBar(parentLayout.getView().getContext());
...
514:    protected boolean useSimpleActionBar() {
515:        return false;
516:    }
```
⇒ a fragment is either `actionBar`-based or `simpleActionBar`-based; the two back paths in `ActionBarLayout.onBackPressed` (1871 vs 1876) target these two mutually exclusive headers.

Default back implementation — `747-764`:
```java
747:    public boolean onBackPressed(boolean invoked) {
748:        if (hasShownSheet()) {
749:            if (invoked) closeSheet();
750:            return false;
751:        }
752:        return true;
753:    }
754:
755:    public boolean closeSheet() {
756:        if (sheetsStack != null) {
757:            for (int i = sheetsStack.size() - 1; i >= 0; --i) {
758:                if (sheetsStack.get(i).isShown()) {
759:                    return sheetsStack.get(i).onAttachedBackPressed();
760:                }
761:            }
762:        }
763:        return false;
764:    }
```
Contract: return `false` = "consumed" (nothing else may act), `true` = "not consumed, close me". `invoked=false` must be a side-effect-free dry run (used by `ActionBarLayout.onBackStarted:1690`). Note `closeSheet()`'s result is discarded at 749 — a shown sheet that refuses to close (`onAttachedBackPressed` returns `false`) still swallows the back press.

Other relevant members: `hasShownSheet()` `183-191`; `hasSheets(BaseFragment)` `203-209` (also inspects the *parent layout's* `sheetFragment`); `isSwipeBackEnabled` `356-358` (default `true`); `canBeginSlide`/`onBeginSlide` `868-884`; `closeLastFragment()` `1401-1403` (default `false`); `onFragmentClosed()` `1421-1423`; `attachSheets`/`detachSheets` `1425-1440`; `bottomSheet[0]` back override `1224-1230` (§1.5); `showAsSheet` `1158+`.

**Two closes per user action** require either (a) a fragment whose `onBackPressed(true)` closes something and returns `true`, or (b) a handler that bypasses the layout's own guard (tablet path). Both exist — see §(e).

---

## 4. `onPause` / `onFragmentPause`, and `ChatActivity.finishFragment()`

* There is **no** `onFragmentPause` in this tree: `grep -rn "onFragmentPause"` returns nothing. Back behaviour can only be altered via `onBackPressed(boolean)`, `closeLastFragment()`, `finishFragment*`, `canBeginSlide`, `isSwipeBackEnabled`, `onBeginSlide`.
* `BaseFragment.onPause()` (`698-718`) dismisses `visibleDialog` (`707-710`), pauses story viewers, closes `simpleMenuOverlay`. It is called *after* the fragment has already been decided to close (`closeLastFragmentInternalRemoveOld:2770`), so it cannot veto a back.
* `ChatActivity.onPause()` (`33128-…`) saves drafts, marks read, pauses the recorder; it calls `super.onPause()` first and does not touch the back path.
* `ChatActivity.finishFragment()` — the only override:
  `ui/ChatActivity.java:33079-33086`
  ```java
  33079:    @Override
  33080:    public void finishFragment() {
  33081:        super.finishFragment();
  33082:        if (scrimPopupWindow != null) {
  33083:            scrimPopupWindow.setPauseNotifications(false);
  33084:            closeMenu();
  33085:        }
  33086:    }
  ```
  i.e. it closes the chat and then closes the popup — no veto, no redirect.
* `ChatActivity.onBackPressed(boolean)` — `38417-38501` (see §5 for the internal order); it ends with `return true` (38500), so it *asks* the caller to close the fragment; it never calls `finishFragment()` on the `true` path (`38482` and `38509` do so from a dialog callback / alert, i.e. asynchronously and after returning `false`).

---

## 5. `ui/ChatActivityContainer.java` and the hashtag/search pager

`ui/ChatActivityContainer.java:29-53` — it is a **`FrameLayout`, not a fragment**:
```java
29: public class ChatActivityContainer extends FrameLayout {
30:
31:     public final ChatActivity chatActivity;
32:     private final INavigationLayout parentLayout;
33:     private View fragmentView;
34:
35:     public ChatActivityContainer(
36:         Context context,
37:         INavigationLayout parentLayout,
38:         Bundle args
39:     ) {
40:         super(context);
41:         this.parentLayout = parentLayout;
42:
43:         chatActivity = new ChatActivity(args) {
...
52:         chatActivity.isInsideContainer = true;
53:     }
```
`initChatActivity()` (`71-95`) calls `chatActivity.onFragmentCreate()`, then `chatActivity.setParentLayout(parentLayout)` (77) — so the inner ChatActivity borrows the **outer** fragment's `parentLayout` (it can call `addFragmentToStack`) but is **never added to `fragmentsStack`**.

Mounting — `ui/ChatActivity.java:8599-8669`:
```java
8599:            searchViewPager.setAdapter(new ViewPagerFixed.Adapter() {
8601:                public int getItemCount() {
8602:                    return 3;
8603:                }
...
8619:                        ChatActivityContainer container = new ChatActivityContainer(context, getParentLayout(), args) {
...
8669:            contentView.addView(searchViewPager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP));
```
plus the tab strip:
```java
8671:            hashtagSearchTabs = new ChatSearchTabs(context) {
...
8690:            hashtagSearchTabs.setTabs(searchViewPager.createTabsView(true, ViewPagerFixed.SELECTOR_TYPE_BUBBLE_STYLE));
8696:            contentView.addView(hashtagSearchTabs, LayoutHelper.createFrameMarginPx(LayoutHelper.MATCH_PARENT, 50, Gravity.FILL_HORIZONTAL | Gravity.TOP, 0, -dp(5), 0, 0));
```
Fields: `694 public ChatSearchTabs hashtagSearchTabs;`, `699 private ViewPagerFixed searchViewPager;`.

So: **the search container is a child view with its own page stack (`ViewPagerFixed` + `ChatSearchTabs`); it is not part of any layout's fragment stack and therefore is never traversed by `ActionBarLayout.onBackPressed` or by the tablet ad-hoc path.**

Back inside it is consumed through the *search collapse* listener, i.e. through `actionBar.closeSearchField()`:

`ui/ChatActivity.java:42474-42494`
```java
42474:    private class SearchItemListener extends ActionBarMenuItem.ActionBarMenuItemSearchListener {
42476:        @Override
42477:        public boolean canCollapseSearch() {
42478:            if (animatorSearchHashtagHistoryVisibility.getValue()) {
42479:                return true;
42480:            }
42481:            if (searchingHashtag != null && hashtagSearchTabs != null) {
42482:                ChatActivity currentChatActivity = ChatActivity.this;
42483:                View currentPage = searchViewPager.getCurrentView();
42484:                if (currentPage instanceof ChatActivityContainer) {
42485:                    currentChatActivity = ((ChatActivityContainer) currentPage).chatActivity;
42486:                }
42487:
42488:                if (!currentChatActivity.isMessagesSearchListVisible()) {
42489:                    currentChatActivity.showMessagesSearchListView(true);
42490:                    return false;
42491:                } else if (hashtagSearchTabs.tabs.getCurrentPosition() != defaultSearchPage) {
42492:                    hashtagSearchTabs.tabs.scrollToTab(defaultSearchPage, defaultSearchPage);
42493:                    return false;
42494:                }
```
Chain on a back press with search open (in the chat fragment):
`ChatActivity.onBackPressed` `38484-38486` → `actionBar.closeSearchField()` → `ActionBar`/`ActionBarMenu.closeSearchField` (`ActionBar/ActionBarMenu.java:443-458`, which consults `listener.canCollapseSearch()` at 450) → the listener above pulls the pager back to the default page (42492) or re-opens the per-page search list (42489), each time returning `false` so the field stays open and back counts as consumed.

Also relevant inside `ChatActivity.onBackPressed`: `38449-38451` (`isMessagesSearchListVisible()` → `showMessagesSearchListView(false)`), and the tab reset on collapse `42583-42590`:
```java
42583:            if (searchViewPager != null) {
42584:                if (hashtagSearchTabs.tabs.getCurrentPosition() != 0) {
42585:                    hashtagSearchTabs.tabs.scrollToTab(0, 0);
42586:                    requestClearSearchPages = true;
42587:                } else {
42588:                    searchViewPager.clearViews();
42589:                }
42590:            }
```

Answers to the question as asked:

* **Does back inside it consume the event?** Yes — but only via the search field being open and `canCollapseSearch()` returning `false`; there is no per-page back stack. With the search field closed, the pager pages are not reachable by back at all.
* **Is it part of the tablet back path?** No: the container lives inside `ChatActivity.contentView`; the only thing on the tablet path is that `ChatActivity.onBackPressed(true)` is invoked directly by the ad-hoc tablet code (`9457`) *or* through `ActionBarLayout` (`1883`). Either way the pager logic above is reached because it is inside `ChatActivity.onBackPressed`, not because of any container-stack traversal.

`ChatActivity.onBackPressed` full order for reference — `ui/ChatActivity.java:38417-38501`:
```
38419 actionBar.getOpenMenuOverlay()      -> closeMenuOverlay(true);      false
38423 topicsPanel.isOpen()               -> topicsPanel.close();          false
38427 Bulletin LottieLayoutWithReactions -> hideReactionsDialog+hide      false
38435 secretVoicePlayer shown            -> dismiss                      false
38438 hasShownSheet()                    -> closeSheet()                 false
38441 selectionReactionsOverlay          -> its onBackPressed            false
38443 ContentPreviewViewer visible       -> closeWithMenu                false
38446 forwardingPreviewView showing      -> dismiss(true)                false
38449 isMessagesSearchListVisible()      -> showMessagesSearchListView(false) false
38452 scrimPopupWindow != null           -> closeMenu()                  false
38455 checkRecordLocked(invoked,false)   -> (dialog)                     false
38457 hasTextSelection()                 -> clearTextSelection()         false
38460 actionBar.isActionModeShowed()     -> clearSelectionMode()         false
38463 enterView.canHidePopupByBackButton -> hidePopup(true)              false
38468 botCommandsMenuIsShowing()         -> hideBotCommands()            false
38471 closeCreationLinkDialog(invoked)   ->                           false
38473 MODE_EDIT_BUSINESS_LINK && closeRenameAlert(invoked)               false
38475 monoForum topic != 0               -> topicsTabs.selectTopic(0,…)  false
38478 MODE_QUICK_REPLIES empty           -> showQuickRepliesRemoveAlert  false
38481 businessLinkHasChanges             -> showBusinessLinksDiscardAlert(this::finishFragment) false
38484 actionBar.isSearchFieldVisible()   -> actionBar.closeSearchField() false
38487 isInPollAddOptionMode()            -> pollAddOptionModeClose()     false
38491 if (invoked) { backToPreviousFragment -> parentLayout.addFragmentToStack(...); instantCameraView.cancel(false); }
38500 return true;
```

---

# (a) Code quotes

All quotes are inline above with `file:line`. The load-bearing ones for the refactor question:

| what | cite |
|---|---|
| tablet ad-hoc right-pane close | `ui/LaunchActivity.java:9455-9459` |
| global back chain | `ui/LaunchActivity.java:9469-9503` |
| predictive callback, tablet early-outs | `ui/LaunchActivity.java:842-844, 863, 890, 905, 921-924` |
| layouts created | `ui/LaunchActivity.java:533, 1134-1138, 1173-1184` |
| right-pane hosting rules | `ui/LaunchActivity.java:9691-9749` |
| `getActionBarLayout()` hides sheets | `ui/LaunchActivity.java:7561-7567` |
| `needCloseLastFragment` | `ui/LaunchActivity.java:9848-9868` |
| `ActionBarLayout.onBackPressed` | `ui/ActionBar/ActionBarLayout.java:1864-1888` |
| predictive API | `ui/ActionBar/ActionBarLayout.java:1667-1737` |
| `closeLastFragment` | `ui/ActionBar/ActionBarLayout.java:2906-2913`, removal `2768-2781` / `3169-3186` |
| swipe tracking | `ui/ActionBar/ActionBarLayout.java:1485-1488, 1548-1631, 1415-1483` |
| `finishFragment` | `ui/ActionBar/BaseFragment.java:595-618` |
| default `onBackPressed` | `ui/ActionBar/BaseFragment.java:747-764` |
| `ChatActivity.finishFragment` | `ui/ChatActivity.java:33080-33086` |
| `ChatActivity.onBackPressed` | `ui/ChatActivity.java:38417-38501` |
| container is a view | `ui/ChatActivityContainer.java:29-53, 71-95` |

---

# (b) One back gesture on tablet — three ordered cases

Assume API ≥ 33 (so `onBackInvoked` is used) and that the global chain (`9469`) returns `true`.

### Case 1 — layers visible (`layersActionBarLayout.getView().getVisibility() == VISIBLE`)

1. `onBackInvoked` → unlock → `AndroidUtilities.isTablet()` (843) → `onBackPressed()`.
2. `onBackPressed()` → `onBackPressed(true)`: debug overlay → drawer → passcode → bottom-sheet-tabs overlay → rename-tag alert → ContentPreview → SecretMedia → PhotoViewer → ArticleViewer singleton. Any hit ⇒ stop (case over).
3. `9452` true ⇒ `layersActionBarLayout.onBackPressed()`.
4. `ActionBarLayout.onBackPressed` `1865`: preview/swipe/transition/empty ⇒ stop.
5. `1868` GroupCallPip.
6. `1871` regular-ActionBar search field ⇒ `closeSearchField()`; stop.
7. `1876` `simpleActionBar.onBackPressed()` (menu overlay or simple search field) ⇒ stop.
8. `1879` layout-level `sheetFragment` (article viewer / bot web view sheet) ⇒ closes that sheet; stop.
9. `1883` top layer fragment's `onBackPressed(true)`; if consumed (`false`) ⇒ stop.
10. `1885` `closeLastFragment(true)` on the layers layout:
    * `2908` `fragment.closeLastFragment()` (DialogsActivity preview redirect) ⇒ stop;
    * `2911` `needCloseLastFragment`: if the main stack is **empty** and this is the only layer ⇒ `super.onBackPressed()` ⇒ **activity finishes**, no fragment closes (`9857-9859`);
    * else animate (alpha, 3053-3089) or remove (3091) and `setVisibility(GONE)` (3092/3060).
11. Nothing pops the right pane or the main pane in this gesture.

Differs from phone: on phone the same user action ends in `actionBarLayout.onBackInvoked()` → `ActionBarLayout.onBackPressed()` **plus** predictive animation; on tablet there is no predictive animation and the `layers` pane is considered *before* the right/main panes.

### Case 2 — right layout non-empty (`9455` true)

1. `onBackInvoked` → `onBackPressed()` (843/922).
2. `onBackPressed(boolean)` global chain (same as Case 1 step 2).
3. `9452` false ⇒ `9455` true ⇒ `BaseFragment lastFragment = rightActionBarLayout.getFragmentStack().get(size-1)` (9456).
4. `9457` `lastFragment.onBackPressed(true)`:
   * returns **`false`** ⇒ gesture consumed (e.g. chat closed its search field, its action mode, a sheet, the topics panel, the hashtag-search page…) — **no fragment is closed**; `closeLastFragment` is never reached, so `needCloseLastFragment`, `GroupCallPip`, `isSearchFieldVisible` of a plain `ActionBar`, `simpleActionBar` and the layout-level `sheetFragment` are all skipped.
   * returns **`true`** ⇒ step 5.
5. `9458` `lastFragment.finishFragment()` → `BaseFragment.finishFragment(true)` (603) → `parentLayout.closeLastFragment(true)` (616) → `ActionBarLayout.closeLastFragment(true,false)` (2906): fragment veto (2908) → `needCloseLastFragment` (9853 sets `backgroundTablet` visible, returns `true`) → `checkTransitionAnimation()` → removal (`2768`/`3091`). With a single fragment the layout gets `GONE` (`3092`) and `backgroundTablet` becomes visible;
6. Back again now falls to `9461` (right stack empty) ⇒ Case 3.

Nothing in the main pane or the layers pane is touched.

### Case 3 — otherwise (`actionBarLayout.onBackPressed()`)

1-2 as above.
3. `9461` `actionBarLayout.onBackPressed()` → full `ActionBarLayout.onBackPressed` (§2.2) including `GroupCallPip`, search field, `simpleActionBar`, layout-level `sheetFragment`, then the main fragment's `onBackPressed(true)` and `closeLastFragment(true)`.
4. In `closeLastFragment` → `needCloseLastFragment` `9850-9852`: main stack size ≤ 1 and not switching account ⇒ `super.onBackPressed()` ⇒ **activity finishes** (app goes to background); with >1 fragment the top one is closed.

---

# (c) `rightActionBarLayout` ad-hoc path vs. `ActionBarLayout.onBackPressed()` — every semantic difference

| # | aspect | ad-hoc path (`LaunchActivity:9456-9459`) | `ActionBarLayout.onBackPressed()` (`1864-1888`) |
|---|---|---|---|
| 1 | guard `transitionAnimationPreviewMode` | **absent** | 1865 |
| 2 | guard `startedTracking` (swipe in progress) | **absent** | 1865 |
| 3 | guard `checkTransitionAnimation()` | **absent at the layout level** (only later, inside `closeLastFragment` 2911 — and that one does not know about `startedTracking`/`animationInProgress`) | 1865 |
| 4 | guard `fragmentsStack.isEmpty()` | partial: checked before reading `size()-1` (9455), not after `onBackPressed` | 1865 + redundant check 1884 |
| 5 | `GroupCallPip.onBackPressed()` | **skipped** | 1868 |
| 6 | regular `ActionBar.isSearchFieldVisible` → `closeSearchField()` | **skipped** (relies on the fragment doing it itself) | 1871-1874 |
| 7 | `storyViewerAttached()` suppression of 6/7 | **skipped** (because both are skipped) | 1871, 1876 |
| 8 | `simpleActionBar.onBackPressed()` (header menu overlay / simple search field) | **skipped** | 1876-1878 |
| 9 | layout-level `sheetFragment.onBackPressed(true)` (attached sheets: article viewer sheet, bot web view sheet on `actionBarLayout.sheetFragment`) | **skipped** | 1879-1881 |
| 10 | which fragment is asked | `rightActionBarLayout`'s last fragment | `this` layout's last fragment |
| 11 | who performs the close | `lastFragment.finishFragment()` → `finishFragment(true)` → `parentLayout.closeLastFragment(true)`; also dismisses `parentDialog` first (596-599) and honours `previewDelegate` (600-602) | `closeLastFragment(true)` directly on the layout |
| 12 | `previewDelegate` / `parentDialog` handling | yes, via `finishFragment()` (`BaseFragment:596-602`) | no |
| 13 | `needCloseLastFragment` | reached indirectly (through `closeLastFragment`) | reached directly |
| 14 | value of `animated` | `true` (hard-coded by `finishFragment()`) | `true` (1885) — same in practice |
| 15 | empty-stack main-layout fallback | **not reachable** — if the right stack is empty the code falls through to 9461 | n/a |
| 16 | activity-finish fallback | never from the right pane (`needCloseLastFragment` 9853 returns `true`) | yes for main (9850) and layers (9857) |
| 17 | re-entrancy protection for a second call in the same gesture | none — `finishFragment()` has no `startedTracking` guard and `isFinished` is only set in `onFragmentDestroy` (651) | guard 1865 + 2911 |
| 18 | predictive-back integration | none (the right layout's `onBackStarted/Progress/Cancelled/Invoked` are unreachable: `actionBarLayout` is the only layout wired at 849/866/892/907/928) | `onBackInvoked` (1726-1733) either finishes the predictive animation or delegates to `onBackPressed` |
| 19 | `GroupCallPip` (PiP call UI) | stays open while the chat closes | closes first |

Point 12 deserves emphasis: the ad-hoc path is **not** "`ActionBarLayout.onBackPressed()` on the right layout minus some guards" — it is a *different* close invocation (`BaseFragment.finishFragment()`), which has two behaviours `closeLastFragment(true)` does not have: dismissing `parentDialog` (596-599) and routing through `previewDelegate` (600-602).

---

# (d) Concrete risks of "unifying" the paths, and affected fragments

### d.1 Risks, in decreasing confidence

1. **Losing the `parentDialog`/`previewDelegate` behaviour** if the unification replaces `finishFragment()` with `closeLastFragment(true)` (diff row 12): a fragment opened as a dialog (`parentDialog != null`) or as a preview (`previewDelegate != null`) would then be *closed off the stack* instead of being dismissed/finished through its preview delegate. Affected: anything using `previewDelegate` (`BaseFragment:600-602`; `PreviewDelegate` interface at `1484`), and `showAsSheet`'s sheet-hosted fragments (`1158+`).
2. **Turning "nothing happened" into "the activity closes"**: if the tablet branch is replaced by `rightActionBarLayout.onBackPressed()` alone, the `9461` fallback disappears — but the inverse unification (always calling `actionBarLayout.onBackPressed()` when the right pane is empty) already exists; the risk is unifying in the other direction: routing all tablet backs through the *main* layout would let `needCloseLastFragment` 9850 call `super.onBackPressed()` and finish the activity while a chat is still visible in the right pane (verified reachable: right pane non-empty + right view not `VISIBLE` — e.g. during/after `checkLayout()`'s `tabletFullSize` switch sets the pane `GONE` at 2117 while `rightFragmentsStack` may still be non-empty at 2098-2112 in the same frame *[ordering inferred from source, not observed]*).
3. **Behaviour changes for fragments that rely on the layout to close their own header.** Removing the ad-hoc path and calling `rightActionBarLayout.onBackPressed()` *adds* behaviours 6-9 (rows 6, 8, 9 in the table) — mostly fixes, but they are **observable changes** for:
   * fragments with a plain `ActionBar` whose search field the fragment itself does not close (row 6 now closes it first);
   * fragments using `useSimpleActionBar()` that never implemented `onBackPressed(boolean)` — they currently cannot close their header menu/search on tablet at all, and would start doing so. Concretely (grep: `useSimpleActionBar()` override **without** any `onBackPressed(boolean)`):
     `ui/RoundVideoSettingsActivity.java`, `ui/WallpapersListActivity.java`, `ui/LiteModeSettingsActivity.java`, `ui/ArchiveSettingsActivity.java`, `ui/DataSettingsActivity.java`, `ui/StatisticActivity.java`, `ui/NotificationsCustomSettingsActivity.java`, `ui/ContactAddActivity.java`, `ui/SaveToGallerySettingsActivity.java`, `ui/PasskeysActivity.java`, `ui/UserInfoActivity.java`, `ui/NotificationsSoundActivity.java`, `ui/InviteContactsActivity.java`, `ui/DataUsage2Activity.java`, `ui/web/WebBrowserSettings.java`, `ui/CacheChatsExceptionsFragment.java`, `ui/PrivacyUsersActivity.java`, `ui/ChatEditTypeActivity.java`, `ui/GroupInviteActivity.java`, `ui/AutoDeleteMessagesActivity.java`, `ui/EnableTopicsActivity.java`, `ui/TopicsNotifySettingsFragments.java`, `ui/ProfileNotificationsActivity.java`, `ui/ChatReactionsEditActivity.java`, `ui/ProxySettingsActivity.java`, `ui/HeaderSettingsActivity.java`, `ui/AudioRecorderSettingsActivity.java`, `ui/LogoutActivity.java`, `ui/ChatLinkActivity.java`, `ui/PrivacySettingsActivity.java`, `ui/LinkEditActivity.java`, `ui/ArchivedStickersActivity.java`, `ui/TON/TONIntroActivity.java`, `ui/Components/HashtagActivity.java`, `ui/DebugSettingsActivity.java`, `ui/DataAutoDownloadActivity.java`, `ui/Stars/StarsIntroActivity.java`, `ui/Stars/BotStarsActivity.java`, `ui/FiltersSetupActivity.java`, `ui/ThemeActivity.java`, `ui/SessionsActivity.java`, `ui/PasscodeActivity.java`, `ui/LanguageSelectActivity.java`, `ui/ChangeUsernameActivity.java`, `ui/ChooseChannelFragment.java`, `ui/NotificationsSettingsActivity.java`, `ui/ManageLinksActivity.java`, `ui/Business/OpeningHoursActivity.java`, `ui/Business/QuickRepliesActivity.java`, `ui/Business/OpeningHoursDayActivity.java`, `ui/Business/TimezoneSelector.java`, `ui/RestrictedLanguagesSelectActivity.java`, `ui/GroupStickersActivity.java`, `ui/FeaturedStickersActivity.java`, `ui/ReactionsDoubleTapManageActivity.java`.
     Reachability of these in the **right pane**: normally they live in the `layersActionBarLayout` (needPresentFragment 9750-9768), so they already go through `ActionBarLayout.onBackPressed`. The one concrete path that puts such a fragment in the right pane is `SettingsActivity.presentSettingFragment` → `forceRightLayout()` (`ui/SettingsActivity.java:923`, matched by `LaunchActivity.java:9692`).
   * `NotificationsCustomSettingsActivity` is a good specimen: it registers a search item in its `simpleActionBar` (`ui/NotificationsCustomSettingsActivity.java:379-419`) and has **no** `onBackPressed(boolean)`.
4. **Behaviour changes for layout-level sheets on tablet** (row 9): today, a sheet hosted on a layout's `sheetFragment` is closed by `ActionBarLayout.onBackPressed` only. The right pane's `sheetFragment` is created lazily and no current code creates it for `rightActionBarLayout` (`getSheetFragment(true)` callers: `ui/ActionBar/BottomSheetTabs.java:110` — main layout only; `ui/web/BotWebViewContainer.java:4680` — resolves the fragment through `LaunchActivity.getSafeLastFragment()`, which returns the **main** layout's last fragment, `ui/LaunchActivity.java:9924-9935`). So unifying *adds* the sheet branch to the right pane; it will not regress anything, but it also will not fix the real, pre-existing ordering hole: a sheet on `actionBarLayout.sheetFragment` is not consulted while the right pane is non-empty (the right pane is evaluated first at 9455).
5. **`GroupCallPip` (row 5)**: unifying would make back close the call PiP before the chat. PiP is global; this is a UX change that must be intended.
6. **Predictive back**: unifying by routing tablet through `actionBarLayout.onBackInvoked()` (as phone does) would make the *right* layout animate the wrong layout — `onBackInvoked()` acts on `this` (`1726-1733`), while the visible pane is `rightActionBarLayout`. Any unification of the predictive path must pass the *target* layout through.
7. **`isTablet()` branches inside fragments**: `BaseFragment.java:659` (`hasForceLightStatusBar() && !AndroidUtilities.isTablet()` inside `onFragmentDestroy`, which is on the close path), `ChatActivity.java:38345`, `ProfileActivity.java:10381`, `BaseFragment.java:520` (`parentLayout.isLayersLayout()`), `LaunchActivity.java:659/669/9609` (volume keys), `ActionBarLayout.java:3195/3198/4054/4094/4100`. A unification that changes *which* layout performs the close changes `getParentLayout()`-derived behaviour only if the layout identity changes — it does not, as long as the close is still executed by `rightActionBarLayout`.
8. **`ActionBarLayout.closeLastFragment` on the right pane already differs from the main pane** (`needCloseLastFragment` 9853-9856 sets `backgroundTablet` visible); unifying the *entry* points must keep that delegate branch intact, otherwise the empty right pane shows a black/stale area. `checkLayout()` re-establishes it (2094-2095) but only on layout changes.

### d.2 Fragments whose `onBackPressed` returns `true`/`false` (grep, 58 overrides)

`onBackPressed(boolean)` overrides found with `grep -rn "boolean onBackPressed(boolean"`:

`ui/PhotoAlbumPickerActivity.java:689`, `ui/iv/RichEditor.java:2525`, `ui/ChatEditActivity.java:528`, `ui/PrivacyControlActivity.java:1723`, `ui/DocumentSelectActivity.java:1180`, `ui/LoginActivity.java:1172` (+ inner `SlideView`s at `5398, 6005, 6250, 7692, 8070, 8560, 10176`), `ui/FilterCreateActivity.java:948`, `ui/FilterChatlistActivity.java:307`, `ui/TopicsFragment.java:4078`, `ui/GroupCreateFinalActivity.java:252`, `ui/SettingsActivity.java:1298`, `ui/MainTabsActivity.java:748`, `ui/ChatActivity.java:38417`, `ui/PeerColorActivity.java:1846`, `ui/PostSuggestionsEditActivity.java:292`, `ui/ViewPagerActivity.java:234`, `ui/StickersActivity.java:336`, `ui/PhotoPickerActivity.java:1628`, `ui/ChatRightsEditActivity.java:1787`, `ui/TwoStepVerificationActivity.java:1267`, `ui/EditWidgetActivity.java:1062`, `ui/PollCreateActivity.java:998`, `ui/PremiumPreviewFragment.java:2254`, `ui/ChannelColorActivity.java:599`, `ui/PaymentFormActivity.java:4822`, `ui/PassportActivity.java:6693` (+ `7876`), `ui/ContactsActivity.java:1061`, `ui/MemberRequestsActivity.java:81`, `ui/ChannelCreateActivity.java:270`, `ui/TwoStepVerificationSetupActivity.java:2116`, `ui/CalendarActivity.java:1762`, `ui/ChatUsersActivity.java:2122`, `ui/Components/MediaActivity.java:635`, `ui/Components/Reactions/ChatCustomReactionsEditActivity.java:821`, `ui/Components/Reactions/ChatSelectionReactionMenuOverlay.java:389`, `ui/Components/SlideView.java:29`, `ui/Components/AvatarConstructorFragment.java:1603`, `ui/Components/FloatingDebug/FloatingDebugView.java:321`, `ui/LocationActivity.java:2821`, `ui/ThemePreviewActivity.java:3527`, `ui/Wallet/WalletActivity.java:1040`, `ui/Wallet/WalletSendSelectSender.java:568`, `ui/ProxyListActivity.java:718`, `ui/CallLogActivity.java:1447`, `ui/DialogsActivity.java:7507`, `ui/ProfileActivity.java:10346`, `ui/GroupCreateActivity.java:955`, `ui/CacheControlActivity.java:3242`, `ui/Business/BusinessLinksActivity.java:291`, `ui/Business/ChatbotsActivity.java:805`, `ui/Business/GreetMessagesActivity.java:213`, `ui/Business/AwayMessagesActivity.java:245`, `ui/Business/BusinessIntroActivity.java:489`, `ui/Business/LocationActivity.java:426`, `ui/QrShare/QrShareActivity.java:1137`, `ui/Delegates/MemberRequestsDelegate.java:273`.

Fragments the tablet right pane can actually contain today: `ChatActivity` (`LaunchActivity.java:9692`) and anything presented while `layout == rightActionBarLayout` (9693) — i.e. the whole chat→profile→media→… chain — plus `forceRightLayout()` presents, of which the codebase has exactly one (`SettingsActivity.java:923`).

Sheets (things that answer back through `AttachedSheet.onAttachedBackPressed`, i.e. items 8/9 of the table):

* `ui/ActionBar/BottomSheet.java:2609-2622`
* `ui/ArticleViewer.java:16159-16188` (`Sheet.onAttachedBackPressed`, returns `true` in every branch)
* `ui/Stories/StoryViewer.java:2711`, and `onAttachedBackPressed()` self-calls at `1119`, `1794`
* `ui/bots/BotWebViewAttachedSheet.java:1534`
* Fragment-level sheets attach on the fragment (`BaseFragment.addSheet`, `1582-1589`); the **layout-level** host is `ActionBarLayout.getSheetFragment` (`605-631`), whose content is only reachable for back via `ActionBarLayout.onBackPressed:1879` and `BaseFragment.hasShownSheet`.

---

# (e) Evidence of double-close (one gesture → 2+ pages)

**(e.1) A fragment that closes itself and then returns `true`.**

`ui/TwoStepVerificationSetupActivity.java:2115-2123`
```java
2115:    @Override
2116:    public boolean onBackPressed(boolean invoked) {
2117:        if (otherwiseReloginDays >= 0 && parentLayout.getFragmentStack().size() == 1) {
2118:            if (invoked) showSetForcePasswordAlert();
2119:            return false;
2120:        }
2121:        if (invoked) finishFragment(); // ???
2122:        return true;
2123:    }
```
Both callers then close *again*:
* `ActionBarLayout.onBackPressed:1883-1886` → `closeLastFragment(true)`;
* `LaunchActivity.onBackPressed:9457-9458` → `finishFragment()`.

Whether the second close actually removes another fragment depends on `checkTransitionAnimation()` (2911): if the first `finishFragment()` went through the animated path, `transitionAnimationInProgress` is already `true` at 2989 and the second call is swallowed; if the layout had no previous fragment (`previousFragment == null`, 3052) or animations are off (`2918` reads `view_animations`, default `true`), the first close is **synchronous** and the second one removes the now-top fragment. In the ad-hoc tablet path the first close is `finishFragment()` → `closeLastFragment(true)` on the *same* layout, so the same reasoning applies. **This is a genuine, one-gesture-two-pages mechanism, independent of the tablet split** (the `// ???` comment in-tree suggests it is known to be suspicious).

**(e.2) The tablet ad-hoc path bypasses `startedTracking`, so a back press during a swipe-back animation closes two pages.**

* Swipe start sets `startedTracking = true` in `prepareForMoving()` (`ActionBarLayout.java:1485-1487`) and it stays `true` until `onSlideAnimationEnd` (`1474`), i.e. across the whole release animation (`animateBackEndAnimation` sets `animationInProgress = true` at `1858-1859`, end listener at `1837-1855`).
* `ActionBarLayout.onBackPressed` refuses to act during that window: `1865 … || startedTracking || …`.
* `LaunchActivity.onBackPressed()` on tablet never reaches that guard for the right pane: it calls `lastFragment.onBackPressed(true)` + `finishFragment()` (9457-9459). `finishFragment`→`closeLastFragment` only checks `checkTransitionAnimation()` (2911) = `transitionAnimationInProgress` (`1951-1960`) — false during a *slide* — so it proceeds: it starts/starts-immediately a close of the current top fragment.
* When the slide finishes, `onSlideAnimationEnd(backAnimation=false)` runs `1421-1428`:
  ```java
  1421:            BaseFragment lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
  1422:            lastFragment.prepareFragmentToSlide(true, false);
  1423:            lastFragment.onPause();
  1424:            lastFragment.onFragmentDestroy();
  1425:            lastFragment.setParentLayout(null);
  1426:
  1427:            fragmentsStack.remove(fragmentsStack.size() - 1);
  1428:            onFragmentStackChanged("onSlideAnimationEnd");
  ```
  and removes **another** fragment (whatever is on top at that moment).
  ⇒ two fragments leave the stack for one user action. On phone the same press is swallowed by 1865.
  *[Ordering inferred from the guards as written; not reproduced on a device. Both guards (`startedTracking` in the layout, its absence in LaunchActivity) are verifiable facts.]*

**(e.3) Two closes inside one *layout* call, without the tablet path.**

`ActionBarLayout.onBackPressed:1879-1886` calls `sheetFragment.onBackPressed(true)` and then, if that returns `true`, `lastFragment.onBackPressed(true)` and `closeLastFragment`. `BaseFragment.onBackPressed` returns `false` whenever `hasShownSheet()` is true (748-751) so the sheet path normally stops there — **unless** the sheet belongs to the layout (`sheetFragment`) and the *fragment* has its own sheets: both checks are independent (`1879` on the layout's sheet host, `1883` on the fragment). Not a double-close per se, but two independent sheet hosts are consulted in one call.

**(e.4) `finishFragment()` is not idempotent during the close.** `BaseFragment.finishFragment(boolean)` only checks `isFinished` (612), which is set in `onFragmentDestroy` (651) — i.e. *after* the animation completes (`3013`) or during the immediate removal (`3047`/`3091`). Any second `finishFragment()`/`removeSelfFromStack()` in the same frame therefore reaches `closeLastFragment` again. Since `closeLastFragment` closes `getLastFragment()` (2907), not `this`, the second call closes a *different* fragment. This is the structural reason (e.1) and (e.2) are possible at all.

**(e.5) Not a double-close, but adjacent**: `removeFragmentFromStackInternal(fragment, true)` (`3173-3175`) and `removeFragmentFromStack` (`3196`, `3201`) funnel into `finishFragment()`; `ChatActivity` calls `removeSelfFromStack()` in 14 places (`grep -c` = 14; lines `19819, 24652, 24662, 25004, 26860, 29794, 29799, 29804, 29812, 30223, 30489, 38316, 38346, 38362`) and has an explicit tablet-dependent branch at `38345-38347` (`if (chatActivity.getDialogId() == getDialogId() && !AndroidUtilities.isTablet()) removeSelfFromStack();`) — tablet code intentionally avoids the extra removal here, which is another hint that stacked removals have bitten this codebase before.
