package com.lenid.zoh.debug

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lenid.zoh.view.ProjectionResizeMotion

/** Debug-only visual preview. Does not bind any projection service or change preferences. */
class ResizeMotionPreviewActivity : Activity() {
    private var motion: ProjectionResizeMotion? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val host = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(80, 150, 120))
            addView(TextView(this@ResizeMotionPreviewActivity).apply {
                text = "Проверка перехода · соединение не используется"
                textSize = 24f
                setTextColor(Color.WHITE)
            })
        }
        var narrow = false
        root.addView(Button(this).apply {
            text = "Свернуть / развернуть"
            setOnClickListener {
                narrow = !narrow
                host.layoutParams = LinearLayout.LayoutParams(if (narrow) 1000 else 1500, 700)
            }
        })
        root.addView(host, LinearLayout.LayoutParams(1500, 700))
        setContentView(root)
        motion = ProjectionResizeMotion(host) { true }
    }
    override fun onDestroy() { motion?.dispose(); super.onDestroy() }
}
