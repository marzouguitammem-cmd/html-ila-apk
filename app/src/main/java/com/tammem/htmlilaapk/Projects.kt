package com.tammem.htmlilaapk

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** One app the user is making. Saved so a rebuild installs as an update of the same app. */
data class Project(
    val id: String,
    val packageName: String,
    val name: String = "",
    /** Last version built (0 = never built). */
    val versionCode: Int = 0,
    /** "code", "zip" or "url". */
    val source: String = SOURCE_CODE,
    val url: String = "",
    val fullscreen: Boolean = false,
    /** "auto", "portrait" or "landscape". */
    val orientation: String = "auto",
    val keepScreenOn: Boolean = false,
    val customIcon: Boolean = false,
    val colorIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val lastApk: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("packageName", packageName)
        .put("name", name)
        .put("versionCode", versionCode)
        .put("source", source)
        .put("url", url)
        .put("fullscreen", fullscreen)
        .put("orientation", orientation)
        .put("keepScreenOn", keepScreenOn)
        .put("customIcon", customIcon)
        .put("colorIndex", colorIndex)
        .put("updatedAt", updatedAt)
        .put("lastApk", lastApk)

    companion object {
        const val SOURCE_CODE = "code"
        const val SOURCE_ZIP = "zip"
        const val SOURCE_URL = "url"

        fun fromJson(o: JSONObject) = Project(
            id = o.getString("id"),
            packageName = o.getString("packageName"),
            name = o.optString("name"),
            versionCode = o.optInt("versionCode"),
            source = o.optString("source", SOURCE_CODE),
            url = o.optString("url"),
            fullscreen = o.optBoolean("fullscreen"),
            orientation = o.optString("orientation", "auto"),
            keepScreenOn = o.optBoolean("keepScreenOn"),
            customIcon = o.optBoolean("customIcon"),
            colorIndex = o.optInt("colorIndex"),
            updatedAt = o.optLong("updatedAt"),
            lastApk = o.optString("lastApk"),
        )
    }
}

class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects")

    fun dir(id: String) = File(root, id)
    fun wwwDir(id: String) = File(dir(id), "www")
    fun iconFile(id: String) = File(dir(id), "icon.png")
    private fun codeFile(id: String) = File(dir(id), "code.html")
    private fun jsonFile(id: String) = File(dir(id), "project.json")

    fun newProject(colorIndex: Int = random.nextInt(IconMaker.PALETTE.size)): Project {
        val id = randomId(10)
        // A fixed, unique package per project: rebuilding it updates the same installed app.
        return Project(id = id, packageName = "com.tammem.apps.a" + randomId(9), colorIndex = colorIndex)
    }

    fun list(): List<Project> =
        (root.listFiles() ?: emptyArray())
            .mapNotNull { d ->
                runCatching { Project.fromJson(JSONObject(File(d, "project.json").readText())) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Project? =
        runCatching { Project.fromJson(JSONObject(jsonFile(id).readText())) }.getOrNull()

    fun save(p: Project) {
        dir(p.id).mkdirs()
        val tmp = File(dir(p.id), "project.json.tmp")
        tmp.writeText(p.toJson().toString())
        tmp.renameTo(jsonFile(p.id))
    }

    fun readCode(id: String): String = runCatching { codeFile(id).readText() }.getOrDefault("")

    fun writeCode(id: String, code: String) {
        dir(id).mkdirs()
        codeFile(id).writeText(code)
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun isEmptyDraft(p: Project, code: String) =
        p.versionCode == 0 && p.name.isBlank() && code.isBlank() && p.url.isBlank() &&
            !wwwDir(p.id).exists() && !p.customIcon

    companion object {
        private val random = SecureRandom()
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        fun randomId(n: Int): String = buildString {
            append(ALPHABET[random.nextInt(26)])
            repeat(n - 1) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }
}
