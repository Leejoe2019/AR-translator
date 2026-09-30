package com.leejoe.artranslator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

data class OverlayRegion(
    val id: Int,
    val sourceRect: RectF,
    val translation: String,
    val background: Bitmap?
)

class TranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var regions: List<OverlayRegion> = emptyList()
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        textAlign = Paint.Align.LEFT
        setShadowLayer(3f, 0f, 1f, 0xcc000000.toInt())
    }

    fun setRegions(frameWidth: Int, frameHeight: Int, value: List<OverlayRegion>) {
        sourceWidth = max(1, frameWidth)
        sourceHeight = max(1, frameHeight)
        regions = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = max(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val dx = (width - sourceWidth * scale) / 2f
        val dy = (height - sourceHeight * scale) / 2f
        regions.forEach { region ->
            if (region.translation.isBlank()) return@forEach
            val s = region.sourceRect
            val d = RectF(s.left * scale + dx, s.top * scale + dy, s.right * scale + dx, s.bottom * scale + dy)
            if (d.width() < 8f || d.height() < 8f) return@forEach
            region.background?.let { canvas.drawBitmap(it, null, d, null) }
            canvas.drawRoundRect(d, 5f, 5f, scrimPaint)
            drawFittedText(canvas, region.translation, d)
        }
    }

    private fun drawFittedText(canvas: Canvas, text: String, rect: RectF) {
        val padding = max(3f, min(rect.width(), rect.height()) * 0.06f)
        val w = max(1, (rect.width() - padding * 2).toInt())
        val h = max(1, (rect.height() - padding * 2).toInt())
        var low = 8f
        var high = max(10f, rect.height() * 0.85f)
        var best = low
        repeat(9) {
            val mid = (low + high) / 2f
            textPaint.textSize = mid
            if (makeLayout(text, w).height <= h) { best = mid; low = mid } else high = mid
        }
        textPaint.textSize = best
        val layout = makeLayout(text, w)
        canvas.save()
        canvas.translate(rect.left + padding, rect.top + (rect.height() - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun makeLayout(text: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.96f)
            .setMaxLines(4)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
}
