package dev.aarso.viz

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/**
 * Every mark of a chart as a virtual accessibility node: its name and figure as the description, a
 * finger-sized target as its bounds, and a click that selects it. The nodes come from the view
 * ([VizView.a11yNodes]); this only presents them, so TalkBack, Switch Access and the tests all see
 * what the sighted user is given.
 */
internal class VizAccessibility(private val view: VizView) : ExploreByTouchHelper(view) {
    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val d = view.density
        return view.hit(view.a11yNodes, (x - view.paddingLeft) / d, (y - view.paddingTop) / d).takeIf { it >= 0 } ?: INVALID_ID
    }

    override fun getVisibleVirtualViews(ids: MutableList<Int>) {
        for (i in view.a11yNodes.indices) ids.add(i)
    }

    override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
        val n = view.a11yNodes.getOrNull(id)
        if (n == null) {
            node.contentDescription = ""
            node.setBoundsInParent(Rect(0, 0, 1, 1))
            return
        }
        val d = view.density
        val r = Rect(
            (n.target.left * d).toInt() + view.paddingLeft, (n.target.top * d).toInt() + view.paddingTop,
            (n.target.right * d).toInt() + view.paddingLeft, (n.target.bottom * d).toInt() + view.paddingTop,
        )
        if (r.isEmpty) r.set(r.left, r.top, r.left + 1, r.top + 1)
        node.contentDescription = n.description
        node.setBoundsInParent(r)
        node.className = "android.view.View"
        if (view.onSelect != null && n.mark != null) {
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
            node.isClickable = true
        }
    }

    override fun onPopulateEventForVirtualView(id: Int, event: AccessibilityEvent) {
        event.contentDescription = view.a11yNodes.getOrNull(id)?.description ?: ""
    }

    override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) return view.selectNode(id)
        return false
    }

    override fun onVirtualViewKeyboardFocusChanged(id: Int, hasFocus: Boolean) {
        view.keyboardFocusChanged(id, hasFocus)
    }
}
