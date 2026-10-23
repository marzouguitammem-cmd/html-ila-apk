package com.tammem.htmlilaapk

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** A Java/Android app built in the cloud. Kept so a rebuild installs as an update. */
data class CloudProject(
    val id: String,
    val packageName: String,
    val name: String = "",
    val versionCode: Int = 0,
    /** "zip" (full project) or "code" (single Java file). */
    val source: String = SOURCE_CODE,
    val permissions: Set<String> = setOf("INTERNET"),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastApk: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("packageName", packageName).put("name", name)
        .put("versionCode", versionCode).put("source", source)
        .put("permissions", permissions.joinToString(","))
        .put("updatedAt", updatedAt).put("lastApk", lastApk)

    companion object {
        const val SOURCE_CODE = "code"
        const val SOURCE_ZIP = "zip"
        fun fromJson(o: JSONObject) = CloudProject(
            id = o.getString("id"), packageName = o.getString("packageName"),
            name = o.optString("name"), versionCode = o.optInt("versionCode"),
            source = o.optString("source", SOURCE_CODE),
            permissions = o.optString("permissions", "INTERNET").split(",").filter { it.isNotBlank() }.toSet(),
            updatedAt = o.optLong("updatedAt"), lastApk = o.optString("lastApk"),
        )
    }
}

class CloudStore(context: Context) {
    private val root = File(context.filesDir, "cloud_projects")
    fun dir(id: String) = File(root, id)
    fun codeFile(id: String) = File(dir(id), "code.java")
    fun zipFile(id: String) = File(dir(id), "project.zip")
    private fun jsonFile(id: String) = File(dir(id), "project.json")

    fun newProject() = CloudProject(id = ProjectStore.randomId(10), packageName = "com.tammem.apps.a" + ProjectStore.randomId(9))

    fun list(): List<CloudProject> = (root.listFiles() ?: emptyArray())
        .mapNotNull { d -> runCatching { CloudProject.fromJson(JSONObject(File(d, "project.json").readText())) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun load(id: String) = runCatching { CloudProject.fromJson(JSONObject(jsonFile(id).readText())) }.getOrNull()

    fun save(p: CloudProject) {
        dir(p.id).mkdirs()
        val tmp = File(dir(p.id), "project.json.tmp")
        tmp.writeText(p.toJson().toString()); tmp.renameTo(jsonFile(p.id))
    }

    fun readCode(id: String) = runCatching { codeFile(id).readText() }.getOrDefault("")
    fun writeCode(id: String, code: String) { dir(id).mkdirs(); codeFile(id).writeText(code) }
    fun delete(id: String) { dir(id).deleteRecursively() }
}

/** Builds the job.json + file set the cloud service expects, from a CloudProject. */
object CloudJob {
    val ALL_PERMISSIONS = listOf(
        "INTERNET" to "الإنترنت",
        "NETWORK_STATE" to "حالة الشبكة",
        "VIDEOS" to "الفيديوهات",
        "IMAGES" to "التصاور",
        "AUDIO" to "الأغاني",
        "STORAGE_READ" to "قراءة الملفات",
        "STORAGE_WRITE" to "كتابة الملفات",
        "MANAGE_STORAGE" to "كل الملفات",
        "CAMERA" to "الكاميرا",
        "MICROPHONE" to "الميكرو",
        "LOCATION" to "الموقع",
        "VIBRATE" to "الاهتزاز",
        "NOTIFICATIONS" to "الإشعارات",
        "INSTALL_APPS" to "تثبيت تطبيقات",
    )

    private val KEY_TO_PERMISSION = mapOf(
        "INTERNET" to "android.permission.INTERNET",
        "NETWORK_STATE" to "android.permission.ACCESS_NETWORK_STATE",
        "VIDEOS" to "android.permission.READ_MEDIA_VIDEO",
        "IMAGES" to "android.permission.READ_MEDIA_IMAGES",
        "AUDIO" to "android.permission.READ_MEDIA_AUDIO",
        "STORAGE_READ" to "android.permission.READ_EXTERNAL_STORAGE",
        "STORAGE_WRITE" to "android.permission.WRITE_EXTERNAL_STORAGE",
        "MANAGE_STORAGE" to "android.permission.MANAGE_EXTERNAL_STORAGE",
        "CAMERA" to "android.permission.CAMERA",
        "MICROPHONE" to "android.permission.RECORD_AUDIO",
        "LOCATION" to "android.permission.ACCESS_FINE_LOCATION",
        "VIBRATE" to "android.permission.VIBRATE",
        "NOTIFICATIONS" to "android.permission.POST_NOTIFICATIONS",
        "INSTALL_APPS" to "android.permission.REQUEST_INSTALL_PACKAGES",
    )

    /** "VIDEOS" -> "android.permission.READ_MEDIA_VIDEO"; full names pass through. */
    fun fullPermission(key: String): String =
        KEY_TO_PERMISSION[key] ?: if (key.contains('.')) key else "android.permission.$key"

    /**
     * Permissions the code itself asks for (Manifest.permission.X or "android.permission.X"),
     * so they get declared automatically — the user doesn't need to know about manifests.
     */
    fun detectPermissions(code: String): Set<String> {
        val out = linkedSetOf<String>()
        Regex("""Manifest\.permission\.([A-Z][A-Z0-9_]+)""").findAll(code).forEach { out += "android.permission." + it.groupValues[1] }
        Regex(""""(android\.permission\.[A-Z][A-Z0-9_]+)"""").findAll(code).forEach { out += it.groupValues[1] }
        return out
    }

    /** Everything to declare: the ticked chips plus what the code asks for. */
    fun permissionsFor(p: CloudProject, code: String): List<String> =
        (p.permissions.map { fullPermission(it) } + detectPermissions(code)).distinct()

    fun packageOf(code: String): String? =
        Regex("""\bpackage\s+([\w.]+)\s*;""").find(code)?.groupValues?.get(1)

    fun mainActivityOf(code: String, pkg: String): String? {
        val m = Regex("""\bclass\s+(\w+)[^{]*\bextends\b[^{]*\bActivity\b""").find(code)
            ?: Regex("""\bclass\s+(\w+)""").find(code)
        val cls = m?.groupValues?.get(1) ?: return null
        return if (pkg.isNotBlank()) "$pkg.$cls" else cls
    }

    /** Returns the files to upload under jobs/<id>/. job.json is included. */
    fun filesFor(p: CloudProject, code: String, zipBytes: ByteArray?, versionCode: Int): Map<String, ByteArray> {
        val job = JSONObject()
            .put("appName", p.name.trim())
            .put("packageName", p.packageName)
            .put("versionCode", versionCode)
            .put("minSdk", 24).put("targetSdk", 34)
            .put("permissions", org.json.JSONArray(permissionsFor(p, if (p.source == CloudProject.SOURCE_ZIP) "" else code)))
        val out = LinkedHashMap<String, ByteArray>()
        if (p.source == CloudProject.SOURCE_ZIP) {
            job.put("type", "gradle")
            out["project.zip"] = zipBytes ?: throw IllegalArgumentException("اختار ملف ZIP للمشروع")
        } else {
            job.put("type", "single")
            val pkg = packageOf(code) ?: p.packageName
            val main = mainActivityOf(code, pkg) ?: throw IllegalArgumentException("ما لقيناش Activity في الكود")
            job.put("packageName", pkg)
            job.put("mainActivity", main)
            val cls = main.substringAfterLast('.')
            val path = (if (pkg.isNotBlank()) pkg.replace('.', '/') + "/" else "") + cls + ".java"
            job.put("files", JSONObject().put(path, code))
        }
        // job.json LAST so GithubBuilder uploads it after project.zip (it sorts job.json last anyway)
        out["job.json"] = job.toString().toByteArray(Charsets.UTF_8)
        return out
    }

    fun safeApkName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t ]"), "_").take(50).lowercase(Locale.ROOT).ifBlank { "app" } + ".apk"
}
