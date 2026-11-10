package com.lenid.zoh.connection.wifi.direct

/**
 * How long to look for the group interface's own IP before handing the phone the one the platform
 * fixes anyway.
 *
 * AOSP gives a P2P group owner 192.168.49.1, but some units pick another subnet per group. The
 * fallback still goes to the phone, never to the stability grade, and waiting for it delays the
 * credentials and the wake poke.
 */
object GroupIpResolutionPolicy {

    /** The address AOSP gives a P2P group owner; some units use another. */
    const val GROUP_OWNER_IP = "192.168.49.1"

    /** Re-reads a client spends waiting for its DHCP lease, one per second, after the first read. */
    const val CLIENT_RETRIES = 15

    /**
     * Re-reads to spend after the first one. A group owner has a fallback that is always right, so
     * it takes the first answer either way; a client has none and has to wait for its lease.
     */
    fun retriesAfterFirstRead(isGroupOwner: Boolean): Int =
        if (isGroupOwner) 0 else CLIENT_RETRIES

    /**
     * The address to deliver, or null when there is none to send. [readIp] is what the interface
     * answered, or null when it did not.
     */
    fun resolve(readIp: String?, isGroupOwner: Boolean): String? =
        readIp ?: if (isGroupOwner) GROUP_OWNER_IP else null
}
