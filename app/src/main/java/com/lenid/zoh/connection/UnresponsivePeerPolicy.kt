package com.lenid.zoh.connection

/**
 * How hard to keep retrying a peer that accepts the TCP connection and then says nothing.
 *
 * Android Auto's built-in head unit server (the "Start head unit server" developer option,
 * port 5277) is a proxy, not an AAP server: it accepts, hands the socket to its own car service
 * over binder, and waits there with no timeout. Its accept loop is serial, so while one connection
 * is parked every later one sits in the kernel backlog, completed by the kernel alone and never
 * served or closed. Stopping and starting the server does clear it: the start binds a new listening
 * socket and runs a new accept loop, leaving only the parked threads behind.
 *
 * So retrying at the normal cadence cannot succeed, and it is not free: each attempt strands
 * another socket on the phone, which never reaps them either.
 *
 * Back off instead, and say once what is actually wrong. Backing off rather than stopping
 * matters: the user can fix this from the phone at any moment, and an app that has given up
 * for good would not notice.
 */
object UnresponsivePeerPolicy {

    /** Consecutive silent failures against one endpoint before the retry cadence drops. */
    const val SILENT_FAILURES_BEFORE_BACKOFF = 3

    /** And before it drops again, once a minute of attempts has plainly not been the answer. */
    const val SILENT_FAILURES_BEFORE_LAST_TIER = 10

    /** The ordinary re-scan delay, unchanged from before this policy existed. */
    const val NORMAL_RESCAN_MS = 10_000L

    /** Slow enough to stop manufacturing stranded sockets, fast enough to notice a fix. */
    const val BACKOFF_RESCAN_MS = 60_000L

    /** A last tier rather than giving up: a WiFi join still kicks discovery whatever this says. */
    const val LAST_TIER_RESCAN_MS = 300_000L

    /**
     * Delay before the next discovery sweep, given how many times in a row the same endpoint
     * has accepted a connection and then not answered.
     */
    fun rescanDelayMs(consecutiveSilentFailures: Int): Long = when {
        consecutiveSilentFailures >= SILENT_FAILURES_BEFORE_LAST_TIER -> LAST_TIER_RESCAN_MS
        consecutiveSilentFailures >= SILENT_FAILURES_BEFORE_BACKOFF -> BACKOFF_RESCAN_MS
        else -> NORMAL_RESCAN_MS
    }

    /**
     * True exactly once per episode, at the point the backoff engages, so the explanation goes
     * into the log once rather than on every cycle.
     */
    fun shouldExplain(consecutiveSilentFailures: Int): Boolean =
        consecutiveSilentFailures == SILENT_FAILURES_BEFORE_BACKOFF

    /**
     * The new streak length after a silent failure. A different endpoint is a different
     * server and starts its own count; the same one continues.
     */
    fun countAfterSilentFailure(
        previousCount: Int,
        previousEndpoint: String?,
        endpoint: String?
    ): Int = if (previousEndpoint != null && previousEndpoint == endpoint) previousCount + 1 else 1
}
