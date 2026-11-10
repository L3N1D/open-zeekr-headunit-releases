package com.lenid.zoh.utils.bluetooth

import java.util.Locale

/**
 * Pairing evidence, not an AA connection or a list of interchangeable socket destinations.
 * Readings are Android's own bond list; a phone not in it cannot be woken over RFCOMM.
 */
class BluetoothPeerInventory(readings: List<Reading>) {
    enum class Source { ANDROID }
    enum class Status { READABLE, ABSENT, UNAVAILABLE, MALFORMED }
    data class Entry(val address: String, val name: String?)
    data class Reading(
        val source: Source,
        val status: Status,
        val entries: List<Entry> = emptyList(),
        val rejectedRows: Int = 0
    )
    data class Peer(val address: String, val name: String?, val sources: Set<Source>) {
        // This is eligibility for the existing Android path, not proof that an AA socket will open.
        val visibleToAndroid: Boolean get() = Source.ANDROID in sources
    }

    val readings = readings.toList()
    val peers: List<Peer>

    init {
        val merged = linkedMapOf<String, Peer>()
        // Android's name wins over a possibly old vendor cache, regardless of provider order.
        readings.sortedBy { if (it.source == Source.ANDROID) 0 else 1 }
            .filter { it.status == Status.READABLE }
            .forEach { reading ->
                reading.entries.forEach entry@{ entry ->
                    val address = normalizeAddress(entry.address) ?: return@entry
                    val old = merged[address]
                    merged[address] = Peer(
                        address, old?.name?.takeIf { it.isNotBlank() } ?: displayName(entry.name),
                        (old?.sources ?: emptySet()) + reading.source
                    )
                }
            }
        peers = merged.values.toList()
    }

    fun peer(address: String): Peer? {
        val normalized = normalizeAddress(address) ?: return null
        return peers.firstOrNull { it.address == normalized }
    }

    /** No names or addresses: useful for reports without copying a user's entire pairing history. */
    fun diagnostic(): String = readings.joinToString("; ") {
        "${it.source}=${it.status}, entries=${it.entries.size}, rejected=${it.rejectedRows}"
    }

    companion object {
        private val compactAddress = Regex("[0-9a-fA-F]{12}")
        private val colonAddress = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
        private val placeholders = setOf("000000000000", "FFFFFFFFFFFF", "020000000000")

        fun normalizeAddress(value: String): String? {
            val compact = when {
                compactAddress.matches(value) -> value
                colonAddress.matches(value) -> value.replace(":", "")
                else -> return null
            }.uppercase(Locale.ROOT)
            if (compact in placeholders) return null
            return compact.chunked(2).joinToString(":")
        }

        private fun displayName(value: String?): String? = value
            ?.filterNot { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }
            ?.trim()?.take(128)?.takeIf { it.isNotEmpty() }
    }
}
