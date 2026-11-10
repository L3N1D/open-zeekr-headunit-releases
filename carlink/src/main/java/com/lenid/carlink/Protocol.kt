package com.lenid.carlink

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Carlinkit CPC200 USB protocol: 16-byte little-endian header (magic, length, type, ~type) + payload. */
object Protocol {
    const val MAGIC = 0x55AA55AA
    const val HEADER_SIZE = 16

    const val OPEN = 0x01
    const val PLUGGED = 0x02
    const val PHASE = 0x03
    const val UNPLUGGED = 0x04
    const val TOUCH = 0x05
    const val VIDEO = 0x06
    const val AUDIO = 0x07
    const val COMMAND = 0x08
    const val BT_NAME = 0x0D
    const val WIFI_NAME = 0x0E
    const val PAIRED_LIST = 0x12
    const val BOX_SETTINGS = 0x19
    const val MEDIA_DATA = 0x2A
    const val SEND_FILE = 0x99
    const val HEARTBEAT = 0xAA
    const val SOFTWARE_VERSION = 0xCC

    // Command (type 0x08) values
    const val CMD_MIC_HOST = 7
    const val CMD_MIC_DONGLE = 15
    const val CMD_FRAME = 12
    const val CMD_NIGHT_ON = 16
    const val CMD_NIGHT_OFF = 17
    const val CMD_AUDIO_BT = 22
    const val CMD_AUDIO_USB = 23
    const val CMD_WIFI_24G = 24
    const val CMD_WIFI_5G = 25
    const val CMD_BACK = 106
    const val CMD_HOME = 200
    const val CMD_PLAY = 201
    const val CMD_PAUSE = 202
    const val CMD_PLAY_PAUSE = 203
    const val CMD_NEXT = 204
    const val CMD_PREV = 205
    const val CMD_REQUEST_VIDEO_FOCUS = 500
    const val CMD_RELEASE_VIDEO_FOCUS = 501
    const val CMD_WIFI_ENABLE = 1000
    const val CMD_AUTO_CONNECT = 1001
    const val CMD_WIFI_CONNECT = 1002
    const val CMD_SCANNING = 1003
    const val CMD_DEVICE_FOUND = 1004
    const val CMD_DEVICE_NOT_FOUND = 1005
    const val CMD_CONNECT_FAILED = 1006
    const val CMD_BT_CONNECTED = 1007
    const val CMD_BT_DISCONNECTED = 1008
    const val CMD_WIFI_CONNECTED = 1009
    const val CMD_WIFI_DISCONNECTED = 1010

    // Audio (type 0x07) 1-byte commands
    const val AUDIO_PHONECALL_START = 4
    const val AUDIO_PHONECALL_STOP = 5
    const val AUDIO_NAVI_START = 6
    const val AUDIO_NAVI_STOP = 7
    const val AUDIO_SIRI_START = 8
    const val AUDIO_SIRI_STOP = 9

    const val TOUCH_DOWN = 14
    const val TOUCH_MOVE = 15
    const val TOUCH_UP = 16

    fun header(type: Int, length: Int): ByteArray =
        le(HEADER_SIZE).putInt(MAGIC).putInt(length).putInt(type).putInt(type.inv()).array()

    fun int(v: Int): ByteArray = le(4).putInt(v).array()

    fun open(width: Int, height: Int, fps: Int): ByteArray =
        le(28).putInt(width).putInt(height).putInt(fps).putInt(5).putInt(49152).putInt(2).putInt(2).array()

    fun file(name: String, content: ByteArray): ByteArray {
        val n = (name + "\u0000").toByteArray()
        return le(8 + n.size + content.size).putInt(n.size).put(n).putInt(content.size).put(content).array()
    }

    fun touch(action: Int, x: Float, y: Float): ByteArray =
        le(16).putInt(action).putInt((x.coerceIn(0f, 1f) * 10000).toInt())
            .putInt((y.coerceIn(0f, 1f) * 10000).toInt()).putInt(0).array()

    fun micAudio(pcm: ByteArray, len: Int): ByteArray =
        le(12 + len).putInt(5).putFloat(0f).putInt(3).put(pcm, 0, len).array()

    fun commandName(c: Int): String = when (c) {
        CMD_SCANNING -> "Поиск телефона…"
        CMD_DEVICE_FOUND -> "Телефон найден"
        CMD_DEVICE_NOT_FOUND -> "Телефон не найден"
        CMD_CONNECT_FAILED -> "Ошибка подключения"
        CMD_BT_CONNECTED -> "Bluetooth подключён"
        CMD_BT_DISCONNECTED -> "Bluetooth отключён"
        CMD_WIFI_CONNECTED -> "Wi-Fi подключён"
        CMD_WIFI_DISCONNECTED -> "Wi-Fi отключён"
        else -> ""
    }

    private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
}
