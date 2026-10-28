package com.example.teleprompter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoCameraFront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.teleprompter.data.ScriptImporter
import com.example.teleprompter.data.AppDatabase
import com.example.teleprompter.data.ScriptEntity
import com.example.teleprompter.engine.TeleprompterSession
import com.example.teleprompter.overlay.OverlayService
import com.example.teleprompter.ui.RecordingScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import androidx.documentfile.provider.DocumentFile

private val Accent = Color(0xFF00E5D4)
private const val PREFS_NAME = "prompterpal_settings"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        TeleprompterSession.wpm.value = savedPrefs.getInt("wpm", 140)
        TeleprompterSession.style.update { current ->
            current.copy(
                fontSizeSp = savedPrefs.getFloat("font_size", current.fontSizeSp),
                highlightColor = savedPrefs.getLong("highlight_color", current.highlightColor),
                neonHighlight = savedPrefs.getBoolean("neon_highlight", current.neonHighlight)
            )
        }
        setContent {
            var darkTheme by remember {
                mutableStateOf(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("dark_theme", true))
            }
            val colors = if (darkTheme) darkColorScheme(
                primary = Accent, onPrimary = Color(0xFF00201D), secondary = Color(0xFF67DAD0),
                background = Color(0xFF08090D), surface = Color(0xFF13151D),
                surfaceVariant = Color(0xFF1A1E29), onBackground = Color(0xFFF3F4F6),
                onSurface = Color(0xFFF3F4F6), onSurfaceVariant = Color(0xFFA7ADBB)
            ) else lightColorScheme(
                primary = Color(0xFF00796F), onPrimary = Color.White, secondary = Color(0xFF006B62),
                background = Color(0xFFF4F6F8), surface = Color.White,
                surfaceVariant = Color(0xFFE6EAEE), onBackground = Color(0xFF15191D),
                onSurface = Color(0xFF15191D), onSurfaceVariant = Color(0xFF5F6872)
            )
            MaterialTheme(colorScheme = colors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot(darkTheme = darkTheme, onDarkThemeChange = { enabled ->
                        darkTheme = enabled
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("dark_theme", enabled).apply()
                    })
                }
            }
        }
    }

    /** Bluetooth keyboard / presentation clicker mapping while this app is in front. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("remote_enabled", true)) {
            return super.onKeyDown(keyCode, event)
        }
        val s = TeleprompterSession
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> s.togglePlay()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> s.adjustWpm(+10)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> s.adjustWpm(-10)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}

@Composable
private fun AppRoot(darkTheme: Boolean, onDarkThemeChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val appPrefs = remember(ctx) { ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val session = TeleprompterSession
    val dao = remember(ctx) { AppDatabase.get(ctx).scriptDao() }
    val savedScripts by dao.observeAll().collectAsState(initial = emptyList())
    val text by session.text.collectAsState()
    val voiceFollow by session.voiceFollow.collectAsState()
    val style by session.style.collectAsState()
    val wpm by session.wpm.collectAsState()
    var studio by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var cameraEnabled by remember { mutableStateOf(appPrefs.getBoolean("camera_enabled", true)) }
    var remoteEnabled by remember { mutableStateOf(appPrefs.getBoolean("remote_enabled", true)) }

    LaunchedEffect(wpm, style.fontSizeSp, style.highlightColor, style.neonHighlight) {
        appPrefs.edit()
            .putInt("wpm", wpm)
            .putFloat("font_size", style.fontSizeSp)
            .putLong("highlight_color", style.highlightColor)
            .putBoolean("neon_highlight", style.neonHighlight)
            .apply()
    }

    LaunchedEffect(text, activeScriptId) {
        if (text.isBlank()) return@LaunchedEffect
        delay(700)
        val currentId = activeScriptId
        val existing = currentId?.let { dao.get(it) }
        val id = dao.upsert(
            ScriptEntity(
                id = currentId ?: 0L,
                title = existing?.title ?: "Untitled script",
                body = text,
                folder = existing?.folder.orEmpty(),
                updatedAt = System.currentTimeMillis()
            )
        )
        if (currentId == null && id > 0) activeScriptId = id
    }
    var activeScriptId by remember { mutableStateOf<Long?>(null) }
    var saveDialog by remember { mutableStateOf(false) }
    var saveTitle by remember { mutableStateOf("") }
    var scriptToDelete by remember { mutableStateOf<ScriptEntity?>(null) }

    val studioPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true && result[Manifest.permission.RECORD_AUDIO] == true) {
            studio = true
        } else {
            Toast.makeText(ctx, "Camera and microphone access are needed for Studio recording.", Toast.LENGTH_LONG).show()
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            session.voiceFollow.value = true
        } else {
            session.voiceFollow.value = false
            Toast.makeText(ctx, "Microphone access is needed for Voice Follow.", Toast.LENGTH_SHORT).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selected -> scope.launch {
            runCatching {
                val importer = ScriptImporter(ctx)
                val body = importer.import(selected)
                val title = importer.displayName(selected).substringBeforeLast('.', importer.displayName(selected)).ifBlank { "Imported script" }
                val id = dao.upsert(ScriptEntity(title = title, body = body))
                activeScriptId = id.takeIf { it > 0 }
                session.text.value = body
                Toast.makeText(ctx, "Saved '$title' to your scripts.", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(ctx, "Could not import this file: ${it.localizedMessage ?: "unsupported format"}", Toast.LENGTH_LONG).show() }
        } }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        treeUri?.let { selected -> scope.launch {
            runCatching {
                runCatching { ctx.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val imported = readScriptsFromTree(ctx, selected)
                var firstId: Long? = null
                imported.forEach { (title, body) ->
                    val id = dao.upsert(ScriptEntity(title = title, body = body))
                    if (firstId == null) firstId = id.takeIf { it > 0 }
                }
                if (imported.isNotEmpty()) {
                    activeScriptId = firstId
                    session.text.value = imported.first().second
                }
                Toast.makeText(ctx, "Imported ${imported.size} script(s) from the folder.", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(ctx, "Could not read this folder: ${it.localizedMessage ?: "access denied"}", Toast.LENGTH_LONG).show() }
        } }
    }

    if (studio) {
        RecordingScreen(onBack = { studio = false }, cameraEnabled = cameraEnabled)
        return
    }

    if (showSettings) {
        SettingsScreen(
            darkTheme = darkTheme,
            onDarkThemeChange = onDarkThemeChange,
            cameraEnabled = cameraEnabled,
            onCameraEnabledChange = { enabled ->
                cameraEnabled = enabled
                appPrefs.edit().putBoolean("camera_enabled", enabled).apply()
            },
            remoteEnabled = remoteEnabled,
            onRemoteEnabledChange = { enabled ->
                remoteEnabled = enabled
                appPrefs.edit().putBoolean("remote_enabled", enabled).apply()
            },
            onBack = { showSettings = false }
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            HomeActions(
                onImport = { picker.launch("*/*") },
                onFolder = { folderPicker.launch(null) },
                onOverlay = {
                    if (!Settings.canDrawOverlays(ctx)) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    } else {
                        OverlayService.start(ctx)
                    }
                },
                onStudio = {
                    if (!cameraEnabled) {
                        studio = true
                    } else {
                        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (granted) studio = true else studioPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VideoCameraFront, null, tint = Color(0xFF00201D), modifier = Modifier.size(25.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("PrompterPal", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp)
                    Text("CREATOR STUDIO", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Surface(color = Color(0xFF12332F), shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Accent))
                        Text("READY", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Your next take starts here", fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                Text("Add your script, then choose how you want to record.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }

            Card(
                modifier = Modifier.fillMaxWidth().heightIn(min = 330.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Description, null, tint = Accent, modifier = Modifier.size(18.dp))
                        Text("YOUR SCRIPT", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${text.trim().split(Regex("\\s+")).count { it.isNotBlank() }} words", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        TextButton(onClick = {
                            saveTitle = savedScripts.firstOrNull { it.id == activeScriptId }?.title ?: ""
                            saveDialog = true
                        }, enabled = text.isNotBlank()) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Save")
                        }
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { session.text.value = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 245.dp),
                        placeholder = { Text("Paste or type your script here…\n\nUse ==double equals== to highlight key phrases.", color = Color(0xFF737987), lineHeight = 25.sp) },
                        textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent.copy(alpha = 0.8f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            cursorColor = Accent
                        )
                    )
                    Text("Your script stays on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF12332F)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Mic, null, tint = Accent, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Voice Follow", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Scroll with your speech", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(
                        checked = voiceFollow,
                        onCheckedChange = { enabled ->
                            if (enabled && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            } else session.voiceFollow.value = enabled
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00201D), checkedTrackColor = Accent)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved scripts", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
                    Text("${savedScripts.size}", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            if (savedScripts.isEmpty()) {
                Text("Imported and saved scripts will appear here on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                savedScripts.forEach { script ->
                    Card(
                        onClick = { activeScriptId = script.id; session.text.value = script.body },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(script.title, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                Text("${script.body.trim().split(Regex("\\s+")).count { it.isNotBlank() }} words", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            IconButton(onClick = { scriptToDelete = script }) { Icon(Icons.Default.Delete, "Delete ${script.title}", tint = Color(0xFFE87979)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (saveDialog) {
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save script") },
            text = {
                OutlinedTextField(value = saveTitle, onValueChange = { saveTitle = it }, label = { Text("Script name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = saveTitle.trim().ifBlank { "Untitled script" }
                    scope.launch {
                        val currentId = activeScriptId
                        val existing = currentId?.let { dao.get(it) }
                        val id = dao.upsert(ScriptEntity(id = currentId ?: 0L, title = title, body = session.text.value, folder = existing?.folder.orEmpty()))
                        activeScriptId = currentId ?: id.takeIf { it > 0 }
                    }
                    saveDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } }
        )
    }
    scriptToDelete?.let { script ->
        AlertDialog(
            onDismissRequest = { scriptToDelete = null },
            title = { Text("Delete script?") },
            text = { Text("'${script.title}' will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        dao.delete(script)
                        if (activeScriptId == script.id) {
                            activeScriptId = null
                            session.text.value = ""
                        }
                    }
                    scriptToDelete = null
                }) { Text("Delete", color = Color(0xFFE87979)) }
            },
            dismissButton = { TextButton(onClick = { scriptToDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HomeActions(onImport: () -> Unit, onFolder: () -> Unit, onOverlay: () -> Unit, onStudio: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 16.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 12.dp)) {
                Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text("File", maxLines = 1, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onFolder, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 12.dp)) {
                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text("Folder", maxLines = 1, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onOverlay, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 12.dp)) {
                Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text("Overlay", maxLines = 1, fontSize = 12.sp)
            }
            Button(onClick = onStudio, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 12.dp)) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(2.dp)); Text("Studio", maxLines = 1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    cameraEnabled: Boolean,
    onCameraEnabledChange: (Boolean) -> Unit,
    remoteEnabled: Boolean,
    onRemoteEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val session = TeleprompterSession
    val style by session.style.collectAsState()
    val wpm by session.wpm.collectAsState()
    val colors = listOf(
        Color(0xFF00E5D4), Color(0xFFFFD740), Color(0xFFFF4FD8), Color(0xFFB2FF59), Color(0xFF64B5FF)
    )

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Back") }
            Spacer(Modifier.width(8.dp))
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text("Make the prompter work the way you create.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        SettingsToggleCard("Camera in Studio", "Turn off to use a text-only prompter screen.", cameraEnabled, onCameraEnabledChange)
        SettingsToggleCard("Bluetooth remote controls", "Use presenter keys: play/pause and faster/slower.", remoteEnabled, onRemoteEnabledChange)
        SettingsToggleCard("Dark appearance", "Use a dark creator-studio interface.", darkTheme, onDarkThemeChange)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scroll speed", fontWeight = FontWeight.SemiBold)
                Text("$wpm words per minute", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Slider(value = wpm.toFloat(), onValueChange = { session.wpm.value = it.toInt() }, valueRange = 40f..400f)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("40 WPM", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text("400 WPM", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text size", fontWeight = FontWeight.SemiBold)
                Text("${style.fontSizeSp.toInt()} sp", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Slider(value = style.fontSizeSp, onValueChange = { size -> session.style.update { it.copy(fontSizeSp = size) } }, valueRange = 20f..56f)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Highlight color", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    colors.forEach { color ->
                        val selected = style.highlightColor == color.value.toLong()
                        Surface(
                            onClick = { session.style.update { it.copy(highlightColor = color.value.toLong()) } },
                            modifier = Modifier.size(if (selected) 42.dp else 36.dp),
                            shape = CircleShape,
                            color = color,
                            border = if (selected) androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null
                        ) {}
                    }
                }
                SettingsToggleCard("Neon highlight", "Use a brighter, bolder reading cue.", style.neonHighlight) {
                    session.style.update { it.copy(neonHighlight = it.neonHighlight.not()) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsToggleCard(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private suspend fun readScriptsFromTree(context: android.content.Context, treeUri: Uri): List<Pair<String, String>> =
    withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val importer = ScriptImporter(context)
        val imported = mutableListOf<Pair<String, String>>()

        suspend fun visit(directory: DocumentFile) {
            directory.listFiles().forEach { entry ->
                if (entry.isDirectory) {
                    visit(entry)
                } else if (entry.name?.substringAfterLast('.', "")?.lowercase() in setOf("txt", "pdf", "docx")) {
                    val body = runCatching { importer.import(entry.uri) }.getOrNull()
                    if (!body.isNullOrBlank()) {
                        val title = entry.name?.substringBeforeLast('.')?.ifBlank { "Imported script" } ?: "Imported script"
                        imported += title to body
                    }
                }
            }
        }

        visit(root)
        imported
    }
 
