package com.lenid.zoh.decoder.audio

// The host JVM has no car framework or vendor build identifiers.
internal object CarAudioPlatform { val isCarPlatform = false; val hasZeekrAudioRoutes = false; const val NAVIGATION_STREAM = 15 }
