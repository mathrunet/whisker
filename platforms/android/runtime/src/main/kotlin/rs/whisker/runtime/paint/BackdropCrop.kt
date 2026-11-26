package rs.whisker.runtime.paint

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Root-space rectangle a backdrop blur must record: the target's bounds grown by the blur's reach,
 * kept inside the root so the screen edges still clamp exactly as a full-root snapshot would.
 *
 * Recording only this region keeps the per-frame cost proportional to the blurred surface rather
 * than the whole screen, which matters while content scrolls beneath a fixed backdrop.
 *
 * Returns `[left, top, right, bottom]`, or null when nothing of the target lies inside the root.
 */
internal fun backdropCropBounds(
    targetLeft: Float,
    targetTop: Float,
    targetWidth: Float,
    targetHeight: Float,
    rootWidth: Int,
    rootHeight: Int,
    radiusPx: Float,
): IntArray? {
    // RenderEffect's blur radius maps to sigma ≈ 0.57735 * radius + 0.5; three sigmas cover the kernel.
    val reach = ceil(radiusPx * 1.75f + 1.5f)
    val left = floor(targetLeft - reach).toInt().coerceAtLeast(0)
    val top = floor(targetTop - reach).toInt().coerceAtLeast(0)
    val right = ceil(targetLeft + targetWidth + reach).toInt().coerceAtMost(rootWidth)
    val bottom = ceil(targetTop + targetHeight + reach).toInt().coerceAtMost(rootHeight)
    if (right <= left || bottom <= top) return null
    return intArrayOf(left, top, right, bottom)
}

/**
 * Resolution the backdrop is recorded and blurred at, as a fraction of the screen's.
 *
 * A blur removes the detail a smaller recording loses, so a wide blur is recorded at a quarter or
 * half of the resolution and scaled back up. The GPU then filters 1/16 or 1/4 of the pixels. The
 * blurred radius at the reduced resolution stays at least 4 px, which keeps the result smooth.
 */
internal fun backdropRecordingScale(radiusPx: Float): Float = when {
    radiusPx >= 32f -> 0.25f
    radiusPx >= 8f -> 0.5f
    else -> 1f
}

/**
 * RenderEffect blur radius that, applied to a recording reduced by [scale], matches the sigma of
 * [radiusPx] at full resolution. Skia maps a radius r to sigma = 0.57735 * r + 0.5 in the pixels it
 * filters, so the constant term has to be corrected as well as the radius scaled.
 */
internal fun scaledBackdropBlurRadius(radiusPx: Float, scale: Float): Float {
    if (scale >= 1f) return radiusPx
    val sigma = (RADIUS_TO_SIGMA * radiusPx + 0.5f) * scale
    return ((sigma - 0.5f) / RADIUS_TO_SIGMA).coerceAtLeast(0f)
}

private const val RADIUS_TO_SIGMA = 0.57735f

/**
 * True when a recording of [recorded] can serve a target that now needs [needed].
 *
 * Recordings are in root space, so a larger recording still holds the pixels behind a target that
 * moved inside it. Once the target settles, a recording larger than needed is made again at the
 * tight size, because the GPU blurs the whole recording whenever its content changes.
 */
internal fun reusesBackdropCrop(recorded: IntArray?, needed: IntArray, moving: Boolean): Boolean {
    if (recorded == null) return false
    if (recorded.contentEquals(needed)) return true
    if (!moving) return false
    return recorded[0] <= needed[0] && recorded[1] <= needed[1] &&
        recorded[2] >= needed[2] && recorded[3] >= needed[3]
}

/** How long after its last move a backdrop target keeps reusing an enlarged recording. */
internal const val BACKDROP_SETTLE_MILLIS = 300L
