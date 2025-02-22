package org.koitharu.kotatsu.utils.ext

import android.content.Context
import android.graphics.Color
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import androidx.annotation.StyleRes
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat

fun Context.getThemeDrawable(
	@AttrRes resId: Int,
) = obtainStyledAttributes(intArrayOf(resId)).use {
	it.getDrawable(0)
}

@ColorInt
fun Context.getThemeColor(
	@AttrRes resId: Int,
	@ColorInt fallback: Int = Color.TRANSPARENT,
) = obtainStyledAttributes(intArrayOf(resId)).use {
	it.getColor(0, fallback)
}

@ColorInt
fun Context.getThemeColor(
	@AttrRes resId: Int,
	@FloatRange(from = 0.0, to = 1.0) alphaFactor: Float,
	@ColorInt fallback: Int = Color.TRANSPARENT,
): Int {
	if (alphaFactor <= 0f) {
		return Color.TRANSPARENT
	}
	val color = getThemeColor(resId, fallback)
	if (alphaFactor >= 1f) {
		return color
	}
	return ColorUtils.setAlphaComponent(color, (0xFF * alphaFactor).toInt())
}

fun Context.getThemeColorStateList(
	@AttrRes resId: Int,
) = obtainStyledAttributes(intArrayOf(resId)).use {
	it.getColorStateList(0)
}

fun TextView.setThemeTextAppearance(@AttrRes resId: Int, @StyleRes fallback: Int) {
	context.obtainStyledAttributes(intArrayOf(resId)).use {
		TextViewCompat.setTextAppearance(this, it.getResourceId(0, fallback))
	}
}
