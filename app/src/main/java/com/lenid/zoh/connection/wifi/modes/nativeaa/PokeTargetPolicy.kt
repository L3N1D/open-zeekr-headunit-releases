package com.lenid.zoh.connection.wifi.modes.nativeaa

/** What the wake poke does when it looks at its target list. */
sealed class PokeTargets {
    /** Poke these addresses, in the order the caller resolves them; [dropped] were chosen but are not phones. */
    data class Selected(val macs: Set<String>, val dropped: Set<String> = emptySet()) : PokeTargets()

    /** Poke every paired device: the opt-in is on and nothing specific was chosen. */
    object AllPaired : PokeTargets()

    /** Poke nothing, because no target is set and widening was not asked for. */
    object None : PokeTargets()
}

/**
 * Who the Native AA wake poke connects to. Widening to every paired device is opt-in, so an empty
 * list means empty. `notPhones` are chosen addresses that are not phones, which the seeding from the
 * auto-start trigger list lets in (the car's own Bluetooth, say); they count as not chosen.
 */
object PokeTargetPolicy {

    fun targets(selected: Set<String>, allPairedOptIn: Boolean, notPhones: Set<String> = emptySet()): PokeTargets {
        val offered = selected - notPhones
        return when {
            offered.isNotEmpty() -> PokeTargets.Selected(offered, selected - offered)
            allPairedOptIn -> PokeTargets.AllPaired
            else -> PokeTargets.None
        }
    }

    /**
     * Whether a device that just completed a handshake may be remembered as the target.
     *
     * Only when there is no phone to overwrite. Adopting a phone over a choice the user made is
     * what made a removal undo itself.
     */
    fun adoptsHandshakedDevice(selected: Set<String>, notPhones: Set<String> = emptySet()): Boolean =
        (selected - notPhones).isEmpty()
}
