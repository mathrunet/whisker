package rs.whisker.runtime.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.whisker.runtime.scene.BackdropDependency.Recapture
import rs.whisker.runtime.scene.BackdropDependency.Redraw
import rs.whisker.runtime.scene.BackdropDependency.Reposition
import kotlin.random.Random

class BackdropRegistryTest {
    private class Node(val parent: Node?, val paints: Boolean = false) {
        val children = mutableListOf<Node>()

        init {
            parent?.children?.add(this)
        }
    }

    private fun registry() = BackdropRegistry<Node>(
        parentOf = Node::parent,
        drawsBefore = { parent, first, second ->
            parent.children.indexOf(first) < parent.children.indexOf(second)
        },
        paintsBefore = { parent, child ->
            parent.paints || parent.children.indexOf(child) > 0
        },
    )

    private val root = Node(null)
    private val underScreen = Node(root)
    private val underScreenContent = Node(underScreen)
    private val topScreen = Node(root)
    private val backdrop = Node(topScreen)
    private val backdropChild = Node(backdrop)
    private val scrollingContent = Node(topScreen)
    private val scrollingRow = Node(scrollingContent)
    private val floatingButton = Node(topScreen)

    @Test
    fun `a moving ancestor re-records the backdrop`() {
        val registry = registry()
        registry.update(backdrop, active = true)

        assertEquals(listOf(backdrop to Recapture), registry.affectedBy(topScreen))
        assertEquals(listOf(backdrop to Recapture), registry.affectedBy(root))
    }

    @Test
    fun `content painted before the backdrop only redraws it`() {
        val registry = registry()
        registry.update(backdrop, active = true)

        assertEquals(listOf(backdrop to Redraw), registry.affectedBy(underScreen))
        assertEquals(listOf(backdrop to Redraw), registry.affectedBy(underScreenContent))
    }

    @Test
    fun `the backdrop's own subtree does not re-record it`() {
        val registry = registry()
        registry.update(backdrop, active = true)

        assertTrue(registry.affectedBy(backdrop).isEmpty())
        assertTrue(registry.affectedBy(backdropChild).isEmpty())
    }

    @Test
    fun `scrolling above the page backdrop only redraws a later backdrop`() {
        val registry = registry()
        registry.update(backdrop, active = true)
        registry.update(floatingButton, active = true)

        assertEquals(listOf(floatingButton to Redraw), registry.affectedBy(scrollingContent))
        assertEquals(listOf(floatingButton to Redraw), registry.affectedBy(scrollingRow))
        assertEquals(listOf(floatingButton to Redraw), registry.affectedBy(backdrop))
        assertTrue(registry.affectedBy(floatingButton).isEmpty())
    }

    @Test
    fun `an inactive backdrop is forgotten`() {
        val registry = registry()
        registry.update(backdrop, active = true)
        registry.update(backdrop, active = false)

        assertTrue(registry.isEmpty)
        assertTrue(registry.affectedBy(underScreen).isEmpty())
    }

    @Test
    fun `a sliding ancestor that paints nothing before the backdrop only repositions it`() {
        val registry = registry()
        registry.update(backdrop, active = true)

        assertEquals(listOf(backdrop to Reposition), registry.affectedBy(topScreen, BackdropChange.Transform))
        // Moving the root also moves the screen beneath, which it paints before the backdrop.
        assertEquals(listOf(backdrop to Recapture), registry.affectedBy(root, BackdropChange.Transform))
    }

    @Test
    fun `a fading ancestor that paints nothing before the backdrop leaves it alone`() {
        val registry = registry()
        registry.update(backdrop, active = true)

        assertTrue(registry.affectedBy(topScreen, BackdropChange.Opacity).isEmpty())
    }

    @Test
    fun `a moving ancestor that paints before the backdrop still re-records it`() {
        val registry = registry()
        registry.update(floatingButton, active = true)

        // The backdrop and the scrolling list are painted before the button inside the same page.
        assertEquals(listOf(floatingButton to Recapture), registry.affectedBy(topScreen, BackdropChange.Transform))
        assertEquals(listOf(floatingButton to Recapture), registry.affectedBy(topScreen, BackdropChange.Opacity))

        val painted = Node(root, paints = true)
        val sheet = Node(painted)
        val sheetRegistry = registry()
        sheetRegistry.update(sheet, active = true)
        assertEquals(listOf(sheet to Recapture), sheetRegistry.affectedBy(painted, BackdropChange.Transform))
    }

    @Test
    fun `property changes below the ancestors keep their content rules`() {
        val registry = registry()
        registry.update(backdrop, active = true)

        assertEquals(listOf(backdrop to Redraw), registry.affectedBy(underScreen, BackdropChange.Transform))
        assertTrue(registry.affectedBy(backdrop, BackdropChange.Transform).isEmpty())
    }

    @Test
    fun `the allocation free lookup matches root path comparison`() {
        val random = Random(7)
        repeat(40) {
            val nodes = mutableListOf(Node(null))
            repeat(60) { nodes += Node(nodes[random.nextInt(nodes.size)]) }
            val registry = registry()
            val backdrops = nodes.shuffled(random).take(6)
            backdrops.forEach { registry.update(it, active = true) }
            for (target in nodes) {
                val expected = backdrops.mapNotNull { backdrop ->
                    referenceDependency(target, backdrop)?.let { backdrop to it }
                }
                assertEquals(expected.toSet(), registry.affectedBy(target).toSet())
            }
        }
    }

    /** The previous implementation, which compared full root paths. */
    private fun referenceDependency(target: Node, backdrop: Node): BackdropDependency? {
        fun path(node: Node) = generateSequence(node, Node::parent).toList().asReversed()
        val targetPath = path(target)
        val backdropPath = path(backdrop)
        val shared = minOf(targetPath.size, backdropPath.size)
        var first = 0
        while (first < shared && targetPath[first] === backdropPath[first]) first++
        if (first == shared) return if (targetPath.size < backdropPath.size) Recapture else null
        if (first == 0) return null
        val parent = targetPath[first - 1]
        return if (parent.children.indexOf(targetPath[first]) < parent.children.indexOf(backdropPath[first])) {
            Redraw
        } else {
            null
        }
    }
}
