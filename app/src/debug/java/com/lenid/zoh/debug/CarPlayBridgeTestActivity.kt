package com.lenid.zoh.debug

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.lenid.zoh.carplay.DirectCarPlayActivity
import java.io.File

/** ADB entry point only in debug APKs; production and real head units never enable the lab port. */
class CarPlayBridgeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.HARDWARE !in listOf("ranchu", "goldfish")) { finish(); return }
        val config = File(noBackupFilesDir, "carplay-usb-bridge.port")
        if (intent.getBooleanExtra("disable", false)) {
            config.delete()
        } else {
            config.writeText("5278")
            startActivity(Intent(this, DirectCarPlayActivity::class.java))
        }
        finish()
    }
}
