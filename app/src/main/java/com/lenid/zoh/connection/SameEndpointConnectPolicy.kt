package com.lenid.zoh.connection

/**
 * A connect to an endpoint an attempt holds a claim for joins it, so a higher tier cannot kill our
 * own attempt before SSL. A live session is not joined, so a deliberate redial still works.
 */
object SameEndpointConnectPolicy {
    enum class Route { ADOPT_HELD, JOIN, DIAL }

    fun joins(inFlight: String?, requested: String?): Boolean =
        inFlight != null && requested != null && inFlight == requested

    /** A socket discovery holds to the endpoint is adopted, since the server is already bound to it. */
    fun route(held: String?, inFlight: String?, requested: String?): Route = when {
        joins(held, requested) -> Route.ADOPT_HELD
        joins(inFlight, requested) -> Route.JOIN
        else -> Route.DIAL
    }

    /** One spelling per endpoint on every connect path: no IPv6 scope id, no brackets. */
    fun endpoint(host: String, port: Int): String = "${host.substringBefore('%').trim('[', ']')}:$port"
}
