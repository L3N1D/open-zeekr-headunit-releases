package com.lenid.zoh.aap

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.lenid.zoh.main.LauncherCard

/**
 * The projection growing out of the home screen card when it opens on the full screen (the
 * launcher's music button, or opening the app): the window starts at the card's place and size
 * and expands. A dark cover hides the stretched old layout until the phone has laid out for the
 * full screen, then fades away.
 *
 * Which window the projection was last in is kept across instances, since the move may recreate
 * the activity rather than move it.
 */
internal object CardToFullScreenTransition {

    private const val GROW_MS = 300L
    private const val COVER_FADE_MS = 220L
    /** Cover lifted even if the phone never confirms the new layout. */
    private const val COVER_MAX_MS = 1200L

    @Volatile private var lastWasCard = false

    /** The card's display while the projection is in it, else null; for "back to the app". */
    @Volatile var cardDisplayId: Int? = null
        private set
    private var cover: View? = null

    /** Records where [activity] is now; true when it has just come from the card to the full screen. */
    fun noteWindow(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val id = activity.display?.displayId ?: return false
        val onCard = id != Display.DEFAULT_DISPLAY
        val cameFromCard = lastWasCard && !onCard
        lastWasCard = onCard
        cardDisplayId = if (onCard) id else null
        return cameFromCard
    }

    /** Grows the activity's content from the card's on-screen bounds to the whole window. */
    fun play(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val card = LauncherCard.boundsOnScreen(activity) ?: return
        showCover(activity, content)
        content.post {
            val loc = IntArray(2).also { content.getLocationOnScreen(it) }
            val w = content.width.toFloat()
            val h = content.height.toFloat()
            if (w <= 0f || h <= 0f) return@post
            content.pivotX = 0f
            content.pivotY = 0f
            content.scaleX = card.width() / w
            content.scaleY = card.height() / h
            content.translationX = (card.left - loc[0]).toFloat()
            content.translationY = (card.top - loc[1]).toFloat()
            content.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(GROW_MS).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        content.postDelayed({ liftCover() }, COVER_MAX_MS)
    }

    /** The phone confirmed the margins for the new window: let the picture through. */
    fun onLayoutConfirmed() {
        // One more frame period or so for the first frame of the new layout to arrive.
        cover?.postDelayed({ liftCover() }, 150)
    }

    private fun showCover(activity: Activity, content: ViewGroup) {
        cover?.let { (it.parent as? ViewGroup)?.removeView(it) }
        cover = View(activity).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = false
            content.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun liftCover() {
        val v = cover ?: return
        cover = null
        v.animate().alpha(0f).setDuration(COVER_FADE_MS).withEndAction {
            (v.parent as? ViewGroup)?.removeView(v)
        }.start()
    }
}
