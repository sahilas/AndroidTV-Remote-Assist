package dev.sahilas.tvassist

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Scaffold only -- no transport, no wiring to the Go server yet. See DESIGN.md.
 *
 * What this service exists to do, and why it is not simply "the same thing the Go
 * server does, without root":
 *
 * The Go server injects at the evdev layer, BELOW the app. That is why its held-OK
 * works everywhere -- the app cannot tell the difference from a physical remote.
 * An AccessibilityService acts at the NODE layer, above the app, so a long press
 * only exists where the focused view exposes ACTION_LONG_CLICK. SurfaceView-based
 * players and games frequently expose nothing. This is narrower coverage than what
 * it replaces, not an equivalent.
 */
class RemoteAssistService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected; sdk=${android.os.Build.VERSION.SDK_INT}")
    }

    // Not used yet. The service must still be declared to receive events for the
    // system to keep it bound.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * The headline capability: a real long-press on whatever currently has focus.
     * Returns false when nothing focusable is found or the node does not support
     * long click -- callers must surface that rather than reporting success, the
     * same way the Go server returns 500 instead of pretending.
     */
    fun longPressFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    companion object {
        private const val TAG = "RemoteAssist"
    }
}
