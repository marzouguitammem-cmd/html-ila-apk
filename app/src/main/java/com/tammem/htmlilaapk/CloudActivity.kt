@file:OptIn(ExperimentalMaterial3Api::class)

package com.tammem.htmlilaapk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CloudActivity : ComponentActivity() {

    private lateinit var store: CloudStore
    private lateinit var github: GithubBuilder

    private var project by mutableStateOf(CloudProject(id = "", packageName = ""))
    private var code by mutableStateOf("")
    private var zipName by mutableStateOf("")
    private var building by mutableStateOf(false)
    private var phase by mutableIntStateOf(0)
    private var resultApk by mutableStateOf<File?>(null)
    private var resultVersion by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var showConnect by mutableStateOf(false)
    private var connecting by mutableStateOf(false)
    private var loginState by mutableStateOf<String?>(null)
    private var offline by mutableStateOf(false)
    private var offlinePhase by mutableStateOf("")
    private val offlineAvailable by lazy { OfflineJava.isAvailable(this) }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importZip(uri)
    }
    private val pickCode = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) { runCatching { Builder.readText(this@CloudActivity, uri) }.getOrNull() }
            if (t != null) { code = t } else toast("ما نجمناش نقرا الملف")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = CloudStore(this)
        github = GithubBuilder(this)
        loginState = github.login
        val last = getSharedPreferences("cloud", MODE_PRIVATE).getString("current", null)?.let { store.load(it) }
        loadProject(last ?: store.newProject())
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Screen()
                    if (showConnect) ConnectDialog()
                }
            }
        }
    }

    override fun onStop() { persist(); super.onStop() }

    private fun persist() {
        val p = project
        if (p.id.isEmpty()) return
        if (p.name.isBlank() && code.isBlank() && p.versionCode == 0 && zipName.isBlank()) return
        store.save(p)
        if (p.source == CloudProject.SOURCE_CODE) store.writeCode(p.id, code)
        getSharedPreferences("cloud", MODE_PRIVATE).edit().putString("current", p.id).apply()
    }

    private fun loadProject(p: CloudProject) {
        project = p
        code = store.readCode(p.id)
        zipName = if (p.source == CloudProject.SOURCE_ZIP && store.zipFile(p.id).isFile) "مشروع.zip" else ""
        resultApk = p.lastApk.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
        error = null
        getSharedPreferences("cloud", MODE_PRIVATE).edit().putString("current", p.id).apply()
    }

    private fun update(change: (CloudProject) -> CloudProject) {
        project = change(project).copy(updatedAt = System.currentTimeMillis())
    }

    private fun importZip(uri: Uri) {
        val p = project
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.dir(p.id).mkdirs()
                    contentResolver.openInputStream(uri)?.use { input ->
                        store.zipFile(p.id).outputStream().use { input.copyTo(it) }
                    } ?: throw Exception("فارغ")
                }
                zipName = Builder.displayName(this@CloudActivity, uri).ifBlank { "مشروع.zip" }
                update { it.copy(source = CloudProject.SOURCE_ZIP) }
                resultApk = null
                toast("✔ المشروع واجد")
            } catch (e: Exception) {
                toast("ما نجمناش نقرا الـ ZIP")
            }
        }
    }

    private fun build() {
        if (building) return
        val p = project
        if (p.name.isBlank()) { toast("اكتب اسم التطبيق"); return }
        if (offline) { buildOffline(); return }
        if (!github.isConfigured) { showConnect = true; return }
        error = null; resultApk = null; building = true; phase = 0
        persist()
        lifecycleScope.launch {
            try {
                val version = p.versionCode + 1
                val zipBytes = if (p.source == CloudProject.SOURCE_ZIP)
                    withContext(Dispatchers.IO) { store.zipFile(p.id).takeIf { it.isFile }?.readBytes() } else null
                val files = CloudJob.filesFor(p, code, zipBytes, version)
                val jobId = "u" + ProjectStore.randomId(11)
                val out = File(File(filesDir, "cloud_out/${p.id}"), CloudJob.safeApkName(p.name))
                out.parentFile?.listFiles()?.forEach { it.delete() }
                val res = withContext(Dispatchers.IO) {
                    github.build(jobId, files, out) { ph -> runOnUiThread { phase = ph } }
                }
                if (!res.ok) {
                    error = "البناء فشل:\n" + tailLog(res.log)
                } else {
                    project = project.copy(versionCode = version, lastApk = out.absolutePath, updatedAt = System.currentTimeMillis())
                    store.save(project)
                    resultApk = out
                    resultVersion = res.versionName
                }
            } catch (e: Throwable) {
                error = (e.message ?: e.javaClass.simpleName)
            } finally {
                building = false
            }
        }
    }

    private fun buildOffline() {
        val p = project
        if (p.source != CloudProject.SOURCE_CODE) { toast("الأوفلاين يقبل كان كود Java"); return }
        if (!offlineAvailable) { error = "هالنسخة من التطبيق ما فيهاش أدوات البناء الأوفلاين. حدّث التطبيق."; return }
        error = null; resultApk = null; building = true; offlinePhase = "prepare"
        persist()
        lifecycleScope.launch {
            try {
                val version = p.versionCode + 1
                val icon = IconMaker.png(IconMaker.auto(p.name, 1))
                val out = File(File(filesDir, "offline_out/${p.id}"), CloudJob.safeApkName(p.name))
                val spec = OfflineJava.Spec(p.name, p.packageName, version, code, icon)
                val res = withContext(Dispatchers.IO) {
                    OfflineJava.build(this@CloudActivity, spec, out) { ph -> runOnUiThread { offlinePhase = ph } }
                }
                project = project.copy(versionCode = version, lastApk = res.apk.absolutePath, updatedAt = System.currentTimeMillis())
                store.save(project)
                resultApk = res.apk
                resultVersion = "1.$version"
            } catch (e: Throwable) {
                error = (e.message ?: e.javaClass.simpleName)
            } finally {
                building = false
            }
        }
    }

    private fun tailLog(log: String): String {
        val lines = log.trim().lines().filter { it.isNotBlank() }
        val interesting = lines.filter { it.contains("error", true) || it.contains("FAIL", true) || it.startsWith("e:") || it.contains("What went wrong") }
        return (if (interesting.isNotEmpty()) interesting else lines).takeLast(8).joinToString("\n").take(900)
    }

    private fun connect(token: String) {
        connecting = true
        lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { github.signIn(token) }
                loginState = name
                showConnect = false
                val hasRepo = withContext(Dispatchers.IO) { github.repoExists() }
                toast(if (hasRepo) "تربط بـ $name ✓" else "تربط بـ $name — أما repo «${github.repo}» ماهوش موجود")
            } catch (e: Exception) {
                toast(e.message ?: "ما نجمناش نربط")
            } finally {
                connecting = false
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun openUrl(u: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun Screen() {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("تطبيق Java", fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ModePicker()
                if (!offline) GithubCard()
                Section("١", "اسم التطبيق") {
                    OutlinedTextField(
                        value = project.name, onValueChange = { v -> update { it.copy(name = v.take(40)) } },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("مثلا: Ocean") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                }
                Section("٢", "المحتوى") { SourcePicker() }
                if (!offline) Section("٣", "الأذونات") { Permissions() }

                Button(
                    onClick = { build() }, enabled = !building,
                    modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.Build, null); Spacer(Modifier.width(8.dp))
                    Text(if (offline) "ابنِ على التليفون" else "ابنِ في السحاب", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }

                if (building) Progress()
                error?.let { ErrorCard(it) }
                resultApk?.let { ResultCard(it) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun ModePicker() {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val options = listOf(false to "☁️ سحابي", true to "📱 أوفلاين")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = offline == key,
                        onClick = {
                            offline = key
                            if (key) update { it.copy(source = CloudProject.SOURCE_CODE) }
                            resultApk = null; error = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }
            if (offline) {
                Hint(
                    if (offlineAvailable)
                        "📱 يبني على التليفون بلا إنترنت. يقبل كان Java بسيطة (الواجهة بالكود، بلا مكتبات خارجية وبلا موارد XML). أول بناء ياخذ شوية (يحضّر الأدوات)."
                    else
                        "⚠️ هالنسخة ما فيهاش أدوات البناء الأوفلاين. حدّث التطبيق لآخر نسخة."
                )
            }
        }
    }

    @Composable
    private fun GithubCard() {
        val login = loginState
        Surface(
            color = if (login != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (login != null) "☁️" else "🔗", fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (login != null) "مربوط بـ GitHub" else "اربط GitHub (مرة وحدة)", fontWeight = FontWeight.Bold)
                    Text(
                        login?.let { "@$it" } ?: "باش يبني في السحاب، فابور",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (login != null) {
                    TextButton(onClick = { github.signOut(); loginState = null }) { Text("افصل") }
                } else {
                    Button(onClick = { showConnect = true }, shape = RoundedCornerShape(12.dp)) { Text("اربط") }
                }
            }
        }
    }

    @Composable
    private fun Section(number: String, title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
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
    private fun SourcePicker() {
        // Offline builds simple single-file Java only; ZIP projects need the cloud.
        val options = if (offline) listOf(CloudProject.SOURCE_CODE to "كود Java")
            else listOf(CloudProject.SOURCE_CODE to "كود Java", CloudProject.SOURCE_ZIP to "مشروع ZIP")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = project.source == key,
                    onClick = { update { it.copy(source = key) }; resultApk = null },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label) }
            }
        }
        if (project.source == CloudProject.SOURCE_CODE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { paste() }, shape = RoundedCornerShape(14.dp)) { Text("📋 لصق الكود") }
                OutlinedButton(onClick = { pickCode.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(14.dp)) { Text("📂 ملف") }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(140.dp)) {
                if (code.isBlank()) {
                    Box(contentAlignment = Alignment.Center) { Text("الصق كود Java هنا (ملف واحد)", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(code.take(1400), modifier = Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Hint("💡 لملف Java واحد فيه Activity. للمشاريع الكاملة استعمل «مشروع ZIP».")
        } else {
            OutlinedButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }, shape = RoundedCornerShape(14.dp)) {
                Text("📦 اختار مشروع ZIP")
            }
            if (zipName.isNotBlank()) Text("✔ $zipName", color = MaterialTheme.colorScheme.secondary)
            Hint("💡 مشروع Android Studio كامل (فيه gradlew و settings.gradle). يتبنى كيف ما هو.")
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Permissions() {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudJob.ALL_PERMISSIONS.forEach { (key, label) ->
                val on = key in project.permissions
                FilterChip(
                    selected = on, onClick = {
                        update { it.copy(permissions = if (on) it.permissions - key else it.permissions + key) }
                    },
                    label = { Text(label) },
                    leadingIcon = if (on) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null,
                )
            }
        }
    }

    @Composable
    private fun Hint(text: String) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(14.dp)) {
            Text(text, Modifier.padding(12.dp), fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }

    @Composable
    private fun Progress() {
        val label = if (offline) when (offlinePhase) {
            "prepare" -> "نحضّر أدوات البناء… (أول مرة تاخذ شوية)"
            "compile" -> "نكمبايلي الكود (Java)…"
            "dex" -> "نحوّل لـ dex…"
            "pack" -> "نجمّع في التطبيق…"
            "sign" -> "نوقّع…"
            "save" -> "نحفظ…"
            else -> "نحضّر…"
        } else when (phase) {
            1 -> "نبعث المشروع لـ GitHub…"
            2 -> "GitHub يبني التطبيق… (3–5 دقايق، خلّي الإنترنت)"
            3 -> "نهبّط الـ APK…"
            else -> "نحضّر…"
        }
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(label, fontWeight = FontWeight.Medium)
                if (offline || phase == 2) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { (phase / 3f).coerceIn(0.05f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun ErrorCard(msg: String) {
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ما نجحش البناء", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    @Composable
    private fun ResultCard(apk: File) {
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("✅ التطبيق واجد!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${apk.name} • ${Builder.sizeLabel(apk.length())}" + (if (resultVersion.isNotBlank()) " • نسخة $resultVersion" else ""))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        when (Builder.install(this@CloudActivity, apk)) {
                            Builder.InstallResult.NEEDS_PERMISSION -> toast("فعّل «السماح من هذا المصدر» ورجع اضغط ثبّت")
                            Builder.InstallResult.FAILED -> toast("ما نجمتش نحلّ المثبّت — جرّب «شارك» ولا عاود ابني")
                            else -> {}
                        }
                    },
                        modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("ثبّت", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { if (!Builder.share(this@CloudActivity, apk)) toast("ما نجمتش نشارك الملف") },
                        modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.4f))) {
                        Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("شارك")
                    }
                }
            }
        }
    }

    @Composable
    private fun ConnectDialog() {
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!connecting) showConnect = false },
            title = { Text("اربط GitHub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("باش يبني في السحاب، نحتاجو «مفتاح» من حسابك في GitHub. فابور وياخذ دقيقة.", fontSize = 14.sp)
                    OutlinedButton(onClick = { openUrl(GithubBuilder.TOKEN_URL) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("١) حلّ صفحة المفتاح")
                    }
                    Text("٢) انزل لتحت واضغط «Generate token»، ومن بعد انسخ المفتاح والصقو هنا:", fontSize = 13.sp)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(value = token, onValueChange = { token = it.trim() }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, placeholder = { Text("ghp_…") })
                    }
                }
            },
            confirmButton = {
                Button(onClick = { connect(token) }, enabled = token.isNotBlank() && !connecting) {
                    Text(if (connecting) "نتثبّت…" else "اربط")
                }
            },
            dismissButton = { TextButton(onClick = { showConnect = false }, enabled = !connecting) { Text("بعد") } },
        )
    }

    private fun paste() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) { toast("ما فماش كود منسوخ"); return }
        code = CodeExtract.strip(t); resultApk = null
    }
}

/** Shared tiny helper so the cloud screen can clean pasted answers too. */
object CodeExtract {
    fun strip(text: String): String = com.tammem.htmlilaapk.engine.CodeCleaner.extractCode(text)
}
