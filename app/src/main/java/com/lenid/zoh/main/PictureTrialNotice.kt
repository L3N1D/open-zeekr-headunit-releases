package com.lenid.zoh.main

import android.view.View
import com.google.android.material.snackbar.Snackbar
import com.lenid.zoh.R
import com.lenid.zoh.utils.PictureSettingsTrial

internal object PictureTrialNotice {
    fun show(view: View) {
        if (!PictureSettingsTrial.pending(view.context)) return
        val bar = Snackbar.make(view, R.string.support_trial, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.support_keep) { PictureSettingsTrial.keep(view.context) }
        bar.show()
        view.postDelayed({ if (!PictureSettingsTrial.pending(view.context)) bar.dismiss() }, 61_000)
    }
}
