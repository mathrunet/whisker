package rs.whisker.runtime.paint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropCropTest {
    @Test
    fun `records only the target grown by the blur reach`() {
        // reach = ceil(16 * 1.75 + 1.5) = 30
        val crop = backdropCropBounds(40f, 2000f, 1000f, 200f, 1080, 2520, 16f)
        assertArrayEquals(intArrayOf(10, 1970, 1070, 2230), crop)
    }

    @Test
    fun `keeps the crop inside the root so screen edges clamp as before`() {
        val crop = backdropCropBounds(0f, 2400f, 1080f, 200f, 1080, 2520, 16f)
        assertArrayEquals(intArrayOf(0, 2370, 1080, 2520), crop)
    }

    @Test
    fun `skips a target entirely outside the root`() {
        assertNull(backdropCropBounds(0f, 3000f, 1080f, 200f, 1080, 2520, 16f))
    }
}

class BackdropRecordingScaleTest {
    @Test
    fun `wide blurs are recorded at reduced resolution`() {
        assertEquals(1f, backdropRecordingScale(6f), 0f)
        assertEquals(0.5f, backdropRecordingScale(10.5f), 0f)
        assertEquals(0.25f, backdropRecordingScale(36.75f), 0f)
    }

    @Test
    fun `the reduced blur keeps the full resolution sigma`() {
        for (radius in listOf(8f, 10.5f, 31f, 32f, 36.75f, 64f)) {
            val scale = backdropRecordingScale(radius)
            val reduced = scaledBackdropBlurRadius(radius, scale)
            val fullSigma = 0.57735f * radius + 0.5f
            val reducedSigmaInScreenPixels = (0.57735f * reduced + 0.5f) / scale
            assertEquals(fullSigma, reducedSigmaInScreenPixels, 1e-3f)
            // Keeps enough of a kernel at the reduced resolution to stay smooth.
            assertTrue(reduced >= 3.5f)
        }
        assertEquals(6f, scaledBackdropBlurRadius(6f, 1f), 0f)
    }

    @Test
    fun `a settled target reuses only an exact recording`() {
        val crop = intArrayOf(10, 20, 110, 220)
        assertTrue(reusesBackdropCrop(crop.copyOf(), crop, moving = false))
        assertFalse(reusesBackdropCrop(intArrayOf(0, 0, 1080, 2400), crop, moving = false))
        assertFalse(reusesBackdropCrop(null, crop, moving = true))
    }

    @Test
    fun `a moving target reuses any recording that still covers it`() {
        val full = intArrayOf(0, 0, 1080, 2400)
        assertTrue(reusesBackdropCrop(full, intArrayOf(10, 300, 1080, 2400), moving = true))
        assertFalse(reusesBackdropCrop(intArrayOf(0, 400, 1080, 2400), intArrayOf(0, 300, 1080, 2400), moving = true))
    }
}
