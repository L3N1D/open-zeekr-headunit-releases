package com.lenid.zoh.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.lenid.zoh.ssl.ConscryptInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Downloads a release APK into the cache and hands it to the system installer, which asks the
 * user to confirm. The head unit may have no browser to fetch the APK with, so this is done here.
 */
object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    private const val MAX_REDIRECTS = 5

    /** The APK in the cache, or a failure; GitHub's asset links redirect to its storage host. */
    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "update.apk")
                var link = url
                var conn: HttpURLConnection? = null
                for (i in 0..MAX_REDIRECTS) {
                    val c = (URL(link).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "OpenHeadunit-App")
                        setRequestProperty("Accept", "application/octet-stream")
                    }
                    if (c is HttpsURLConnection) {
                        ConscryptInitializer.httpsSocketFactory(context)?.let { c.sslSocketFactory = it }
                    }
                    val code = c.responseCode
                    if (code in 300..399) {
                        link = c.getHeaderField("Location") ?: break
                        c.disconnect()
                        continue
                    }
                    if (code != 200) return@withContext Result.failure(Exception("HTTP $code"))
                    conn = c
                    break
                }
                val c = conn ?: return@withContext Result.failure(Exception("Too many redirects"))
                val total = c.contentLengthLong
                var done = 0L
                var lastPercent = -1
                c.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != lastPercent) { lastPercent = p; onProgress(p) }
                            }
                        }
                    }
                }
                if (total > 0 && done != total) return@withContext Result.failure(Exception("Download cut short"))
                AppLog.i("$TAG: downloaded ${done / 1024} KB")
                Result.success(target)
            } catch (e: java.io.IOException) {
                // A connection lost on the move; the next check tries again.
                AppLog.w("$TAG: download failed: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                AppLog.e("$TAG: download failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    /** Allowed to start the installer; before Android 8 the global "unknown sources" switch rules. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** The system page where the user lets this app install updates. */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("$TAG: cannot open the install permission page", e)
        }
    }

    /** Opens the system installer on [apk]; the user confirms there. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
