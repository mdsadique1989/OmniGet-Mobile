package com.local.omnigetmobile

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class FormatChoice(val formatId: String, val label: String)
data class LinkPreview(val url: String, val title: String, val uploaderOrMime: String, val isExtractedStream: Boolean, val formats: List<FormatChoice>, val contentLength: Long = -1L)
enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }
data class DownloadTask(val id: String = UUID.randomUUID().toString(), val url: String, val title: String, val isExtractedStream: Boolean, val formatId: String = "best", val progressPercent: Int = 0, val statusLine: String = "Queued", val status: DownloadStatus = DownloadStatus.QUEUED, val cookieHeader: String? = null)
data class Flashcard(val id: Long, val sourceFile: String, val question: String, val answer: String)

class StudyDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "omniget_study.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE flashcards (id INTEGER PRIMARY KEY AUTOINCREMENT, sourceFile TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS flashcards")
        onCreate(db)
    }
    fun insertCard(sourceFile: String, question: String, answer: String) {
        writableDatabase.use { db ->
            val values = ContentValues().apply { put("sourceFile", sourceFile); put("question", question); put("answer", answer) }
            db.insert("flashcards", null, values)
        }
    }
    fun getAllCards(): List<Flashcard> {
        val list = mutableListOf<Flashcard>()
        readableDatabase.rawQuery("SELECT id, sourceFile, question, answer FROM flashcards ORDER BY id DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(Flashcard(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
            }
        }
        return list
    }
    fun deleteCard(id: Long) { writableDatabase.use { db -> db.delete("flashcards", "id = ?", arrayOf(id.toString())) } }
}

class MainActivity : ComponentActivity() {
    private val httpClient by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build() }
    private lateinit var studyDb: StudyDatabaseHelper
    private var engineReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        studyDb = StudyDatabaseHelper(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                Aria2c.getInstance().init(applicationContext)
                engineReady = true
            }
        }
        val sharedUrl = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT) ?: "" else ""
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OmniGetMainScreen(initialUrl = sharedUrl, engineReady = engineReady, httpClient = httpClient, studyDb = studyDb)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniGetMainScreen(initialUrl: String, engineReady: Boolean, httpClient: OkHttpClient, studyDb: StudyDatabaseHelper) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadsDir = remember { File(context.getExternalFilesDir(null), "Downloads").apply { mkdirs() } }

    var selectedTab by remember { mutableIntStateOf(0) }
    var urlInput by remember { mutableStateOf(initialUrl) }
    var cookieInput by remember { mutableStateOf("") }
    var showCookieField by remember { mutableStateOf(false) }
    var isInspecting by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LinkPreview?>(null) }
    var selectedFormatId by remember { mutableStateOf("best") }

    val downloadTasks = remember { mutableStateListOf<DownloadTask>() }
    val activeJobs = remember { mutableStateMapOf<String, Job>() }
    var savedFiles by remember { mutableStateOf(downloadsDir.listFiles()?.toList() ?: emptyList()) }
    var activeMediaFile by remember { mutableStateOf<File?>(null) }
    var activePdfFile by remember { mutableStateOf<File?>(null) }
    var flashcards by remember { mutableStateOf(studyDb.getAllCards()) }

    fun refreshFiles() { savedFiles = downloadsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }

    fun startOrResumeTask(task: DownloadTask) {
        activeJobs[task.id]?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            val idx = downloadTasks.indexOfFirst { it.id == task.id }
            if (idx != -1) downloadTasks[idx] = downloadTasks[idx].copy(status = DownloadStatus.DOWNLOADING, statusLine = "Starting download...")
            try {
                if (task.isExtractedStream && engineReady) {
                    val request = YoutubeDLRequest(task.url).apply {
                        addOption("-f", task.formatId)
                        addOption("-o", "${downloadsDir.absolutePath}/%(title).80s.%(ext)s")
                        addOption("--continue")
                        addOption("--no-playlist")
                        addOption("--downloader", "libaria2c.so")
                        if (!task.cookieHeader.isNullOrBlank()) addOption("--add-header", "Cookie:${task.cookieHeader}")
                    }
                    YoutubeDL.getInstance().execute(request, task.id) { progress, _, line ->
                        val cIdx = downloadTasks.indexOfFirst { it.id == task.id }
                        if (cIdx != -1) downloadTasks[cIdx] = downloadTasks[cIdx].copy(progressPercent = progress.toInt().coerceIn(0, 100), statusLine = line.take(65))
                    }
                } else {
                    val targetFile = File(downloadsDir, task.title)
                    val existingBytes = if (targetFile.exists()) targetFile.length() else 0L
                    val reqBuilder = Request.Builder().url(task.url)
                    if (existingBytes > 0) reqBuilder.addHeader("Range", "bytes=$existingBytes-")
                    if (!task.cookieHeader.isNullOrBlank()) reqBuilder.addHeader("Cookie", task.cookieHeader)
                    httpClient.newCall(reqBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful && response.code != 206) throw Exception("HTTP ${response.code}")
                        val isAppending = response.code == 206
                        val body = response.body ?: throw Exception("Empty body")
                        val total = body.contentLength() + if (isAppending) existingBytes else 0L
                        FileOutputStream(targetFile, isAppending).use { out ->
                            val buffer = ByteArray(16 * 1024)
                            var bytesRead: Int
                            var currentBytes = if (isAppending) existingBytes else 0L
                            val stream = body.byteStream()
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                ensureActive()
                                out.write(buffer, 0, bytesRead)
                                currentBytes += bytesRead
                                val pct = if (total > 0) ((currentBytes * 100) / total).toInt() else 0
                                val cIdx = downloadTasks.indexOfFirst { it.id == task.id }
                                if (cIdx != -1) downloadTasks[cIdx] = downloadTasks[cIdx].copy(progressPercent = pct.coerceIn(0, 100), statusLine = "${currentBytes / 1024} KB downloaded")
                            }
                        }
                    }
                }
                val doneIdx = downloadTasks.indexOfFirst { it.id == task.id }
                if (doneIdx != -1) downloadTasks[doneIdx] = downloadTasks[doneIdx].copy(status = DownloadStatus.COMPLETED, progressPercent = 100, statusLine = "Saved to Library")
                withContext(Dispatchers.Main) { refreshFiles() }
            } catch (e: CancellationException) {
                YoutubeDL.getInstance().destroyProcessById(task.id)
                val pIdx = downloadTasks.indexOfFirst { it.id == task.id }
                if (pIdx != -1) downloadTasks[pIdx] = downloadTasks[pIdx].copy(status = DownloadStatus.PAUSED, statusLine = "Paused")
            } catch (e: Exception) {
                val fIdx = downloadTasks.indexOfFirst { it.id == task.id }
                if (fIdx != -1) downloadTasks[fIdx] = downloadTasks[fIdx].copy(status = DownloadStatus.FAILED, statusLine = "Failed: ${e.localizedMessage?.take(50)}")
            }
        }
        activeJobs[task.id] = job
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OmniGet Mobile", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        statusBanner = "Checking for extractor updates..."
                        scope.launch(Dispatchers.IO) {
                            runCatching { YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE) }
                                .onSuccess { status -> withContext(Dispatchers.Main) { statusBanner = "Extractor: ${status?.name ?: "Up to date"}" } }
                                .onFailure { e -> withContext(Dispatchers.Main) { statusBanner = "Update failed: ${e.localizedMessage}" } }
                        }
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Update") }
                    IconButton(onClick = { showCookieField = !showCookieField }) { Icon(Icons.Default.VpnKey, contentDescription = "Cookie") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Downloader") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { refreshFiles(); selectedTab = 1 }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Library") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { flashcards = studyDb.getAllCards(); selectedTab = 2 }, icon = { Icon(Icons.Default.School, null) }, label = { Text("Flashcards") })
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            when (selectedTab) {
                0 -> {
                    OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, label = { Text("Paste Link (Media Stream, PDF, Magnet)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (showCookieField) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = cookieInput, onValueChange = { cookieInput = it }, label = { Text("Session Cookie (key=value)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = {
                        val cleanUrl = urlInput.trim()
                        if (cleanUrl.startsWith("magnet:?")) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl))) }
                            return@Button
                        }
                        isInspecting = true
                        statusBanner = null
                        preview = null
                        scope.launch(Dispatchers.IO) {
                            val lower = cleanUrl.substringBefore("?").lowercase()
                            val isDirect = lower.endsWith(".pdf") || lower.endsWith(".epub") || lower.endsWith(".zip") || lower.endsWith(".mp3")
                            if (!isDirect && engineReady) {
                                val res = runCatching {
                                    val req = YoutubeDLRequest(cleanUrl).apply { addOption("--dump-json"); addOption("--no-playlist"); if (cookieInput.isNotBlank()) addOption("--add-header", "Cookie:${cookieInput.trim()}") }
                                    val info = YoutubeDL.getInstance().getInfo(req)
                                    val formats = info.formats?.mapNotNull { f ->
                                        val h = f.height ?: 0; val ext = f.ext ?: "mp4"; val note = f.formatNote ?: ""
                                        val lbl = if (h > 0) "${h}p ($ext) $note".trim() else if (note.isNotBlank()) "$note ($ext)" else null
                                        if (lbl != null && f.formatId != null) FormatChoice(f.formatId!!, lbl) else null
                                    }?.distinctBy { it.label }?.takeLast(6)?.reversed() ?: emptyList()
                                    val choices = listOf(FormatChoice("best", "Best Available Video + Audio"), FormatChoice("bestaudio/best", "Audio Only")) + formats
                                    LinkPreview(cleanUrl, info.title ?: "Extracted Media", info.uploader ?: "Stream", true, choices)
                                }
                                if (res.isSuccess) {
                                    withContext(Dispatchers.Main) { preview = res.getOrNull(); selectedFormatId = "best"; isInspecting = false }
                                    return@launch
                                }
                            }
                            runCatching {
                                val req = Request.Builder().url(cleanUrl).head().apply { if (cookieInput.isNotBlank()) addHeader("Cookie", cookieInput.trim()) }.build()
                                httpClient.newCall(req).execute().use { resp ->
                                    val name = URLDecoder.decode(cleanUrl.substringAfterLast("/").substringBefore("?").ifBlank { "file.bin" }, "UTF-8")
                                    LinkPreview(cleanUrl, name, resp.header("Content-Type") ?: "File", false, listOf(FormatChoice("direct", "Direct Download")), resp.header("Content-Length")?.toLongOrNull() ?: -1L)
                                }
                            }.onSuccess { withContext(Dispatchers.Main) { preview = it; selectedFormatId = "direct"; isInspecting = false } }
                             .onFailure { withContext(Dispatchers.Main) { preview = LinkPreview(cleanUrl, cleanUrl.substringAfterLast("/").substringBefore("?").ifBlank { "media.mp4" }, "Direct Link", false, listOf(FormatChoice("direct", "Direct Download"))); isInspecting = false } }
                        }
                    }, enabled = urlInput.isNotBlank() && !isInspecting, modifier = Modifier.fillMaxWidth()) {
                        Text(if (isInspecting) "Inspecting..." else "Inspect Link & Pick Quality")
                    }
                    statusBanner?.let { Spacer(modifier = Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    preview?.let { item ->
                        Spacer(modifier = Modifier.height(12.dp))
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(item.uploaderOrMime, style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(8.dp))
                                if (item.formats.size > 1) {
                                    Text("Select Quality:", style = MaterialTheme.typography.labelLarge)
                                    LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                                        items(item.formats) { fmt ->
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selectedFormatId = fmt.formatId }) {
                                                RadioButton(selected = selectedFormatId == fmt.formatId, onClick = { selectedFormatId = fmt.formatId })
                                                Text(fmt.label, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = {
                                    val task = DownloadTask(url = item.url, title = item.title, isExtractedStream = item.isExtractedStream, formatId = selectedFormatId, cookieHeader = cookieInput.takeIf { it.isNotBlank() })
                                    downloadTasks.add(0, task)
                                    startOrResumeTask(task)
                                    preview = null
                                }, modifier = Modifier.fillMaxWidth()) { Text("Download Now") }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Download Queue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(downloadTasks, key = { it.id }) { task ->
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(task.title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                                        Text(task.status.name, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(progress = { (task.progressPercent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(task.statusLine, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                                        Row {
                                            if (task.status == DownloadStatus.DOWNLOADING) TextButton(onClick = { activeJobs[task.id]?.cancel() }) { Text("Pause") }
                                            if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) TextButton(onClick = { startOrResumeTask(task) }) { Text("Resume") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    activeMediaFile?.let { MediaPlayerDialog(file = it, onDismiss = { activeMediaFile = null }) }
                    activePdfFile?.let { PdfViewerDialog(file = it, onDismiss = { activePdfFile = null }) }
                    Text("Saved Files (${savedFiles.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (savedFiles.isEmpty()) Text("No files downloaded yet.", style = MaterialTheme.typography.bodyMedium)
                    else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(savedFiles) { file ->
                                ElevatedCard(modifier = Modifier.fillMaxWidth().clickable {
                                    val ext = file.extension.lowercase()
                                    when {
                                        ext == "pdf" -> activePdfFile = file
                                        ext in listOf("mp4", "mkv", "webm", "mp3", "m4a", "wav") -> activeMediaFile = file
                                    }
                                }) {
                                    Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(file.name, fontWeight = FontWeight.SemiBold)
                                            Text("${file.length() / 1024} KB • Tap to open viewer", style = MaterialTheme.typography.bodySmall)
                                        }
                                        IconButton(onClick = { file.delete(); refreshFiles() }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    var q by remember { mutableStateOf("") }
                    var a by remember { mutableStateOf("") }
                    val src = savedFiles.firstOrNull()?.name ?: "General Study"
                    Text("Create Flashcard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Question") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = a, onValueChange = { a = it }, label = { Text("Answer") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        if (q.isNotBlank() && a.isNotBlank()) {
                            studyDb.insertCard(src, q.trim(), a.trim())
                            flashcards = studyDb.getAllCards()
                            q = ""; a = ""
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save Card") }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Deck (${flashcards.size} cards)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(flashcards, key = { it.id }) { card ->
                            var showAns by remember { mutableStateOf(false) }
                            ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { showAns = !showAns }) {
                                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(if (showAns) "ANSWER" else "QUESTION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(if (showAns) card.answer else card.question, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    }
                                    IconButton(onClick = { studyDb.deleteCard(card.id); flashcards = studyDb.getAllCards() }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaPlayerDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }, title = { Text(file.name, maxLines = 1) }, text = {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } }, modifier = Modifier.fillMaxWidth().height(260.dp))
    })
}

@Composable
fun PdfViewerDialog(file: File, onDismiss: () -> Unit) {
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(1) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, pageIndex) {
        withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        pageCount = renderer.pageCount
                        val idx = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                        renderer.openPage(idx).use { page ->
                            val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pageBitmap = bmp
                        }
                    }
                }
            }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }, title = { Text("${file.name} (Page ${pageIndex + 1} of $pageCount)") }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            pageBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "PDF Page", modifier = Modifier.fillMaxWidth().height(360.dp)) }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (pageIndex > 0) pageIndex-- }, enabled = pageIndex > 0) { Text("Prev") }
                Button(onClick = { if (pageIndex < pageCount - 1) pageIndex++ }, enabled = pageIndex < pageCount - 1) { Text("Next") }
            }
        }
    })
}
