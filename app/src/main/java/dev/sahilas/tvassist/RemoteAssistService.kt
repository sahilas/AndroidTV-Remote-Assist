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

    // Debug-only trigger so the capabilities can be measured over adb before any
    // transport exists. Registered at runtime rather than in the manifest, and it
    // MUST NOT survive into a shipped build: anything on the device could
    // broadcast to it and drive the focused UI.
    private val debug = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getStringExtra("op")) {
                "longpress" -> Log.i(TAG, "RESULT longpress=${longPressFocused()}")
                "describe" -> Log.i(TAG, "RESULT focus=${describeFocus()}")
                "exec" -> Log.i(TAG, "RESULT exec=${execFromNativeLibDir()}")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected; sdk=${android.os.Build.VERSION.SDK_INT}")
        registerReceiver(debug, IntentFilter(DEBUG_ACTION))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(debug) }
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
        return "cls=${n.className} pkg=${n.packageName} " +
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
    }
}
