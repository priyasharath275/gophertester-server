package com.example.gophertester.ui.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.gophertester.ui.tab.ConnectionRepository
import com.example.gophertester.util.ContactsResolver
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdminScreen(vm: AdminViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current

    // also observe app-wide state so we can mark "(me)"
    val appUi by ConnectionRepository.state.collectAsState()
    val localPhone = appUi.localPhone

    DisposableEffect(Unit) {
        vm.startPolling(ctx)
        onDispose { vm.stopPolling() }
    }

    // ── label helper: contact name if available; "(me)" for own number
    fun labelFor(phone: String): String =
        ContactsResolver.displayLabel(ctx, phone, localPhone)

    // ── 3 rows of (left, right) slots
    val rows = 3
    val leftSlots  = remember { mutableStateListOf<String?>(null, null, null) }
    val rightSlots = remember { mutableStateListOf<String?>(null, null, null) }

    // slot hit areas (window coords)
    val leftRects  = remember { mutableStateListOf<Rect?>(null, null, null) }
    val rightRects = remember { mutableStateListOf<Rect?>(null, null, null) }

    // drag overlay
    var draggingPhone by remember { mutableStateOf<String?>(null) }
    var draggingPos by remember { mutableStateOf(Offset.Zero) }   // **window coords**
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }  // window → canvas local

    fun slotPairs(): List<Pair<String, String>> =
        (0 until rows).mapNotNull { i ->
            val a = leftSlots[i]; val b = rightSlots[i]
            if (a != null && b != null) a to b else null
        }

    // purge offline
    LaunchedEffect(ui.online) {
        val online = ui.online.toSet()
        repeat(rows) { i ->
            if (leftSlots[i] != null && leftSlots[i] !in online)  leftSlots[i] = null
            if (rightSlots[i] != null && rightSlots[i] !in online) rightSlots[i] = null
        }
    }

    // active pairs (undirected)
    val activeUndirected = ui.activePairs.map { setOf(it.first, it.second) }.toSet()

    var poolRect by remember { mutableStateOf<Rect?>(null) }

    // Canvas rows as undirected sets  ⬅ recompute from contents, not list references
    val rowUndirected = remember {
        derivedStateOf { slotPairs().map { setOf(it.first, it.second) }.toSet() }
    }.value

    // Do not wrap this in remember; let it reflect the latest state
    val hasActiveHere = ui.activePairs.any { setOf(it.first, it.second) in rowUndirected }
    val anyActive = ui.activePairs.isNotEmpty()
    val showStop = anyActive

    // pool (not already used)
    val used = remember {
        derivedStateOf { (leftSlots + rightSlots).filterNotNull().toSet() }
    }.value
    val pool = ui.online.filter { it !in used }

    LaunchedEffect(ui.activePairs, ui.resetVisualNonce) {
        val canvasEmpty = leftSlots.all { it == null } && rightSlots.all { it == null }
        if (canvasEmpty && ui.activePairs.isNotEmpty()) {
            repeat(rows) { i -> leftSlots[i] = null; rightSlots[i] = null }
            ui.activePairs.take(rows).forEachIndexed { idx, (a, b) ->
                leftSlots[idx] = a
                rightSlots[idx] = b
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("WS: ${ui.ws}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Online: ${ui.online.size}   •   Active pairs: ${ui.activePairs.size}",
                fontWeight = FontWeight.Bold
            )
            ui.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

            // ── Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .onGloballyPositioned {
                        val p = it.positionInWindow()
                        canvasOffset = Offset(p.x, p.y)
                    }
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Sender", fontWeight = FontWeight.SemiBold)
                        Text("Receiver", fontWeight = FontWeight.SemiBold)
                    }
                    repeat(rows) { i ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val rowIsActive =
                                leftSlots[i] != null && rightSlots[i] != null &&
                                        setOf(leftSlots[i]!!, rightSlots[i]!!) in activeUndirected

                            // LEFT
                            SlotBox(
                                modifier = Modifier.weight(1f),
                                phone = leftSlots[i],
                                active = rowIsActive,
                                placeholder = "Drop sender",
                                onMeasured = { r -> leftRects[i] = r },
                                onClear = { leftSlots[i] = null },
                                onDragFromHere = { draggingPhone = it },
                                onDrag = { global -> draggingPos = global },
                                onDropped = { phone, global ->
                                    val target = findTarget(global, leftRects, rightRects)
                                    when (target) {
                                        is Origin.Left  -> {
                                            val idx = target.index
                                            if (idx != i) { leftSlots[idx] = phone; leftSlots[i] = null }
                                        }
                                        is Origin.Right -> {
                                            val idx = target.index
                                            rightSlots[idx] = phone; leftSlots[i] = null
                                        }
                                        null -> {
                                            // drop over pool => remove from slot → shows in pool
                                            if (poolRect?.contains(global) == true) {
                                                leftSlots[i] = null
                                            }
                                        }
                                    }
                                    draggingPhone = null
                                },
                                labelFor = ::labelFor
                            )

                            // RIGHT
                            SlotBox(
                                modifier = Modifier.weight(1f),
                                phone = rightSlots[i],
                                active = rowIsActive,
                                placeholder = "Drop receiver",
                                onMeasured = { r -> rightRects[i] = r },
                                onClear = { rightSlots[i] = null },
                                onDragFromHere = { draggingPhone = it },
                                onDrag = { global -> draggingPos = global },
                                onDropped = { phone, global ->
                                    val target = findTarget(global, leftRects, rightRects)
                                    when (target) {
                                        is Origin.Left  -> {
                                            val idx = target.index
                                            leftSlots[idx] = phone; rightSlots[i] = null
                                        }
                                        is Origin.Right -> {
                                            val idx = target.index
                                            if (idx != i) { rightSlots[idx] = phone; rightSlots[i] = null }
                                        }
                                        null -> {
                                            if (poolRect?.contains(global) == true) {
                                                rightSlots[i] = null
                                            }
                                        }
                                    }
                                    draggingPhone = null
                                },
                                labelFor = ::labelFor
                            )
                        }
                    }
                }

                // drag ghost follows finger (convert window → canvas local)
                draggingPhone?.let { phone ->
                    val local = draggingPos - canvasOffset
                    DragGhost(
                        label = labelFor(phone),
                        modifier = Modifier.offset {
                            IntOffset((local.x - 80).roundToInt(), (local.y - 24).roundToInt())
                        }
                    )
                }
            }

            // ── Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val stagedPairs = slotPairs()
                Button(
                    onClick = {
                        if (showStop) {
                            // stop only the pairs in the canvas that are active; if none are in view, stop all active
                            val activeHere = stagedPairs.filter { setOf(it.first, it.second) in activeUndirected }
                            when {
                                activeHere.isNotEmpty() -> vm.stopPairs(ctx, activeHere)
                                anyActive               -> vm.stopPairs(ctx, ui.activePairs)
                            }
                        } else if (stagedPairs.isNotEmpty()) {
                            vm.connectPairs(ctx, stagedPairs)
                        }
                    },
                    enabled = if (showStop) {
                        (stagedPairs.any { setOf(it.first, it.second) in activeUndirected }) || anyActive
                    } else {
                        stagedPairs.isNotEmpty()
                    }
                ) {
                    Text(if (showStop) "Stop" else "Connect")
                }

                OutlinedButton(onClick = { vm.refreshNow(ctx) }) { Text("Refresh") }

                Spacer(Modifier.weight(1f))
                Text(
                    if (slotPairs().isEmpty()) "No staged pairs" else "Staged: ${slotPairs().size}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // ── Pool
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .onGloballyPositioned { coords -> poolRect = coords.boundsInWindow() }
            ) {
                Text("Available users", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pool.forEach { phone ->
                        key(phone) {
                            DraggableChip(
                                text = labelFor(phone),
                                background = MaterialTheme.colorScheme.secondaryContainer,
                                onDragStart = { draggingPhone = phone },
                                onDrag = { global -> draggingPos = global },
                                onDragEnd = { global ->
                                    when (findTarget(global, leftRects, rightRects)) {
                                        is Origin.Left -> {
                                            val idx = (findTarget(global, leftRects, rightRects) as Origin.Left).index
                                            leftSlots[idx] = phone
                                        }
                                        is Origin.Right -> {
                                            val idx = (findTarget(global, leftRects, rightRects) as Origin.Right).index
                                            rightSlots[idx] = phone
                                        }
                                        null -> Unit
                                    }
                                    draggingPhone = null
                                }
                            )
                        }
                    }
                    if (pool.isEmpty()) Text("No free users right now…")
                }
            }
        }
    }
}

/* ── helpers & small composables ─────────────────────────────────────────── */

private sealed interface Origin {
    data class Left(val index: Int) : Origin
    data class Right(val index: Int) : Origin
}

@Composable
private fun SlotBox(
    modifier: Modifier = Modifier,
    phone: String?,
    active: Boolean,
    placeholder: String,
    onMeasured: (Rect) -> Unit,
    onClear: () -> Unit,
    onDragFromHere: (String) -> Unit,
    onDrag: (Offset /*window*/) -> Unit,
    onDropped: (String, Offset /*window*/) -> Unit,
    labelFor: (String) -> String
) {
    val bg = if (active) Color(0xFF22C55E) else Color(0xFFE5E7EB)
    val fg = if (active) Color.White else Color.Black

    Surface(
        modifier = modifier
            .height(56.dp)
            .onGloballyPositioned { coords -> onMeasured(coords.boundsInWindow()) },
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        if (phone == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            key(phone) {
                DraggableChip(
                    modifier = Modifier.fillMaxSize(),
                    text = labelFor(phone),
                    background = bg,
                    contentColor = fg,
                    onDragStart = { onDragFromHere(phone) },
                    onDrag = onDrag,
                    onDragEnd = { pos -> onDropped(phone, pos) },
                    trailing = {
                        TextButton(
                            onClick = onClear,
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text("✕", color = fg, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DraggableChip(
    modifier: Modifier = Modifier,
    text: String,
    background: Color,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    onDragStart: () -> Unit,
    onDrag: (Offset /*window*/) -> Unit,
    onDragEnd: (Offset /*window*/) -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    var chipOrigin by remember { mutableStateOf(Offset.Zero) } // window coords of chip's top-left
    var lastGlobal by remember { mutableStateOf<Offset?>(null) }

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .padding(2.dp)
            .onGloballyPositioned { coords ->
                val p = coords.positionInWindow()
                chipOrigin = Offset(p.x, p.y)
                lastGlobal = null                        // reset on re-layout
            }
            .pointerInput(text) {                        // key to label
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, _ ->
                        val global = chipOrigin + change.position
                        lastGlobal = global
                        onDrag(global)
                    },
                    onDragEnd = {
                        onDragEnd(lastGlobal ?: chipOrigin)
                        lastGlobal = null
                    },
                    onDragCancel = {
                        onDragEnd(lastGlobal ?: chipOrigin)
                        lastGlobal = null
                    }
                )
            },
        color = background,
        contentColor = contentColor,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text, fontWeight = FontWeight.SemiBold)
            trailing?.invoke()
        }
    }
}

@Composable
private fun DragGhost(label: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xFFD6E3FF),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 6.dp
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
        }
    }
}

private fun findTarget(
    globalPos: Offset,              // window coords
    leftRects: List<Rect?>,
    rightRects: List<Rect?>
): Origin? {
    leftRects.forEachIndexed { i, r -> if (r?.contains(globalPos) == true) return Origin.Left(i) }
    rightRects.forEachIndexed { i, r -> if (r?.contains(globalPos) == true) return Origin.Right(i) }
    return null
}
