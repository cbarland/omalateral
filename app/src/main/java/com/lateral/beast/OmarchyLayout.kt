package com.lateral.beast

import kotlin.math.abs

enum class WorkspaceLayout { DWINDLE, SCROLLING }
enum class WindowDirection { LEFT, RIGHT, UP, DOWN }

internal data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height
    val centerX get() = x + width / 2f
    val centerY get() = y + height / 2f
}

/** Omarchy v4.0.4: preserve_split, force_split=2, square frames and 5/10 gaps. */
internal class DwindleLayout(saved: String? = null) {
    private sealed interface Node
    private data class Leaf(val id: Long) : Node
    private data class Split(var horizontal: Boolean, var ratio: Float, var first: Node, var second: Node) : Node
    private var root: Node? = decode(saved)

    fun sync(ids: List<Long>, focused: Long?, width: Int, height: Int, anchors: Map<Long, Long?> = emptyMap()) {
        fun prune(node: Node?): Node? = when (node) {
            is Leaf -> node.takeIf { it.id in ids }
            is Split -> {
                val first = prune(node.first); val second = prune(node.second)
                if (first == null) second else if (second == null) first
                else node.apply { this.first = first; this.second = second }
            }
            null -> null
        }
        root = prune(root)
        for (id in ids) {
            val placed = bounds(width, height, 0, 0)
            if (id in placed) continue
            val target = anchors[id]?.takeIf { it in placed } ?: focused?.takeIf { it in placed } ?: placed.keys.lastOrNull()
            val cell = placed[target]
            fun insert(node: Node): Node = when (node) {
                is Leaf -> if (node.id == target) Split((cell?.width ?: width) >= (cell?.height ?: height), .5f, node, Leaf(id)) else node
                is Split -> node.apply { first = insert(first); second = insert(second) }
            }
            root = root?.let(::insert) ?: Leaf(id)
        }
    }

    fun bounds(width: Int, height: Int, outer: Int, gap: Int): Map<Long, WindowBounds> {
        val result = linkedMapOf<Long, WindowBounds>()
        fun place(node: Node, box: WindowBounds) {
            when (node) {
                is Leaf -> result[node.id] = box
                is Split -> {
                    val size = if (node.horizontal) box.width else box.height
                    val spacing = gap.coerceIn(0, (size - 2).coerceAtLeast(0))
                    val available = (size - spacing).coerceAtLeast(2)
                    val firstSize = (available * node.ratio).toInt().coerceIn(1, available - 1)
                    val secondSize = available - firstSize
                    if (node.horizontal) {
                        place(node.first, box.copy(width = firstSize))
                        place(node.second, box.copy(x = box.x + firstSize + spacing, width = secondSize))
                    } else {
                        place(node.first, box.copy(height = firstSize))
                        place(node.second, box.copy(y = box.y + firstSize + spacing, height = secondSize))
                    }
                }
            }
        }
        val inset = outer.coerceIn(0, ((minOf(width, height) - 2) / 2).coerceAtLeast(0))
        root?.let { place(it, WindowBounds(inset, inset, (width - 2 * inset).coerceAtLeast(1), (height - 2 * inset).coerceAtLeast(1))) }
        return result
    }

    private fun contains(node: Node, id: Long): Boolean = when (node) {
        is Leaf -> node.id == id
        is Split -> contains(node.first, id) || contains(node.second, id)
    }

    private fun parent(id: Long, horizontal: Boolean? = null): Split? {
        fun find(node: Node?): Split? = if (node is Split && contains(node, id)) {
            find(if (contains(node.first, id)) node.first else node.second)
                ?: node.takeIf { horizontal == null || it.horizontal == horizontal }
        } else null
        return find(root)
    }

    fun toggleSplit(id: Long) { parent(id)?.let { it.horizontal = !it.horizontal } }
    fun resize(id: Long, horizontal: Boolean, delta: Float) {
        parent(id, horizontal)?.let { split ->
            split.ratio = (split.ratio + if (contains(split.first, id)) delta else -delta).coerceIn(.15f, .85f)
        }
    }

    fun swap(first: Long, second: Long) {
        fun replace(node: Node): Node = when (node) {
            is Leaf -> Leaf(when (node.id) { first -> second; second -> first; else -> node.id })
            is Split -> node.apply { this.first = replace(this.first); this.second = replace(this.second) }
        }
        root = root?.let(::replace)
    }

    fun save(): String {
        fun encode(node: Node): String = when (node) {
            is Leaf -> "L,${node.id}"
            is Split -> "${if (node.horizontal) "H" else "V"},${node.ratio},${encode(node.first)},${encode(node.second)}"
        }
        return root?.let(::encode).orEmpty()
    }

    private fun decode(saved: String?): Node? = runCatching {
        if (saved.isNullOrBlank()) return@runCatching null
        val parts = saved.split(','); require(parts.size <= 512)
        var index = 0
        fun read(): Node = when (val kind = parts[index++]) {
            "L" -> Leaf(parts[index++].toLong())
            "H", "V" -> { val ratio = parts[index++].toFloat(); require(ratio in .15f.. .85f); Split(kind == "H", ratio, read(), read()) }
            else -> error("invalid split")
        }
        read().also { require(index == parts.size) }
    }.getOrNull()
}

/** Prefer a neighbor sharing an edge; diagonal windows are a fallback. */
internal fun directionalWindow(bounds: Map<Long, WindowBounds>, focused: Long?, direction: WindowDirection): Long? {
    val current = bounds[focused] ?: return bounds.keys.firstOrNull()
    return bounds.entries.filter { (id, box) ->
        id != focused && when (direction) {
            WindowDirection.LEFT -> box.centerX < current.centerX
            WindowDirection.RIGHT -> box.centerX > current.centerX
            WindowDirection.UP -> box.centerY < current.centerY
            WindowDirection.DOWN -> box.centerY > current.centerY
        }
    }.minByOrNull { (_, box) ->
        val horizontal = direction == WindowDirection.LEFT || direction == WindowDirection.RIGHT
        val overlap = if (horizontal) box.y < current.bottom && box.bottom > current.y else box.x < current.right && box.right > current.x
        val distance = if (horizontal) abs(box.centerX - current.centerX) else abs(box.centerY - current.centerY)
        val cross = if (horizontal) abs(box.centerY - current.centerY) else abs(box.centerX - current.centerX)
        (if (overlap) 0f else 1_000_000f) + distance + cross * .25f
    }?.key
}
