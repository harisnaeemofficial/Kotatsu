package org.koitharu.kotatsu.base.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.View.OnClickListener
import androidx.annotation.DrawableRes
import androidx.core.view.children
import com.google.android.material.R as materialR
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.utils.ext.castOrNull
import org.koitharu.kotatsu.utils.ext.getThemeColorStateList

class ChipsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = com.google.android.material.R.attr.chipGroupStyle,
) : ChipGroup(context, attrs, defStyleAttr) {

	private var isLayoutSuppressedCompat = false
	private var isLayoutCalledOnSuppressed = false
	private val chipOnClickListener = OnClickListener {
		onChipClickListener?.onChipClick(it as Chip, it.tag)
	}
	private val chipOnCloseListener = OnClickListener {
		onChipCloseClickListener?.onChipCloseClick(it as Chip, it.tag)
	}
	var onChipClickListener: OnChipClickListener? = null
		set(value) {
			field = value
			val isChipClickable = value != null
			children.forEach { it.isClickable = isChipClickable }
		}
	var onChipCloseClickListener: OnChipCloseClickListener? = null
		set(value) {
			field = value
			val isCloseIconVisible = value != null
			children.forEach { (it as? Chip)?.isCloseIconVisible = isCloseIconVisible }
		}

	override fun requestLayout() {
		if (isLayoutSuppressedCompat) {
			isLayoutCalledOnSuppressed = true
		} else {
			super.requestLayout()
		}
	}

	fun setChips(items: Collection<ChipModel>) {
		suppressLayoutCompat(true)
		try {
			for ((i, model) in items.withIndex()) {
				val chip = getChildAt(i) as Chip? ?: addChip()
				bindChip(chip, model)
			}
			if (childCount > items.size) {
				removeViews(items.size, childCount - items.size)
			}
		} finally {
			suppressLayoutCompat(false)
		}
	}

	fun <T> getCheckedData(cls: Class<T>): Set<T> {
		val result = LinkedHashSet<T>(childCount)
		for (child in children) {
			if (child is Chip && child.isChecked) {
				result += cls.castOrNull(child.tag) ?: continue
			}
		}
		return result
	}

	private fun bindChip(chip: Chip, model: ChipModel) {
		chip.text = model.title
		if (model.icon == 0) {
			chip.isChipIconVisible = false
		} else {
			chip.isChipIconVisible = true
			chip.setChipIconResource(model.icon)
		}
		chip.isClickable = onChipClickListener != null || model.isCheckable
		chip.isCheckable = model.isCheckable
		chip.isChecked = model.isChecked
		chip.tag = model.data
	}

	private fun addChip(): Chip {
		val chip = Chip(context)
		val drawable = ChipDrawable.createFromAttributes(context, null, 0, R.style.Widget_Kotatsu_Chip)
		chip.setChipDrawable(drawable)
		chip.isCheckedIconVisible = true
		chip.setCheckedIconResource(R.drawable.ic_check)
		chip.checkedIconTint = context.getThemeColorStateList(materialR.attr.colorControlNormal)
		chip.isCloseIconVisible = onChipCloseClickListener != null
		chip.setOnCloseIconClickListener(chipOnCloseListener)
		chip.setEnsureMinTouchTargetSize(false)
		chip.setOnClickListener(chipOnClickListener)
		addView(chip)
		return chip
	}

	private fun suppressLayoutCompat(suppress: Boolean) {
		isLayoutSuppressedCompat = suppress
		if (!suppress) {
			if (isLayoutCalledOnSuppressed) {
				requestLayout()
				isLayoutCalledOnSuppressed = false
			}
		}
	}

	class ChipModel(
		@DrawableRes val icon: Int,
		val title: CharSequence,
		val isCheckable: Boolean,
		val isChecked: Boolean,
		val data: Any? = null,
	) {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false

			other as ChipModel

			if (icon != other.icon) return false
			if (title != other.title) return false
			if (isCheckable != other.isCheckable) return false
			if (isChecked != other.isChecked) return false
			if (data != other.data) return false

			return true
		}

		override fun hashCode(): Int {
			var result = icon
			result = 31 * result + title.hashCode()
			result = 31 * result + isCheckable.hashCode()
			result = 31 * result + isChecked.hashCode()
			result = 31 * result + (data?.hashCode() ?: 0)
			return result
		}
	}

	fun interface OnChipClickListener {

		fun onChipClick(chip: Chip, data: Any?)
	}

	fun interface OnChipCloseClickListener {

		fun onChipCloseClick(chip: Chip, data: Any?)
	}
}
