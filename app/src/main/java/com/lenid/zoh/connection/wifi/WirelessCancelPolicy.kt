package com.lenid.zoh.connection.wifi

/**
 * Whether the X on the status pill still holds the wireless stack down.
 *
 * Only a wireless connection the user asks for by hand lifts it. The phone's Bluetooth arriving is
 * the state the user cancelled in, not news, and in Native mode the cancelled poke's own ACL would
 * otherwise re-arm the stack seconds after the tap.
 */
object WirelessCancelPolicy {

    fun refusesBringUp(cancelledByUser: Boolean, userRequested: Boolean): Boolean =
        cancelledByUser && !userRequested
}
