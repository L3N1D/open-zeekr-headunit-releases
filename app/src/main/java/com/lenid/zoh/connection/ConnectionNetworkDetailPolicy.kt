package com.lenid.zoh.connection

import androidx.annotation.StringRes
import com.lenid.zoh.R
import com.lenid.zoh.connection.wifi.direct.WifiP2pChannelPolicy

/** Which string, with which arguments, the pill shows for a [ConnectionNetworkDetail]. */
object ConnectionNetworkDetailPolicy {

    data class Line(@StringRes val id: Int, val args: List<Any> = emptyList())

    fun lineFor(detail: ConnectionNetworkDetail): Line {
        val frequency = detail.frequencyMhz
        return when (detail.note) {
            ConnectionNetworkDetail.Note.RETRYING_5GHZ ->
                Line(R.string.network_detail_retrying_5ghz)
            ConnectionNetworkDetail.Note.CLIENT_UNFRIENDLY_CHANNEL ->
                Line(R.string.network_detail_unfriendly_channel, listOf(WifiP2pChannelPolicy.channelFor(frequency)))
            ConnectionNetworkDetail.Note.NONE -> when {
                frequency <= 0 -> Line(R.string.network_detail_unknown)
                WifiP2pChannelPolicy.is24GHz(frequency) ->
                    Line(R.string.network_detail_24ghz, listOf(WifiP2pChannelPolicy.channelFor(frequency)))
                else -> Line(R.string.network_detail_5ghz, listOf(frequency))
            }
        }
    }
}
