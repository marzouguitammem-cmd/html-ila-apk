package com.tammem.htmlilaapk

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Builds apps in the cloud through the user's own GitHub account (free), so that
 * real Java/Kotlin projects — with libraries and resources — can be built without
 * any toolchain on the phone. The phone only uploads the project and downloads the APK.
 *
 * The user pastes a Personal Access Token once; it is kept in EncryptedSharedPreferences.
 * All work happens in one repo: <login>/appmaker-cloud (built by its build-job workflow).
 */
class GithubBuilder(private val context: Context) {

    val repo = "html-ila-apk"
    private val api = "https://api.github.com"

    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "github_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var login: String?
        get() = prefs.getString("login", null)
        private set(v) { prefs.edit().putString("login", v).apply() }

    private var token: String?
        get() = prefs.getString("token", null)
        set(v) { prefs.edit().putString("token", v).apply() }

    val isConfigured: Boolean get() = !token.isNullOrBlank() && !login.isNullOrBlank()

    fun signOut() {
        prefs.edit().clear().apply()
    }

    class GithubException(message: String) : IOException(message)

    /** Verifies a token, stores it, and returns the login. Throws with an Arabic message. */
    fun signIn(rawToken: String): String {
        val t = rawToken.trim()
        if (t.isEmpty()) throw GithubException("المفتاح فارغ")
        val user = request("GET", "$api/user", t)
        if (user.code == 401) throw GithubException("المفتاح غلط ولا منتهي. اعمل واحد جديد.")
        if (user.code != 200) throw GithubException("ما نجمناش نتثبّتو من المفتاح (${user.code})")
        val name = JSONObject(user.body).optString("login")
        if (name.isBlank()) throw GithubException("ما لقيناش الحساب")
        token = t
        login = name
        return name
    }

    /** True if <login>/appmaker-cloud exists. */
    fun repoExists(): Boolean {
        val r = request("GET", "$api/repos/${login}/$repo", token)
        return r.code == 200
    }

    fun repoUrl(): String = "https://github.com/${login ?: ""}/$repo"

    data class Result(val ok: Boolean, val versionName: String, val log: String)

    /**
     * Uploads a job and waits for its APK. [files] maps a path under jobs/<id>/ to its bytes.
     * job.json must be one of them; it is uploaded LAST so the build triggers only when complete.
     * [onPhase]: 1 uploading, 2 building, 3 downloading.
     */
    fun build(jobId: String, files: Map<String, ByteArray>, outApk: File, onPhase: (Int) -> Unit = {}): Result {
        val tok = token ?: throw GithubException("ما عملتش تسجيل الدخول لـ GitHub")
        val owner = login ?: throw GithubException("ما لقيناش الحساب")
        if (!repoExists()) throw GithubException("ما لقيناش repo باسم «$repo». اعملو في github.com/new (Public).")

        onPhase(1)
        val ordered = files.entries.sortedBy { it.key.endsWith("job.json") } // job.json last
        for ((rel, bytes) in ordered) {
            putFile(owner, "jobs/$jobId/$rel", bytes, tok)
        }

        onPhase(2)
        val result = pollResult(owner, jobId, tok)
        if (!result.ok) return result

        onPhase(3)
        downloadApk(owner, jobId, outApk, tok)
        return result
    }

    // ------------------------------------------------------------------ REST

    private fun putFile(owner: String, path: String, content: ByteArray, tok: String) {
        val body = JSONObject()
            .put("message", "appmaker: $path")
            .put("content", Base64.encodeToString(content, Base64.NO_WRAP))
        val r = request("PUT", "$api/repos/$owner/$repo/contents/$path", tok, body.toString())
        if (r.code !in 200..201) {
            throw GithubException("ما نجمناش نبعثو الملف (${r.code}). تثبّت من المفتاح والإنترنت.")
        }
    }

    private fun pollResult(owner: String, jobId: String, tok: String): Result {
        val url = "$api/repos/$owner/$repo/contents/jobs/$jobId/result.json"
        val deadline = System.currentTimeMillis() + 10 * 60_000
        var wait = 4000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(wait)
            wait = (wait + 1000).coerceAtMost(9000)
            val r = request("GET", url, tok, accept = "application/vnd.github.raw")
            if (r.code == 200) {
                val o = JSONObject(r.body)
                return Result(o.optString("status") == "ok", o.optString("versionName", ""), o.optString("log", ""))
            }
        }
        throw GithubException("البناء طوّل برشا. ثبّت من الإنترنت وعاود، ولا شوف GitHub Actions في الـ repo.")
    }

    private fun downloadApk(owner: String, jobId: String, outApk: File, tok: String) {
        val rel = request("GET", "$api/repos/$owner/$repo/releases/tags/job-$jobId", tok)
        if (rel.code != 200) throw GithubException("ما لقيناش الـ APK الجاهز (${rel.code})")
        val assets = JSONObject(rel.body).optJSONArray("assets") ?: JSONArray()
        var dl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { dl = a.optString("browser_download_url"); break }
        }
        if (dl == null) throw GithubException("الـ APK ما ثماش في النتيجة")
        outApk.parentFile?.mkdirs()
        val conn = open(dl, "GET", tok)
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) throw GithubException("ما نجمناش نهبّط الـ APK (${conn.responseCode})")
        conn.inputStream.use { input -> outApk.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        if (outApk.length() < 1000) throw GithubException("الملف المهبّط فاسد")
    }

    private data class Resp(val code: Int, val body: String)

    private fun request(method: String, url: String, tok: String?, body: String? = null,
                        accept: String = "application/vnd.github+json"): Resp {
        val conn = open(url, method, tok, accept)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Resp(code, text)
        } catch (e: Exception) {
            throw GithubException("مشكل في الاتصال: ${e.message ?: "ما فماش إنترنت"}")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String, tok: String?, accept: String = "application/vnd.github+json"): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "AppMaker-Android")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (tok != null) conn.setRequestProperty("Authorization", "Bearer $tok")
        return conn
    }

    companion object {
        /** Opens GitHub's token page with the needed scopes pre-ticked. */
        const val TOKEN_URL = "https://github.com/settings/tokens/new?description=AppMaker&scopes=repo,workflow"
        const val NEW_REPO_URL = "https://github.com/new"
    }
}
