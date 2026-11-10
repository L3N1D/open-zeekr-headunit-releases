package com.lenid.zoh.utils

import android.content.IntentFilter
import com.lenid.zoh.contract.KeyIntent

object IntentFilters {
    val keyEvent = IntentFilter(KeyIntent.action)
}