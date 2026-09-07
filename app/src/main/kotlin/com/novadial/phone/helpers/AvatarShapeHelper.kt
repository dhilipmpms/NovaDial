package com.novadial.phone.helpers

import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView

object AvatarShapeHelper {

    const val SHAPE_SQUIRCLE = 0
    const val SHAPE_CIRCLE = 1
    const val SHAPE_SQUARE = 2
    const val SHAPE_COOKIE = 3

    fun applyAvatarShape(view: ImageView, shapeIndex: Int) {
        view.clipToOutline = true

        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val width = view.width
                val height = view.height
                if (width <= 0 || height <= 0) return

                val minSize = Math.min(width, height).toFloat()

                when (shapeIndex) {
                    SHAPE_CIRCLE -> {
                        outline.setOval(0, 0, width, height)
                    }
                    SHAPE_SQUARE -> {
                        outline.setRect(0, 0, width, height)
                    }
                    SHAPE_COOKIE -> {
                        val cornerRadius = minSize * 0.35f
                        outline.setRoundRect(0, 0, width, height, cornerRadius)
                    }
                    SHAPE_SQUIRCLE -> {
                        val cornerRadius = minSize * 0.28f
                        outline.setRoundRect(0, 0, width, height, cornerRadius)
                    }
                    else -> {
                        val cornerRadius = minSize * 0.28f
                        outline.setRoundRect(0, 0, width, height, cornerRadius)
                    }
                }
            }
        }
    }

    fun getShapeName(shapeIndex: Int): String {
        return when (shapeIndex) {
            SHAPE_SQUIRCLE -> "Squircle"
            SHAPE_CIRCLE -> "Circle"
            SHAPE_SQUARE -> "Square"
            SHAPE_COOKIE -> "Soft Cookie"
            else -> "Squircle"
        }
    }
}
