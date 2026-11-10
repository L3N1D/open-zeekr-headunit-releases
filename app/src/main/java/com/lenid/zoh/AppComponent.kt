package com.lenid.zoh

import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import com.lenid.zoh.connection.CommManager
import com.lenid.zoh.decoder.audio.AudioDecoder
import com.lenid.zoh.decoder.video.DeviceMemoryProfile
import com.lenid.zoh.decoder.video.VideoDecoder
import com.lenid.zoh.connection.carkey.CarKeysManager
import com.lenid.zoh.utils.SUExecutor
import com.lenid.zoh.utils.Settings

class AppComponent(private val app: App) {

    val settings = Settings(app)
    // A function, not a reading: this decoder is a process singleton, so anything resolved here
    // once would outlive every settings change the user makes.
    val videoDecoder = VideoDecoder(settings) {
        DeviceMemoryProfile.readWithOverride(app, settings.debugForceMemoryProfile)
    }
    val audioDecoder = AudioDecoder()

    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val commManager = CommManager(app, settings, audioDecoder, videoDecoder)

    val suExecutor = SUExecutor()

    val carKeysManager = CarKeysManager()
}
