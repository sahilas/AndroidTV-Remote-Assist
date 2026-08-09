package dev.sahilas.tvassist

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the Go server process.
 *
 * Why the app runs it at all: on a locked box `init` will not read a service
 * definition from any partition we can write, so the parent repo's server dies on
 * every reboot. An enabled AccessibilityService is started by the system on every
 * boot, which makes this the only route to persistence without root.
 *
 * The binary ships as `lib/<abi>/libtlsproxy.so` and is exec'd from
 * `nativeLibraryDir` — W^X forbids exec from `filesDir`, so the jniLibs path is
 * not a packaging trick, it is the only supported one. Measured working on
 * arm64-v8a and armeabi-v7a.
 */
class ServerProcess(private val ctx: Context) {

    /** Where the server reads cert.pem, key.pem, ca.crt and token. */
    val dataDir: File get() = File(ctx.filesDir, "tvremote")

    private val binary: File
        get() = File(ctx.applicationInfo.nativeLibraryDir, "libtlsproxy.so")

    private val running = AtomicBoolean(false)
    private var proc: Process? = null
    private var supervisor: Thread? = null

    fun isRunning(): Boolean = proc?.isAlive == true

    /**
     * Copy provisioning files the deploy script staged.
     *
     * The server needs a certificate and a token, and the app cannot generate them
     * without becoming a second implementation of `gen-cert.sh`. The deploy script
     * stages them somewhere adb can write, and this pulls them into the app's own
     * storage, where they end up owned by the app uid and unreadable by anything
     * else on the device.
     *
     * Returns a human-readable report rather than a boolean: when this fails, the
     * reason (which file, what error) is the whole diagnostic.
     */
    fun provisionFrom(staging: File): String {
        if (!staging.isDirectory) return "staging absent: ${staging.absolutePath}"
        dataDir.mkdirs()
        val report = StringBuilder()
        var copied = 0
        for (name in PROVISION_FILES) {
            val src = File(staging, name)
            val dst = File(dataDir, name)
            try {
                if (!src.exists()) { report.append("$name:absent "); continue }
                src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                // Readable only by this app. The TLS private key and the auth token
                // are in here; the whole point of moving them is that they stop
                // being world-readable.
                dst.setReadable(false, false); dst.setReadable(true, true)
                dst.setWritable(false, false); dst.setWritable(true, true)
                copied++
                report.append("$name:${dst.length()}B ")
            } catch (e: Exception) {
                report.append("$name:FAILED(${e.javaClass.simpleName}:${e.message}) ")
            }
        }
        return "copied=$copied/${PROVISION_FILES.size} $report".trim()
    }

    /**
     * Port and mDNS name come from the config the deploy script generated, not from
     * constants here. Hardcoding 8443 would ignore a box where that port is already
     * taken -- which is not hypothetical: a Google Android TV image was measured
     * serving something else on it.
     */
    fun config(): Pair<Int, String> {
        var port = DEFAULT_PORT
        var mdns = DEFAULT_MDNS
        val f = File(dataDir, "config")
        if (f.exists()) {
            runCatching {
                f.readLines().forEach { line ->
                    val t = line.trim()
                    if (t.startsWith("#") || "=" !in t) return@forEach
                    val (k, v) = t.split("=", limit = 2)
                    when (k.trim()) {
                        "HTTPS_PORT" -> v.trim().toIntOrNull()?.let { port = it }
                        "MDNS_HOST" -> if (v.isNotBlank()) mdns = v.trim()
                    }
                }
            }
        }
        return port to mdns
    }

    fun provisioned(): Boolean =
        File(dataDir, "cert.pem").exists() &&
            File(dataDir, "key.pem").exists() &&
            File(dataDir, "token").exists()

    /**
     * Start the server and keep it started.
     *
     * Restart is deliberately backed off. Without it, a server that cannot bind —
     * a port already taken, which was measured on a real Android TV image — spins
     * as fast as the CPU allows and looks like the app is broken rather than the
     * port being busy.
     */
    @Synchronized
    fun start(portOverride: Int? = null, mdnsOverride: String? = null): String {
        val (cfgPort, cfgMdns) = config()
        val port = portOverride ?: cfgPort
        val mdnsHost = mdnsOverride ?: cfgMdns
        if (!binary.exists()) return "binary missing: ${binary.absolutePath}"
        if (!provisioned()) return "not provisioned: ${dataDir.absolutePath} lacks cert/key/token"
        if (isRunning()) return "already running"

        running.set(true)
        supervisor = Thread {
            var backoffMs = 1_000L
            while (running.get()) {
                try {
                    val p = ProcessBuilder(
                        binary.absolutePath,
                        "-listen", ":$port",
                        "-dir", dataDir.absolutePath,
                        "-mdns-host", mdnsHost,
                    ).redirectErrorStream(true).start()
                    proc = p
                    Log.i(TAG, "server started")   // Process.pid() is API 26; minSdk here is 21
                    // Drain output or the pipe fills and the process blocks on write.
                    p.inputStream.bufferedReader().forEachLine { Log.i(TAG, "server: $it") }
                    val code = p.waitFor()
                    Log.w(TAG, "server exited code=$code")
                    if (!running.get()) break
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                } catch (e: Exception) {
                    Log.e(TAG, "server launch failed: ${e.javaClass.simpleName}: ${e.message}")
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                }
                if (!running.get()) break
                Log.i(TAG, "restarting in ${backoffMs}ms")
                try { Thread.sleep(backoffMs) } catch (e: InterruptedException) { break }
            }
            Log.i(TAG, "supervisor stopped")
        }.apply { isDaemon = true; name = "tlsproxy-supervisor"; start() }
        return "starting on :$port dir=${dataDir.absolutePath}"
    }

    @Synchronized
    fun stop(): String {
        running.set(false)
        val p = proc
        supervisor?.interrupt()
        p?.destroy()
        proc = null
        return if (p == null) "was not running" else "stopped"
    }

    companion object {
        private const val TAG = "RemoteAssist"
        const val DEFAULT_PORT = 8443
        const val DEFAULT_MDNS = "androidtvremote"

        /** Staged by the deploy script; adb can write here, the app can read it. */
        val STAGING = File("/data/local/tmp/tvremote")

        private val PROVISION_FILES = listOf("cert.pem", "key.pem", "ca.crt", "token", "config")
    }
}
