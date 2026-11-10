package com.lenid.zoh.view

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.lenid.zoh.R
import com.google.android.material.button.MaterialButton

/**
 * One segment of a Material 3 segmented button (style Widget.OZH.SegmentedButton): the chosen
 * segment shows a check before its label, as the spec draws it.
 */
class SegmentButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonOutlinedStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        // Called by MaterialButton's own constructor too, before context is fully ours; the
        // drawable lookup only needs the context it was given.
        icon = if (checked) ContextCompat.getDrawable(context, R.drawable.ic_segment_check) else null
    }
}
