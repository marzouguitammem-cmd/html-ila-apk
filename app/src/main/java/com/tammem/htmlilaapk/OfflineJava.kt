package com.tammem.htmlilaapk

import android.content.Context
import com.tammem.htmlilaapk.engine.ApkMaker
import com.tammem.htmlilaapk.engine.JavaCompiler
import com.tammem.htmlilaapk.engine.SigningKeys
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Builds a Java app into an APK entirely on the phone (no internet), for simple
 *  single-file Java (UI built in code, no external libraries, no XML resources). */
object OfflineJava {

    fun isAvailable(context: Context): Boolean = JavaCompiler(context).isAvailable()

    data class Spec(
        val appName: String,
        val packageName: String,
        val versionCode: Int,
        val code: String,
        val iconPng: ByteArray,
    )

    /** onPhase: "prepare" | "compile" | "dex" | "pack" | "sign" | "save". */
    fun build(context: Context, spec: Spec, outApk: File, onPhase: (String) -> Unit = {}): Builder.Result {
        require(spec.appName.isNotBlank()) { "اكتب اسم التطبيق" }
        val cleanCode = com.tammem.htmlilaapk.engine.CodeCleaner.extractCode(spec.code)
        require(cleanCode.isNotBlank()) { "حط الكود قبل" }
        val pkg = CloudJob.packageOf(cleanCode) ?: spec.packageName
        val activity = CloudJob.mainActivityOf(cleanCode, pkg)
            ?: throw IOException("ما لقيناش Activity في الكود")
        val className = activity.substringAfterLast('.')
        val relPath = (if (pkg.isNotBlank()) pkg.replace('.', '/') + "/" else "") + className + ".java"

        val compiler = JavaCompiler(context)
        if (!compiler.isAvailable()) throw IOException("هالنسخة من التطبيق ما فيهاش أدوات البناء الأوفلاين")

        val dex = compiler.compileToDex(linkedMapOf(relPath to cleanCode)) { step -> onPhase(step) }

        onPhase("pack")
        val template = template(context)
        val maker = ApkMaker.Spec().apply {
            label = spec.appName.trim()
            packageName = spec.packageName
            versionCode = spec.versionCode
            versionName = "1.${spec.versionCode}"
            iconPng = spec.iconPng
            replacementDex = dex
            mainActivity = activity
        }
        val keys = SigningKeys.loadOrCreate(File(context.filesDir, "keys"), "AppMaker")
        outApk.parentFile?.let { dir -> dir.mkdirs(); dir.listFiles()?.forEach { if (it != outApk) it.delete() } }
        ApkMaker.build(template, maker, keys, File(context.cacheDir, "jpack"), outApk) { step ->
            if (step == ApkMaker.STEP_SIGN) onPhase("sign")
        }

        onPhase("save")
        val saved = Builder.saveToDownloads(context, outApk)
        return Builder.Result(outApk, spec.versionCode, saved)
    }

    /** Copies java_template.apk out of our assets once per app version. */
    private fun template(context: Context): ApkMaker.Template {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        val dir = File(context.filesDir, "jtemplate")
        val file = File(dir, "java-$version.apk")
        if (!file.isFile) {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val tmp = File(dir, "copy.tmp")
            context.assets.open("java_template.apk").use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            if (!tmp.renameTo(file)) throw IOException("java template copy failed")
        }
        return context.assets.open("java_template_info.properties").use { ApkMaker.Template.load(file, it) }
    }
}
