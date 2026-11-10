package com.lenid.zoh.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.lenid.zoh.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps a journal of what went wrong, so it can be read off the head unit (files/crashes, by
 * adb) and, with a Sentry DSN built in (BuildConfig.SENTRY_DSN), sent there for reading from
 * anywhere. An uncaught exception is written as it happens; ANRs, native crashes and kills by
 * the system are what Android remembers of the last exit, written at the next start. Sending is
 * plain HTTP to Sentry's store endpoint, so no SDK is needed; a report not sent waits for the
 * next try.
 *
 * Every line AppLog emits at info or above also goes into a short trail in memory, and each
 * report carries it, so a report shows what led up to it. An error logged by the app (not only
 * a crash) is a report of its own: once per place and message per run, a few dozen a day at
 * most, sent shortly after, so a fault the app survives is still seen.
 */
object CrashJournal {
    private const val TAG = "CrashJournal"
    private const val KEEP = 40
    private const val PREFS = "crash_journal"
    private const val LAST_EXIT = "last_exit_ts"
    private const val DAY = "log_day"
    private const val DAY_COUNT = "log_day_count"
    /** Logged errors sent in one day; crashes are never held back. */
    private const val MAX_PER_DAY = 20
    private const val TRAIL = 300
    private const val UPLOAD_DELAY_S = 30L

    @Volatile private var app: Context? = null

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "CrashJournal").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val uploadPending = AtomicBoolean(false)

    // The trail: a ring written lock-free from any thread. A line torn by a race costs a line in
    // a report, where a lock would cost every logging thread a little on every line.
    private val trailTimes = LongArray(TRAIL)
    private val trailLevels = CharArray(TRAIL)
    private val trailLines = arrayOfNulls<String>(TRAIL)
    private val trailNext = AtomicLong()

    /** Errors already reported in this run, by place and message without its numbers. */
    private val reported = ConcurrentHashMap.newKeySet<String>()

    private fun dir(context: Context) = File(context.filesDir, "crashes").apply { mkdirs() }
    private fun sentDir(context: Context) = File(dir(context), "sent").apply { mkdirs() }

    fun install(context: Context) {
        val app = context.applicationContext
        this.app = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val crumbs = trail()
                write(app, "crash", header(app, "Uncaught exception in thread \"${t.name}\"") +
                    Log.getStackTraceString(e) + trailText(crumbs),
                    JSONObject().put("exception", exceptionJson(e)).put("breadcrumbs", crumbs))
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
        worker.execute {
            try {
                recordLastExits(app)
                upload(app)
            } catch (e: Exception) {
                AppLog.w("$TAG: journal pass failed: ${e.message}")
            }
        }
    }

    /**
     * Every line AppLog emits, on the thread that emits it: keep it in the trail, and report an
     * error. [msg] is AppLog's formatted line, "[thread] Class.method | text".
     */
    fun onLog(priority: Int, msg: String, tr: Throwable?) {
        if (priority < Log.INFO) return
        val slot = (trailNext.getAndIncrement() % TRAIL).toInt()
        trailTimes[slot] = System.currentTimeMillis()
        trailLevels[slot] = if (priority >= Log.ERROR) 'E' else if (priority == Log.WARN) 'W' else 'I'
        trailLines[slot] = msg.substringBefore('\n')
        if (priority >= Log.ERROR) reportLogged(msg, tr)
    }

    private fun reportLogged(msg: String, tr: Throwable?) {
        val app = app ?: return
        val text = msg.substringAfter(" | ", msg).substringBefore('\n')
        val origin = if (msg.startsWith("[") && " | " in msg) msg.substringAfter("] ").substringBefore(" | ")
            else tr?.stackTrace?.firstOrNull { it.className.startsWith("com.lenid.zoh.") }
                ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "?"
        val template = template(text)
        if (!reported.add("$origin|$template")) return
        val crumbs = trail()
        worker.execute {
            try {
                if (!countToday(app)) return@execute
                val title = "Logged error at $origin: ${mask(text).take(200)}"
                write(app, "error", header(app, title) + mask(msg) +
                    (tr?.let { "\n\n" + stackText(it) } ?: "") + trailText(crumbs),
                    JSONObject().apply {
                        tr?.let { put("exception", exceptionJson(it)) }
                        put("message", title)
                        put("breadcrumbs", crumbs)
                        put("fingerprint", JSONArray().put("logged").put(origin).put(template))
                        put("origin", origin)
                    })
                scheduleUpload(app)
            } catch (e: Exception) {
                AppLog.w("$TAG: could not keep a logged error: ${e.message}")
            }
        }
    }

    /** The whole trace: Log.getStackTraceString leaves out any with an UnknownHostException in it. */
    private fun stackText(e: Throwable) = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString()

    /** Takes one of today's places for a logged error, or says the day's are used up. */
    private fun countToday(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val count = if (prefs.getString(DAY, null) == today) prefs.getInt(DAY_COUNT, 0) else 0
        if (count >= MAX_PER_DAY) return false
        prefs.edit().putString(DAY, today).putInt(DAY_COUNT, count + 1).apply()
        return true
    }

    /** Errors come in bursts; one send a little later takes the whole burst. */
    private fun scheduleUpload(context: Context) {
        if (!uploadPending.compareAndSet(false, true)) return
        worker.schedule({
            uploadPending.set(false)
            try { upload(context) } catch (e: Exception) { AppLog.w("$TAG: send failed: ${e.message}") }
        }, UPLOAD_DELAY_S, TimeUnit.SECONDS)
    }

    /** The trail as Sentry breadcrumbs, oldest first, with places and numbers that identify masked. */
    private fun trail(): JSONArray {
        val end = trailNext.get()
        val crumbs = JSONArray()
        for (i in maxOf(0L, end - TRAIL) until end) {
            val slot = (i % TRAIL).toInt()
            val line = trailLines[slot] ?: continue
            crumbs.put(JSONObject().put("timestamp", trailTimes[slot] / 1000.0)
                .put("level", when (trailLevels[slot]) { 'E' -> "error"; 'W' -> "warning"; else -> "info" })
                .put("category", "log").put("message", mask(line)))
        }
        return crumbs
    }

    private fun trailText(crumbs: JSONArray) = buildString {
        if (crumbs.length() == 0) return@buildString
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        append("\n\n--- Recent log ---\n")
        for (i in 0 until crumbs.length()) {
            val c = crumbs.getJSONObject(i)
            append(time.format(Date((c.getDouble("timestamp") * 1000).toLong()))).append(' ')
                .append(c.getString("level").first().uppercaseChar()).append(' ')
                .append(c.getString("message")).append('\n')
        }
    }

    private val coordinates = Regex("-?\\d{1,3}\\.\\d{4,}")
    private val macs = Regex("(?i)\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b")

    /** Where the car is and which phone it talks to stay on the unit. */
    internal fun mask(s: String) = macs.replace(coordinates.replace(s, "***"), "**:**:**:**:**:**")

    /** A message without what changes between occurrences, so they count as one. */
    internal fun template(s: String) = Regex("\\d+").replace(mask(s), "#").take(160)

    private fun header(context: Context, title: String) = buildString {
        append(title).append('\n')
        append("App: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE)
            .append(", ").append(BuildConfig.GIT_SHA).append(")\n")
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE).append('\n')
        append("Time: ").append(stamp(System.currentTimeMillis())).append("\n\n")
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /** A report, with what Sentry should know beyond the text (exception, trail, grouping) beside it. */
    private fun write(context: Context, kind: String, text: String, extra: JSONObject? = null) {
        val d = dir(context)
        val name = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + "-$kind.txt"
        File(d, name).writeText(text)
        extra?.let { File(d, "$name.json").writeText(it.toString()) }
        val old = d.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedBy { it.name } ?: return
        old.dropLast(KEEP).forEach { it.delete(); File(d, it.name + ".json").delete() }
    }

    /** What Android kept of the exits since the last start (API 30): ANR, native crash, kill. */
    private fun recordLastExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(LAST_EXIT, 0L)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        var newest = since
        for (x in exits.sortedBy { it.timestamp }) {
            if (x.timestamp <= since) continue
            newest = maxOf(newest, x.timestamp)
            val kind = when (x.reason) {
                ApplicationExitInfo.REASON_ANR -> "anr"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "lowmem"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "resources"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init"
                else -> null // Java crashes are written by the handler; the rest are normal exits.
            } ?: continue
            val trace = if (x.reason == ApplicationExitInfo.REASON_ANR) {
                try { x.traceInputStream?.use { String(it.readBytes().take(64 * 1024).toByteArray()) } } catch (_: Exception) { null }
            } else null
            write(context, kind, header(context, "Last exit: $kind (${x.description ?: "no description"})") +
                "Exited: ${stamp(x.timestamp)}, process ${x.processName}, importance ${x.importance}, " +
                "pss ${x.pss} kB, rss ${x.rss} kB\n\n" + (trace ?: ""))
        }
        if (newest != since) prefs.edit().putLong(LAST_EXIT, newest).apply()
    }

    private const val SEND = "send_reports"

    /** Whether reports leave the unit. Off, they are still kept in files/crashes, and go once it is back on. */
    fun sendReports(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SEND, true)

    fun setSendReports(context: Context, send: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SEND, send).apply()
        if (send) worker.execute { try { upload(context.applicationContext) } catch (_: Exception) {} }
    }

    /** Every report not yet in Sentry goes there; sent ones move to crashes/sent. */
    private fun upload(context: Context) {
        if (!sendReports(context)) return
        val dsn = parseDsn(BuildConfig.SENTRY_DSN) ?: return
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedBy { it.name } ?: return
        for (f in files) {
            val json = File(f.parentFile, f.name + ".json").takeIf { it.exists() }?.readText()
            val event = event(f, json?.let { JSONObject(it) })
            if (!post(dsn, event)) return // offline or refused: try again with the next report or start
            f.renameTo(File(sentDir(context), f.name))
            File(f.parentFile, f.name + ".json").delete()
            AppLog.i("$TAG: sent ${f.name} to Sentry")
        }
    }

    private class Dsn(val key: String, val endpoint: String)

    private fun parseDsn(dsn: String): Dsn? {
        if (dsn.isBlank()) return null
        return try {
            val u = URL(dsn)
            val key = u.userInfo?.substringBefore(':') ?: return null
            val project = u.path.trim('/').substringAfterLast('/')
            val prefix = u.path.trim('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "/$it" }
            Dsn(key, "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}$prefix/api/$project/store/")
        } catch (_: Exception) { null }
    }

    private fun event(file: File, extra: JSONObject?): JSONObject {
        val text = file.readText()
        val kind = file.name.substringAfterLast('-').removeSuffix(".txt")
        // Reports kept by 1.0.1 have the bare exception beside them.
        val exception = if (extra?.has("type") == true) extra else extra?.optJSONObject("exception")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return JSONObject().apply {
            put("event_id", UUID.randomUUID().toString().replace("-", ""))
            put("timestamp", iso.format(Date(file.lastModified())))
            put("platform", "java")
            put("level", when (kind) { "crash", "native", "anr" -> "fatal"; else -> "error" })
            put("release", "open-zeekr-headunit@${BuildConfig.VERSION_NAME}")
            put("environment", BuildConfig.BUILD_TYPE)
            put("tags", JSONObject().put("kind", kind).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", Build.VERSION.RELEASE).apply { extra?.optString("origin")?.takeIf { it.isNotEmpty() }?.let { put("origin", it) } })
            if (exception != null) put("exception", JSONObject().put("values", JSONArray().put(exception)))
            put("message", JSONObject().put("formatted",
                extra?.optString("message")?.takeIf { it.isNotEmpty() } ?: text.lineSequence().firstOrNull().orEmpty()))
            extra?.optJSONArray("breadcrumbs")?.let { put("breadcrumbs", JSONObject().put("values", it)) }
            extra?.optJSONArray("fingerprint")?.let { put("fingerprint", it) }
            put("extra", JSONObject().put("report", text.take(100_000)))
        }
    }

    /** Sentry's shape of an exception: type, message and frames, innermost last. */
    private fun exceptionJson(e: Throwable): JSONObject {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val frames = JSONArray()
        root.stackTrace.reversed().forEach { st ->
            frames.put(JSONObject().put("module", st.className).put("function", st.methodName)
                .put("filename", st.fileName ?: "").put("lineno", st.lineNumber)
                .put("in_app", st.className.startsWith("com.lenid.zoh.")))
        }
        return JSONObject().put("type", root.javaClass.name).put("value", mask(root.message ?: ""))
            .put("stacktrace", JSONObject().put("frames", frames))
    }

    private fun post(dsn: Dsn, event: JSONObject): Boolean = try {
        val c = (URL(dsn.endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Sentry-Auth",
                "Sentry sentry_version=7, sentry_client=open-zeekr-headunit/1.1, sentry_key=${dsn.key}")
        }
        c.outputStream.use { it.write(event.toString().toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299) AppLog.w("$TAG: Sentry answered $code")
        code in 200..299
    } catch (e: Exception) {
        AppLog.w("$TAG: Sentry unreachable: ${e.message}")
        false
    }
}
