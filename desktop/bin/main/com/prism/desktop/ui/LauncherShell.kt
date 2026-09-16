package com.prism.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.SlotAssignment
import com.prism.launcher.SlotPreferences
import kotlinx.coroutines.launch

/**
 * Prism's launcher shell: a horizontal pager over user-assignable page slots.
 *
 * THIS IS THE ANDROID DESIGN, NOT A PHONE-SHAPED WINDOW. An earlier attempt at "mobile layout"
 * constrained the content to a 420dp column so it would look like a phone. That was the wrong
 * reading. What makes Prism's launcher what it is has nothing to do with being narrow -- it is
 * the *navigation model*: the user assigns pages to slots, and the slots are a pager you move
 * through laterally, with a switcher for jumping. So this uses the full width of the window and
 * ports the model instead of the proportions.
 *
 * The slots come from [SlotPreferences], the same store the Android build writes, which means a
 * user's page arrangement is genuinely shared rather than re-created: assign Nebula to slot 2 on
 * the phone and it is slot 2 here.
 *
 * KEYS, because a mouse has no horizontal fling:
 *
 *   TAB            next page -- the leftward swipe
 *   SHIFT+TAB      previous page
 *   DOWN           open the page switcher -- the drag up from the bottom edge
 *   UP / ESCAPE    close the switcher
 *   1..9           jump straight to a slot
 *
 * TAB RATHER THAN THE ARROW KEYS, and this is a real trade rather than a preference. Arrows are
 * what a text field, a list and a slider all consume, so paging had to be claimed in the preview
 * phase and taken away from every one of them -- you could not move a caret or scroll a list
 * inside a page without changing page. Tab's normal job is focus traversal, which Prism's pages
 * do not rely on, so claiming it costs far less. Arrows are handed back to the page content.
 *
 * The pager still responds to a trackpad's horizontal scroll where one exists, so this is an
 * addition to the gesture rather than a replacement for it.
 */
@Composable
fun LauncherShell(onExit: () -> Unit) {
    val prefs = remember { SlotPreferences() }
    var slots by remember { mutableStateOf(prefs.getAssignments().toList()) }
    var switcherOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { slots.size.coerceAtLeast(1) })
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    fun goTo(index: Int) {
        if (index in slots.indices) scope.launch { pagerState.animateScrollToPage(index) }
    }

    fun step(delta: Int) = goTo((pagerState.currentPage + delta).coerceIn(slots.indices))

    // The shell owns the keyboard. Without this the events go wherever the window focused by
    // default and none of the bindings fire.
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            // Preview, so Tab is intercepted before Compose's focus system consumes it -- by
            // the time an ordinary key handler sees Tab, focus has already moved. The arrow keys
            // are deliberately NOT handled here any more, so text fields and scrollable content
            // inside a page keep them.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Tab -> {
                        if (!switcherOpen) step(if (event.isShiftPressed) -1 else 1)
                        // Consumed either way: letting Tab through while the switcher is open
                        // would move focus behind it.
                        true
                    }
                    Key.DirectionDown -> if (switcherOpen) false else { switcherOpen = true; true }
                    Key.DirectionUp, Key.Escape ->
                        if (switcherOpen) { switcherOpen = false; true } else false
                    Key.One -> { goTo(0); true }
                    Key.Two -> { goTo(1); true }
                    Key.Three -> { goTo(2); true }
                    Key.Four -> { goTo(3); true }
                    Key.Five -> { goTo(4); true }
                    Key.Six -> { goTo(5); true }
                    Key.Seven -> { goTo(6); true }
                    Key.Eight -> { goTo(7); true }
                    Key.Nine -> { goTo(8); true }
                    else -> false
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            LauncherTopBar(
                slot = slots.getOrNull(pagerState.currentPage) ?: SlotAssignment.Default,
                index = pagerState.currentPage,
                total = slots.size,
                onEdit = { editing = true },
                onExit = onExit,
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { index ->
                // Full width. The page fills the window exactly as it fills a phone screen.
                Box(Modifier.fillMaxSize()) {
                    SlotPage(slots.getOrNull(index) ?: SlotAssignment.Default, index)
                }
            }

            PageDots(pagerState.currentPage, slots.size) { goTo(it) }
        }

        AnimatedVisibility(
            visible = switcherOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PageSwitcher(slots, pagerState.currentPage) { goTo(it); switcherOpen = false }
        }

        // ── Cross-page drag ──────────────────────────────────────────────────────────────────
        //
        // Held at the shell, above the pager, because a drag that starts in the drawer has to
        // survive the page change that carries it to the desktop. See DragController.
        val controller = LocalDragController.current

        // Dragging to an edge flips the page, which is what makes a cross-page drag possible at
        // all -- the destination page is not composed until the pager reaches it. Debounced by
        // isScrollInProgress so holding at the edge advances one page at a time rather than
        // racing through every slot.
        LaunchedEffect(controller.flipDirection) {
            val dir = controller.flipDirection
            if (dir == 0 || controller.payload == null) return@LaunchedEffect
            kotlinx.coroutines.delay(400)
            if (controller.flipDirection == dir && controller.payload != null) {
                val next = (pagerState.currentPage + dir).coerceIn(slots.indices)
                if (next != pagerState.currentPage) pagerState.animateScrollToPage(next)
            }
        }

        controller.payload?.let { payload ->
            // The edge hint, so it is discoverable that dragging to the side changes page.
            if (controller.flipDirection != 0) {
                Box(
                    Modifier
                        .align(if (controller.flipDirection < 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(70.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                )
            }

            // The dragged icon, following the cursor above everything.
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            controller.position.x.roundToInt() - 34,
                            controller.position.y.roundToInt() - 34,
                        )
                    }
                    .size(68.dp)
                    .alpha(0.9f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconOrFallback(payload.icon, fallbackIcon(payload.item), 40.dp)
                    Text(
                        payload.label,
                        fontSize = 9.sp,
                        maxLines = 1,
                        color = Color.White,
                    )
                }
            }
        }
    }

    if (editing) {
        SlotEditorDialog(
            slots = slots,
            prefs = prefs,
            onDismiss = { editing = false },
            onChanged = { slots = prefs.getAssignments().toList() },
        )
    }
}

/** Maps a slot to the page that fills it. */
@Composable
private fun SlotPage(slot: SlotAssignment, index: Int) {
    when (slot) {
        is SlotAssignment.DesktopGrid -> DesktopGridPage(pageIndex = index)
        is SlotAssignment.Browser -> BrowserPage()
        is SlotAssignment.AgenticTools -> AgenticToolsPage()
        is SlotAssignment.KineticHalo -> KineticHaloPage()
        is SlotAssignment.AppDrawer -> AppDrawerPage()
        is SlotAssignment.FileExplorer -> FileExplorerPage()
        is SlotAssignment.Messaging -> NoraChatPage()
        is SlotAssignment.Models -> ModelsPage()
        is SlotAssignment.ModelStore -> ModelsPage()
        is SlotAssignment.Default -> DesktopGridPage(pageIndex = index)
        // The rest name features whose UI is not ported. Naming the specific blocker rather than
        // showing a generic empty state, for the same reason the rail does.
        else -> UnportedSlot(slot)
    }
}

@Composable
private fun UnportedSlot(slot: SlotAssignment) {
    val (label, why) = when (slot) {
        is SlotAssignment.NebulaSocial -> "Nebula" to
            "Storage is ported and working; the feed UI is 675 lines of Android views and is the " +
                "largest single screen left."
        is SlotAssignment.VirtualizationOs -> "Virtualization" to
            "Android Virtualization Framework is Android-only; desktop would drive QEMU or libvirt."
        is SlotAssignment.Custom -> "Plugin page" to
            "Android loads these from APKs. Desktop will scan a plugins directory for JARs."
        is SlotAssignment.Wallet -> "Wallet" to
            "Key derivation, signing and the mining maths are all in :core and already run here; " +
                "what is Android-only is the Keystore that encrypts the recovery phrase at rest, " +
                "and the page UI. Desktop needs an equivalent WalletCipher before this can open a " +
                "wallet safely."
        else -> "Unassigned" to "Press ↓ to open the switcher, or Edit pages to assign something."
    }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Dashboard, null, tint = Color(0xFF3A3A44), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(18.dp))
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(why, fontSize = 13.sp, color = Color(0xFF9A9AA6), modifier = Modifier.widthIn(max = 460.dp))
    }
}

@Composable
private fun LauncherTopBar(
    slot: SlotAssignment,
    index: Int,
    total: Int,
    onEdit: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF121215)).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(slotIcon(slot), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Text(slotLabel(slot), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        Text("${index + 1}/$total", fontSize = 11.sp, color = Color(0xFF55555F))

        Spacer(Modifier.weight(1f))
        Text("Tab / Shift+Tab pages   ↓ switcher   1-9 jump", fontSize = 10.sp, color = Color(0xFF48484F))
        Spacer(Modifier.width(16.dp))
        Text(
            "Edit pages", fontSize = 12.sp, color = Color(0xFF9A9AA6),
            modifier = Modifier.clickableRow { onEdit() }.padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Exit launcher", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickableRow { onExit() }.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PageDots(current: Int, total: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF121215)).padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            val active = i == current
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (active) 8.dp else 6.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else Color(0xFF3A3A44),
                        RoundedCornerShape(50)
                    )
                    .clickableRow { onPick(i) }
            )
        }
    }
}

@Composable
private fun PageSwitcher(slots: List<SlotAssignment>, current: Int, onPick: (Int) -> Unit) {
    Surface(color = Color(0xFF1A1A20), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Text(
                "PAGES", fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF6C6C78), modifier = Modifier.padding(start = 20.dp, bottom = 10.dp)
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp)) {
                items(slots.withIndex().toList()) { (i, slot) ->
                    val active = i == current
                    Surface(
                        color = if (active) Color(0xFF2A2A34) else Color(0xFF202028),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(horizontal = 5.dp).size(width = 112.dp, height = 96.dp)
                    ) {
                        Column(
                            Modifier.fillMaxSize().clickableRow { onPick(i) }.padding(10.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                slotIcon(slot), null,
                                tint = if (active) MaterialTheme.colorScheme.primary else Color(0xFFB9B9C4),
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                slotLabel(slot), fontSize = 11.sp,
                                color = if (active) Color.White else Color(0xFF9A9AA6)
                            )
                            Text("${i + 1}", fontSize = 9.sp, color = Color(0xFF55555F))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Add, remove and reassign slots -- the desktop equivalent of Android's page picker.
 *
 * Android opens that with an overscroll fling, which has no mouse equivalent, so it is a button
 * plus the switcher. Writes go straight through [SlotPreferences], so a change here is a change
 * the phone build sees.
 */
@Composable
private fun SlotEditorDialog(
    slots: List<SlotAssignment>,
    prefs: SlotPreferences,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val options = remember {
        listOf(
            SlotAssignment.DesktopGrid, SlotAssignment.AppDrawer, SlotAssignment.FileExplorer,
            SlotAssignment.Messaging, SlotAssignment.Browser, SlotAssignment.NebulaSocial,
            SlotAssignment.KineticHalo, SlotAssignment.Models, SlotAssignment.ModelStore,
            SlotAssignment.AgenticTools, SlotAssignment.VirtualizationOs,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Pages") },
        text = {
            Column(Modifier.width(460.dp).heightIn(max = 460.dp)) {
                slots.forEachIndexed { i, slot ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}", fontSize = 11.sp, color = Color(0xFF55555F), modifier = Modifier.width(20.dp))
                        Icon(slotIcon(slot), null, tint = Color(0xFF9A9AA6), modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(slotLabel(slot), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        // Removal is refused at one page by SlotPreferences itself, so the shell
                        // cannot end up with nothing to show.
                        if (slots.size > 1) {
                            Icon(
                                Icons.Filled.Close, "Remove", tint = Color(0xFF6C6C78),
                                modifier = Modifier.size(16.dp).clickableRow {
                                    prefs.removeAt(i); onChanged()
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("ADD A PAGE", fontSize = 10.sp, letterSpacing = 1.1.sp, color = Color(0xFF6C6C78))
                Spacer(Modifier.height(6.dp))
                LazyRow {
                    items(options) { option ->
                        Surface(
                            color = Color(0xFF202028),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(
                                Modifier
                                    .clickableRow { prefs.addAt(slots.size, option); onChanged() }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(slotIcon(option), null, tint = Color(0xFF9A9AA6), modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(slotLabel(option), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    )
}

private fun slotLabel(slot: SlotAssignment): String = when (slot) {
    is SlotAssignment.Default -> "Home"
    is SlotAssignment.Browser -> "Browser"
    is SlotAssignment.DesktopGrid -> "Desktop"
    is SlotAssignment.AppDrawer -> "Apps"
    is SlotAssignment.Messaging -> "Messages"
    is SlotAssignment.KineticHalo -> "Halo"
    is SlotAssignment.FileExplorer -> "Files"
    is SlotAssignment.NebulaSocial -> "Nebula"
    is SlotAssignment.VirtualizationOs -> "Virtualization"
    is SlotAssignment.Models -> "Models"
    is SlotAssignment.ModelStore -> "Model Store"
    is SlotAssignment.AgenticTools -> "Agentic Tools"
    is SlotAssignment.Wallet -> "Wallet"
    is SlotAssignment.Custom -> "Plugin"
}

private fun slotIcon(slot: SlotAssignment): ImageVector = when (slot) {
    is SlotAssignment.Default -> Icons.Filled.Home
    is SlotAssignment.Browser -> Icons.Filled.Public
    is SlotAssignment.DesktopGrid -> Icons.Filled.GridView
    is SlotAssignment.AppDrawer -> Icons.Filled.Apps
    is SlotAssignment.Messaging -> Icons.Filled.Chat
    is SlotAssignment.KineticHalo -> Icons.Filled.BlurOn
    is SlotAssignment.FileExplorer -> Icons.Filled.Folder
    is SlotAssignment.NebulaSocial -> Icons.Filled.Groups
    is SlotAssignment.VirtualizationOs -> Icons.Filled.Computer
    is SlotAssignment.Models -> Icons.Filled.Tune
    is SlotAssignment.ModelStore -> Icons.Filled.Science
    is SlotAssignment.AgenticTools -> Icons.Filled.Build
    is SlotAssignment.Wallet -> Icons.Filled.AccountBalanceWallet
    is SlotAssignment.Custom -> Icons.Filled.Extension
}
