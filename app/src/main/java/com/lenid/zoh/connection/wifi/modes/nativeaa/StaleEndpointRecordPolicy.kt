package com.lenid.zoh.connection.wifi.modes.nativeaa

/**
 * Whether a Bluetooth handshake that just landed disproves the stale-endpoint record.
 *
 * The rejection works, which means the phone stops dialling, which means a served dial can never
 * arrive to retire the record it raised. This is the other route back.
 */
object StaleEndpointRecordPolicy {

    /**
     * A phone runs its dial loop *beside* its Bluetooth handshake rather than instead of it, which
     * is measured, so one landing is not on its own proof the dialling stopped. A landing with no
     * dial since the previous one is. Never where the address may have moved: a phone holding an
     * address nobody holds never dials us, so no refusal is recorded and a landing disproves nothing.
     */
    fun retiredByHandshake(refusedADialSinceLastLanding: Boolean, addressMayHaveMoved: Boolean = false): Boolean =
        !addressMayHaveMoved && !refusedADialSinceLastLanding
}
