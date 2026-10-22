@file:OptIn(ExperimentalMaterial3Api::class)

package com.tammem.htmlilaapk

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.lifecycleScope
import com.tammem.htmlilaapk.engine.CodeCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var store: ProjectStore

    private var project by mutableStateOf(Project(id = "", packageName = ""))
    private var code by mutableStateOf("")
    private var zipFiles by mutableIntStateOf(0)
    private var iconVersion by mutableIntStateOf(0)
    private var building by mutableStateOf(false)
    private var step by mutableIntStateOf(0)
    private var result by mutableStateOf<Builder.Result?>(null)
    private var error by mutableStateOf<String?>(null)
    private var showProjects by mutableStateOf(false)
    private var showEditor by mutableStateOf(false)
    private var sharedCode by mutableStateOf<String?>(null)
    private var sharedZip by mutableStateOf<Uri?>(null)
    private var deleting by mutableStateOf<Project?>(null)

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setIconFrom(uri)
    }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openPicked(uri)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        val last = prefs().getString("current", null)?.let { store.load(it) }
        loadProject(last ?: store.newProject(), save = false)

        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    if (showEditor) CodeEditor() else MainScreen()
                    Dialogs()
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        persist()
        super.onStop()
    }

    private fun prefs() = getSharedPreferences("app", MODE_PRIVATE)

    private fun persist() {
        val p = project
        if (p.id.isEmpty() || p.id == SELFTEST_ID) return
        if (store.isEmptyDraft(p, code)) return
        store.save(p)
        if (p.source == Project.SOURCE_CODE) store.writeCode(p.id, code)
        prefs().edit().putString("current", p.id).apply()
    }

    private fun loadProject(p: Project, save: Boolean = true) {
        if (save) persist()
        project = p
        code = store.readCode(p.id)
        zipFiles = store.wwwDir(p.id).takeIf { p.source == Project.SOURCE_ZIP }
            ?.walkTopDown()?.count { it.isFile } ?: 0
        iconVersion++
        error = null
        result = p.lastApk.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
            ?.let { Builder.Result(it, p.versionCode, false) }
        prefs().edit().putString("current", p.id).apply()
    }

    private fun update(change: (Project) -> Project) {
        project = change(project).copy(updatedAt = System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ actions

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("selftest", false)) {
            runSelfTest()
            return
        }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null && isZip(stream, intent.type) -> sharedZip = stream
                    stream != null -> readSharedFile(stream)
                    !text.isNullOrBlank() -> receiveCode(text)
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { readSharedFile(it) }
        }
        intent.action = null
    }

    private fun isZip(uri: Uri, type: String?): Boolean =
        type?.contains("zip") == true || Builder.displayName(this, uri).lowercase(Locale.ROOT).endsWith(".zip")

    private fun readSharedFile(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { Builder.readText(this@MainActivity, uri) }.getOrNull() }
            if (text.isNullOrBlank()) toast("ما نجمتش نقرا الملف") else receiveCode(text)
        }
    }

    /** Code arriving from outside: straight into an empty draft, otherwise ask where it goes. */
    private fun receiveCode(raw: String) {
        if (store.isEmptyDraft(project, code)) applyCode(raw) else sharedCode = raw
    }

    private fun applyCode(raw: String) {
        val clean = CodeCleaner.extractCode(raw)
        code = clean
        update { it.copy(source = Project.SOURCE_CODE) }
        if (project.name.isBlank()) {
            titleOf(clean)?.let { t -> update { it.copy(name = t) } }
        }
        if (clean.length + 20 < raw.trim().length) toast("خذيت كان جزء الكود من الجواب ✂️")
        result = null
        error = null
    }

    private fun titleOf(html: String): String? =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(30)?.takeIf { it.isNotEmpty() }

    private fun paste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("ما فماش حتى كود منسوخ — انسخ الكود قبل")
            return
        }
        applyCode(text)
    }

    private fun openPicked(uri: Uri) {
        if (isZip(uri, contentResolver.getType(uri))) {
            importZip(uri)
        } else {
            readSharedFile(uri)
        }
    }

    private fun importZip(uri: Uri) {
        val p = project
        lifecycleScope.launch {
            try {
                val n = withContext(Dispatchers.IO) { Builder.importZip(this@MainActivity, uri, store.wwwDir(p.id)) }
                zipFiles = n
                update { it.copy(source = Project.SOURCE_ZIP) }
                if (project.name.isBlank()) {
                    val idx = File(store.wwwDir(p.id), "index.html")
                    titleOf(runCatching { idx.readText() }.getOrDefault(""))?.let { t -> update { it.copy(name = t) } }
                }
                result = null
                toast("✔ $n ملف")
            } catch (e: Exception) {
                toast(e.message ?: "ما نجمتش نحل الـ ZIP")
            }
        }
    }

    private fun setIconFrom(uri: Uri) {
        val p = project
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bmp = IconMaker.fromImage(this@MainActivity, uri)
                    store.dir(p.id).mkdirs()
                    store.iconFile(p.id).writeBytes(IconMaker.png(bmp))
                }
                update { it.copy(customIcon = true) }
                iconVersion++
            } catch (e: Exception) {
                toast("ما نجمتش نحل الصورة")
            }
        }
    }

    private fun preview() {
        val p = project
        when (p.source) {
            Project.SOURCE_URL -> {
                val url = Builder.normalizeUrl(p.url)
                if (url == null) toast("اكتب رابط صحيح") else PreviewActivity.open(this, null, url, p)
            }
            Project.SOURCE_ZIP -> {
                val www = store.wwwDir(p.id)
                if (File(www, "index.html").isFile) PreviewActivity.open(this, www, null, p) else toast("اختار ملف ZIP قبل")
            }
            else -> {
                val clean = CodeCleaner.extractCode(code)
                if (clean.isBlank()) {
                    toast("حط الكود قبل")
                    return
                }
                lifecycleScope.launch {
                    val www = withContext(Dispatchers.IO) {
                        val dir = store.wwwDir(p.id)
                        dir.deleteRecursively()
                        dir.mkdirs()
                        File(dir, "index.html").writeText(CodeCleaner.prepareHtml(clean))
                        dir
                    }
                    PreviewActivity.open(this@MainActivity, www, null, p)
                }
            }
        }
    }

    private fun build() {
        if (building) return
        val p = project
        error = null
        result = null
        building = true
        step = 1
        persist()
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    Builder.build(this@MainActivity, store, p, code) { s -> runOnUiThread { step = s } }
                }
                project = project.copy(versionCode = r.versionCode, lastApk = r.apk.absolutePath, updatedAt = System.currentTimeMillis())
                store.save(project)
                result = r
            } catch (e: Throwable) {
                Log.e("AppMaker", "build failed", e)
                error = if (e is IllegalArgumentException && e.message?.any { it in '؀'..'ۿ' } == true) {
                    e.message
                } else {
                    "صار مشكل وقت البناء:\n${e.javaClass.simpleName}: ${e.message}"
                }
            } finally {
                building = false
            }
        }
    }

    private fun newProject() {
        loadProject(store.newProject())
        showProjects = false
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Used by the build machine: makes an app on this phone without any tap. */
    private fun runSelfTest() {
        val dir = getExternalFilesDir(null) ?: filesDir
        File(dir, "selftest.done").delete()
        lifecycleScope.launch(Dispatchers.IO) {
            val log = StringBuilder()
            try {
                val p = Project(id = SELFTEST_ID, packageName = "com.tammem.apps.selftest", name = "اختبار ذاتي", colorIndex = 3)
                val answer = "Here it is:\n```html\n<!doctype html><html lang=\"ar\" dir=\"rtl\"><head><title>t</title></head>" +
                    "<body style=\"background:#311b92;color:#fff;text-align:center;font-family:sans-serif;padding-top:35vh\">" +
                    "<h1>✔ تبنى على التليفون</h1><p id=\"x\"></p>" +
                    "<script>document.getElementById('x').textContent='JS يخدم '+(6*7)</script></body></html>\n```\nEnjoy"
                val r = Builder.build(this@MainActivity, store, p, answer, saveCopy = true, outFile = File(dir, "selftest.apk"))
                log.append("OK size=").append(r.apk.length()).append(" downloads=").append(r.savedToDownloads)
            } catch (t: Throwable) {
                log.append("FAIL ").append(Log.getStackTraceString(t))
            }
            store.delete(SELFTEST_ID)
            File(dir, "selftest.log").writeText(log.toString())
            File(dir, "selftest.done").writeText("1")
        }
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun MainScreen() {
        val p = project
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("صانع التطبيقات", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { persist(); showProjects = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "تطبيقاتي")
                        }
                    },
                    actions = {
                        IconButton(onClick = { newProject() }) {
                            Icon(Icons.Default.Add, contentDescription = "تطبيق جديد")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Section("١", "اسم التطبيق") {
                    OutlinedTextField(
                        value = p.name,
                        onValueChange = { v -> update { it.copy(name = v.take(40)) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("مثلا: لعبة الحروف") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                }

                Section("٢", "الأيقونة") { IconPicker(p) }

                Section("٣", "المحتوى") { SourcePicker(p) }

                Section("٤", "الإعدادات") { Settings(p) }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { preview() },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("جرّب")
                    }
                    Button(
                        onClick = { build() },
                        enabled = !building,
                        modifier = Modifier.weight(1.7f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("حوّل إلى APK", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (building) Progress()
                error?.let { ErrorCard(it) }
                result?.let { ResultCard(it) }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun Section(number: String, title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(number, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                content()
            }
        }
    }

    @Composable
    private fun IconPicker(p: Project) {
        val letter = IconMaker.firstLetter(p.name)
        val icon: Bitmap = remember(p.id, p.customIcon, iconVersion, letter, p.colorIndex) {
            val custom = if (p.customIcon) store.iconFile(p.id).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } else null
            IconMaker.visiblePart(custom ?: IconMaker.auto(p.name, p.colorIndex))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("🖼️ صورة") }
                    if (p.customIcon) {
                        TextButton(onClick = { update { it.copy(customIcon = false) } }) { Text("حرف ولون") }
                    }
                }
                if (!p.customIcon) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconMaker.PALETTE.forEachIndexed { i, c ->
                            val selected = i == p.colorIndex
                            Box(
                                Modifier
                                    .size(if (selected) 26.dp else 22.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), CircleShape)
                                    .clickable { update { it.copy(colorIndex = i) } },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SourcePicker(p: Project) {
        val options = listOf(Project.SOURCE_CODE to "كود HTML", Project.SOURCE_ZIP to "ملف ZIP", Project.SOURCE_URL to "موقع")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = p.source == key,
                    onClick = { update { it.copy(source = key) }; result = null },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        when (p.source) {
            Project.SOURCE_CODE -> CodeSource()
            Project.SOURCE_ZIP -> ZipSource()
            else -> UrlSource(p)
        }
    }

    @Composable
    private fun CodeSource() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { paste() }, shape = RoundedCornerShape(14.dp)) { Text("📋 لصق الكود") }
            OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(14.dp)) { Text("📂 ملف") }
            OutlinedButton(onClick = { showEditor = true }, shape = RoundedCornerShape(14.dp)) { Text("✏️ تعديل") }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(150.dp).clickable { showEditor = true },
        ) {
            if (code.isBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "الكود يظهر هنا\nاضغط «📋 لصق الكود» بعد ما تنسخو",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        code.take(1600),
                        modifier = Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val kind = remember(code) { CodeCleaner.kind(code) }
        val (msg, ok) = when (kind) {
            CodeCleaner.HTML -> "✔ كود HTML • ${String.format(Locale.ROOT, "%,d", code.length)} حرف" to true
            CodeCleaner.REACT -> "⚠️ هذا كود React. اطلب من Claude: «اعطيني نفس الحاجة في ملف HTML واحد»" to false
            CodeCleaner.NOT_HTML -> "⚠️ هذا ما يشبهش لكود HTML" to false
            else -> "" to true
        }
        if (msg.isNotEmpty()) {
            Text(msg, color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error, fontSize = 14.sp)
        }
        Hint("💡 قول لـ Claude ولا Gemini: «اعملّي … في ملف HTML واحد فيه كل شي». انسخ الجواب كامل والصقو هنا، نخذ منو الكود وحدي. ولا من تطبيق Claude اضغط «مشاركة» واختار صانع التطبيقات.")
    }

    @Composable
    private fun ZipSource() {
        OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(14.dp)) {
            Text("📦 اختار ملف ZIP")
        }
        if (zipFiles > 0) {
            Text("✔ $zipFiles ملف، فيه index.html", color = MaterialTheme.colorScheme.secondary)
        }
        Hint("💡 للمشاريع اللي فيها برشا ملفات (صور، أصوات، CSS…). لازم يكون فيها index.html.")
    }

    @Composable
    private fun UrlSource(p: Project) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = p.url,
                onValueChange = { v -> update { it.copy(url = v.trim()) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
        }
        Hint("💡 التطبيق يحلّ الموقع هذا مباشرة، يلزمو إنترنت.")
    }

    @Composable
    private fun Hint(text: String) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(14.dp)) {
            Text(
                text,
                modifier = Modifier.padding(12.dp),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }

    @Composable
    private fun Settings(p: Project) {
        SwitchRow("ملء الشاشة", "يخبّي شريط الساعة والأزرار — باهي للألعاب", p.fullscreen) { v -> update { it.copy(fullscreen = v) } }
        HorizontalDivider()
        Text("اتجاه الشاشة", fontWeight = FontWeight.Medium)
        val options = listOf("auto" to "تلقائي", "portrait" to "واقف", "landscape" to "راقد")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = p.orientation == key,
                    onClick = { update { it.copy(orientation = key) } },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(label) }
            }
        }
        HorizontalDivider()
        SwitchRow("الشاشة ما تطفاش", "وقت التطبيق محلول", p.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
    }

    @Composable
    private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun Progress() {
        val label = when (step) {
            1 -> "نحضّر في الملفات…"
            2 -> "نجمّع في التطبيق…"
            3 -> "نوقّع في التطبيق…"
            else -> "نحفظ في Download…"
        }
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(label, fontWeight = FontWeight.Medium)
                LinearProgressIndicator(progress = { step / 4f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun ErrorCard(msg: String) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(msg, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }

    @Composable
    private fun ResultCard(r: Builder.Result) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("✅ التطبيق واجد!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val mb = String.format(Locale.ROOT, "%.1f", r.apk.length() / 1048576.0)
                Text("${r.apk.name} • $mb MB • نسخة ${r.versionCode}")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (!Builder.install(this@MainActivity, r.apk)) {
                                toast("فعّل «السماح من هذا المصدر» ورجع اضغط ثبّت")
                            }
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("ثبّت", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { Builder.share(this@MainActivity, r.apk) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.4f)),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("شارك")
                    }
                }
                val notes = buildList {
                    if (r.savedToDownloads) add("📁 تلقاه زادة في Download/${Builder.DOWNLOAD_FOLDER}")
                    if (r.versionCode > 1) add("🔁 نسخة جديدة: تتثبّت فوق القديمة وما تفسخش المعطيات")
                    add("🛡️ كان طلعلك تحذير Play Protect اضغط «تثبيت على أي حال»")
                }
                Text(notes.joinToString("\n"), fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }

    @Composable
    private fun CodeEditor() {
        var editor by remember { mutableStateOf<EditText?>(null) }
        val close = {
            editor?.let { e ->
                val text = e.text.toString()
                if (text != code) {
                    code = text
                    result = null
                }
            }
            showEditor = false
        }
        BackHandler { close() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showEditor = false }) { Icon(Icons.Default.Close, contentDescription = "إلغاء") }
                    Text("الكود", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editor?.setText("") }) { Text("مسح الكل") }
                    Button(onClick = { close() }, shape = RoundedCornerShape(12.dp)) { Text("تم") }
                }
                val fg = MaterialTheme.colorScheme.onSurface
                AndroidView(
                    factory = { ctx ->
                        EditText(ctx).apply {
                            setText(code)
                            typeface = Typeface.MONOSPACE
                            textSize = 13f
                            gravity = Gravity.TOP or Gravity.START
                            layoutDirection = View.LAYOUT_DIRECTION_LTR
                            textDirection = View.TEXT_DIRECTION_LTR
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            setHorizontallyScrolling(false)
                            background = null
                            setPadding(28, 16, 28, 16)
                            setTextColor(android.graphics.Color.argb(255, (fg.red * 255).toInt(), (fg.green * 255).toInt(), (fg.blue * 255).toInt()))
                            editor = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Composable
    private fun Dialogs() {
        if (showProjects) ProjectsSheet()

        sharedCode?.let { text ->
            AlertDialog(
                onDismissRequest = { sharedCode = null },
                title = { Text("وصلك كود جديد") },
                text = { Text("وين تحب تحطّو؟") },
                confirmButton = {
                    TextButton(onClick = {
                        sharedCode = null
                        loadProject(store.newProject())
                        applyCode(text)
                    }) { Text("تطبيق جديد") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        sharedCode = null
                        applyCode(text)
                    }) { Text("بدّل كود «${project.name.ifBlank { "الحالي" }}»") }
                },
            )
        }

        sharedZip?.let { uri ->
            AlertDialog(
                onDismissRequest = { sharedZip = null },
                title = { Text("وصلك ملف ZIP") },
                text = { Text("نعملو بيه تطبيق جديد؟") },
                confirmButton = {
                    TextButton(onClick = {
                        sharedZip = null
                        loadProject(store.newProject())
                        importZip(uri)
                    }) { Text("تطبيق جديد") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        sharedZip = null
                        importZip(uri)
                    }) { Text("في «${project.name.ifBlank { "الحالي" }}»") }
                },
            )
        }

        deleting?.let { d ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("نفسخ «${d.name.ifBlank { "بلا اسم" }}»؟") },
                text = { Text("يتفسخ من القائمة هنا برك. كان مثبّت في التليفون يقعد.") },
                confirmButton = {
                    TextButton(onClick = {
                        store.delete(d.id)
                        if (d.id == project.id) loadProject(store.newProject(), save = false)
                        deleting = null
                    }) { Text("افسخ", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("لا") } },
            )
        }
    }

    @Composable
    private fun ProjectsSheet() {
        val list = remember(showProjects, deleting) { store.list() }
        ModalBottomSheet(onDismissRequest = { showProjects = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("تطبيقاتي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { newProject() }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("جديد")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (list.isEmpty()) {
                    Text(
                        "مازال ما عملت حتى تطبيق",
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn {
                    items(list, key = { it.id }) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (item.id == project.id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable {
                                    loadProject(item)
                                    showProjects = false
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                                    .background(Color(IconMaker.PALETTE[Math.floorMod(item.colorIndex, IconMaker.PALETTE.size)])),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(IconMaker.firstLetter(item.name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name.ifBlank { "بلا اسم" }, fontWeight = FontWeight.Medium)
                                val when_ = DateUtils.getRelativeTimeSpanString(item.updatedAt).toString()
                                Text(
                                    (if (item.versionCode > 0) "نسخة ${item.versionCode}" else "مازال ما تبناش") + " • " + when_,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { deleting = item }) {
                                Icon(Icons.Default.Delete, contentDescription = "افسخ")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    companion object {
        private const val SELFTEST_ID = "selftest"
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0A2A73),
    secondary = Color(0xFF15803D),
    secondaryContainer = Color(0xFFD6F5E0),
    onSecondaryContainer = Color(0xFF0B3B1E),
    tertiaryContainer = Color(0xFFFFF4D6),
    onTertiaryContainer = Color(0xFF5A4300),
    background = Color(0xFFF6F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF1F8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB8FF),
    onPrimary = Color(0xFF0A2A73),
    primaryContainer = Color(0xFF213F8C),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF7EE2A0),
    secondaryContainer = Color(0xFF1C4A2D),
    onSecondaryContainer = Color(0xFFD6F5E0),
    tertiaryContainer = Color(0xFF4A3B12),
    onTertiaryContainer = Color(0xFFFFE8A8),
    background = Color(0xFF111318),
    surface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFF272B35),
)

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
