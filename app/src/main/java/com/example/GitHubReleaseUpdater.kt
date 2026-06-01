package com.example

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object GitHubReleaseUpdater {
    private const val OWNER = "Areo-RGB"
    private const val REPO = "game-app"
    private const val APK_NAME_HINT = ".apk"
    private const val APP_USER_AGENT = "game-app-android-updater"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private val client = OkHttpClient()
    private val downloadClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()

    data class UpdateInfo(
        val tagName: String,
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val body: String,
    )

    suspend fun checkForUpdate(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", APP_USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    when (response.code) {
                        404 -> error("No GitHub release published yet.")
                        403 -> error("GitHub API rate limit reached. Try again later.")
                        else -> {
                            val message = runCatching { JSONObject(payload).optString("message") }.getOrNull()
                            error("GitHub release check failed: HTTP ${response.code}${if (message.isNullOrBlank()) "" else " ($message)"}")
                        }
                    }
                }

                val json = JSONObject(payload)
                val tag = json.optString("tag_name")
                if (tag.isBlank()) error("Latest release is missing tag_name.")

                val releaseName = json.optString("name", tag)
                val body = json.optString("body")
                val assets = json.optJSONArray("assets") ?: error("Release has no assets.")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(APK_NAME_HINT, ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }

                val downloadUrl = apkUrl ?: error("Release has no APK asset.")
                val releaseVersionCode = parseVersionCode(tag, releaseName, body)
                if (releaseVersionCode > BuildConfig.VERSION_CODE) {
                    UpdateInfo(
                        tagName = tag,
                        versionCode = releaseVersionCode,
                        versionName = tag.removePrefix("v"),
                        apkUrl = downloadUrl,
                        body = body,
                    )
                } else {
                    null
                }
            }
        }
    }

    suspend fun downloadAndInstall(activity: Activity, update: UpdateInfo): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(update.apkUrl)
                .header("User-Agent", APP_USER_AGENT)
                .build()
            val apkFile = File(activity.cacheDir, "game-app-${update.versionName}.apk")
            try {
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("APK download failed: HTTP ${response.code}")
                    response.body?.byteStream()?.use { input ->
                        apkFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("APK download had no body")
                }
            } catch (_: SocketTimeoutException) {
                error("APK download timed out. Check connection and try again.")
            }
            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(activity, "Allow installs for this app, then tap update again.", Toast.LENGTH_LONG).show()
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    )
                    return@withContext
                }

                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    activity.startActivity(installIntent)
                } catch (_: ActivityNotFoundException) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(fallbackIntent)
                }
            }
        }
    }

    private fun parseVersionCode(vararg values: String): Int {
        values.forEach { value ->
            Regex("versionCode\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(value)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { return it }
        }
        values.firstOrNull()?.let { tag ->
            val parts = tag.removePrefix("v").split('.', '-', '_').mapNotNull { it.toIntOrNull() }
            if (parts.isNotEmpty()) return parts.fold(0) { acc, part -> acc * 1000 + part }
        }
        return 0
    }
}
