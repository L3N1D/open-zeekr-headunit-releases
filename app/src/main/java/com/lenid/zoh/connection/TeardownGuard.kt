package com.lenid.zoh.connection

/**
 * Runs [teardown], then always runs [close], even when [teardown] throws.
 *
 * The socket close is the one step that must happen: Android Auto's head unit server is deaf until
 * the user restarts it if a peer goes away without closing, and the steps before it — a blocking
 * ByeBye send, two decoder stops that are known to time out — can all throw.
 */
object TeardownGuard {

    fun runThenClose(teardown: () -> Unit, close: () -> Unit, onError: (String, Exception) -> Unit) {
        try {
            teardown()
        } catch (e: Exception) {
            onError("teardown", e)
        } finally {
            try {
                close()
            } catch (e: Exception) {
                onError("close", e)
            }
        }
    }
}
