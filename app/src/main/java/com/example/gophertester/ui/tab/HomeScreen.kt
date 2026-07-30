package com.example.gophertester.ui.tab

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.example.gophertester.model.UserLocation
import com.example.gophertester.ui.admin.AdminActivity

fun s(x: String?) = x ?: "—"

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()

    // keep screen awake during active test
    val view = LocalView.current
    val screenAwake = ui.connected || ui.connecting || ui.receiverMode
    DisposableEffect(screenAwake) {
        view.keepScreenOn = screenAwake
        onDispose { view.keepScreenOn = false }
    }

    // Text shown in the input field. We keep this separate from the normalized value
    // so we can display "Name(+1...)" while still sending a clean "+<digits>" to backend.
    var targetDisplay by remember { mutableStateOf(ui.targetPhone) }

    val pickPhoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val dataUri = result.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.query(
                dataUri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val iName = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val iNum  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val name = c.getString(iName)?.trim().orEmpty()
                    val raw  = c.getString(iNum).orEmpty()
                    val normalized = normalizePhone(raw) // → "+<digits>"
                    if (normalized.isNotEmpty()) {
                        // Show Name(+1...) in the field, keep normalized value in VM/state
                        targetDisplay = if (name.isNotEmpty()) "$name($normalized)" else normalized
                        vm.setTargetPhone(normalized)
                    }
                }
            }
        }
    }

    if (ui.askForPhone) {
        var input by remember { mutableStateOf(ui.localPhone) }
        AlertDialog(
            onDismissRequest = { /* keep open until provided */ },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = input.trim()
                        if (trimmed.isNotEmpty()) {
                            vm.savePhoneAndReconnect(ctx, trimmed)
                            ConnectionRepository.setAskForPhone(false)
                        }
                    }
                ) { Text("Save") }
            },
            title = { Text("Your phone number") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("We couldn’t read your number from the SIM. Please enter the phone number you want to use as your ID.")
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        label = { Text("Phone number (e.g. +15551234567)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )
    }

    val inputsEnabled = !(ui.connected || ui.connecting || ui.receiverMode)
    val showStop = (ui.connected || ui.connecting || ui.receiverMode)

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ui.localPhone.isNotBlank()) Text("Local ID: ${ui.localPhone}")
            Text("WS: ${ui.wsStatus}")
            Text("Time sync: offset ${ui.clockOffsetMs?.let { "%+d".format(it) } ?: "—"} ms  (RTT ${ui.clockRttMs ?: "—"} ms)")

            if (ui.receiverMode) {
                Text("Mode: RECEIVER — answering ${ui.receiverFrom ?: "unknown"}", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Status: ${ui.status}")
            }

            // Target phone (shows "Name(+1...)" when a contact is chosen)
            OutlinedTextField(
                value = targetDisplay,
                onValueChange = { newText ->
                    targetDisplay = newText
                    // Derive a clean phone for backend from whatever the user typed
                    val normalized = normalizePhone(newText)
                    vm.setTargetPhone(normalized)
                },
                label = { Text("Other user's phone number") },
                singleLine = true,
                enabled = inputsEnabled,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (inputsEnabled) {
                                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                                pickPhoneLauncher.launch(intent)
                            }
                        },
                        enabled = inputsEnabled
                    ) { Icon(Icons.Filled.Person, contentDescription = "Pick from contacts") }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // Send interval (ms)
            var intervalText by remember(ui.sendIntervalMs) { mutableStateOf(ui.sendIntervalMs.toString()) }
            OutlinedTextField(
                value = intervalText,
                onValueChange = { new ->
                    val digits = new.filter { it.isDigit() }
                    intervalText = digits
                    digits.toLongOrNull()?.let { vm.saveSendIntervalMs(ctx, it) }
                },
                enabled = inputsEnabled,
                singleLine = true,
                label = { Text("Send interval (ms)") },
                modifier = Modifier.fillMaxWidth()
            )

            // Desired message size (KB) — clamp only when focus leaves or IME Done
            var sizeKbText by remember(ui.desiredSizeKb) { mutableStateOf(ui.desiredSizeKb.toString()) }
            val focusManager = LocalFocusManager.current
            var hadFocus by remember { mutableStateOf(false) }

            fun commitSize() {
                val kb = sizeKbText.filter { it.isDigit() }.toLongOrNull() ?: 0L
                val clamped = kb.coerceIn(0L, 15L) // 0 = default; max 15 KB
                if (clamped.toString() != sizeKbText) sizeKbText = clamped.toString()
                vm.saveDesiredSizeKb(ctx, clamped)
            }

            OutlinedTextField(
                value = sizeKbText,
                onValueChange = { new ->
                    // allow free typing; just strip non-digits locally
                    sizeKbText = new.filter { it.isDigit() }
                },
                enabled = inputsEnabled,
                singleLine = true,
                label = { Text("Desired message size (KB)  •  0=default, max 15") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitSize()
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { f ->
                        if (!f.isFocused && hadFocus) {
                            // user left the field -> now clamp & save
                            commitSize()
                        }
                        hadFocus = f.isFocused
                    }
            )

            // Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val canConnect = !showStop && ui.targetPhone.trim().isNotEmpty()
                Button(
                    onClick = { vm.toggleConnect(ctx) },
                    enabled = if (showStop) true else canConnect,
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) { Text(if (showStop) "Stop" else "Connect") }

                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(ctx, AdminActivity::class.java)) },
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) { Text("Admin") }
            }

            Spacer(Modifier.height(8.dp))

            if (ui.receiverMode) {
                Text("A location", fontWeight = FontWeight.Bold)
                LocationDetails(ui.peerLocation)
            } else {
                Text("Delays (ms)", fontWeight = FontWeight.Bold)
                Text("App ➜ Server: ${ui.delayAtoServer?.let { "%.2f".format(it) } ?: "—"}")
                Text("Server ➜ App: ${ui.delayServerToA?.let { "%.2f".format(it) } ?: "—"}")
                Text("Server ➜ B: ${ui.delayServerToB?.let { "%.2f".format(it) } ?: "—"}")
                Text("B ➜ Server: ${ui.delayBtoServer?.let { "%.2f".format(it) } ?: "—"}")

                Spacer(Modifier.height(6.dp))
                Text("Round-trips (ms)", fontWeight = FontWeight.Bold)
                Text("App ↔ App: ${ui.appToAppRtt?.let { "%.2f".format(it) } ?: "—"}")
                Text("Server ↔ B ↔ Server: ${ui.serverRttB?.let { "%.2f".format(it) } ?: "—"}")

                Spacer(Modifier.height(8.dp))
                Text("B location", fontWeight = FontWeight.Bold)
                LocationDetails(ui.bLocation)

                Spacer(Modifier.height(8.dp))
                Text("Active CSV: ${ui.logFileName ?: "—"}")
            }
        }
    }
}

@Composable
private fun LocationDetails(loc: UserLocation?) {
    fun String?.orDash() = if (this.isNullOrBlank()) "—" else this
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("lat: ${loc?.latitude.orDash()}  lon: ${loc?.longitude.orDash()}")
        Text("alt: ${loc?.altitude.orDash()}  acc: ${loc?.accuracy.orDash()}")
        Text("speed: ${s(loc?.speed)}    speedAcc: ${s(loc?.speedAccuracy)}")
        Text("bearing: ${s(loc?.bearing)}  bearingAcc: ${s(loc?.bearingAccuracy)}")
    }
}

/**
 * Normalize any free-form input to “+<digits>”.
 * - Keeps digits only
 * - Accepts either "+153656536" or "153656536"
 * - If it starts with "00", converts that to "+"
 */
private fun normalizePhone(raw: String): String {
    val t = raw.trim()
    val digits = t.filter { it.isDigit() }
    if (digits.isEmpty()) return ""
    val payload = if (t.startsWith("00") && digits.length >= 2) digits.drop(2) else digits
    return "+$payload"
}
