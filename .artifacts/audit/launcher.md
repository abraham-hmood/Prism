# Prism Launcher — Shell & Customization Audit

**Scope:** `LauncherActivity`, pager/page picker/drawer/desktop grid/folders/delete zone/Kinetic Halo, the
plugin page system, theming/typography/icon packs, onboarding, lock screen, widgets, search host, app sync,
and `AndroidManifest.xml` wiring.
**Mode:** read-only static audit. Nothing was edited, built, installed or run.
**Method:** full reads of every file named in the brief plus the layouts, `SlotPreferences`,
`DesktopShortcutStore`, `LockStore`, `SettingsActivity` sections and the sample projects. Line numbers are
from the files as they exist in this tree.
**Honesty note:** "would work at runtime" is only asserted where the code path is unbroken from the entry
point to the effect. Anything that depends on the platform (cross-app code loading, background activity
launch, foreground keys, AppCompat inflation) is marked **unverified** with the reason.

Environment caveat: the shell tool failed in this session (`SetNamedSecurityInfoW failed (Win32 5):
grantWrite(...)`), so no Gradle build, `aapt2`, lint or APK inspection was possible. Read/grep tools worked.
That means *nothing here is confirmed to compile*, and two findings that depend on resource compilation
(`android:showOnLockScreen`, missing `<queries>`) are explicitly flagged.

---

## 1. Three-pane launcher (the page pager)

**What it does.** `LauncherActivity` hosts one `ViewPager2` (`desktopPager`) whose pages are produced by
`MainDesktopPagerAdapter` one-per-slot. A slot is a `SlotAssignment` value read from
`SlotPreferences.getAssignments()`, so "the three panes" are no longer fixed left/centre/right: any slot can
hold any page. Fresh installs get four slots — Browser, DesktopGrid, AppDrawer, Notifications
(`core/.../SlotAssignment.kt:130-135`). Pages are built in `onBindViewHolder` and thrown away in
`onViewRecycled` (`MainDesktopPagerAdapter.kt:42-62`), with `offscreenPageLimit = 2`
(`LauncherActivity.kt:351`). Each page change scales/fades the host and sets a system background blur on
API 31+ (`LauncherActivity.kt:353-378`). A shake gesture toggles a "page lock" that disables paging
(`ShakeDetector.kt`, `LauncherActivity.kt:536-542`).

**User path.** It is the HOME app. Swipe left/right. Shake the phone to lock/unlock paging. Home from
anywhere returns here; `onResume` re-checks the lock gate, the tour, trusted-device offers
(`LauncherActivity.kt:469-501`).

**Status: PARTIAL.**

**Evidence that it is wired:** manifest HOME/DEFAULT/LAUNCHER filter (`AndroidManifest.xml:100-105`);
adapter attached at `LauncherActivity.kt:349`; page factory switch at `MainDesktopPagerAdapter.kt:64-123`
covers 20 assignment types. What is *not* verified: that every page class inflates and survives recycling
(each page's own code is out of scope here).

**Bugs.**

1. **Background blur and window scale are stuck on full for every page at index ≥ 2.**
   `LauncherActivity.kt:358` — `val isDrawer = position >= 2 // App Drawer is usually right-most`: the
   variable is never used for a drawer check, instead `progress` is forced to `1.0f` for *any* position ≥ 2
   (`:361-365`). Consequence: with the default four slots, the notifications page (slot 3) and everything
   after it always renders at `scale 0.92`, `scrim 0.45`, and `window.setBackgroundBlurRadius(160)`. A live
   blur radius of 160 stays applied while sitting on that page (cost + a permanently "zoomed out" home
   screen). The check also keys off the *slot index*, not the page type, so putting the drawer in slot 0
   means the drawer never triggers the effect while a wallet page in slot 4 always does.
2. **Overscroll-to-picker ignores overlays that are on top of it.** `LauncherActivity.kt:685-731`
   (`handleOverscrollDetection`) bails out only for `pagePickerOverlay` and `pageLocked` (`:686-687`).
   `dispatchTouchEvent` runs before children, so a fast vertical swipe *on the onboarding tour* or *on a
   folder popup* still opens the page picker underneath them. The tour is added last to the same root
   (`OnboardingOverlay.kt:227-234`), so the user sees nothing happen; state and the back handler have
   silently changed.
3. **`openPagePicker` discards the current page.** `LauncherActivity.kt:804` —
   `showPickerPositionsStep(initialSlot.coerceIn(0, 2))`. The argument is the *current pager item*, but it is
   clamped to 0..2, and then re-clamped to `itemCount - 1` (`:808`). On a 7-page setup, overscrolling from
   page 7 opens the picker at page 3. This hard-codes the old three-pane assumption into the new variable
   page count.
4. **Back on the home screen recreates the launcher.** `LauncherActivity.kt:412-416` disables the callback
   and calls `onBackPressedDispatcher.onBackPressed()`; with no other handling the activity finishes and the
   system immediately relaunches HOME. Nothing catastrophic, but it is a visible flash and it drops all page
   state (browser, editor, cloud session are recreated) for a stray back press.
5. **Adapter uses `notifyDataSetChanged` on a `ViewPager2`.** `MainDesktopPagerAdapter.kt:25-28`. Supported
   but forces full re-bind (`onBindViewHolder`'s cache key saves the rebuild) and, together with
   `LauncherActivity.removePageAt` changing `currentItem` in the same turn (`:840-843`), pages can be
   recycled mid-transition.
6. **Dead code:** `isUnderLauncherAppTarget` / `findViewAt` (`LauncherActivity.kt:977-1004`) are never
   called. `tag_prism_launcher_app_target` is written by `DrawerAppsAdapter.kt:239-240` and
   `DesktopGridAdapter.kt:143` and read by nothing.
7. **Silent catches on the main launch path.** `LauncherActivity.kt:776-778` swallows every exception from
   `startActivity` with the comment "Usually uninstalled while app is running"; a `SecurityException` or a
   missing activity looks identical to a dead tap. `logLaunchStat` (`:793-795`) swallows DB errors the same
   way, so the hotseat quietly stops learning.

---

## 2. Page picker & slots

**What it does.** Two-step overlay: step 1 lists positions (`PositionPickerAdapter`) with a 1.5 s
press-to-delete balloon, step 2 lists page types (`VerticalPageOptionsAdapter`, 18 built-ins + every
discovered plugin) with a search field injected above the pager. Applying calls `SlotPreferences.setAt`
and rebuilds the adapter (`LauncherActivity.kt:938-968`). Opening is via the vertical overscroll gesture, not
a long-press (`:685-731`).

**User path.** Swipe hard down or up on a page at the edge of its scroll (700 px travel **and** >1500 px/s
velocity, `:707-718`) → picker. Then "Configure content for this slot" → pick a page type. "+" at the top
adds a page to the left, "+" below the pager adds to the right (`activity_launcher.xml:98-122`).

**Status: PARTIAL** — the picker works and is reachable, but it is undiscoverable, mis-targets the current
page, and adding a page on the left corrupts every desktop page's contents.

**Bugs.**

1. **"Add page left" renumbers every desktop page's contents.** `LauncherActivity.kt:425-428` →
   `addPageAt(0)` → `SlotPreferences.addAt(0, …)` (`SlotPreferences.kt:179-185`) inserts at index 0. Desktop
   content is keyed by *slot index*: `DesktopShortcutStore(pageIndex)` reads/writes
   `cells_page_$pageIndex` (`DesktopShortcutStore.kt:140-151`), constructed with the adapter position in
   `DesktopGridPage.kt:29` via `MainDesktopPagerAdapter.kt:67-73`. Inserting at 0 means page 1 now shows
   page 2's icons, page 2 shows page 3's, and so on — exactly the failure `SlotPreferences.kt:143-153`
   documents ("an insert would silently hand each desktop page the contents of its neighbour") and which
   the notifications migration was carefully designed to avoid.
2. **Deleting a page has the same effect in reverse.** `LauncherActivity.kt:829-844` (`removePageAt`) saves
   the shortened list and rebuilds the adapter, but every later page's store key is unchanged. Delete slot 0
   and every desktop page silently inherits the next page's contents; the last page's grid is orphaned in
   `cells_page_N` and reappears if a page is added later.
3. **Slot delete has no confirmation and no undo.** `PagePickerAdapters.kt:111-128`: a 1.5 s hold
   (`:86-89`) starts a balloon-pop animation and calls `onDeletePosition`. The hold target is `cardRoot`, i.e.
   the whole card, and nothing on screen says "hold to delete". With `removePageAt` above, an accidental hold
   silently rearranges the home screen's data.
4. **Stale post-delay callbacks.** `PagePickerAdapters.kt:40,63-71,89`: the `Handler` and `deleteAction`
   runnable are per-bind and the handler is never cleared when the adapter is replaced
   (`LauncherActivity.kt:814` creates a new adapter every time the position step is shown). A hold that
   crosses a step change fires `onDeletePosition` against the *old* adapter's position.
5. **Picker search field is added at `subtitleIndex + 1`.** `LauncherActivity.kt:900-901` inserts it between
   the subtitle and the "+ add page left" button; `showPickerPositionsStep` hides it by tag (`:810-811`) and
   `hidePagePicker` hides it again (`:921-922`). The field is never removed, so it is re-shown with
   `setText("")` (`:916`) which fires the `TextWatcher` (`:903-911`) → `adapter.filter("")` *and*
   `setCurrentItem(0)` on every entry to the step. Harmless today, but the comment at `:913-914` assumes the
   watcher only reads the live adapter.
6. **`PagePickChoice.BuiltIn` is unreachable.** It is handled in `applySlotPick`
   (`LauncherActivity.kt:940`) but `allOptions` (`PagePickerAdapters.kt:156-176`) never offers it, so
   `SlotAssignment.Default` cannot be selected from the picker even though fresh installs use it
   (`MainDesktopPagerAdapter.kt:115-121`).
7. **Discovering plugins runs a `PackageManager` query on the UI thread** every time the picker opens
   (`LauncherActivity.kt:803`). For a picker that is usually opened from a gesture, that is a main-thread IPC
   on the critical path.

---

## 3. Drag reorder (desktop grid)

**What it does.** `DesktopGridPage` attaches an `ItemTouchHelper` (`:90-106`) *and* a long-press
`startDragAndDrop` on every cell (`DesktopGridAdapter.kt:145-168`), plus a grid-level drop handler that
distinguishes internal moves (`localState` = source index) from external drops (`LauncherActivity`'s
`allowDrawerDrag` / `acceptDesktopDrawerDrops`, `:338-347`). Cross-item drops run a full interaction matrix
(`DesktopGridPage.kt:383-475`) that makes folders, moves into folders, or writes a `.link` into a real
directory.

**User path.** Long-press an icon → drag → release over another cell (swap), an empty cell (move), another
icon (new folder), or the top strip (delete).

**Status: PARTIAL.** Internal moves work; the two competing drag systems, the occupancy invariant, and the
"copy instead of move" paths do not line up.

**Bugs.**

1. **Two drag mechanisms on the same gesture.** `ItemTouchHelper` with the default long-press drag
   (`DesktopGridPage.kt:90-106`) and `setOnLongClickListener { startDragAndDrop(...) }`
   (`DesktopGridAdapter.kt:147-168`) are both attached to the same `itemView`. Whichever wins, the other is
   wasted; worse, if `ItemTouchHelper` wins, `onMove` calls `adapter.move()` (`:100`) which does a raw swap
   with **no guard for `DesktopItem.Widget` / `Occupied`** (`DesktopGridAdapter.kt:47-51`). Swapping a
   widget's anchor cell leaves the `Occupied(ownerIndex)` cells pointing at the old index
   (`DesktopShortcutStore.kt:76`, `:216-233`), so the grid is corrupt: the widget overlay still draws over the
   old cells, the anchor cell holds `Occupied`, and `clearWidget` can no longer find the widget.
2. **Widgets can be dragged by their cell and dropped "successfully" doing nothing.** The long-press handler
   runs for every non-null cell (`DesktopGridAdapter.kt:147`) including `Widget`/`PluginWidget`/`Occupied`;
   the drop matrix deliberately ignores them (`DesktopGridPage.kt:385,387,424,442`), so the drag animates,
   the release happens, and nothing changes. The one path that *would* act — dropping onto an empty cell —
   calls `placeAt` (`:173`), moving the item in the store while the widget layer keeps drawing at the old
   position until a refresh, because `placeAt` → `persist()` does not call `widgetLayer.refresh()`.
3. **`applyDropMatrix` clears the source cell after a move that already moved it.** e.g.
   `DesktopGridPage.kt:417-419`: `adapter.move(srcPos, dstPos)` then `adapter.clearCellAt(srcPos)`. Harmless
   only because `move` already emptied the source; if the swap semantics change it deletes a real item. The
   same pattern appears at `:468-470`.
4. **`combineIntoFolder` leaks the dragged folder's directory.** `DesktopGridPage.kt:353-359` creates a new
   folder id, serialises the existing folder into it as a `nest_*.link`, and never deletes the old folder's
   on-disk `.link` files under `filesDir/Desktop/Folders/<oldId>`. Repeated nesting grows storage with content
   nothing can reach.
5. **Folder ids are 4 hex chars.** `DesktopGridPage.kt:335` (`UUID.randomUUID().toString().substring(0,4)`)
   for link names and `:354` (8 chars) for folder ids. 65 536 folder ids and 65 536 link-name suffixes is
   fine in practice, but a collision silently overwrites an existing shortcut's file.
6. **Drop on a directory writes into the user's storage without confirmation.** `addItemToStorageFolder`
   (`:365-379`) writes `prism_xxxx.link` into any writable directory; a failure only logs (`:376-378`) after
   the UI has already moved the icon into that directory (`:397-400`), so the item disappears from the desktop
   and is not in the folder either.
7. **`move`/`placeAt` write preferences on every drop and on every autoscroll move** (`DesktopGridAdapter.kt:50,56,86`):
   `SharedPreferences.edit().apply()` per swap is acceptable for a drop, but it is also called from
   `DesktopWidgetLayer.applyResize` on every `ACTION_MOVE` (see §13).

---

## 4. Folders

**What it does.** Two folder kinds share one popup (`FolderPopupView`): a virtual folder stored under
`filesDir/Desktop/Folders/<id>` with one `.link` file per member (`DesktopGridPage.kt:331-351`), and a
`DirectoryRef` pointing at a real directory on disk (`FolderPopupView.kt:134-139`). The popup shows a 4-column
grid, supports entering a nested folder with a back stack (`:76-82,98-107`), renames the root by editing the
title (`:54-63`), and draws a 2×2 preview of member icons in the launcher cell
(`DesktopGridAdapter.kt:179-231`).

**User path.** Drag one icon onto another → folder appears. Tap the folder cell → popup. Tap the title (root
only) → keyboard → Done renames. In a nested folder, the back arrow or system back returns one level. Drag an
item out of the popup onto the desktop.

**Status: PARTIAL.** Open/rename/nest/launch work; dragging out of a folder duplicates instead of moving, and
the delete zone can never be reached from a folder.

**Bugs.**

1. **Dragging an item out of a folder copies it and leaves the original.** `FolderPopupView.kt:231-238` sets
   `localState = item` (a `DesktopItem`). The desktop's drop handler reads
   `event.localState as? Int ?: -1` (`DesktopGridPage.kt:145`) — a `DesktopItem` is not an `Int`, so the drop
   is treated as an *external* drop and `resolveDraggedItem` deserialises a brand-new item
   (`:320-326`). The `.link` file in the folder is untouched. Result: a duplicate.
2. **`onDragStarted` is an empty stub, so the delete zone never appears for folder drags.**
   `FolderPopupView.kt:83-94` contains only a comment ("Expose show/hide via package-private extension") and a
   lookup that discards its result. `DeleteZoneView` is `INVISIBLE` unless `showDeleteZone()` runs, which only
   happens from `DesktopGridAdapter.onDragStarted` (`DesktopGridPage.kt:53`). Dragging from the popup to the
   trash therefore cannot work at all.
3. **Even if it did, the trash does not delete folder members.** `deleteItem("prism_desktop_item", …)`
   dispatches on the item type and only touches the grid's cells (`DesktopGridPage.kt:282-303`); the `.link`
   file inside the folder remains, so the item reappears the next time the folder is opened.
4. **Folder contents are read on the UI thread, synchronously, on every open and every nesting step.**
   `FolderPopupView.kt:70` → `resolveFolderContents` (`:109-144`) does `listFiles()` and one `readText()` per
   `.link` on the main thread, with `android.util.Log.d` per file. A folder of 40 apps does 40 file reads
   before the first frame.
5. **Unbounded file content is parsed.** `:127` reads every `.link` whole; a corrupt/oversized file (or a user
   dropping a large file there) is parsed in memory without a size check.
6. **Rename feedback is silent.** `:57` calls `onRename(text)`; `LauncherActivity.updateDesktopItem`
   (`:1184-1205`) only writes when the item is found, so renaming a `DirectoryRef` whose path also appears in
   an unrelated folder id does nothing visible. There is no toast either way.
7. **The popup is a 320 dp card with a fixed 320 dp internal grid** (`view_folder_popup.xml:12-13,53`): a
   folder with more than ~12 items scrolls inside a small box on a small phone, and the card does not adapt to
   landscape (the activity is `fullUser`).
8. **`NeonGlowDrawable` is assigned as the card background** (`FolderPopupView.kt:37-41`) on a hardware
   accelerated view. Its `BlurMaskFilter`s (`NeonGlowDrawable.kt:56,61`) are ignored by hardware canvases —
   the code itself acknowledges this for the drawer at `DrawerAppsAdapter.kt:260-264` and sets
   `LAYER_TYPE_SOFTWARE` only there. The folder card therefore draws as a plain 4 dp stroke, not a glow.

---

## 5. Delete zone

**What it does.** A `DeleteZoneView` at the top of each desktop page (`page_desktop_root.xml:9-24`): rounded
rect, animated red border, manual trash-can drawing, and a red fill that radiates from the hover point
(`DeleteZoneView.kt:136-167`). It becomes visible when a desktop cell starts dragging
(`DesktopGridPage.kt:53,246-255`) and deletes on `ACTION_DROP` (`:124-131`).

**User path.** Long-press a desktop icon, drag it to the top strip, release.

**Status: PARTIAL.** Works for desktop icons only; the layout side effects are wrong.

**Bugs.**

1. **After the first drag, the desktop permanently loses ~10 % of its height.** `showDeleteZone`
   (`DesktopGridPage.kt:246-255`) sets the child's `layoutParams` to 70 % width × 10 % screen height, then
   `VISIBLE`. `hideDeleteZone` (`:257-260`) sets `INVISIBLE` and calls `reset()` but **never restores the 0 dp
   dimensions**. `INVISIBLE` still participates in layout, and `deleteZoneContainer` is `wrap_content`
   (`page_desktop_root.xml:10-15`), so the container keeps the 10 % height and the icon grid is shifted down
   by that much until the page view is rebuilt (pager recycle or process restart).
2. **Showing it mid-drag moves every drop target.** The zone is *inside the page's vertical `LinearLayout`,
   above the grid*. Making it visible at `ACTION_DRAG_STARTED` relayouts the grid while the finger is down, so
   the cell under the finger changes between the long-press and the release. The drop then lands on a
   different cell than the one the user aimed at.
3. **The zone is unreachable for anything but desktop cells.** It is only shown from
   `DesktopGridAdapter.onDragStarted`, i.e. from `DesktopGridAdapter.kt:149`; drags that begin in the drawer
   (`DrawerAppsAdapter.kt:242-253`), in a folder popup (`FolderPopupView.kt:83-94`, a no-op), or in the widget
   layer never show it. Cross-page drops are explicitly gated by `acceptDesktopDrawerDrops`
   (`DesktopGridPage.kt:153,163`) *after* the zone was never shown.
4. **`ACTION_DRAG_EXITED` is mapped to `dz.onDragExited()` which only clears `isHovering`** but leaves
   `hasAnimatedFill`/`fillRadius` set (`DeleteZoneView.kt:100-134`); re-entering the zone skips the fill
   animation (`:110-113` returns early), so the second hover shows a frozen solid disc instead of the
   radiating animation.
5. **A 1.8 s infinite `ValueAnimator` runs whenever any desktop page is attached** (`DeleteZoneView.kt:65-68,
   76-95`), invalidating only when hovering — but it still wakes the choreographer forever for a view that is
   `INVISIBLE` 99.9 % of the time, on every one of up to 9 pager neighbours kept alive by
   `offscreenPageLimit = 2`.

---

## 6. App drawer

**What it does.** `DrawerPageView` is a 4-column grid over `DrawerAppsAdapter` (`ListAdapter` + `DiffUtil`).
Content comes from the Room `installedAppDao().observeAll()` flow, resolved through `PackageManager`
(`DrawerPageView.kt:259-267,410-432`); if the table is empty it falls back to a live PM scan (`:413-416`).
Typing in the search bar filters apps, then appends *widget* results and then *notification history* results
as labelled sections (`:325-383`). Long-press starts a drag to the desktop; there is a reload button that
wipes and rescans the table (`:309-323`) and a settings button that opens `SettingsActivity` (`:122-124`).
The staccato rail on the right jumps to a letter (`:145-250`).

**User path.** Add the "App Drawer" page type from the page picker → swipe to it. Search box at the top;
settings gear and reload arrow beside it; letter rail on the right edge.

**Status: PARTIAL.** Listing/search/launch/drag work; the alphabet rail scrolls to the wrong row, grouping can
collide, and the "show labels" setting is not honoured.

**Bugs.**

1. **The staccato rail scrolls to the wrong row.** `scrollToLetter` computes the index in the *flat* `allApps`
   list (`DrawerPageView.kt:241-244`) but calls `scrollToPositionWithOffset(index, 0)` on a list that contains
   extra full-width `Group` and `Section` rows (`DrawerAppsAdapter.kt:349-388`). Every group header and every
   inserted section shifts the target row, so tapping "S" lands somewhere earlier in the alphabet — worse the
   more apps are grouped.
2. **The rail crashes on an app whose label is empty.** `DrawerPageView.kt:242` calls `it.label.first()`
   with no emptiness guard. A `DrawerAppEntry` with `label == ""` (possible from `ai.loadLabel` or a
   trusted-device entry, `:289-294`) throws `NoSuchElementException` on the main thread.
3. **Grouping can produce duplicate `Group` rows with the same title.** `groupDrawerApps`
   (`DrawerAppsAdapter.kt:356-370`) maps any category not in its `when` to the string `"Applications"`. Two
   such categories with ≥2 apps each produce two `DrawerItem.Group("Applications", …)` rows, and `DiffUtil`
   identity is the title alone (`:66`), so the adapter treats them as the same item and animates/replaces the
   wrong row.
4. **"Show drawer labels" is a no-op.** The toggle writes `KEY_SHOW_DRAWER_LABELS`
   (`SettingsActivity.kt:868-873`) and nothing else in the app reads it in the drawer:
   `bindApp` always sets `holder.binding.drawerLabel.text = e.label`
   (`DrawerAppsAdapter.kt:214-215`) with no visibility check. The setting is read only to render its own
   toggle (`SettingsActivity.kt:871`).
5. **The reload button can crash the app or leave itself stuck.** `reloadAppsFromPackageManager`
   (`DrawerPageView.kt:309-323`) has no `try/catch` around `dao.clearAll()` / `insertAll(queryLauncherApps)`
   inside `withContext(Dispatchers.IO)`. A DB failure propagates out of `lifecycleScope.launch` to the thread's
   uncaught handler (a crash), and because `isReloading = false` is only reached on success (`:320`), a caught
   failure would leave the button permanently inert. It also wipes the table (losing any custom state) before
   knowing the rescan succeeds, and can race `AppSyncWorker`/`AppPackageReceiver` doing the same thing.
6. **`getIconPackDrawable` is called per bind on the main thread** (`DrawerAppsAdapter.kt:217-221`), and the
   first call parses the pack's `appfilter.xml` synchronously (`IconPackEngine.kt:67-101`); see §9.
7. **Inner group `RecyclerView` is recreated on every bind.** `onBindViewHolder` builds a new
   `InnerGroupAdapter` and a new `LinearLayoutManager` for every group row (`DrawerAppsAdapter.kt:137-142`),
   inside `ListAdapter`'s bind, so scroll position within the horizontal group is lost on any list change.
8. **Two different empty-DB behaviours.** `DrawerPageView` falls back to a live scan (`:413-416`);
   `KineticHaloPageView` does not (see §8). A user who uses only the halo sees an empty ring on first run.
9. **Drawer search is not debounced on the first keystroke path.** `filterApps` cancels the previous job and
   sleeps 150 ms (`:327-334`), but the blank-query branch submits before the delay (`:329-332`), so
   clear-and-retype flickers the full grouped list in between.

---

## 7. Kinetic Halo

**What it does.** A custom `FrameLayout` that holds up to 32 `AppIconView`s and arranges them on a circle of
radius `300f * summonProgress` around the last touch point, rotating with the finger and coasting with an
inertia animator (`KineticHaloPageView.kt:99-187`). Tapping an icon launches it. Haptics tick once per icon
step (`:189-195`).

**User path.** Add "Kinetic Halo" from the page picker → touch anywhere on the page; drag in a circle to
rotate; lift to keep spinning; tap an icon to launch.

**Status: PARTIAL (functional but visually and physically wrong on real densities).**

**Bugs.**

1. **Every dimension is a raw pixel constant.** Icon size `LayoutParams(120, 120)` (`:91`), ring radius
   `300f` (`:101,150`), no `dp` conversion anywhere. On a 420 dpi phone the icons are ~29 dp and the ring
   ~72 dp — a cluster of tiny icons around the finger; on a low-density tablet they are huge. The page is
   unusable at one end of the density range.
2. **Child positions are overwritten by the next layout pass.** Icons are added as ordinary children of a
   `FrameLayout` (`:91`) and positioned by setting `view.x` / `view.y` (`:161-162`). `FrameLayout.onLayout`
   repositions every child from its gravity on any layout pass (pager settling, keyboard, window inset
   change), which stacks all 32 icons in the top-left corner until the next touch.
3. **Invisible icons stay clickable.** `layoutIcons` sets `alpha = summonProgress * 0.9f` and
   `scaleX/Y = summonProgress` (`:165-167`) but never `visibility = GONE` or `isClickable = false`. At rest,
   all 32 views are 120×120 px, alpha 0, stacked at `centerPoint` (radius 0) — tapping what looks like empty
   space launches an arbitrary app from the stack.
4. **The centre is wherever the finger lands, and the "hold" in the doc does not exist.** `:110-115` sets
   `centerPoint` from `ACTION_DOWN` and calls `animateSummon(true)` immediately; the class doc says "Touch and
   hold to summon the ring". There is no hold timer, and the ring is centred on the touch, so a touch near the
   screen edge draws most of the ring off-screen.
5. **No PackageManager fallback.** `resolveDrawerEntries` (`:210-223`) returns an empty list when the DB is
   empty, unlike `DrawerPageView.kt:413-416`. Fresh install → blank page.
6. **Only 32 apps, no search, no filter.** `allApps.take(32)` (`:88`) is sorted alphabetically, so apps after
   the 32nd are unreachable from this page with no indication that they exist.
7. **Dead placeholder code.** `AppIconView.init` builds a `View` and assigns
   `BlurMaskFilter(...).let { null }` (`:232-234`); the view is never added. The comment "Placeholder logic"
   is honest but the object is garbage on every icon creation.
8. **`velocity` is a single angle delta, not a velocity.** `:120` then `applyInertia` (`:173-187`) animates
   from that delta with a duration derived from it (`abs(v)*100` ms); a fast flick and a slow one both end at
   an arbitrary stop, and each `ACTION_UP` starts a new animator without cancelling the previous one.

---

## 8. Plugins (custom pages)

**Two unrelated systems share the word "plugin".**

**(a) Android custom page = an installed APK whose activity declares action
`com.prism.launcher.action.DESKTOP_PAGE` and two pieces of metadata.** `PluginPageDiscovery` queries for that
action (`PluginPageDiscovery.kt:9-29`), reads `com.prism.launcher.PAGE_VIEW_CLASS`
(`PrismContracts.kt:5`), and `DynamicPageLoader` creates a package context with
`CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY`, then reflectively constructs the named `View` with a
`Context` constructor (`DynamicPageLoader.kt:12-56`). The resulting view is placed in the slot by
`MainDesktopPagerAdapter.kt:80-87`, with a text error view when loading returns null (`:125-132`).
The working sample is `sample-custom-page/` (manifest + `SampleGlowPageView`).

**(b) JAR plugin SPI** (`PrismPlugin`, `PluginPages`, `PluginNode`, `sample-plugin/`) — this is the **desktop**
host's system: `PluginPages` is referenced only from `desktop/…` (`PluginsPage.kt`, `Main.kt`,
`DesktopGridPage.kt`), never from `app/`. On Android it is scaffold: `DesktopItem.PluginWidget` exists in the
portable model (`DesktopShortcutStore.kt:62,89,108`), `placePluginWidget` exists (`:261-281`), but
`DesktopWidgetLayer.refresh` only handles `DesktopItem.Widget` (`DesktopWidgetLayer.kt:100-101`) and nothing
on Android calls `placePluginWidget`. A plugin widget placed by any future code would be an invisible occupied
cell.

**User path (a).** Install the plugin APK → open the page picker (hard vertical overscroll) → choose the
plugin's label from the list → it loads into that slot. The plugin's own activity (`PageRegistrationActivity`)
is a stub that finishes immediately and is never launched by Prism.

**Status: (a) PARTIAL / runtime unverified; (b) SCAFFOLD-ONLY on Android.**

**Evidence.** Discovery, metadata read, menu entry, instantiation and error fallback are all present and
connected end to end (`LauncherActivity.kt:803` → `PagePickerAdapters.kt:175` → `applySlotPick`
`:959-962` → `SlotPreferences` → `MainDesktopPagerAdapter.kt:80-87`). Whether `createPackageContext` +
`Class.forName` on another app's dex still works on the Android versions Prism targets is **unverified** —
cross-app code loading has been progressively restricted, and there is no device test or instrumentation in
the tree to contradict that. The failure is at least graceful (error view).

**Bugs.**

1. **`CONTEXT_IGNORE_SECURITY` is requested for a view that is then handed the host's resources.**
   `DynamicPageLoader.kt:14-17`. The flag is deprecated and disables resource security checks; the instance is
   created with the *foreign* context (`:20`) and only falls back to the host (`:20`). Nothing here validates
   the plugin, its signature, or what it does with the host `Activity` behind that context.
2. **No lifecycle, no detach contract, no timeout.** The plugin `View` is created in `onBindViewHolder` and
   dropped in `onViewRecycled` (`MainDesktopPagerAdapter.kt:42-62`). A plugin that registers receivers,
   starts threads or holds the activity leaks until the process dies; there is no `onDetachedFromWindow`
   contract documented in `PrismContracts.kt` (6 lines, no doc).
3. **A plugin page whose app is uninstalled leaves a dead slot.** `SlotAssignment.Custom` keeps the package
   name (`SlotAssignment.kt:22`); on next load the class is not found and the user sees
   `R.string.failed_load_page` (`MainDesktopPagerAdapter.kt:86,127`) with no way to see *which* plugin failed
   or to clear the slot from that screen.
4. **Duplicate/self entries are not filtered.** `discover` returns every resolver, unsorted, without dedupe or
   excluding Prism's own package (should it ever declare the action).
5. **The picker's plugin list is only refreshed when the picker opens** (`LauncherActivity.kt:803`); installing
   a plugin while the launcher runs requires re-opening the picker. Acceptable, but combined with
   `discoveredPlugins` cached in a field (`:41`) it means the options step can list a plugin whose APK was
   removed seconds earlier.
6. **The sample-plugin Gradle module cannot be what an Android plugin author copies.** `sample-plugin`
   is a `kotlin("jvm")` JAR with a `Prism-Page-Class` manifest attribute (`sample-plugin/build.gradle.kts:1-39`);
   it is the desktop SPI. Anyone reading "sample-plugin" as "the Android plugin API" gets a JAR that Prism on
   Android never loads.

---

## 9. Theming (theme mode, glow/neon)

**What it does.** `PrismBaseActivity` maps `PrismSettings.getThemeMode()` (auto/light/dark) onto
`AppCompatDelegate.setDefaultNightMode` before `super.onCreate`, and colours the status bar from the *actual*
`uiMode` (`PrismBaseActivity.kt:15-44`). The accent is `getGlowColor()` (`PrismSettings.kt:1157-1162`, default
`0xFF7C9EFF`), consumed by `NeonGlowDrawable` (a three-layer stroke: ambient halo, core glow, sharp inner
stroke, `NeonGlowDrawable.kt:44-67`) and `NeonGlowEngine.applyNeonText` (`NeonGlowEngine.kt:23-26`).

**User path.** Settings (gear in the drawer, or the drawer's settings button) → "Launcher & Appearance" →
theme icon in the toolbar (light/dark), "Glow Accent" picker (Cyan/Magenta/Lime/Gold/Electric Blue).

**Status: PARTIAL.** Theme switch works (AppCompat does the work). The accent is applied only to newly
constructed views, and half of the "neon" primitives do nothing under hardware acceleration.

**Bugs.**

1. **"Follow system" is unreachable after the first tap.** `SettingsActivity.kt:760-774`: the toggle computes
   `if (mode == LIGHT) DARK else LIGHT`, so the default `THEME_AUTO` (0) becomes `LIGHT` on the first press and
   there is no control anywhere that writes `THEME_AUTO` back. `PrismBaseActivity.kt:16-21` still supports it.
2. **Changing the glow colour re-themes nothing that is already on screen.** `SettingsActivity.kt:893-894`
   calls `setGlowColor` then `refresh()` — which rebuilds the *settings list*, not the launcher. Every consumer
   captures the colour at construction time: `DrawerPageView.kt:77` (`private val glowColor =
   PrismSettings.getGlowColor()`), `FolderPopupView.kt:36`, `DeleteZoneView` (hard-coded reds), and
   `DrawerAppsAdapter` re-reads it per bind (`:232,236`) but the desktop grid and the drawer's own chrome do
   not. The comment "Refresh to update neon borders if needed" is wrong; the user must restart.
3. **Blur-based glows are silently not drawn on hardware canvases.** `NeonGlowDrawable.kt:56,61` use
   `BlurMaskFilter`, which a hardware-accelerated canvas ignores. The code knows this
   (`DrawerAppsAdapter.kt:260-264` sets `LAYER_TYPE_SOFTWARE` on the icon wrapper *only*). All the other
   consumers — drawer search bar, settings/reload buttons, staccato bubble (`DrawerPageView.kt:109-128`),
   folder card (`FolderPopupView.kt:37-41`), any settings card — get a flat stroke instead of a glow.
4. **`NeonGlowDrawable.setAlpha` only affects the core stroke.** `NeonGlowDrawable.kt:69-71`: `glowPaint` and
   `ambientPaint` keep full alpha, so animating a view's alpha (e.g. a fade) leaves a bright halo behind.
   `setColorFilter` has the same problem (`:73-75`).
5. **The drawable's inset is a raw 20 px.** `NeonGlowDrawable.kt:46`, not dp, and if the view is smaller than
   40 px the rect inverts and nothing draws.
6. **`NeonGlowEngine.applyNeonGlow` is a no-op.** `NeonGlowEngine.kt:65-69` — an empty `if` with a "Future:"
   comment. Any caller expecting an outer glow gets nothing (currently no callers).
7. **Glow colour labels do not match the colours.** `SettingsActivity.kt:877,891`: index 0 is labelled "Cyan"
   but writes `#FF7C9EFF`, a periwinkle blue. A user picking "Cyan" and getting blue reads as a bug.
8. **Theme change recreates every activity from a base-class `onCreate`.** `PrismBaseActivity.kt:26-28` guards
   on inequality (the comment documents a previous recreation loop), but the call still happens *before*
   `super.onCreate`, so the very first screen after a change is created twice.

---

## 10. Icon packs

**What it does.** Enumerates installed ADW/Nova-compatible packs
(`com.novalauncher.THEME`, `org.adw.launcher.THEMES`, `com.dlto.atom.launcher.THEME`, an action/category pair)
(`IconPackEngine.kt:34-62`), parses the pack's `appfilter.xml` into a component→drawable-name map
(`:67-101`) and resolves a `Drawable` per `ComponentName` (`:106-124`). The chosen package is stored in
`KEY_ICON_PACK_PACKAGE` (`PrismSettings.kt:42-46`).

**User path.** Settings → Launcher & Appearance → "Icon Pack" picker → pick a pack. Applies to the **app
drawer** icons on the next bind.

**Status: PARTIAL** — it works in the drawer only, and discovery is at the mercy of package visibility.

**Bugs.**

1. **Icon packs are not applied to the desktop grid.** `DesktopGridAdapter.resolveIcon`
   (`DesktopGridAdapter.kt:241-247`) goes straight to `getActivityIcon` / `getApplicationIcon` with no
   `IconPackEngine` call, and its cache (`:268-271`) is keyed only by component. So a desktop shortcut shows
   the system icon while the same app in the drawer shows the pack icon. The hotseat
   (`DesktopGridPage.kt:524-531`) and Kinetic Halo (`KineticHaloPageView.kt:219`) are likewise untouched.
2. **No `<queries>` entry for the icon-pack actions.** `AndroidManifest.xml:59-75` declares MAIN/LAUNCHER,
   `DESKTOP_PAGE` and `RecognitionService` only. On API 30+, `queryIntentActivities` for an invisible package
   returns nothing, so a pack with no launcher activity of its own is invisible to Prism and never appears in
   the picker. Most packs ship a config app and are therefore visible via the MAIN/LAUNCHER query — this is
   the difference between "works on most devices" and "correct".
3. **A parse failure poisons the cache permanently.** `loadIconPack` sets `currentLoadedPack = packageName`
   *before* parsing (`IconPackEngine.kt:68-70`) and the `catch` only logs (`:98-100`). If the XML throws
   (malformed pack, `getXml` failure), the pack is marked loaded with an empty map and every icon falls back
   to the system icon until `trimCache()` (memory pressure) or process restart. Switching packs appears to do
   nothing.
4. **The map is unsynchronised and mutated from two threads.** `iconCache` is a plain `mutableMapOf`
   (`:16`); `getIconPackDrawable` is called from `onBindViewHolder` on the main thread while
   `trimCache()` can run from `onTrimMemory` and the drawer resolves icons on `Dispatchers.IO`
   (`DrawerPageView.kt:261-262`). Concurrent write during iteration in `loadIconPack` can throw
   `ConcurrentModificationException`.
5. **Only `drawable`-typed resources are looked up.** `resources.getIdentifier(drawableName, "drawable", pkg)`
   (`:116`); packs that alias their icons through `mipmap` or `appfilter`-referenced XML drawables resolve to 0
   and are skipped silently.
6. **The Settings picker queries the PackageManager three times per refresh** (`SettingsActivity.kt:851,854,862`),
   four intents each — on the UI thread, inside the settings list build.
7. **A stale package is displayed as "System Default".** If `currentPkg` names an uninstalled pack, the
   computed index is 0 (`SettingsActivity.kt:855-856`) while the preference still holds the dead package;
   nothing clears it.

---

## 11. Typography (font style / custom font)

**What it does.** `PrismFontEngine.getTypeface` returns a `Typeface` for `default` (null), `nasalization`
(downloaded once to `filesDir/nasalization.otf`) or `custom` (a path from Settings)
(`PrismFontEngine.kt:30-73`). For WebViews it base64-embeds the file into injected `@font-face` CSS
(`:113-143`, called from `PrismWebViewClient.kt:277`). Injection into screens is attempted from
`PrismBaseActivity.onCreateView` (`PrismBaseActivity.kt:66-80`) and explicitly at a handful of programmatic
call sites (`AetherCommandPopup.kt:142,201`, `NoraCommandPopup.kt:137,196`,
`ModelLoadProgressDialog.kt:82`, `NoraTrainingActivity.kt:381`, `AetherTrainingActivity.kt:385`).

**User path.** Settings → Launcher & Appearance → Typography → "Font Style" (System / Nasalization / Custom) →
for Custom, "Select Custom Font" opens the document picker.

**Status: PARTIAL, and the "system-wide" claim is unverified/very likely false.**

**Bugs.**

1. **The inflation hook almost certainly never runs.** `PrismBaseActivity` extends `AppCompatActivity`
   (`:13`), and AppCompat installs its own `Factory2`; `Activity.onCreateView(parent, …)` is the *private*
   factory, consulted only for tags AppCompat cannot inflate. Ordinary `<TextView>`s are inflated by
   `AppCompatViewInflater` and never reach `PrismBaseActivity.onCreateView`, so the typeface is not applied to
   XML layouts at all. (Evidence that the author ran into this: every place the font actually matters has a
   manual `applyToView` call.) Runtime effect: unverified without a device, but the mechanism is inspectable.
2. **`else if (view is Button)` is dead.** `PrismFontEngine.kt:103-107`: `Button extends TextView`, so the
   second branch can never be reached.
3. **Nasalization caches a corrupt file forever.** `loadNasalization` (`:50-62`) checks `file.exists()` and
   returns whatever is there; `downloadNasalization` (`:75-99`) writes the response body without checking the
   HTTP status or the file being a font. A 404 page or a partial download is saved to
   `filesDir/nasalization.otf`, `createFromFile` throws, the throw is logged, and because the file now
   *exists* no retry ever happens. Selecting "Nasalization" then does nothing at all.
4. **The download URL is a dead-repo guess.** `PrismFontEngine.kt:28`, a raw GitHub URL for
   `tyrel/nasalization-font`; nothing in the tree pins or verifies it (**unverified** whether it resolves, but
   there is no fallback and no user-visible failure either way).
5. **Font copy failure is silent.** `SettingsActivity.copyFontToInternal` (`:544-562`) writes every picked
   file to `filesDir/custom_font.ttf` and on any exception only calls `e.printStackTrace()` (`:558-559`) — no
   toast, no log through `PrismLogger`. The user sees a successful picker and no change.
6. **No validation that the picked file is a font.** `fontPicker.launch("*/*")` (`:1934,1943`); the engine then
   silently falls back to `null` when `Typeface.createFromFile` throws (`PrismFontEngine.kt:64-73`).
7. **The custom path is fixed at `custom_font.ttf` regardless of the real type.** `copyFontToInternal`
   (`:548`); `getWebViewCss` decides `opentype` vs `truetype` from the *file name* (`:129`), so every custom
   font is declared `truetype` even when it is OTF (usually harmless, occasionally not).
8. **The whole font is base64-inlined into every page's CSS** (`PrismFontEngine.kt:126-139`, no size limit, no
   cache). A 300 KB font becomes ~400 KB of CSS injected per WebView navigation; that is a browser-scope cost
   caused by this engine.

---

## 12. Onboarding tour

**What it does.** A full-screen `LinearLayout` overlay added to the launcher's root, walking a data-driven
list of sections/steps (data only: `OnboardingContent.SECTIONS`, 487 lines, ~40 steps)
with section label, glyph, title, scrollable body, a "Where:" chip, progress, Back/Skip/Next, a
"Jump to a section" picker, and horizontal swipe navigation (`OnboardingOverlay.kt:33-239`). Shown from
`LauncherActivity.onResume` when `getOnboardingSeen()` is false (`LauncherActivity.kt:489`). Completion/Skip
writes the flag (`OnboardingOverlay.kt:208-212`). Replay: Settings → "Getting started" → "Take the tour again"
sets the flag false and finishes (`SettingsActivity.kt:2379-2394`).

**User path.** First launch after install. Replay via Settings → Other → Getting started. On desktop classes,
the same object host is used by `desktop/…/OnboardingOverlay.kt`.

**Status: WORKING (wiring), with several wrong instructions.**

**Bugs.**

1. **The tour teaches a gesture that does not exist.** `OnboardingContent.kt:46-49` and `:59`: "Long-press an
   empty spot on any page to swap it for a different one, or to add another" and its `whereToFind` is
   "Long-press an empty area of a page". The page picker opens on a *vertical overscroll* (700 px + 1500 px/s,
   `LauncherActivity.kt:685-731`); the grid background has no long-click listener at all
   (`DesktopGridPage.kt` attaches long-click only to cells). A new user will long-press and see nothing.
2. **"Long-press the home screen, or the drawer's menu" for Settings** (`OnboardingContent.kt:67`) — again no
   such long-press; Settings is the gear in the drawer (`DrawerPageView.kt:122-124`) only.
3. **The tour points at an emergency-code control that does not exist.** `OnboardingContent.kt:454` says
   "Settings > Other > Medical" for the duress code, and `LockSetupActivity.kt:109` promises "You can skip this
   and set it later in Settings." There is no such entry: the only lock row is "Lock screen"
   (`SettingsActivity.kt:2402-2412`), which re-runs the *whole* setup, and `LockStore.configure` wipes any
   existing duress hash (`LockStore.kt:107-109`).
4. **The body scroller is never reset between steps.** `render()` (`OnboardingOverlay.kt:179-193`) sets
   `body.text` but the enclosing `ScrollView` (`:85-93`) keeps its scroll offset. Read step 12 to the bottom,
   press Next, and step 13 opens scrolled past its own text (appearing blank).
5. **Swipe navigation is registered on the overlay, not a gesture detector.** `onTouchEvent` (`:157-166`)
   only looks at horizontal delta on `ACTION_UP` and returns true unconditionally, so it also swallows taps
   that do not land on a child (that is intended) and can fire on the tail of a vertical drag the children did
   not claim.
6. **`showIfNeeded(force = true)` has no caller.** `OnboardingOverlay.kt:222-235`; replay works only by
   clearing the preference, so the parameter is dead and the class comment's "replayable from Settings" is
   achieved by a different mechanism.
7. **Overlay lifetime is unmanaged.** It is added to `binding.root` and removed by `finish()`; if the activity
   is recreated (theme change, configuration not in `configChanges`) the tour restarts at step 0 because the
   index lives in the view (`:43`).

---

## 13. Lock screen / pattern / duress

**What it does.** `LockGate` arms on `ACTION_SCREEN_OFF` and shows `PrismLockActivity` on `ACTION_SCREEN_ON`,
and re-checks on launcher resume (`LockGate.kt:52-127`). `PrismLockActivity` draws wall­paper + scrim + clock +
one of three entry UIs (PIN keypad, password field, 3×3 `PatternView`), offers `BiometricPrompt` when the
device supports it, and shows a medical card readable without unlocking
(`PrismLockActivity.kt:77-560`). `LockStore` holds two salted, 20 000-iteration SHA-256 credentials in
`lock/credentials.json` — a normal one and a duress one — and returns which one matched
(`core/.../lock/LockStore.kt:99-178`). A duress unlock sets a flag (`PrismSettings.setDuressActive(true)`),
starts the SMS alert thread (`DuressResponder.kt:55-67`) and the launcher then jumps to the decoy
`DummyLauncherActivity`, which shows a grid of boring apps (`LauncherActivity.kt:475-481`,
`DummyLauncherActivity.kt:53-196`).

**User path.** Settings → (group "Other") → Medical → "Lock screen" → choose PIN/password/pattern → enter twice
→ optionally enter a duress credential twice → done. After that: screen off/on. Unlock with the credential; a
fingerprint may be offered. The duress credential unlocks to the decoy.

**Status: PARTIAL.** The credential math and the entry UIs are real and wired; the *arming* depends on a
background activity launch, there is no way to turn the lock off, and the "set the emergency code later"
promise has no UI.

**Bugs.**

1. **No way to disable the lock, clear it, or change it from the app.** `PrismSettings.setLockScreenEnabled`
   is called exactly once in the entire app, with `true` (`LockSetupActivity.kt:235`).
   `LockStore.setEnabled(false)`, `LockStore.clear()` and `LockStore.clearDuress()` have **no Android caller**
   (they are used only by the desktop `LockPage.kt:175,185,233`). Once a credential exists the only exit is
   wiping app data.
2. **A failed background activity launch silently disables the lock for the life of the process.**
   `LockGate.launch` sets `showing = true` *before* `startActivity` (`LockGate.kt:92-109`). Starting an
   activity from an `ACTION_SCREEN_ON` receiver is exactly the background-activity-launch case Android 10+
   restricts; when the launch is dropped (no exception, just a logcat warning + no window) `showing` stays
   `true`, and `show()`/`showIfLocked()` return early forever (`:83,89`). The user believes the lock is on;
   the screen never appears again until the process restarts. `showing` is also never reset in
   `onDestroy`/`onStop` of the lock activity, so a low-memory kill reproduces the same stuck state.
3. **Biometric cannot be turned off.** `PrismSettings.getLockBiometric()` is read at
   `PrismLockActivity.kt:449` with default `true`, and `setLockBiometric` has **no caller anywhere** on
   Android. The lock setup screen even advertises the sensor (`LockSetupActivity.kt:85-91`). A user who does
   not want fingerprint unlock has no control.
4. **Latent crash on the "no credential" path.** `PrismLockActivity.onCreate` returns early after `finish()`
   when `!LockStore.isConfigured()` (`:82-91`), before `root` is assigned (`:93`). `onResume` unconditionally
   does `root.post(ticker)` (`:105-108`). Android still delivers `onResume` for a finishing activity, so this
   throws `UninitializedPropertyAccessException` and takes the launcher process down. Reachable when the
   credential file is missing while `KEY_LOCKSCREEN` is still true (e.g. `LockStore.clear()` from the desktop
   build, or app data partially cleared).
5. **The manifest attribute meant to show the lock over the keyguard is not a framework attribute.**
   `AndroidManifest.xml:620` — `android:showOnLockScreen="true"`. The public API is
   `android:showWhenLocked` (API 27+). Either `aapt2` rejects the manifest or the attribute is ignored;
   either way the comment above it ("showWhenLocked is set in code as well") is the only thing that actually
   works, in `PrismLockActivity.kt:127-144`. **Unverified at build time because this session could not run
   `aapt2`.**
6. **Credential checks run on the UI thread.** `submit` (`PrismLockActivity.kt:380-386`) →
   `LockStore.verify` does one salted 20 000-round SHA-256 (`LockStore.kt:199-207`). Every digit that reaches
   the expected length blocks the main thread, and the lock screen is the one screen that must feel instant.
7. **Rate limiting is cosmetic and resettable.** `attempts` is an in-memory field (`:67`), reset on every
   activity creation; the 30 s hold (`:399-408`) removes the views but is not persisted, so force-stopping the
   app or killing the activity clears it. It also keeps running the 1 s clock ticker behind the "wait a moment"
   state.
8. **Biometric is offered once and has no error/retry path.** `offerBiometric` (`:448-475`) overrides only
   `onAuthenticationSucceeded`; `onAuthenticationError`/`onAuthenticationFailed` are not overridden, so after a
   cancel or a lockout the prompt never returns in that activity instance.
9. **The decoy has no documented exit that exists.** `DummyLauncherActivity.kt:42-45` says the way out is
   "Settings inside Prism proper"; nothing in the decoy or `SettingsActivity` exposes a stand-down control, and
   `DuressResponder.standDown` is called only from a *normal* unlock (`PrismLockActivity.kt:422`). Pressing
   Home does escape (Prism is HOME and `LauncherActivity.onResume` only redirects while
   `DuressResponder.isActive` is still true — which it is until the next normal unlock, so Home re-enters the
   decoy). The practical recovery is: wait for the screen to lock, unlock normally. That is a real trap for
   anyone who triggers duress by accident.
10. **The duress SMS has no delivery guarantee and several silent failure modes.** `sendAlerts`
    (`DuressResponder.kt:78-109`) requires emergency contacts, an SMS manager, and `SEND_SMS`; each missing
    piece logs and returns. `sendMultipartTextMessage` with null sent/delivery intents reports nothing back,
    and `composeMessage` includes "Sent automatically by Prism" — so a blocked send leaves the user believing
    contacts were notified. `READ_SMS`/`SEND_SMS`/`RECEIVE_SMS` are declared
    (`AndroidManifest.xml:31-33`) but the permission prompt is wired to the *messaging page*
    (`LauncherActivity.kt:308-315`), so a user who never opened Messaging has duress configured but cannot
    send the alert.
11. **`LockSetupActivity` has no "change" path that preserves anything.** Re-running it always calls
    `LockStore.configure`, which resets the salt and blanks `duress_hash` (`LockStore.kt:99-112`). A user who
    only wanted to add the emergency code after skipping it must redo the primary credential as well.
12. **Pattern strength is unbounded and stored as a plain string hash.** `PatternView` output is joined and
    hashed (`PrismLockActivity.kt:283`, `LockStore.kt:199`); minimum 4 dots is enforced in setup
    (`LockSetupActivity.kt:145-148`) but there is no duress-length check for patterns (only PIN,
    `LockStore.kt:130-135`), so a short duress pattern is possible; the "identical taps" guarantee the design
    depends on only holds for PINs.
13. **Medical card strings are raw and unlocalised, and the card's position is a hard-coded 240 dp margin**
    (`PrismLockActivity.kt:543-549`), which can overlap the PIN keypad on a short screen with a large font
    scale.

---

## 14. Widgets

**What it does.** `PrismWidgetHost` wraps one process-wide `AppWidgetHost` with a fixed `HOST_ID`
(`PrismWidgetHost.kt:42`), started/stopped from the launcher's `onStart`/`onStop`
(`LauncherActivity.kt:225-237`). `WidgetPlacement` is the allocate → bind → (configure) state machine that
turns a drop into a `DesktopItem.Widget` plus `Occupied` cells
(`WidgetPlacement.kt:33-187`, `DesktopShortcutStore.kt:198-233`). Widgets are drawn by `DesktopWidgetLayer`, a
transparent overlay above the grid that measures cell rectangles from the grid's own children and tells each
provider its size (`DesktopWidgetLayer.kt:39-322`). Long-press on a widget opens a Resize/Remove dialog
(`:287-295`). Widgets are *discovered* through the drawer's search box, which appends a "Widgets" section
(`DrawerPageView.kt:352-368`, `DrawerAppsAdapter.kt:146-186`).

**User path.** Open the drawer → type something that matches a widget's label or its app's label → tap the
result (places it on the first desktop page) or long-press it and drag onto a grid cell. Then long-press the
widget itself for Resize/Remove.

**Status: PARTIAL.** Placement, resize and remove are real code, but the entry point is a search result, the
commit path leaks ids, and resize writes storage on every touch move.

**Bugs.**

1. **The allocate id leaks when the cell is taken at commit time.** `WidgetPlacement.commit`
   (`WidgetPlacement.kt:159-180`): after `pending = null`, if `firstFitting` returns −1 (`:167-168`) the
   function just `return`s — no `releaseId`, no toast, no grid change. The id is leaked for the life of the
   host (the class comment at `:30-31` says "EVERY ABANDONED PATH RELEASES THE ID"). The user sees nothing.
2. **There is no widget picker.** `DrawerPageView.filterApps` only builds `DrawerItem.Widget` entries in the
   non-blank-query branch (`:352-368`); the blank list is apps and groups only (`:329-332`). A widget whose
   label and app label do not contain what the user types can never be added. There is also no page-level
   "add widget" affordance.
3. **Resize writes SharedPreferences on every `ACTION_MOVE`.** `DesktopWidgetLayer.applyResize`
   (`:250-277`) calls `store.writeGrid(mutable)` (`:271`) and `onChanged()` (`:274`) per motion event, plus
   `requestLayout()`; a single drag produces dozens of full 24-cell JSON-ish serialisations and grid rebuild
   callbacks.
4. **Resize mode can never be left by touching elsewhere.** `beginResize` (`:199-209`) sets `resizingIndex`;
   only `endResize` (from `ACTION_UP` after a handle grab, or `remove`) clears it. If the user opens the menu,
   taps "Resize", then taps anywhere else, `onInterceptTouchEvent`/`onTouchEvent` return false
   (`:223-244`) and the white frame + handle stay drawn (`onDraw` `:213-221`) indefinitely; the widget's
   long-press menu now starts in resize mode again.
5. **The resize/remove menu is unreachable on any widget whose surface is clickable.**
   `host.setOnLongClickListener` (`:126-129`) is on the `AppWidgetHostView`; a `RemoteViews` child that is
   clickable consumes the long press first, so a clock/weather widget with a tap target never reaches the
   menu. The comment at `:123-125` claims long-press is "the one gesture RemoteViews content almost never
   claims" — that is not true for most modern widgets.
6. **`DesktopItem.PluginWidget` is never drawn.** `refresh` only handles `is DesktopItem.Widget`
   (`:100-101`) and `clearWidget` handles both (`DesktopShortcutStore.kt:236-241`), so a plugin widget is
   invisible occupancy on Android (see §8b).
7. **Widget cell geometry is silently skipped when a cell is not laid out.** `cellRect` returns null when the
   anchor or corner row is not attached (`:172-194`) and `onLayout` just `continue`s (`:136-138`); a widget in
   the last row of a page whose grid is scrolled (or on a page that has never been laid out) does not get
   sized, so it stays at whatever size it last had.
8. **The provider is gone check runs only on `refresh()`.** Uninstalling a widget's app leaves the host view
   until something calls `refresh()` (`:103-113`); `LauncherActivity` calls it on placement and on
   `onActivityResult` (`:246-249,300-303`) but not on `onResume`, so a stale widget can survive until the page
   is recreated.
9. **Widget taps land on the grid when the layer is not resizing.** The layer is `isClickable = false`
   (`:90`) and `onTouchEvent` returns false unless resizing (`:226-244`), so empty areas of the layer fall
   through to the icon grid underneath — which is intended — but a widget host view that fails to consume a
   DOWN (a non-clickable RemoteViews root) lets the *grid cell under the widget* launch an app behind it.
10. **No `BIND_APPWIDGET` declaration in the manifest.** `AndroidManifest.xml` declares no
    `android.permission.BIND_APPWIDGET`. That is workable only while Prism is the default home
    (the platform grants bind to the current home); the code acknowledges this at `PrismWidgetHost.kt:105-114`
    and falls back to the system dialog. Not a defect on its own, but it means every widget placement shows a
    system dialog on a device where Prism is installed but not yet chosen as home.

---

## 15. Search host

**What it does.** `PrismSearchHost` is the mesh-facing entry point for Prism's own search engine: it publishes
a P2P DNS record for `PRISM_SEARCH_DOMAIN` to a mesh IP (or loopback) so `prism.com` resolves locally instead
of on the internet (`PrismSearchHost.kt:39-51`), and hands a raw `Socket` to `PrismSearchServer.serveHttp`
when the proxy recognises the domain (`:53-60`). It is started from `PrismApp` alongside the loopback listener
(`PrismApp.kt:329-332`). Settings surfaces the live address in a top row with a tap-to-copy
(`SettingsActivity.kt:141-153`).

**User path.** Nothing to tap: it runs at app start. The visible part is the Settings root row
"Prism Search · <address>" (tap copies) and the browser's `prism.com` / loopback URL.

**Status: PARTIAL / runtime unverified.** `claimDomain` is idempotent and called unconditionally; whether the
mesh proxy actually dispatches to `serve` depends on `PrismProxyServer`/`P2pDnsManager` (out of scope) and on
the mesh being up.

**Bugs.**

1. **A blank mesh IP silently publishes the name to this device's own loopback.** `PrismSearchHost.kt:42`:
   `MeshUtils.getLocalMeshIp().ifBlank { "127.0.0.1" }` when `isPrismSearchOnMesh()` is true. A peer resolving
   `prism.com` would then be pointed at its *own* loopback — i.e. the record is wrong, not missing, and the
   browser on the peer shows that device's local engine or a connection error. No warning is emitted.
2. **Everything is caught and logged, so a persistent DNS failure looks like success.** `:48-50` catches
   `Exception`, logs, and the caller (`PrismApp.kt:332`) ignores the result. The Settings row reports "Published
   on the mesh" purely from `isPrismSearchOnMesh()` (`SettingsActivity.kt:143`), not from whether the record
   was written.
3. **The mesh/local decision is derived from VPN role, not from listener state.**
   `PrismSettings.isPrismSearchOnMesh()` = `getMeshEnabled() && getPrismVpnRole() == SERVER`
   (`PrismSettings.kt:132-133`). If the role is SERVER but the VPN tunnel failed to start, the domain is still
   published and points nowhere.
4. **`serve` has no read timeout or size bound of its own** (`:53-60` delegates straight to
   `PrismSearchServer.serveHttp`); a peer that connects and stalls holds a coroutine on `Dispatchers.IO`
   until the socket layer gives up.

---

## 16. App sync (`AppSyncWorker` + `AppPackageReceiver`)

**What it does.** `AppSyncWorker` is a one-shot `CoroutineWorker` that clears the `installed_apps` table and
repopulates it from a `ACTION_MAIN`/`CATEGORY_LAUNCHER` query; enqueued once with
`ExistingWorkPolicy.KEEP` (`PrismApp.kt:370-375`). `AppPackageReceiver` is a manifest receiver for
ADDED/REPLACED/REMOVED/FULLY_REMOVED/CHANGED that upserts or deletes a single package
(`AppPackageReceiver.kt:21-60`, manifest `:109-120`). The drawer observes the table live
(`DrawerPageView.kt:259-267`).

**User path.** None — background maintenance. Effects are visible when installing/removing an app, or via the
drawer's reload button.

**Status: PARTIAL.** The receiver is wired and normally works; the failure modes are silent staleness.

**Bugs.**

1. **`GlobalScope` work in a receiver can be cut short.** `AppPackageReceiver.kt:36` launches on
   `Dispatchers.IO` without `goAsync()`/`finish()`. Once `onReceive` returns, the process is eligible to be
   killed; a package installed while the launcher is not running can be added *without* its DB row.
2. **A stale table is never repaired.** `AppSyncWorker` runs once (`KEEP`), and the drawer only falls back to
   a live PackageManager scan when the table is *completely empty* (`DrawerPageView.kt:413-416`). One missed
   row + a non-empty table = an app that is invisible in the drawer until the user finds the reload button
   (`DrawerPageView.kt:130-132`) or clears data. There is no periodic reconciliation anywhere.
3. **`PACKAGE_CHANGED` deletes rows for packages that still have a launcher activity in a disabled state.**
   `AppPackageReceiver.kt:40-49`: the query returns nothing for a disabled component and the code deletes the
   row; re-enabling the app produces no broadcast Prism handles, so it stays missing.
4. **`Result.retry()` on a permanent error.** `AppSyncWorker.kt:31-34` retries any `Throwable`, including a
   corrupt database or a `SQLiteException` that will never succeed, with WorkManager's backoff — an infinite
   retry loop for a deterministic failure.
5. **The worker wipes before it can replace.** `dao.clearAll()` then `insertAll` (`:27-28`); a crash or process
   death between the two leaves an empty drawer that falls back to a live scan (visible), and the next KEEP
   enqueue will not run again — the table stays empty until the reload button.
6. **The receiver trusts `schemeSpecificPart`.** `:25` — for `PACKAGE_CHANGED`/`ADDED` this is the package
   name, fine, but there is no validation and no `EXTRA_REPLACING` handling for `PACKAGE_CHANGED`, so an
   in-place update of a multi-activity app can transiently delete and re-add rows (a visible flicker in the
   drawer).
7. **Manifest declares `RECEIVE_BOOT_COMPLETED` with no boot receiver.** `AndroidManifest.xml:30`; there is no
   `BOOT_COMPLETED` intent filter anywhere in the manifest, so the permission is dead (and the private VPN /
   lock gate are not re-armed at boot from a receiver).

---

## Cross-cutting findings

1. **A preference that is written and never honoured:** `show_drawer_labels`
   (`SettingsActivity.kt:868-873`, `PrismSettings.kt:49-53`) — the drawer never reads it
   (`DrawerAppsAdapter.kt:214-215`).
2. **Preferences that are read but can never be written from Android:** `lock_biometric`
   (`PrismSettings.kt:1977`, read at `PrismLockActivity.kt:449`, `setLockBiometric` has no caller);
   `lock_screen_enabled` is only ever written `true` (`LockSetupActivity.kt:235`), and `LockStore`'s
   disable/clear APIs are desktop-only.
3. **Dead UI:** `SettingsActivity.buildSlotPickers()` (`:2556-2597`) is never called — 42 lines of "Page N
   Content" pickers are unreachable. Had it been reachable it would corrupt assignments: any slot type outside
   its nine options is displayed as "Desktop Grid" (`else -> 1`, `:2572`) and rewritten as such.
4. **Dead code:** `LauncherActivity.isUnderLauncherAppTarget`/`findViewAt` (`:977-1004`);
   `NeonGlowEngine.applyNeonGlow` (`:65-69`); `OnboardingOverlay.showIfNeeded(force)` parameter (`:222`);
   `PagePickChoice.BuiltIn` (`PagePickerAdapters.kt:11`, unreachable in `allOptions`);
   `DesktopGridAdapter.dragSourceIndex` (`:31`, set and reset but the drop handler uses `localState`);
   `AppIconView`'s placeholder `bg` view (`KineticHaloPageView.kt:232-234`);
   `FolderInternalAdapter.onDragStarted` body (`FolderPopupView.kt:83-94`).
5. **Manifest questions that this session could not compile:** `android:showOnLockScreen`
   (`AndroidManifest.xml:620`, not a public framework attribute) and the absent `<queries>` for the icon-pack
   theme actions (`:59-75`).
6. **Main-thread blocking work on the launcher's critical path:** plugin discovery per picker open
   (`LauncherActivity.kt:803`), folder contents per open (`FolderPopupView.kt:70`), icon-pack parsing per first
   bind (`IconPackEngine.kt:67`), 20 000-round hashing per unlock (`LockStore.kt:202`), and grid writes per
   resize move (`DesktopWidgetLayer.kt:271`).

---

## Status roll-up

| # | Feature | Status |
|---|---|---|
| 1 | Three-pane launcher / pager | PARTIAL |
| 2 | Page picker & slots | PARTIAL |
| 3 | Drag reorder | PARTIAL |
| 4 | Folders | PARTIAL |
| 5 | Delete zone | PARTIAL |
| 6 | App drawer | PARTIAL |
| 7 | Kinetic Halo | PARTIAL |
| 8 | Plugins (APK page) | PARTIAL / runtime unverified |
| 8b | Plugins (JAR SPI, sample-plugin) | SCAFFOLD-ONLY on Android |
| 9 | Theming / glow | PARTIAL |
| 10 | Icon packs | PARTIAL |
| 11 | Typography | PARTIAL (system-wide injection unverified, likely inert) |
| 12 | Onboarding | WORKING (wiring) with wrong instructions |
| 13 | Lock / pattern / duress | PARTIAL |
| 14 | Widgets | PARTIAL |
| 15 | Search host | PARTIAL / runtime unverified |
| 16 | App sync | PARTIAL |

No feature in this scope is BROKEN in the sense of "cannot be reached"; several are BROKEN in the sense of
"reaches a state the user cannot get out of" (duress exit, resize mode, lock disabling, page-content
renumbering). Those are called out individually above.
