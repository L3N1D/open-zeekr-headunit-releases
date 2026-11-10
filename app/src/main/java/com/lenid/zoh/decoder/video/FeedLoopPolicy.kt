package com.lenid.zoh.decoder.video

/** Whether the feed thread may touch the codec: a codec the output thread declared dead is not fed. */
object FeedLoopPolicy {
    fun shouldFeed(running: Boolean, isCurrentThread: Boolean, hasCodec: Boolean, restartPending: Boolean): Boolean =
        running && isCurrentThread && hasCodec && !restartPending
}
