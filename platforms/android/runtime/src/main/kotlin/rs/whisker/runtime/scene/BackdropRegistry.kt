package rs.whisker.runtime.scene

/** How a change elsewhere in the tree affects one backdrop node. */
internal enum class BackdropDependency {
    /**
     * The change is baked into the backdrop recording (an ancestor, which the capture replays
     * inline, or the tree structure above it), so the recording must be made again.
     */
    Recapture,

    /**
     * An ancestor moved, but nothing it paints before the backdrop is part of the recording. The
     * recorded root-space pixels are still valid; only the node's offset into them changed.
     */
    Reposition,

    /**
     * The change is inside content painted before the backdrop. The recording references that
     * content's RenderNode, so the recorded pixels follow it without a new recording. The backdrop
     * node only redraws so its whole blurred area is repainted with the current content.
     */
    Redraw,
}

/** What kind of change invalidated a view, as far as backdrop recordings are concerned. */
internal enum class BackdropChange {
    /** The view's display list changed, or the change is unknown. */
    Content,

    /** Only the view's translation or scale RenderNode properties changed. */
    Transform,

    /** Only the view's alpha RenderNode property changed. */
    Opacity,
}

/**
 * Tracks the nodes that paint a backdrop blur and decides which of them must re-record.
 *
 * A backdrop node bakes a snapshot of everything painted before it, plus its own offset from the
 * Whisker root, into its display list. Android only re-records that list when the node itself is
 * invalidated, so a change elsewhere (an ancestor sliding in, the screen beneath repainting) would
 * otherwise leave a stale, shifted blur on screen.
 *
 * Lookups run on every descendant invalidation (each scrolled or animated frame), so they walk the
 * target's ancestors against per-backdrop index maps instead of allocating root paths.
 */
internal class BackdropRegistry<V : Any>(
    private val parentOf: (V) -> V?,
    private val drawsBefore: (parent: V, first: V, second: V) -> Boolean,
    /**
     * True when [parent] paints anything of its own, or any child, before [child]. Such content is
     * replayed inline by a capture, so moving [parent] changes the recorded pixels.
     */
    private val paintsBefore: (parent: V, child: V) -> Boolean = { _, _ -> true },
) {
    private class Entry<V>(val path: List<V>, val depthOf: Map<V, Int>)

    // A node keeps its ancestors while it stays attached (Android detaches a view before it can be
    // re-parented, and detaching unregisters it), so the path is computed once per registration.
    private val nodes = LinkedHashMap<V, Entry<V>>()

    val isEmpty: Boolean
        get() = nodes.isEmpty()

    fun update(node: V, active: Boolean) {
        if (!active) {
            nodes.remove(node)
            return
        }
        if (node in nodes) return
        val path = generateSequence(node, parentOf).toList().asReversed()
        val depthOf = HashMap<V, Int>(path.size * 2)
        path.forEachIndexed { index, view -> depthOf[view] = index }
        nodes[node] = Entry(path, depthOf)
    }

    /** Calls [action] for every backdrop whose output may depend on [target]. */
    fun forEachAffectedBy(
        target: V,
        change: BackdropChange = BackdropChange.Content,
        action: (V, BackdropDependency) -> Unit,
    ) {
        if (nodes.isEmpty()) return
        for ((backdrop, entry) in nodes) {
            dependency(target, change, entry)?.let { action(backdrop, it) }
        }
    }

    fun affectedBy(
        target: V,
        change: BackdropChange = BackdropChange.Content,
    ): List<Pair<V, BackdropDependency>> {
        val affected = ArrayList<Pair<V, BackdropDependency>>()
        forEachAffectedBy(target, change) { backdrop, dependency -> affected += backdrop to dependency }
        return affected
    }

    private fun dependency(target: V, change: BackdropChange, entry: Entry<V>): BackdropDependency? {
        val path = entry.path
        val last = path.size - 1
        // Walk up from the target until it meets the backdrop's root path.
        var below: V? = null
        var current: V? = target
        while (current != null) {
            val depth = entry.depthOf[current]
            if (depth != null) {
                if (below == null) {
                    // The target is on the backdrop's path: the backdrop itself, or an ancestor.
                    if (depth == last) return null
                    return ancestorDependency(depth, change, path)
                }
                // The backdrop's own subtree paints after the snapshot.
                if (depth == last) return null
                return if (drawsBefore(current, below, path[depth + 1])) {
                    BackdropDependency.Redraw
                } else {
                    null
                }
            }
            below = current
            current = parentOf(current)
        }
        return null
    }

    private fun ancestorDependency(
        depth: Int,
        change: BackdropChange,
        path: List<V>,
    ): BackdropDependency? {
        if (change == BackdropChange.Content) return BackdropDependency.Recapture
        // A property change of an ancestor only affects the recording through what that ancestor's
        // subtree paints before the backdrop; the screen beneath is referenced, not replayed.
        for (index in depth until path.size - 1) {
            if (paintsBefore(path[index], path[index + 1])) return BackdropDependency.Recapture
        }
        // Alpha is applied when the ancestor's layer is composited, around the blurred result.
        return if (change == BackdropChange.Transform) BackdropDependency.Reposition else null
    }
}
