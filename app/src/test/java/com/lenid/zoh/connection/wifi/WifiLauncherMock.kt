package com.lenid.zoh.connection.wifi

import com.lenid.zoh.connection.wifi.modes.WifiLauncherNative
import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeStrategy
import org.mockito.kotlin.mock

object WifiLauncherMock {

    fun create(
        mode: WifiLauncherMode,
        nativeStrategy: NativeStrategy? = null
    ) : WifiLauncher {
        val manager = mock<WifiLauncherManager>()

        return WifiLauncherNative(manager, nativeStrategy ?: NativeStrategy.DEFAULT)
    }
}
