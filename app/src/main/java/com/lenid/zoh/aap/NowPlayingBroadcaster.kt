package com.lenid.zoh.aap

import android.content.Context
import android.content.Intent
import com.lenid.zoh.aap.protocol.proto.MediaPlayback
import com.lenid.zoh.utils.AppLog
import com.lenid.zoh.utils.protoUint32ToLong

/**
 * Announces the phone's now-playing track with the legacy `com.android.music.*` broadcasts.
 * Many headunit firmwares (Geely/ECARX mods among them) feed the instrument cluster and their
 * own media widgets from these intents rather than from the active MediaSession, so without
 * them the track only reaches Open Headunit's own UI.
 */
internal class NowPlayingBroadcaster(private val context: Context) {

    private var lastPlaying: Boolean? = null

    fun metadataChanged(meta: MediaPlayback.MediaMetaData, positionMs: Long, isPlaying: Boolean) {
        send(ACTION_META_CHANGED, meta, positionMs, isPlaying)
        lastPlaying = isPlaying
    }

    /** Playback status arrives about once a second; only a PLAYING/PAUSED change is announced. */
    fun playStateChanged(meta: MediaPlayback.MediaMetaData?, positionMs: Long, isPlaying: Boolean) {
        if (lastPlaying == isPlaying) return
        lastPlaying = isPlaying
        send(ACTION_PLAY_STATE_CHANGED, meta, positionMs, isPlaying)
    }

    fun stopped() {
        if (lastPlaying == null) return
        lastPlaying = null
        send(ACTION_PLAY_STATE_CHANGED, null, 0L, false)
    }

    private fun send(action: String, meta: MediaPlayback.MediaMetaData?, positionMs: Long, isPlaying: Boolean) {
        val intent = Intent(action).apply {
            putExtra(EXTRA_SENDER, context.packageName)
            putExtra("package", context.packageName)
            putExtra("playing", isPlaying)
            putExtra("isPlaying", isPlaying)
            putExtra("position", positionMs)
            if (meta != null) {
                val track = meta.song.orEmpty()
                val artist = meta.artist.orEmpty()
                val album = meta.album.orEmpty()
                val durationMs = if (meta.hasDurationSeconds()) meta.durationSeconds.protoUint32ToLong() * 1000L else 0L
                putExtra("id", (track + artist + album).hashCode().toLong())
                putExtra("track", track)
                putExtra("title", track)
                putExtra("artist", artist)
                putExtra("album", album)
                putExtra("duration", durationMs)
            }
        }
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            AppLog.w("NowPlayingBroadcaster: $action failed: ${e.message}")
        }
    }

    companion object {
        const val ACTION_META_CHANGED = "com.android.music.metachanged"
        const val ACTION_PLAY_STATE_CHANGED = "com.android.music.playstatechanged"

        /** Marks our own announcements so RemoteControlReceiver does not treat them as commands. */
        const val EXTRA_SENDER = "com.lenid.zoh.extra.NOW_PLAYING_SENDER"
    }
}
