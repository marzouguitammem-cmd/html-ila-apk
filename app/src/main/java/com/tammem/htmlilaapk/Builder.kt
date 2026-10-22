package com.tammem.htmlilaapk

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tammem.htmlilaapk.engine.ApkMaker
import com.tammem.htmlilaapk.engine.CodeCleaner
import com.tammem.htmlilaapk.engine.SigningKeys
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Turns a [Project] into an installable APK, and moves APK files around. */
object Builder {

    const val DOWNLOAD_FOLDER = "AppMaker"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024

    data class Result(val apk: File, val versionCode: Int, val savedToDownloads: Boolean)

    /**
     * Builds the next version of [p]. [code] is the HTML for "code" projects.
     * [onStep] gets 1 = preparing, 2 = packing, 3 = signing, 4 = saving.
     */
    fun build(
        context: Context,
        store: ProjectStore,
        p: Project,
        code: String,
        saveCopy: Boolean = true,
        outFile: File? = null,
        onStep: (Int) -> Unit = {},
    ): Result {
        onStep(1)
        val name = p.name.trim()
        require(name.isNotEmpty()) { "اكتب اسم التطبيق" }

        val www: File? = when (p.source) {
            Project.SOURCE_CODE -> {
                val clean = CodeCleaner.extractCode(code)
                require(clean.isNotBlank()) { "حط الكود قبل" }
                val dir = store.wwwDir(p.id)
                dir.deleteRecursively()
                dir.mkdirs()
                File(dir, "index.html").writeText(CodeCleaner.prepareHtml(clean))
                dir
            }
            Project.SOURCE_ZIP -> {
                val dir = store.wwwDir(p.id)
                require(File(dir, "index.html").isFile) { "اختار ملف ZIP فيه index.html" }
                dir
            }
            else -> {
                require(normalizeUrl(p.url) != null) { "اكتب رابط الموقع صحيح (يبدا بـ https://)" }
                null
            }
        }

        val icon = if (p.customIcon && store.iconFile(p.id).isFile) {
            store.iconFile(p.id).readBytes()
        } else {
            IconMaker.png(IconMaker.auto(name, p.colorIndex))
        }

        val version = p.versionCode + 1
        val spec = ApkMaker.Spec().apply {
            label = name
            packageName = p.packageName
            versionCode = version
            versionName = "1.$version"
            configJson = configFor(p)
            iconPng = icon
            wwwDir = www
        }

        val template = template(context)
        val keys = SigningKeys.loadOrCreate(File(context.filesDir, "keys"), "AppMaker")
        val out = outFile ?: File(File(context.filesDir, "out/${p.id}"), safeFileName(name) + ".apk").also { f ->
            // Only this project's own folder: drop APKs left from an older name.
            f.parentFile?.listFiles()?.forEach { if (it != f) it.delete() }
        }
        out.parentFile?.mkdirs()
        ApkMaker.build(template, spec, keys, File(context.cacheDir, "build"), out) { step ->
            if (step == ApkMaker.STEP_PACK) onStep(2)
            if (step == ApkMaker.STEP_SIGN) onStep(3)
        }

        var saved = false
        if (saveCopy) {
            onStep(4)
            saved = saveToDownloads(context, out)
        }
        return Result(out, version, saved)
    }

    fun configFor(p: Project): String = JSONObject()
        .put("fullscreen", p.fullscreen)
        .put("orientation", p.orientation)
        .put("keepScreenOn", p.keepScreenOn)
        .put("startUrl", if (p.source == Project.SOURCE_URL) normalizeUrl(p.url) ?: "" else "")
        .put("bg", "#FFFFFF")
        .toString()

    fun normalizeUrl(raw: String): String? {
        var u = raw.trim()
        if (u.isEmpty()) return null
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        val parsed = Uri.parse(u)
        if (parsed.host.isNullOrBlank() || !parsed.host!!.contains('.')) return null
        return u
    }

    fun safeFileName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").take(60).ifBlank { "app" }

    /** template.apk lives inside our own APK; copy it out once per version of this app. */
    private fun template(context: Context): ApkMaker.Template {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        val dir = File(context.filesDir, "template")
        val file = File(dir, "template-$version.apk")
        if (!file.isFile) {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val tmp = File(dir, "copy.tmp")
            context.assets.open("template.apk").use { input ->
                FileOutputStream(tmp).use { input.copyTo(it) }
            }
            if (!tmp.renameTo(file)) throw IOException("template copy failed")
        }
        return context.assets.open("template_info.properties").use { ApkMaker.Template.load(file, it) }
    }

    // ------------------------------------------------------------------ files

    /** Saves a copy in Download/AppMaker so it can be found from any file manager. */
    fun saveToDownloads(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val folder = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER
            resolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(apk.name, "$folder%"),
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
                put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, folder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Opens Android's installer. Returns false if we first need the "unknown apps" permission. */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, apk), APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun share(context: Context, apk: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(APK_MIME)
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, apk))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, apk.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------ zip import

    /**
     * Unpacks a website ZIP into [dest]. If index.html sits in a sub-folder (common when a
     * folder was zipped), that folder becomes the root. Returns the number of files.
     */
    fun importZip(context: Context, uri: Uri, dest: File): Int {
        val tmp = File(context.cacheDir, "zip-import")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val base = tmp.canonicalPath + File.separator
        var total = 0L
        context.contentResolver.openInputStream(uri).use { raw ->
            ZipInputStream(raw ?: throw IOException("لا يمكن فتح الملف")).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.replace('\\', '/')
                    if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue
                    val f = File(tmp, name)
                    if (!(f.canonicalPath + if (e.isDirectory) File.separator else "").startsWith(base)) continue
                    if (e.isDirectory) {
                        f.mkdirs()
                        continue
                    }
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = zip.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_ZIP_BYTES) throw IOException("الملف كبير برشا")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
        }
        val root = findSiteRoot(tmp) ?: run {
            tmp.deleteRecursively()
            throw IOException("ما لقيتش index.html في الـ ZIP")
        }
        dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!root.renameTo(dest)) {
            root.copyRecursively(dest, overwrite = true)
        }
        tmp.deleteRecursively()
        return dest.walkTopDown().count { it.isFile }
    }

    /** The shallowest folder that contains index.html (or a single .html file). */
    private fun findSiteRoot(dir: File): File? {
        var level = listOf(dir)
        repeat(6) {
            for (d in level) {
                val files = d.listFiles() ?: continue
                files.firstOrNull { it.isFile && it.name.equals("index.html", true) }?.let { idx ->
                    if (idx.name != "index.html") idx.renameTo(File(d, "index.html"))
                    return d
                }
                val htmls = files.filter { it.isFile && (it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) }
                if (htmls.size == 1) {
                    htmls[0].renameTo(File(d, "index.html"))
                    return d
                }
            }
            level = level.flatMap { d -> (d.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name != "__MACOSX" } }
            if (level.isEmpty()) return null
        }
        return null
    }

    fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("لا يمكن فتح الملف")

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        }
        return uri.lastPathSegment ?: ""
    }
}
