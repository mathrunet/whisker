package rs.whisker.runtime.paint

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.SystemClock
import android.os.Trace
import android.view.View
import androidx.annotation.RequiresApi
import rs.whisker.runtime.WhiskerView
import rs.whisker.runtime.scene.HostNode
import kotlin.math.ceil

/** API 31+ renderer for Whisker's single backdrop blur primitive. */
@RequiresApi(31)
internal class HostBackdropBlurRenderer {
    private val backdrop = RenderNode("whisker-backdrop")
    private var appliedRadiusPx = 0f
    private var recordedCrop: IntArray? = null
    private var recordedScale = 1f
    private var recordedScaleX = 1f
    private var recordedScaleY = 1f
    private var lastMoveUptime = Long.MIN_VALUE / 2

    fun invalidateCapture() {
        recordedCrop = null
    }

    /**
     * Records that the target moved without anything in its recording changing. While it keeps
     * moving, the next capture covers the whole root so later frames only shift into it.
     */
    fun notePositionChanged() {
        lastMoveUptime = SystemClock.uptimeMillis()
    }

    fun draw(
        canvas: Canvas,
        root: WhiskerView,
        target: HostNode,
        radiusPx: Float,
        clip: Path,
    ) {
        if (radiusPx <= 0f || root.width <= 0 || root.height <= 0) return
        val offset = descendantOffset(root, target)
        val crop = backdropCropBounds(
            offset[0],
            offset[1],
            target.width.toFloat(),
            target.height.toFloat(),
            root.width,
            root.height,
            radiusPx,
        ) ?: return
        val (left, top, right, bottom) = crop
        if (!canvas.isHardwareAccelerated) {
            val bitmap = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
            try {
                val recordingCanvas = Canvas(bitmap)
                recordingCanvas.translate(-left.toFloat(), -top.toFloat())
                root.recordBackdrop(recordingCanvas, target)
                boxBlur(bitmap, radiusPx.toInt().coerceIn(1, 64))
                val save = canvas.save()
                canvas.clipPath(clip)
                canvas.drawBitmap(bitmap, left - offset[0], top - offset[1], null)
                canvas.restoreToCount(save)
            } finally {
                bitmap.recycle()
            }
            return
        }

        val scale = backdropRecordingScale(radiusPx)
        val moving = SystemClock.uptimeMillis() - lastMoveUptime < BACKDROP_SETTLE_MILLIS
        val reusable = recordedScale == scale && backdrop.hasDisplayList() &&
            reusesBackdropCrop(recordedCrop, crop, moving)
        if (!reusable) {
            // A repaint of the filtered node (for example its tint) does not
            // necessarily change the pixels behind it.
            recordedCrop = null
            val recordCrop = if (moving) intArrayOf(0, 0, root.width, root.height) else crop
            val (recordLeft, recordTop, recordRight, recordBottom) = recordCrop
            val width = recordRight - recordLeft
            val height = recordBottom - recordTop
            val recordedWidth = ceil(width * scale).toInt().coerceAtLeast(1)
            val recordedHeight = ceil(height * scale).toInt().coerceAtLeast(1)
            // The RenderNode's own size is what HWUI allocates for the blur layer, so a reduced
            // recording also reduces the filtered pixels.
            backdrop.setPosition(0, 0, recordedWidth, recordedHeight)
            val recordingCanvas = backdrop.beginRecording(recordedWidth, recordedHeight)
            val tracing = Trace.isEnabled()
            if (tracing) {
                Trace.beginSection("Whisker backdrop capture")
                Trace.setCounter(
                    "Whisker backdrop capture pixels",
                    recordedWidth.toLong() * recordedHeight,
                )
            }
            try {
                recordedScaleX = recordedWidth.toFloat() / width
                recordedScaleY = recordedHeight.toFloat() / height
                recordingCanvas.scale(recordedScaleX, recordedScaleY)
                recordingCanvas.translate(-recordLeft.toFloat(), -recordTop.toFloat())
                root.recordBackdrop(recordingCanvas, target)
            } finally {
                if (tracing) Trace.endSection()
                backdrop.endRecording()
            }
            recordedCrop = recordCrop
            recordedScale = scale
        }
        val recorded = checkNotNull(recordedCrop)
        val effectRadius = scaledBackdropBlurRadius(radiusPx, scale)
        if (effectRadius != appliedRadiusPx) {
            backdrop.setRenderEffect(
                RenderEffect.createBlurEffect(effectRadius, effectRadius, Shader.TileMode.CLAMP),
            )
            appliedRadiusPx = effectRadius
        }

        val save = canvas.save()
        canvas.clipPath(clip)
        canvas.translate(recorded[0] - offset[0], recorded[1] - offset[1])
        canvas.scale(1f / recordedScaleX, 1f / recordedScaleY)
        canvas.drawRenderNode(backdrop)
        canvas.restoreToCount(save)
    }

    /** Two linear passes keep the software/screenshot fallback bounded. */
    private fun boxBlur(bitmap: Bitmap, radius: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val source = IntArray(width * height)
        val temporary = IntArray(source.size)
        val output = IntArray(source.size)
        bitmap.getPixels(source, 0, width, 0, 0, width, height)
        val diameter = radius * 2 + 1

        for (y in 0 until height) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = source[y * width + offset.coerceIn(0, width - 1)]
                alpha += color ushr 24
                red += color ushr 16 and 0xff
                green += color ushr 8 and 0xff
                blue += color and 0xff
            }
            for (x in 0 until width) {
                temporary[y * width + x] =
                    (alpha / diameter shl 24) or (red / diameter shl 16) or
                    (green / diameter shl 8) or (blue / diameter)
                val leaving = source[y * width + (x - radius).coerceIn(0, width - 1)]
                val entering = source[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                alpha += (entering ushr 24) - (leaving ushr 24)
                red += (entering ushr 16 and 0xff) - (leaving ushr 16 and 0xff)
                green += (entering ushr 8 and 0xff) - (leaving ushr 8 and 0xff)
                blue += (entering and 0xff) - (leaving and 0xff)
            }
        }
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = temporary[offset.coerceIn(0, height - 1) * width + x]
                alpha += color ushr 24
                red += color ushr 16 and 0xff
                green += color ushr 8 and 0xff
                blue += color and 0xff
            }
            for (y in 0 until height) {
                output[y * width + x] =
                    (alpha / diameter shl 24) or (red / diameter shl 16) or
                    (green / diameter shl 8) or (blue / diameter)
                val leaving = temporary[(y - radius).coerceIn(0, height - 1) * width + x]
                val entering = temporary[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                alpha += (entering ushr 24) - (leaving ushr 24)
                red += (entering ushr 16 and 0xff) - (leaving ushr 16 and 0xff)
                green += (entering ushr 8 and 0xff) - (leaving ushr 8 and 0xff)
                blue += (entering and 0xff) - (leaving and 0xff)
            }
        }
        bitmap.setPixels(output, 0, width, 0, 0, width, height)
    }

    private fun descendantOffset(root: View, target: View): FloatArray {
        var x = 0f
        var y = 0f
        var current: View? = target
        while (current != null && current !== root) {
            x += current.x - current.scrollX
            y += current.y - current.scrollY
            current = current.parent as? View
        }
        check(current === root) { "backdrop target must remain below its Whisker root" }
        return floatArrayOf(x, y)
    }
}
