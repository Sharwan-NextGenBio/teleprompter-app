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
import androidx.documentfile.provider.DocumentFile

private val AppBg = Color(0xFF08090D)
private val Panel = Color(0xFF13151D)
private val PanelRaised = Color(0xFF1A1E29)
private val Accent = Color(0xFF00E5D4)
private val MutedText = Color(0xFFA7ADBB)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = darkColorScheme(
                primary = Accent,
                onPrimary = Color(0xFF00201D),
                secondary = Color(0xFF67DAD0),
                background = AppBg,
                surface = Panel,
                surfaceVariant = PanelRaised,
                onBackground = Color(0xFFF3F4F6),
                onSurface = Color(0xFFF3F4F6),
                onSurfaceVariant = MutedText
            )
            MaterialTheme(colorScheme = colors) { Surface(color = AppBg) { AppRoot() } }
        }
    }

    /** Bluetooth keyboard / presentation clicker mapping while this app is in front. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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
private fun AppRoot() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = TeleprompterSession
    val dao = remember(ctx) { AppDatabase.get(ctx).scriptDao() }
    val savedScripts by dao.observeAll().collectAsState(initial = emptyList())
    val text by session.text.collectAsState()
    val voiceFollow by session.voiceFollow.collectAsState()
    var studio by remember { mutableStateOf(false) }
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
                imported.forEach { (title, body) -> dao.upsert(ScriptEntity(title = title, body = body)) }
                imported.firstOrNull()?.let { session.text.value = it.second }
                Toast.makeText(ctx, "Imported ${imported.size} script(s) from the folder.", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(ctx, "Could not read this folder: ${it.localizedMessage ?: "access denied"}", Toast.LENGTH_LONG).show() }
        } }
    }

    if (studio) {
        RecordingScreen(onBack = { studio = false })
        return
    }

    Scaffold(
        containerColor = AppBg,
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
                    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) studio = true else studioPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
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
                    Text("CREATOR STUDIO", color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
                Surface(color = Color(0xFF12332F), shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Accent))
                        Text("READY", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Your next take starts here", fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                Text("Add your script, then choose how you want to record.", color = MutedText, fontSize = 14.sp)
            }

            Card(
                modifier = Modifier.fillMaxWidth().heightIn(min = 330.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF292D39))
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Description, null, tint = Accent, modifier = Modifier.size(18.dp))
                        Text("YOUR SCRIPT", color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${text.trim().split(Regex("\\s+")).count { it.isNotBlank() }} words", color = MutedText, fontSize = 12.sp)
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
                            unfocusedBorderColor = Color(0xFF303441),
                            focusedContainerColor = Color(0xFF0D0F15),
                            unfocusedContainerColor = Color(0xFF0D0F15),
                            cursorColor = Accent
                        )
                    )
                    Text("Your script stays on this device.", color = Color(0xFF777E8D), fontSize = 12.sp)
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF292D39))
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
                        Text("Scroll with your speech", color = MutedText, fontSize = 12.sp)
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
                Surface(color = PanelRaised, shape = CircleShape) {
                    Text("${savedScripts.size}", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = MutedText, fontSize = 11.sp)
                }
            }
            if (savedScripts.isEmpty()) {
                Text("Imported and saved scripts will appear here on this device.", color = MutedText, fontSize = 13.sp)
            } else {
                savedScripts.forEach { script ->
                    Card(
                        onClick = { activeScriptId = script.id; session.text.value = script.body },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp),
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF292D39))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(script.title, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                Text("${script.body.trim().split(Regex("\\s+")).count { it.isNotBlank() }} words", color = MutedText, fontSize = 11.sp)
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
                    scope.launch { dao.delete(script); if (activeScriptId == script.id) activeScriptId = null }
                    scriptToDelete = null
                }) { Text("Delete", color = Color(0xFFE87979)) }
            },
            dismissButton = { TextButton(onClick = { scriptToDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HomeActions(onImport: () -> Unit, onFolder: () -> Unit, onOverlay: () -> Unit, onStudio: () -> Unit) {
    Surface(color = AppBg, shadowElevation = 16.dp) {
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
