package com.lenid.zoh.utils
import com.lenid.zoh.decoder.audio.PlaybackFocusPolicy
class Settings {
    var killOnDisconnect=false
    var staticAudioFocus=false
    var separateAudioStreams=false
    var mediaAudioStream=3
    var guidanceAudioStream=0
    var systemAudioStream=5
    var mediaVolumeOffset=0
    var guidanceVolumeOffset=0
    var systemVolumeOffset=0
    var audioLatencyMultiplier=2
    var audioQueueCapacity=0
    var audioQuality = com.lenid.zoh.aap.AudioQuality.AUTO
    var useAacAudio: Boolean
        get() = audioQuality == com.lenid.zoh.aap.AudioQuality.AAC
        set(value) { audioQuality = if (value) com.lenid.zoh.aap.AudioQuality.AAC else com.lenid.zoh.aap.AudioQuality.AUTO }
    var useAAudioOutput=false
    var enableAudioSink=true
    var attachHwDspEqualizer=false
    var playbackFocusMode=PlaybackFocusPolicy.Mode.ALWAYS
    var playbackFocusSelfDefeating=false
}
