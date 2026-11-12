package com.lenid.zoh.main

import com.lenid.zoh.connection.usb.UsbSelfGrant
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.addCallback
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lenid.zoh.App
import com.lenid.zoh.R
import com.lenid.zoh.aap.AapProjectionActivity
import com.lenid.zoh.aap.AapService
import com.lenid.zoh.connection.usb.UsbAccessoryMode
import com.lenid.zoh.connection.usb.UsbDeviceCompat
import com.lenid.zoh.connection.usb.UsbDeviceDiagnostics
import com.lenid.zoh.connection.usb.UsbReceiver
import com.lenid.zoh.utils.Settings
import com.lenid.zoh.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UsbListFragment : DialogFragment() {
    private lateinit var adapter: DeviceAdapter
    private lateinit var settings: Settings
    private lateinit var noUsbDeviceTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private var connectionPanel: com.lenid.zoh.utils.ConnectionPanel? = null


    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.AppTheme_ConnectionOverlay)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog =
        super.onCreateDialog(savedInstanceState).also { windowDialog ->
            (windowDialog as? androidx.activity.ComponentDialog)?.onBackPressedDispatcher
                ?.addCallback(this) { dismiss() }
        }

    override fun dismiss() {
        val panel = connectionPanel
        if (panel != null && panel.root.isAttachedToWindow) panel.dismiss { super.dismiss() }
        else super.dismiss()
    }

    override fun onDestroyView() {
        connectionPanel = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setWindowAnimations(0)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val panel = com.lenid.zoh.utils.ConnectionPanel(requireContext(), R.string.usb,
            R.string.usb_panel_subtitle, R.drawable.ic_usb, requireDialog().window!!) { dismiss() }
        connectionPanel = panel
        val density = resources.displayMetrics.density
        noUsbDeviceTextView = panel.text(R.string.no_usb_device_connected, 24f, true).apply {
            gravity = android.view.Gravity.CENTER
            minHeight = (200 * density).toInt()
            setPadding((24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(requireContext(), R.color.home_tile))
                cornerRadius = 24 * density
            }
        }
        recyclerView = RecyclerView(requireContext()).apply { isNestedScrollingEnabled = false }
        panel.body.addView(noUsbDeviceTextView, android.widget.LinearLayout.LayoutParams(-1, -2))
        panel.body.addView(recyclerView, android.widget.LinearLayout.LayoutParams(-1, (320 * density).toInt()))
        panel.body.addView(panel.text(R.string.usb_panel_hint, 16f, true).apply {
            setPadding(0, (24 * density).toInt(), 0, 0)
        })
        settings = Settings(requireContext())
        adapter = DeviceAdapter(requireContext(), settings, lifecycleScope)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Add padding
        val padding = resources.getDimensionPixelSize(R.dimen.list_padding)
        recyclerView.setPadding(padding, padding, padding, padding)
        recyclerView.clipToPadding = false

        return panel.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel.usbDevices.observe(viewLifecycleOwner, Observer {
            val allowDevices = settings.allowedDevices
            adapter.setData(it, allowDevices)

            if (it.isEmpty()) {
                // A unit whose ROM never declared USB host enumerates nothing whatever is plugged
                // in, so "no device connected" reads as the user's fault when it is not.
                noUsbDeviceTextView.setText(
                    if (UsbDeviceDiagnostics.hasUsbHostFeature(requireContext())) R.string.no_usb_device_connected
                    else R.string.no_usb_host_support
                )
                noUsbDeviceTextView.visibility = VISIBLE
                recyclerView.visibility = GONE
            } else {
                noUsbDeviceTextView.visibility = GONE
                recyclerView.visibility = VISIBLE
            }
        })
    }

    override fun onPause() {
        super.onPause()
        settings.commit()
    }

    private class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val allowButton = itemView.findViewById<Button>(android.R.id.button1)
        val startButton = itemView.findViewById<Button>(android.R.id.button2)
    }

    private class DeviceAdapter(
        private val mContext: Context,
        private val mSettings: Settings,
        private val scope: CoroutineScope,
    ) : RecyclerView.Adapter<DeviceViewHolder>(), View.OnClickListener {
        private var allowedDevices: MutableSet<String> = mutableSetOf()
        private var deviceList: List<UsbDeviceCompat> = listOf()
        private var lastClickTime: Long = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(mContext).inflate(R.layout.list_item_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = deviceList[position]

            // Background styling logic
            val isTop = position == 0
            val isBottom = position == itemCount - 1
            val bgRes = when {
                isTop && isBottom -> R.drawable.bg_setting_single
                isTop -> R.drawable.bg_setting_top
                isBottom -> R.drawable.bg_setting_bottom
                else -> R.drawable.bg_setting_middle
            }
            holder.itemView.setBackgroundResource(bgRes)

            val vidPidText = " (${mSettings.formatUsbVidPidDisplay(device.vendorId, device.productId)})"
            val title = TextUtils.htmlEncode("${device.uniqueName}$vidPidText")
            val path = TextUtils.htmlEncode(device.deviceName)
            holder.startButton.text = HtmlCompat.fromHtml(
                "<b>$title</b><br/>$path",
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )
            holder.startButton.tag = position
            holder.startButton.setOnClickListener(this)

            val isBlacklisted = mSettings.isUsbDeviceBlacklisted(device.wrappedDevice)

            if (device.isInAccessoryMode) {
                holder.allowButton.setText(R.string.allowed)
                holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_green_700))
                holder.allowButton.isEnabled = false
            } else if (isBlacklisted) {
                holder.allowButton.setText(R.string.blacklisted)
                holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_red_700))
                holder.allowButton.tag = position
                holder.allowButton.isEnabled = true
                holder.allowButton.setOnClickListener(this)
            } else {
                if (allowedDevices.contains(device.uniqueName)) {
                    holder.allowButton.setText(R.string.allowed)
                    holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_green_700))
                } else {
                    holder.allowButton.setText(R.string.ignored)
                    holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_orange_700))
                }
                holder.allowButton.tag = position
                holder.allowButton.isEnabled = true
                holder.allowButton.setOnClickListener(this)
            }
        }

        override fun getItemCount(): Int {
            return deviceList.size
        }

        override fun onClick(v: View) {
            // Debounce clicks (prevent double tap)
            if (SystemClock.elapsedRealtime() - lastClickTime < 1000) {
                return
            }
            lastClickTime = SystemClock.elapsedRealtime()

            val position = v.tag as? Int ?: return
            if (position < 0 || position >= deviceList.size) {
                return
            }
            val device = deviceList[position]
            if (v.id == android.R.id.button1) {
                val isBlacklisted = mSettings.isUsbDeviceBlacklisted(device.wrappedDevice)
                val isAllowed = allowedDevices.contains(device.uniqueName)

                when {
                    isBlacklisted -> {
                        // Blacklisted -> Ignored
                        mSettings.removeUsbDeviceFromBlacklist(device.wrappedDevice)
                    }
                    isAllowed -> {
                        // Allowed -> Blacklisted
                        allowedDevices.remove(device.uniqueName)
                        mSettings.allowedDevices = allowedDevices
                        mSettings.addUsbDeviceToBlacklist(device.wrappedDevice)
                    }
                    else -> {
                        // Ignored -> Allowed
                        allowedDevices.add(device.uniqueName)
                        mSettings.allowedDevices = allowedDevices
                    }
                }
                notifyDataSetChanged()
            } else {
                HomeUsbConnect.connect(mContext, mSettings, scope, device) { notifyDataSetChanged() }
            }
        }

        fun setData(deviceList: List<UsbDeviceCompat>, allowedDevices: Set<String>) {
            this.allowedDevices = allowedDevices.toMutableSet()
            this.deviceList = deviceList
            notifyDataSetChanged()
        }
    }

}
