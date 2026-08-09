package dev.sahilas.tvassist

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/**
 * Scaffold. No transport yet -- see DESIGN.md.
 *
 * What this service exists to do, and why it is not simply "the same thing the Go
 * server does, without root":
 *
 * The Go server injects at the evdev layer, BELOW the app. That is why its held-OK
 * works everywhere -- the app cannot tell the difference from a physical remote.
 * An AccessibilityService acts at the NODE layer, above the app, so a long press
 * only exists where the focused view exposes ACTION_LONG_CLICK. SurfaceView-based
 * players and games frequently expose nothing. Narrower coverage than what it
 * replaces, not an equivalent.
 */
class RemoteAssistService : AccessibilityService() {

    // Probe trigger, so capabilities can be measured over adb before any transport
    // exists. Registered ONLY when the property below is set, because an always-on
    // receiver would let anything on the device drive the focused UI:
    //
    //     adb shell setprop debug.tvassist.probe 1
    //
    // A release build with the property unset registers nothing. The property does
    // not survive a reboot, so this cannot be left on by accident.
    private val debug = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getStringExtra("op")) {
                "longpress" -> Log.i(TAG, "RESULT longpress=${longPressFocused()}")
                "describe" -> Log.i(TAG, "RESULT focus=${describeFocus()}")
                "exec" -> Log.i(TAG, "RESULT exec=${execFromNativeLibDir()}")
                "provision" -> Log.i(TAG, "RESULT provision=${server.provisionFrom(ServerProcess.STAGING)}")
                "start" -> Log.i(TAG, "RESULT start=${server.start()}")
                "stop" -> Log.i(TAG, "RESULT stop=${server.stop()}")
                "dpad" -> Log.i(TAG, "RESULT dpad=${dpad(i.getStringExtra("dir") ?: "down")}")
                "status" -> Log.i(TAG, "RESULT status=running=${server.isRunning()} provisioned=${server.provisioned()} dir=${server.dataDir}")
            }
        }
    }

    private val server by lazy { ServerProcess(this) }
    private var probeRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
        Log.i(TAG, "connected; sdk=${android.os.Build.VERSION.SDK_INT} abi=$abi " +
            "nativeLibDir=${applicationInfo.nativeLibraryDir}")
        if (probeEnabled()) {
            // From API 34 registerReceiver THROWS unless an export flag is given,
            // and this runs inside onServiceConnected -- so the omission killed the
            // rest of the callback silently. Everything after it (provisioning, the
            // server autostart) simply never ran, while the service still logged
            // "connected" and looked healthy.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(debug, IntentFilter(DEBUG_ACTION), Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(debug, IntentFilter(DEBUG_ACTION))
            }
            probeRegistered = true
            Log.i(TAG, "probe receiver registered")
        }

        // This is the reason the app exists. The system starts an enabled
        // AccessibilityService on every boot, so starting the server here is what
        // makes it survive a reboot on a box where init cannot be used.
        //
        // Re-provision on every connect rather than only when unprovisioned: a
        // redeploy stages fresh material (a rotated token, a regenerated
        // certificate), and silently keeping the old copy would look exactly like
        // the deploy having no effect.
        runCatching { Log.i(TAG, "provision: ${server.provisionFrom(ServerProcess.STAGING)}") }
            .onFailure { Log.e(TAG, "provision threw: $it") }
        runCatching { Log.i(TAG, "autostart: ${server.start()}") }
            .onFailure { Log.e(TAG, "autostart threw: $it") }
    }

    /**
     * Reads the probe property.
     *
     * Reflection first, exec second: measured on SDK 37, an app cannot run
     * /system/bin/getprop at all, so the exec form silently returns false and the
     * probe can never be enabled. It fails closed, which is the safe direction, but
     * it also made the mechanism useless on newer Android.
     *
     * A debug build always registers, so the capability probes remain usable
     * without weakening the release build.
     */
    private fun probeEnabled(): Boolean {
        if (BuildConfig.DEBUG) return true
        runCatching {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            if ((get.invoke(null, PROBE_PROP) as? String) == "1") return true
        }
        runCatching {
            val p = ProcessBuilder("/system/bin/getprop", PROBE_PROP).start()
            val v = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (v == "1") return true
        }
        return false
    }

    override fun onDestroy() {
        if (probeRegistered) runCatching { unregisterReceiver(debug) }
        // Do not leave an orphan holding the port: a survivor makes the next start
        // fail on bind while the old binary keeps serving, so a health check would
        // pass against code you thought you had replaced.
        Log.i(TAG, "shutdown: ${server.stop()}")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * rootInActiveWindow alone is not enough: measured on a TV Settings screen it
     * returned no focus at all while a row was visibly selected. Walk every
     * interactive window (which is what flagRetrieveInteractiveWindows is for)
     * and take the first real focus found.
     */
    private fun focused(): AccessibilityNodeInfo? {
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            windows.forEach { w -> w.root?.let { add(it) } }
        }
        for (r in roots) {
            r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        }
        for (r in roots) {
            r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo.declaresLongClick(): Boolean =
        actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }

    /**
     * Focus often lands on the CONTAINER (a GridView on the TV launcher), not the
     * selected tile. Look for the nearest node that actually declares
     * ACTION_LONG_CLICK: the focused node itself, then its selected/focused
     * descendants, then its ancestors.
     */
    private fun longClickTarget(): AccessibilityNodeInfo? {
        val start = focused() ?: return null
        if (start.declaresLongClick()) return start

        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(start) }
        var seen = 0
        while (queue.isNotEmpty() && seen < 200) {
            val n = queue.removeFirst(); seen++
            if (n.declaresLongClick() && (n.isSelected || n.isFocused || n.isAccessibilityFocused)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        var p = start.parent
        while (p != null) {
            if (p.declaresLongClick()) return p
            p = p.parent
        }
        return null
    }

    /** What has focus, and whether it admits to being long-clickable. */
    fun describeFocus(): String {
        val n = focused() ?: return "none (windows=${windows.size})"
        val t = longClickTarget()
        return "sig=${focusSignature()} " +
            "declaresLongClick=${n.declaresLongClick()} | " +
            "target=${t?.className ?: "NONE"} targetDeclares=${t?.declaresLongClick()}"
    }

    /**
     * The headline capability: a real long-press on whatever currently has focus.
     * Must return false rather than pretend -- a silent no-op reported as success
     * is worse than an error, because the user cannot tell it from a dead button.
     */
    fun longPressFocused(): Boolean {
        // performAction's return value cannot be trusted: measured on the TV
        // launcher, it returned true for a GridView whose actionList does not
        // contain ACTION_LONG_CLICK, i.e. it reported success for a no-op. Only
        // claim success when the target actually declares the action.
        val t = longClickTarget() ?: return false
        return t.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    /**
     * GLOBAL_ACTION_DPAD_* was added in API 34. Before that there is no
     * accessibility route to directional navigation at all, which is the whole
     * question for whether this app can drive a TV.
     *
     * Reports focus before and after, because performGlobalAction's return value
     * has already been shown to lie once: ACTION_LONG_CLICK returned true on a node
     * that did not support it. A moved focus is the only real evidence.
     */
    fun dpad(dir: String): String {
        if (android.os.Build.VERSION.SDK_INT < 34) {
            return "unsupported: sdk=${android.os.Build.VERSION.SDK_INT} needs 34"
        }
        val action = when (dir) {
            "up" -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
            "down" -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
            "left" -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
            "right" -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
            "center" -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
            else -> return "unknown direction: $dir"
        }
        val before = focusSignature()
        val returned = performGlobalAction(action)
        // Poll rather than sleep a fixed interval. A TV launcher animates focus, and
        // a single 600ms sample reported "did not move" for movement that simply had
        // not landed yet -- a false negative that would have condemned the whole
        // approach.
        var after = before
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            val now = focusSignature()
            if (now != before) { after = now; break }
            after = now
        }
        return "dir=$dir returned=$returned moved=${before != after} before=[$before] after=[$after]"
    }

    /** Enough of the focused node to tell whether focus actually moved. */
    private fun focusSignature(): String {
        val n = focused() ?: return "none"
        val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        return "${n.className}|${n.text}|${n.contentDescription}|$r"
    }

    /**
     * Decides the architecture (DESIGN.md, option B): can this app exec a bundled
     * binary? W^X forbids exec from filesDir; nativeLibraryDir is the supported
     * route, which is why the Go server would ship as lib*.so.
     */
    fun execFromNativeLibDir(): String {
        val bin = File(applicationInfo.nativeLibraryDir, "libtlsproxy.so")
        if (!bin.exists()) return "missing:${bin.absolutePath}"
        return try {
            val p = ProcessBuilder(bin.absolutePath, "-h")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim().take(200)
            p.waitFor()
            "ok exit=${p.exitValue()} canExec=${bin.canExecute()} out=<$out>"
        } catch (e: Exception) {
            "FAILED ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "RemoteAssist"
        const val DEBUG_ACTION = "dev.sahilas.tvassist.DEBUG"
        private const val PROBE_PROP = "debug.tvassist.probe"
    }
}
