package com.yugahashimoto.andcode.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.yugahashimoto.andcode.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer APK published on the configured GitHub repository. */
data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val releaseUrl: String,
)

/**
 * Checks the configured GitHub Releases feed for a newer build and installs it.
 *
 * The APK is downloaded into the app cache and handed to the system installer through a
 * FileProvider; Android still shows its own confirmation, and the user must allow AndCode as an
 * install source. Version order comes from a `versionCode: N` line in the release notes, so the
 * feed does not need to be parsed from the APK itself.
 */
class AppUpdateRepository {
    suspend fun checkForUpdate(
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
    ): Result<AppUpdateInfo?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val repository = BuildConfig.UPDATE_REPOSITORY.trim()
                require(repository.isNotEmpty()) { "No update repository configured" }
                val connection =
                    (URL("https://api.github.com/repos/$repository/releases?per_page=10").openConnection() as
                        HttpURLConnection)
                        .apply {
                            requestMethod = "GET"
                            connectTimeout = 15_000
                            readTimeout = 20_000
                            setRequestProperty("Accept", "application/vnd.github+json")
                            setRequestProperty("User-Agent", "AndCode-Updater")
                        }
                try {
                    require(connection.responseCode in 200..299) {
                        "GitHub returned ${connection.responseCode}"
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    parseLatestUpdate(body, currentVersionCode)
                } finally {
                    connection.disconnect()
                }
            }
        }

    suspend fun download(
        context: Context,
        update: AppUpdateInfo,
        onProgress: (Int) -> Unit = {},
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, "andcode-update.apk")
                if (target.exists()) target.delete()
                val connection =
                    (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "AndCode-Updater")
                    }
                try {
                    require(connection.responseCode in 200..299) {
                        "Download returned ${connection.responseCode}"
                    }
                    val total = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0) {
                                    onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                                }
                            }
                        }
                    }
                    onProgress(100)
                    target
                } finally {
                    connection.disconnect()
                }
            }
        }

    /**
     * Opens the system installer for [apk]. Returns false when the user must first allow AndCode
     * as an install source; [openInstallPermissionSettings] is opened in that case.
     */
    fun install(
        context: Context,
        apk: File,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            openInstallPermissionSettings(context)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

/** Newest release whose notes declare a `versionCode` greater than [currentVersionCode]. */
internal fun parseLatestUpdate(
    json: String,
    currentVersionCode: Int,
): AppUpdateInfo? {
    val releases = JSONArray(json)
    var newest: AppUpdateInfo? = null
    for (index in 0 until releases.length()) {
        val release = releases.getJSONObject(index)
        if (release.optBoolean("draft", false)) continue
        val assets = release.optJSONArray("assets") ?: continue
        val apk =
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { asset -> asset.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: continue
        val apkUrl = apk.optString("browser_download_url")
        if (apkUrl.isBlank()) continue
        val versionCode = parseVersionCode(release.optString("body")) ?: continue
        if (versionCode <= currentVersionCode) continue
        val info =
            AppUpdateInfo(
                versionName = release.optString("tag_name").ifBlank { release.optString("name") },
                versionCode = versionCode,
                apkUrl = apkUrl,
                releaseUrl = release.optString("html_url"),
            )
        if (newest == null || info.versionCode > newest.versionCode) newest = info
    }
    return newest
}

/** Reads the machine-readable version from the release notes, e.g. `versionCode: 100123`. */
internal fun parseVersionCode(body: String?): Int? {
    if (body.isNullOrBlank()) return null
    return Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(body)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}
