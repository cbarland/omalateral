package com.lateral.beast

import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/** Let native clones below our window receive input, while keeping our chrome interactive. */
internal object ProtectedTileInputRegions {
    private val tiles = WeakHashMap<ProtectedTileSurface, Boolean>()
    private val regions = WeakHashMap<View, Region>()
    private val windowControlOwners = WeakHashMap<View, Boolean>()

    fun windowControls(view: View, active: Boolean) {
        if (Build.VERSION.SDK_INT < 35) return
        val root = view.rootView
        if (active) windowControlOwners[root] = true else windowControlOwners.remove(root)
        regions.remove(root)
        root.rootSurfaceControl?.setTouchableRegion(null)
        if (!active) tiles.keys.filter { it.rootView === root }.toList().forEach { update(it, it.isShown && it.protectedContent) }
    }

    fun update(tile: ProtectedTileSurface, active: Boolean) {
        if (Build.VERSION.SDK_INT < 35) return
        if (active) tiles[tile] = true else tiles.remove(tile)
        val root = tile.rootView
        val surface = root.rootSurfaceControl ?: return
        if (windowControlOwners[root] == true) { surface.setTouchableRegion(null); return }
        val protected = tiles.keys.filter { it.rootView === root && it.isShown && it.protectedContent }
        if (protected.isEmpty()) {
            if (regions.remove(root) != null) surface.setTouchableRegion(null)
            return
        }
        val origin = IntArray(2); root.getLocationOnScreen(origin)
        val region = Region(0, 0, root.width, root.height)
        fun bounds(view: View): Rect? = Rect().takeIf { view.getGlobalVisibleRect(it) }?.apply { offset(-origin[0],-origin[1]) }
        protected.forEach { tile ->
            tile.contentBoundsOnScreen()?.apply { offset(-origin[0], -origin[1]) }
                ?.let { region.op(it, Region.Op.DIFFERENCE) }
        }

        fun retainControls(view: View) {
            if (!view.isShown || view.alpha <= 0f) return
            if (view.isClickable || view.isLongClickable || view.isFocusable) {
                bounds(view)?.let { region.op(it, Region.Op.UNION) }
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) retainControls(view.getChildAt(i))
            }
        }
        protected.forEach { tile ->
            // Only siblings painted AFTER this tile can cover it. Ancestors, the
            // full-screen cursor and the desktop behind the task must not fill the hole.
            var child: View = tile
            while (true) {
                val parent = child.parent as? ViewGroup ?: break
                val ordered = (0 until parent.childCount).map(parent::getChildAt).sortedBy { it.z }
                ordered.drop(ordered.indexOf(child)+1).forEach(::retainControls)
                child = parent
            }
        }
        if (regions[root] != region) {
            regions[root] = Region(region)
            surface.setTouchableRegion(region)
        }
    }
}
