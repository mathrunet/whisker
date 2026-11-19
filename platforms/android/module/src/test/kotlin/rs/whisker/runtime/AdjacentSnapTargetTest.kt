package rs.whisker.runtime

import org.junit.Assert.*
import org.junit.Test

class AdjacentSnapTargetTest {
    private val targets = listOf(0, 390, 780, 1170)

    @Test fun stepsFromRestingSnapPoint() {
        assertEquals(390, adjacentSnapTarget(targets, 0, 1))
        assertEquals(390, adjacentSnapTarget(targets, 780, -1))
    }

    @Test fun interruptedSettleDoesNotPickThePreviousTargetAgain() {
        // The previous snap was settling toward 390 and stopped just short of it.
        assertEquals(780, adjacentSnapTarget(targets, 389, 1))
        assertEquals(0, adjacentSnapTarget(targets, 389, -1))
        // Settling backward toward 390 from above.
        assertEquals(0, adjacentSnapTarget(targets, 391, -1))
        assertEquals(780, adjacentSnapTarget(targets, 391, 1))
    }

    @Test fun clampsAtEdges() {
        assertEquals(1170, adjacentSnapTarget(targets, 1170, 1))
        assertEquals(0, adjacentSnapTarget(targets, 0, -1))
        assertEquals(0, adjacentSnapTarget(targets, -40, -1))
    }

    @Test fun returnsNullWithoutTargets() {
        assertNull(adjacentSnapTarget(emptyList(), 100, 1))
    }
}
