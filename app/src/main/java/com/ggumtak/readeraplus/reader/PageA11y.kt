package com.ggumtak.readeraplus.reader

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * The page as a tree for a screen reader (touch exploration on; [PageView] returns no provider otherwise, so a UI dump
 * or another service sees the plain view). The host is the PageView itself (scrollable, the two scroll actions);
 * its children are the paragraph parts of the page shown (in scroll mode the visible lines of the virtual page) and,
 * as separate clickable children, their links ([A11yFragments]). Navigation stays the host's scroll actions.
 *
 * The nodes are built when a service asks and kept while the page, its layout and the view size are the same
 * objects; nothing is built or sent per draw. Nothing is drawn for the focus: an e-ink page shows no ring.
 * Main thread only. Written without a device: TalkBack's behaviour is not verified.
 */
internal class PageA11y(private val view: PageView, private val cb: PageView.Callbacks) : AccessibilityNodeProvider() {

    private class Entry(val id: Int, val node: A11yNode)

    private class Snapshot(
        val source: A11ySource,
        val width: Int,
        val height: Int,
        val entries: List<Entry>,
        val nodes: List<A11yNode>,
    ) {
        fun matches(s: A11ySource, w: Int, h: Int): Boolean =
            s.page === source.page && s.layout === source.layout && s.left == source.left && s.top == source.top &&
                w == width && h == height
    }

    private val ids = A11yIds()
    private var snap: Snapshot? = null
    private var owner: Any? = null
    private var generation = -1
    private var focusedId = NONE
    private var hoveredId = NONE
    private val loc = IntArray(2)

    private val manager: AccessibilityManager? get() = view.context.getSystemService(AccessibilityManager::class.java)

    /** The page on screen changed: the nodes are built again at the next ask. */
    fun invalidate() {
        snap = null
    }

    /** Touch exploration went off or the view left the window: forget focus, hover and the nodes. */
    fun reset() {
        snap = null
        focusedId = NONE
        hoveredId = NONE
    }

    private fun snapshot(): Snapshot? {
        val src = cb.accessibilitySource() ?: return null
        val w = view.width
        val h = view.height
        val cached = snap
        if (cached != null && cached.matches(src, w, h)) return cached
        if (owner !== src.owner || generation != src.generation) {
            // Another book or layout: old ids, focus and hover mean nothing.
            ids.clear()
            owner = src.owner
            generation = src.generation
            focusedId = NONE
            hoveredId = NONE
        }
        val nodes = A11yFragments.build(src.layout, src.page, src.left, src.top, w, h)
        val entries = ArrayList<Entry>(nodes.size)
        for (n in nodes) entries += Entry(ids.idFor(A11yKey(src.section, n.start, n.role)), n)
        ids.trim(nodes.size)
        return Snapshot(src, w, h, entries, nodes).also { snap = it }
    }

    private fun entry(id: Int): Entry? = snapshot()?.entries?.firstOrNull { it.id == id }

    @Suppress("DEPRECATION")
    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        val s = snapshot()
        if (virtualViewId == HOST_VIEW_ID) {
            val info = AccessibilityNodeInfo.obtain(view)
            view.onInitializeAccessibilityNodeInfo(info)
            if (s != null) for (e in s.entries) info.addChild(view, e.id)
            return info
        }
        val e = s?.entries?.firstOrNull { it.id == virtualViewId } ?: return null
        val n = e.node
        val info = AccessibilityNodeInfo.obtain(view, virtualViewId)
        info.setParent(view)
        info.packageName = view.context.packageName
        info.className = className(n)
        info.text = n.text
        info.isEnabled = true
        info.isFocusable = true
        info.isVisibleToUser = view.isShown
        info.isAccessibilityFocused = focusedId == virtualViewId
        if (Build.VERSION.SDK_INT >= 28 && n.heading) info.isHeading = true
        val inParent = Rect(n.left, n.top, n.right, n.bottom)
        info.setBoundsInParent(inParent)
        view.getLocationOnScreen(loc)
        info.setBoundsInScreen(Rect(inParent).apply { offset(loc[0], loc[1]) })
        info.addAction(
            if (focusedId == virtualViewId) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS,
        )
        if (n.role == A11yRole.LINK) {
            info.isClickable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }
        return info
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        // The host: the scroll actions (and the platform's own) as before.
        if (virtualViewId == HOST_VIEW_ID) return view.performAccessibilityAction(action, arguments)
        val s = snapshot() ?: return false
        val e = s.entries.firstOrNull { it.id == virtualViewId } ?: return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focusedId == virtualViewId) return false
                val old = focusedId
                focusedId = virtualViewId
                if (old != NONE) send(old, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focusedId != virtualViewId) return false
                focusedId = NONE
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLICK -> {
                val href = e.node.href ?: return false
                // The same handler a tap on the link reaches.
                cb.onAccessibilityLink(s.source.section, href)
                true
            }
            else -> false
        }
    }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? {
        if (focus != AccessibilityNodeInfo.FOCUS_ACCESSIBILITY || focusedId == NONE) return null
        return if (entry(focusedId) != null) createAccessibilityNodeInfo(focusedId) else null
    }

    /**
     * Touch exploration's hover: the node under the finger is entered (the previous one exited), so the service finds
     * the text. True when a node took the event; the area between paragraphs falls back to the plain view.
     */
    fun onHover(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val s = snapshot()
                val i = if (s == null) -1 else A11yFragments.indexAt(s.nodes, ev.x, ev.y)
                val id = if (s != null && i >= 0) s.entries[i].id else NONE
                setHovered(id)
                return id != NONE
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hoveredId == NONE) return false
                setHovered(NONE)
                return true
            }
        }
        return false
    }

    private fun setHovered(id: Int) {
        if (id == hoveredId) return
        val old = hoveredId
        hoveredId = id
        if (old != NONE) send(old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        if (id != NONE) send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
    }

    /**
     * After the host's tree-changed event for a page change: when a node had accessibility focus, it moves to the
     * first paragraph of the new page (the old node is gone). No focus before: nothing is moved or announced here.
     */
    fun afterPageChange() {
        if (focusedId == NONE) return
        val first = snapshot()?.entries?.firstOrNull { it.node.role == A11yRole.PARAGRAPH }
        if (first == null) {
            focusedId = NONE
            return
        }
        if (first.id == focusedId) return
        focusedId = first.id
        send(first.id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    }

    @Suppress("DEPRECATION")
    private fun send(id: Int, type: Int) {
        if (manager?.isEnabled != true) return
        val e = AccessibilityEvent.obtain(type)
        val en = snap?.entries?.firstOrNull { it.id == id }
        e.setSource(view, id)
        e.packageName = view.context.packageName
        e.isEnabled = true
        if (en != null) {
            e.className = className(en.node)
            e.text.add(en.node.text)
        }
        view.parent?.requestSendAccessibilityEvent(view, e)
    }

    private fun className(n: A11yNode): String =
        if (n.role == A11yRole.LINK) "android.widget.Button" else "android.widget.TextView"

    companion object {
        /** No node (virtual ids are positive). */
        const val NONE = Int.MIN_VALUE
    }
}
