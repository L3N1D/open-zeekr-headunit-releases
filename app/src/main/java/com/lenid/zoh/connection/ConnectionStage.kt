package com.lenid.zoh.connection

import androidx.annotation.StringRes
import com.lenid.zoh.R

/**
 * A user-visible step of bringing a connection up, shown on the home screen status pill.
 *
 * Deliberately coarser than the AppLog milestones it is reported from: retries inside one step
 * collapse onto one stage so the pill does not stutter while that machinery works.
 *
 * [rank] is the display order, declared rather than taken from ordinal so a stage can be inserted
 * without renumbering the rest. Stages from different paths may share a rank; only one launcher is
 * ever active, so they never race.
 */
enum class ConnectionStage(val rank: Int, @StringRes val label: Int) {
    ARMED(10, R.string.stage_armed),
    USB_ATTACHED(20, R.string.stage_usb_attached),
    PREPARING_NETWORK(20, R.string.stage_preparing_network),
    SEARCHING(25, R.string.stage_searching),
    USB_SWITCHING(30, R.string.stage_usb_switching),
    CREATING_NETWORK(30, R.string.stage_creating_network),
    WAITING_FOR_PHONE(40, R.string.stage_waiting_for_phone),
    WAKING_PHONE(45, R.string.stage_waking_phone),
    PHONE_ANSWERED(50, R.string.stage_phone_answered),
    SENDING_CREDENTIALS(55, R.string.stage_sending_credentials),
    PHONE_JOINING(60, R.string.stage_phone_joining),
    CONNECTING(70, R.string.stage_connecting),
    SECURING(80, R.string.stage_securing),
    STARTING_PROJECTION(90, R.string.stage_starting_projection),
}
