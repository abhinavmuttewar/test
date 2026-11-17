package com.voicepilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.voicepilot.agent.PhoneController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reads the screen and performs taps, typing, scrolling and navigation for the assistant. */
class VoiceAccessService : AccessibilityService(), PhoneController {

    // Element ids from the last describeScreen() call.
    @Volatile private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        instance = this
        // Resume background listening after a reboot, if Android allows it.
        try { ListenerService.start(this) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- Screen reading ----

    override fun describeScreen(): String {
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        val found = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        roots.forEach { root ->
            sb.append("--- ${root.packageName} ---\n")
            walk(root, 0, found, sb)
        }
        nodes = found
        return if (found.isEmpty() && sb.length < 40) "(screen not readable)" else sb.toString().take(MAX_DUMP)
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: MutableList<AccessibilityNodeInfo>, sb: StringBuilder) {
        if (!n.isVisibleToUser || out.size >= MAX_NODES) return
        val label = listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString())
            .map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" | ").take(120)
        val actionable = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable
        if (actionable || label.isNotEmpty()) {
            val flags = buildList {
                if (n.isEditable) add("editable")
                if (n.isScrollable) add("scrollable")
                if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                if (n.isFocused) add("focused")
                if (!n.isEnabled) add("disabled")
            }.joinToString(",")
            val type = n.className?.toString()?.substringAfterLast('.') ?: "View"
            sb.append("  ".repeat(minOf(depth, 6)))
            if (actionable) { sb.append("[${out.size}] "); out.add(n) }
            sb.append(type)
            if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
            if (flags.isNotEmpty()) sb.append(" (").append(flags).append(')')
            sb.append('\n')
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, out, sb) }
    }

    private fun node(id: Int?): AccessibilityNodeInfo? = id?.let { nodes.getOrNull(it) }

    // ---- Actions ----

    override fun tap(elementId: Int): Boolean = click(node(elementId) ?: return false, long = false)
    override fun longPress(elementId: Int): Boolean = click(node(elementId) ?: return false, long = true)

    private fun click(n: AccessibilityNodeInfo, long: Boolean): Boolean {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if ((if (long) cur.isLongClickable else cur.isClickable) && cur.performAction(action)) return true
            cur = cur.parent
        }
        val r = Rect().also { n.getBoundsInScreen(it) }
        return gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), if (long) 800 else 60)
    }

    override fun typeText(elementId: Int?, text: String, submit: Boolean): Boolean {
        val target = node(elementId)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && submit && android.os.Build.VERSION.SDK_INT >= 30) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }

    override fun scroll(direction: String, elementId: Int?): Boolean {
        val target = node(elementId)?.takeIf { it.isScrollable } ?: nodes.firstOrNull { it.isScrollable }
        if (target != null) {
            val action = when (direction) {
                "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            val fallback = if (direction == "down" || direction == "right")
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target.performAction(action.id) || target.performAction(fallback)) return true
        }
        // Swipe gesture across the middle of the screen.
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat(); val h = m.heightPixels.toFloat()
        return when (direction) {
            "down" -> gesture(w / 2, h * 0.75f, w / 2, h * 0.25f, 350)
            "up" -> gesture(w / 2, h * 0.25f, w / 2, h * 0.75f, 350)
            "left" -> gesture(w * 0.85f, h / 2, w * 0.15f, h / 2, 300)
            else -> gesture(w * 0.15f, h / 2, w * 0.85f, h / 2, 300)
        }
    }

    override fun globalAction(action: String): Boolean = performGlobalAction(
        when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
    )

    override fun openApp(name: String): Boolean {
        val q = name.lowercase().trim()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it to it.loadLabel(packageManager).toString().lowercase() }
        val match = apps.firstOrNull { it.second == q }
            ?: apps.firstOrNull { it.second.startsWith(q) }
            ?: apps.firstOrNull { it.second.contains(q) || q.contains(it.second) }
            ?: return false
        val info = match.first.activityInfo
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(info.packageName, info.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
        return true
    }

    override fun tapText(text: String): Boolean {
        describeScreen()
        val q = text.lowercase()
        fun labelOf(n: AccessibilityNodeInfo) =
            "${n.text ?: ""} ${n.contentDescription ?: ""}".lowercase().trim()
        val target = nodes.firstOrNull { labelOf(it) == q } ?: nodes.firstOrNull { labelOf(it).contains(q) }
            ?: rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
            ?: return false
        return click(target, long = false)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val done = CountDownLatch(1)
        var ok = false
        val cb = object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; done.countDown() }
            override fun onCancelled(d: GestureDescription?) { done.countDown() }
        }
        val dispatched = if (Looper.myLooper() == Looper.getMainLooper()) dispatchGesture(g, null, null)
        else dispatchGesture(g, cb, Handler(Looper.getMainLooper()))
        if (!dispatched) return false
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        done.await(ms + 2000, TimeUnit.MILLISECONDS)
        return ok
    }

    companion object {
        @Volatile var instance: VoiceAccessService? = null
        private const val MAX_NODES = 250
        private const val MAX_DUMP = 24_000
    }
}
