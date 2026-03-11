package com.umutsar.z_cananalyzer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private lateinit var tvLogs: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggleConnect: Button
    private lateinit var btnStopResume: Button
    private lateinit var scrollView: ScrollView
    private lateinit var fabScrollDown: FloatingActionButton
    private lateinit var btnSend: Button
    private lateinit var etSendDlc: EditText

    private val dataLayoutIds = arrayOf(R.id.layoutD0, R.id.layoutD1, R.id.layoutD2, R.id.layoutD3, R.id.layoutD4, R.id.layoutD5, R.id.layoutD6, R.id.layoutD7)
    private val dataEditIds = arrayOf(R.id.etD0, R.id.etD1, R.id.etD2, R.id.etD3, R.id.etD4, R.id.etD5, R.id.etD6, R.id.etD7)

    private var usbSerialPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private val packetBuffer = mutableListOf<Byte>()

    private var isConnected = false
    private var isDataPaused = false
    private var autoScroll = true
    private var isPeriodicSending = false
    private val sendHandler = Handler(Looper.getMainLooper())
    private var periodicSendRunnable: Runnable? = null

    private val ACTION_USB_PERMISSION = "com.umutsar.z_cananalyzer.USB_PERMISSION"

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                runOnUiThread { openUsbConnection(granted) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLogs = findViewById(R.id.tvLogs)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggleConnect = findViewById(R.id.btnToggleConnect)
        btnStopResume = findViewById(R.id.btnStopResume)
        scrollView = findViewById(R.id.scrollView)
        fabScrollDown = findViewById(R.id.fabScrollDown)
        btnSend = findViewById(R.id.btnSend)
        etSendDlc = findViewById(R.id.etSendDlc)

        updateDataByteVisibility()
        etSendDlc.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateDataByteVisibility()
            }
        })

        applyDataByteFilters()
        findViewById<RadioGroup>(R.id.rgMode).setOnCheckedChangeListener { _, _ ->
            applyDataByteFilters()
        }

        findViewById<RadioGroup>(R.id.rgSendMode).setOnCheckedChangeListener { _, checkedId ->
            val isPeriodic = checkedId == R.id.rbPeriodic
            findViewById<View>(R.id.layoutInterval).visibility = if (isPeriodic) View.VISIBLE else View.GONE
        }

        findViewById<View>(R.id.txPanelBar).setOnClickListener { toggleTxPanel() }

        findViewById<View>(R.id.rootLayout).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hideKeyboard()
            }
            false
        }

        btnToggleConnect.setOnClickListener { if (!isConnected) connectUsb() else disconnectUsb() }
        btnStopResume.setOnClickListener { toggleDataFlow() }
        btnSend.setOnClickListener { onSendButtonClicked() }

        fabScrollDown.setOnClickListener {
            autoScroll = true
            scrollView.fullScroll(View.FOCUS_DOWN)
            fabScrollDown.visibility = View.GONE
        }

        scrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) hideKeyboard()
            false
        }
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (oldScrollY > scrollY) {
                autoScroll = false
                fabScrollDown.visibility = View.VISIBLE
            }
            val view = scrollView.getChildAt(scrollView.childCount - 1)
            val diff = view.bottom - (scrollView.height + scrollView.scrollY)
            if (diff <= 0) {
                autoScroll = true
                fabScrollDown.visibility = View.GONE
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            tvStatus.text = status
            tvStatus.setTextColor(Color.parseColor("#FF5252"))
        }
    }

    private fun updateLog(msg: String) {
        runOnUiThread {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            tvLogs.append("\n[$ts] $msg")
            tvLogs.setTextColor(Color.parseColor("#B0BEC5"))
            if (tvLogs.lineCount > 50000) tvLogs.text = getString(R.string.trace_cleared) + "\n"
            if (autoScroll) {
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun toggleDataFlow() {
        if (!isConnected) return
        isDataPaused = !isDataPaused
        tvLogs.setTextIsSelectable(isDataPaused)
        if (isDataPaused) {
            btnStopResume.text = getString(R.string.btn_resume)
            btnStopResume.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            updateStatus(getString(R.string.status_rx_paused))
        } else {
            btnStopResume.text = getString(R.string.btn_pause)
            btnStopResume.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
            updateStatus(getString(R.string.status_listening))
        }
    }

    private fun connectUsb() {
        try {
            usbIoManager?.stop()
            usbIoManager = null
            usbSerialPort?.close()
            usbSerialPort = null
        } catch (e: Exception) { }

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            updateStatus(getString(R.string.status_no_device))
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!manager.hasPermission(device)) {
            try {
                val intent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(packageName) // Explicit intent for API 34+
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
                manager.requestPermission(device, permissionIntent)
                updateStatus(getString(R.string.status_awaiting_permission))
            } catch (e: Exception) {
                updateStatus(getString(R.string.status_connection_error) + ": ${e.message}")
            }
            return
        }

        openUsbConnection(granted = true)
    }

    private fun openUsbConnection(granted: Boolean) {
        if (!granted) {
            updateStatus(getString(R.string.status_connection_error))
            return
        }

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            updateStatus(getString(R.string.status_no_device))
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!manager.hasPermission(device)) {
            updateStatus(getString(R.string.status_awaiting_permission))
            return
        }

        val connection = manager.openDevice(device) ?: run {
            updateStatus(getString(R.string.status_connection_error))
            return
        }

        val port = driver.ports[0]

        try {
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbSerialPort = port
            usbIoManager = SerialInputOutputManager(usbSerialPort, this)
            usbIoManager?.start()
            isConnected = true
            isDataPaused = false
            tvLogs.setTextIsSelectable(false)
            btnToggleConnect.text = getString(R.string.btn_disconnect)
            btnToggleConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            updateStatus(getString(R.string.status_connected))
        } catch (e: Exception) {
            try {
                port.close()
            } catch (_: Exception) { }
            connection.close()
            updateStatus(getString(R.string.status_connection_error) + ": ${e.message}")
        }
    }

    private fun disconnectUsb() {
        stopPeriodicSend()
        try {
            usbIoManager?.stop()
            usbIoManager = null
            usbSerialPort?.close()
            usbSerialPort = null
            isConnected = false
            btnToggleConnect.text = getString(R.string.btn_connect)
            btnToggleConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            updateStatus(getString(R.string.status_disconnected_msg))
        } catch (e: Exception) { }
    }

    override fun onNewData(data: ByteArray?) {
        if (isDataPaused) return
        data?.let { bytes ->
            for (b in bytes) {
                packetBuffer.add(b)
                while (packetBuffer.size >= 16) {
                    if (packetBuffer[0] == 0xAA.toByte() && packetBuffer[15] == 0xBB.toByte()) {
                        val fullPacket = packetBuffer.take(16).toByteArray()
                        processCanPacket(fullPacket)
                        repeat(16) { packetBuffer.removeAt(0) }
                    } else { packetBuffer.removeAt(0) }
                }
            }
        }
    }

    private fun processCanPacket(p: ByteArray) {
        val canId = ((p[2].toInt() and 0xFF shl 24) or (p[3].toInt() and 0xFF shl 16) or (p[4].toInt() and 0xFF shl 8) or (p[5].toInt() and 0xFF)).toLong()
        val dlc = p[6].toInt()
        val hexData = p.sliceArray(7..14).joinToString(" ") { "%02X".format(it) }
        updateLog(getString(R.string.log_rx, canId.toString(16).uppercase(), dlc, hexData))
    }

    override fun onRunError(e: Exception?) { runOnUiThread { disconnectUsb() } }

    private fun applyDataByteFilters() {
        val isHex = findViewById<RadioButton>(R.id.rbHex).isChecked
        val filters = if (isHex) {
            arrayOf<InputFilter>(
                InputFilter.LengthFilter(2),
                InputFilter { source, start, end, _, _, _ ->
                    source?.subSequence(start, end)?.toString()
                        ?.filter { c -> c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f' } ?: ""
                }
            )
        } else {
            arrayOf<InputFilter>(
                InputFilter.LengthFilter(3),
                InputFilter { source, start, end, dest, dstart, dend ->
                    val filtered = source?.subSequence(start, end)?.toString()?.filter { it in '0'..'9' } ?: ""
                    val newStr = StringBuilder(dest).replace(dstart, dend, filtered).toString()
                    val newVal = newStr.toIntOrNull() ?: 0
                    when {
                        newVal > 255 -> dest.subSequence(dstart, dend)
                        filtered != (source?.subSequence(start, end)?.toString() ?: "") -> filtered
                        else -> null
                    }
                }
            )
        }
        for (id in dataEditIds) {
            findViewById<EditText>(id).filters = filters
        }
        convertDataByteValuesOnModeSwitch(isHex)
    }

    private fun convertDataByteValuesOnModeSwitch(isHex: Boolean) {
        for (id in dataEditIds) {
            val et = findViewById<EditText>(id)
            val s = et.text.toString().trim()
            if (s.isEmpty()) continue
            val value = try {
                if (isHex) s.toInt(16) else s.toInt(10)
            } catch (_: Exception) { continue }
            val clamped = value.coerceIn(0, 255)
            val newText = if (isHex) "%02X".format(clamped) else clamped.toString()
            if (s != newText) {
                et.setText(newText)
                et.setSelection(newText.length)
            }
        }
    }

    private var isTxPanelExpanded = false

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        currentFocus?.clearFocus()
    }

    private fun toggleTxPanel() {
        isTxPanelExpanded = !isTxPanelExpanded
        val content = findViewById<View>(R.id.txPanelContent)
        val chevron = findViewById<TextView>(R.id.txPanelChevron)
        content.visibility = if (isTxPanelExpanded) View.VISIBLE else View.GONE
        chevron.text = if (isTxPanelExpanded) "▼" else "▶"
    }

    private fun updateDataByteVisibility() {
        val dlc = etSendDlc.text.toString().toIntOrNull() ?: 8
        val count = dlc.coerceIn(1, 8)
        for (i in 0..7) {
            findViewById<View>(dataLayoutIds[i]).visibility = if (i < count) View.VISIBLE else View.GONE
        }
    }

    private fun onSendButtonClicked() {
        if (isPeriodicSending) {
            stopPeriodicSend()
            return
        }
        val isPeriodic = findViewById<RadioButton>(R.id.rbPeriodic).isChecked
        if (isPeriodic) {
            val intervalStr = findViewById<EditText>(R.id.etIntervalMs).text.toString().trim()
            val intervalMs = intervalStr.toLongOrNull() ?: 0L
            if (intervalMs < 10) {
                updateStatus(getString(R.string.status_interval_min))
                return
            }
            startPeriodicSend(intervalMs)
        } else {
            sendCanData()
        }
    }

    private fun startPeriodicSend(intervalMs: Long) {
        if (!performSendCanData()) return
        isPeriodicSending = true
        btnSend.text = getString(R.string.btn_stop_transmit)
        btnSend.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
        periodicSendRunnable = object : Runnable {
            override fun run() {
                if (!isPeriodicSending || !isConnected) return
                performSendCanData()
                sendHandler.postDelayed(this, intervalMs)
            }
        }
        sendHandler.postDelayed(periodicSendRunnable!!, intervalMs)
    }

    private fun stopPeriodicSend() {
        isPeriodicSending = false
        periodicSendRunnable?.let { sendHandler.removeCallbacks(it) }
        periodicSendRunnable = null
        btnSend.text = getString(R.string.btn_transmit)
        btnSend.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#BB86FC"))
    }

    private fun sendCanData() {
        performSendCanData()
    }

    private fun performSendCanData(): Boolean {
        if (usbSerialPort == null || !isConnected) { updateStatus(getString(R.string.status_connect_first)); return false }
        try {
            val idStr = findViewById<EditText>(R.id.etSendId).text.toString().trim()
            if (idStr.isEmpty()) { updateStatus(getString(R.string.status_enter_can_id)); return false }
            val idLong = idStr.toLongOrNull(16) ?: run { updateStatus(getString(R.string.status_invalid_can_id)); return false }
            if (idLong < 0 || idLong > 0x1FFFFFFFL) { updateStatus(getString(R.string.status_can_id_range)); return false }

            var dlc = findViewById<EditText>(R.id.etSendDlc).text.toString().toIntOrNull() ?: 8
            dlc = dlc.coerceIn(1, 8)
            findViewById<EditText>(R.id.etSendDlc).setText(dlc.toString())

            val sendBuffer = ByteArray(16)
            sendBuffer[0] = 0xAA.toByte()
            sendBuffer[1] = 0x00.toByte()
            sendBuffer[2] = (idLong shr 24).toByte()
            sendBuffer[3] = (idLong shr 16).toByte()
            sendBuffer[4] = (idLong shr 8).toByte()
            sendBuffer[5] = idLong.toByte()
            sendBuffer[6] = dlc.toByte()

            val isHex = findViewById<RadioButton>(R.id.rbHex).isChecked
            val dataIds = arrayOf(R.id.etD0, R.id.etD1, R.id.etD2, R.id.etD3, R.id.etD4, R.id.etD5, R.id.etD6, R.id.etD7)
            for (i in 0 until dlc) {
                val inputStr = findViewById<EditText>(dataIds[i]).text.toString().trim()
                if (inputStr.isNotEmpty()) {
                    val value = try { if (isHex) inputStr.toInt(16) else inputStr.toInt(10) } catch (_: Exception) { -1 }
                    if (value !in 0..255) { updateStatus(getString(R.string.status_byte_range, i)); return false }
                    sendBuffer[7 + i] = value.toByte()
                } else { sendBuffer[7 + i] = 0x00.toByte() }
            }
            for (i in dlc until 8) { sendBuffer[7 + i] = 0x00.toByte() }
            sendBuffer[15] = 0xBB.toByte()

            usbSerialPort?.write(sendBuffer, 1000)
            val dataHex = sendBuffer.sliceArray(7 until 7 + dlc).joinToString(" ") { "%02X".format(it) }
            updateLog(getString(R.string.log_tx, idStr.uppercase(), dlc, dataHex))
            return true
        } catch (e: Exception) { updateLog(getString(R.string.log_error, e.message ?: "Unknown")); return false }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) { }
        stopPeriodicSend()
        disconnectUsb()
        super.onDestroy()
    }
}