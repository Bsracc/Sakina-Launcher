package com.bsracc.screenguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class CensorOverlayView(context: Context) : View(context) {

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
    }

    @Volatile
    private var blockedAreas: List<RectF> = emptyList()

    fun update(rectangles: List<RectF>) {
        blockedAreas = rectangles.map { RectF(it) }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        blockedAreas.forEach { canvas.drawRect(it, blockPaint) }
    }
}
