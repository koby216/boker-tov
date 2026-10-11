package com.koby.bokertov

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** "העיניים והאצבעות" של האפליקציה: קורא את המסך ולוחץ במקום האדם. */
class ControlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun scroll(down: Boolean): Boolean {
        val root = rootInActiveWindow
        val node = root?.let { findScrollable(it) }
        if (node != null) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(action)) return true
        }
        // אם אין רשימה שאפשר לגלול, מחקים החלקה של אצבע
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val from = m.heightPixels * (if (down) 0.72f else 0.28f)
        val to = m.heightPixels * (if (down) 0.28f else 0.72f)
        return swipe(x, from, x, to)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build()
        return dispatchGesture(g, null, null)
    }

    private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findScrollable(c)?.let { return it }
        }
        return null
    }

    private fun labelOf(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "")

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    /** מחפש כפתור/טקסט במסך לפי מה שכתוב עליו. מעדיף התאמה מלאה. */
    fun findByLabel(label: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val target = Text.norm(label)
        if (target.isEmpty()) return null
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all)
        val matches = all.filter {
            val l = Text.norm(labelOf(it)); l.isNotEmpty() && l.contains(target)
        }
        return matches.firstOrNull { Text.norm(labelOf(it)) == target } ?: matches.firstOrNull()
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    /** לוחץ על הראשון מבין כמה שמות אפשריים (למשל "שלח" או "Send"). */
    fun clickAny(labels: List<String>): Boolean {
        for (l in labels) {
            val n = findByLabel(l) ?: continue
            if (click(n)) return true
        }
        return false
    }

    /** לוחץ על כפתור השליחה של וואטסאפ: קודם לפי המזהה שלו, ואחר כך לפי השם "שליחה". */
    fun clickWhatsappSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString()?.startsWith("com.whatsapp") != true) return false
        for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && click(nodes[0])) return true
        }
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all)
        val names = setOf("שליחה", "שלח", "send")
        val btn = all.firstOrNull {
            it.contentDescription?.toString()?.let { d -> Text.norm(d) in names } == true
        }
        return btn != null && click(btn)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) {
            val all = ArrayList<AccessibilityNodeInfo>()
            collect(root, all)
            target = all.firstOrNull { it.isEditable }
        }
        if (target == null || target.isPassword) return false
        val old = target.text?.toString().orEmpty()
        val now = if (old.isEmpty()) text else "$old $text"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, now)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** קורא את הטקסטים שעל המסך, בלי שדות סיסמה. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all)
        val parts = LinkedHashSet<String>()
        for (n in all) {
            if (n.isPassword) continue
            val l = labelOf(n).trim()
            if (l.length >= 2) parts.add(l)
            if (parts.size >= 12) break
        }
        return parts.joinToString(". ")
    }

    companion object {
        @Volatile
        var instance: ControlAccessibilityService? = null
    }
}
