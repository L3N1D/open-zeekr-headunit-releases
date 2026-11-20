package com.lenid.zoh.main

import com.lenid.zoh.R
import com.lenid.zoh.utils.ConnectionIssue

internal object ConnectionIssueText {
    fun label(issue: ConnectionIssue): Int = when (issue) {
                ConnectionIssue.BLUETOOTH_SENT_NO_DATA -> R.string.connection_issue_banner_bt_silent
                ConnectionIssue.BSSID_UNAVAILABLE -> R.string.connection_issue_banner_bssid
                ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED ->
                    R.string.connection_issue_banner_wifi_direct_refused
                ConnectionIssue.WIFI_DIRECT_STACK_CYCLED ->
                    R.string.connection_issue_banner_wifi_direct_cycled
                ConnectionIssue.WIFI_DIRECT_STACK_STUCK ->
                    R.string.connection_issue_banner_wifi_direct_stuck
                ConnectionIssue.WIFI_RADIO_OFF ->
                    R.string.connection_issue_banner_wifi_radio_off
                ConnectionIssue.VIDEO_LINK_TOO_SLOW ->
                    R.string.connection_issue_banner_video_link_too_slow
                ConnectionIssue.FIVE_GHZ_CHANNEL_REFUSED ->
                    R.string.connection_issue_banner_five_ghz_channel_refused
                ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING ->
                    R.string.connection_issue_banner_headunit_server_deaf
                ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE ->
                    R.string.connection_issue_banner_hands_free_held
                ConnectionIssue.HANDS_FREE_RECORD_REFUSED ->
                    R.string.connection_issue_banner_hands_free_record_refused
                ConnectionIssue.PHONE_HOLDS_STALE_ENDPOINT ->
                    R.string.connection_issue_banner_stale_endpoint
                ConnectionIssue.HOME_WIFI_REJOINED_BESIDE_GROUP ->
                    R.string.connection_issue_banner_home_wifi_rejoined
                ConnectionIssue.STALE_USB_ACCESSORY ->
                    R.string.connection_issue_banner_stale_usb_accessory
            }
}
