package com.lenid.carlink

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/** Dongle settings. Changes are applied by MainActivity when it resumes (new handshake if needed). */
class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private lateinit var list: LinearLayout
    private var service: CarlinkService? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as CarlinkService.LocalBinder).service
            build()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)
        list = findViewById(R.id.list)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        build()
        bindService(Intent(this, CarlinkService::class.java), conn, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        unbindService(conn)
        super.onDestroy()
    }

    private fun build() {
        list.removeAllViews()

        header("Изображение")
        choice("Разрешение", settings.resolution,
            listOf("auto" to "Авто (по экрану)", "800x480" to "800 × 480", "1280x720" to "1280 × 720",
                "1920x720" to "1920 × 720", "1920x1080" to "1920 × 1080")) { settings.resolution = it }
        choice("Частота кадров", settings.fps, listOf(30 to "30 кадров/с", 60 to "60 кадров/с")) { settings.fps = it }
        choice("Плотность (DPI)", settings.dpi,
            listOf(0 to "Авто", 120 to "120 — мелко", 160 to "160", 200 to "200", 240 to "240", 280 to "280 — крупно")) {
            settings.dpi = it
        }
        choice("Ночной режим", settings.nightMode, listOf(0 to "Как в системе", 1 to "Всегда день", 2 to "Всегда ночь")) {
            settings.nightMode = it
        }
        toggle("Правый руль", "Панель Android Auto / CarPlay справа", "Панель слева", settings.rightHandDrive) {
            settings.rightHandDrive = it
        }

        header("Звук и связь")
        toggle("Звук через Bluetooth донгла", "Звук идёт по Bluetooth донгла", "Звук идёт по USB в магнитолу",
            settings.bluetoothAudio) {
            settings.bluetoothAudio = it
        }
        toggle("Микрофон донгла", "Используется микрофон донгла", "Используется микрофон магнитолы", settings.dongleMic) {
            settings.dongleMic = it
        }
        choice("Задержка медиа", settings.mediaDelay,
            listOf(0 to "0 мс", 150 to "150 мс", 300 to "300 мс (по умолчанию)", 500 to "500 мс", 1000 to "1000 мс")) {
            settings.mediaDelay = it
        }
        toggle("Wi-Fi 5 ГГц", "Быстрее, меньше помех", "2,4 ГГц: дальше, но медленнее", settings.wifi5g) { settings.wifi5g = it }
        row("Имя донгла", settings.dongleName) {
            editText("Имя донгла", settings.dongleName, InputType.TYPE_CLASS_TEXT) { settings.dongleName = it }
        }

        header("Отладка")
        toggle("Донгл по сети", "Через dongle_bridge.py на ${settings.bridgeHost}:${CarlinkService.BRIDGE_PORT}",
            "Донгл по USB", settings.debugBridge) {
            settings.debugBridge = it
            service?.reconnect()
        }
        row("Адрес моста", settings.bridgeHost) {
            editText("Адрес моста", settings.bridgeHost, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) {
                settings.bridgeHost = it
                service?.reconnect()
            }
        }

        header("Донгл")
        row("Переподключить", "Заново отправить настройки в донгл") {
            service?.reconnect()
            Snackbar.make(list, "Донгл переподключается…", Snackbar.LENGTH_SHORT).show()
        }
        val info = service?.info
        if (info.isNullOrEmpty()) {
            row("Информация", "Появится после подключения донгла", null)
        } else {
            info.forEach { (k, v) -> row(k, v, null) }
        }
    }

    private fun header(text: String) {
        val v = layoutInflater.inflate(R.layout.item_header, list, false) as TextView
        v.text = text
        list.addView(v)
    }

    private fun row(title: String, value: String, onClick: (() -> Unit)?): View {
        val v = layoutInflater.inflate(R.layout.item_setting, list, false)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.value).text = value
        if (onClick != null) v.setOnClickListener { onClick() } else v.background = null
        list.addView(v)
        return v
    }

    private fun toggle(title: String, onHint: String, offHint: String, value: Boolean, set: (Boolean) -> Unit) {
        val v = row(title, if (value) onHint else offHint) {}
        val hint = v.findViewById<TextView>(R.id.value)
        val sw = v.findViewById<MaterialSwitch>(R.id.toggle)
        sw.visibility = View.VISIBLE
        sw.isChecked = value
        v.setOnClickListener {
            sw.isChecked = !sw.isChecked
            hint.text = if (sw.isChecked) onHint else offHint
            set(sw.isChecked)
        }
    }

    private fun <T> choice(title: String, current: T, options: List<Pair<T, String>>, set: (T) -> Unit) {
        val label = options.firstOrNull { it.first == current }?.second ?: current.toString()
        row(title, label) {
            val checked = options.indexOfFirst { it.first == current }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), checked) { d, i ->
                    set(options[i].first)
                    d.dismiss()
                    build()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun editText(title: String, value: String, type: Int, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(value)
            inputType = type
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) { save(text); build() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
