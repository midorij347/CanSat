package com.example.myapplicationplp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat


class UsbCdcRepo(
    private val context: Context
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "com.example.USB_PERMISSION"
    private val permIntent = PendingIntent.getBroadcast(
        context, 0, Intent(permissionAction),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                permissionAction -> {
                    val device = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && granted) openDevice(device)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    (i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE))?.let { tryOpen(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    (i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE))?.let { if (it == currentDevice) close() }
                }
            }
        }
    }

    init {
        val f = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, f, ContextCompat.RECEIVER_EXPORTED)
    }

    private var currentDevice: UsbDevice? = null
    private var conn: UsbDeviceConnection? = null
    private var intfData: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var intfCtrl: UsbInterface? = null

    /** デバイス探索→権限リクエスト */
    fun findAndConnect(): Boolean {
        val candidates = usbManager.deviceList.values
        val dev = candidates.firstOrNull { hasCdcInterfaces(it) } ?: return false
        return tryOpen(dev)
    }

    private fun hasCdcInterfaces(device: UsbDevice): Boolean {
        // CDC-ACMは通常 IF0: Communication(0x02), IF1: Data(0x0A)
        var hasComm = false
        var hasData = false
        for (i in 0 until device.interfaceCount) {
            when (device.getInterface(i).interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> hasComm = true
                UsbConstants.USB_CLASS_CDC_DATA -> hasData = true
            }
        }
        return hasComm && hasData
    }

    private fun tryOpen(device: UsbDevice): Boolean {
        currentDevice = device
        return if (usbManager.hasPermission(device)) openDevice(device) else {
            usbManager.requestPermission(device, permIntent); false
        }
    }

    /** 実際にオープンしてIF/EPを確定、CDC初期化 */
    private fun openDevice(device: UsbDevice): Boolean {
        val connection = usbManager.openDevice(device) ?: return false

        var comm: UsbInterface? = null
        var data: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            when (itf.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> comm = itf
                UsbConstants.USB_CLASS_CDC_DATA -> data = itf
            }
        }
        if (data == null) { connection.close(); return false }

        // クレーム
        if (comm != null && !connection.claimInterface(comm, true)) { connection.close(); return false }
        if (!connection.claimInterface(data, true)) { connection.releaseInterface(comm); connection.close(); return false }

        // エンドポイント探索（Bulk IN/OUT）
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (e in 0 until data.endpointCount) {
            val ep = data.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
        }
        if (inEp == null || outEp == null) {
            connection.releaseInterface(data); comm?.let { connection.releaseInterface(it) }; connection.close(); return false
        }

        // CDC-ACM 初期化（Line Coding/Control Line State）
        comm?.let {
            // 115200bps, 1stop, no parity, 8bit  (デバイス側が無視することも多いが一応送る)
            val lineCoding = byteArrayOf(
                0x00, 0xC2.toByte(), 0x01, 0x00, // 115200 = 0x0001C200 (LE)
                0x00, // 1 stop bit
                0x00, // no parity
                0x08  // 8 data bits
            )
            connection.controlTransfer(
                UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_OUT,
                0x20, 0, it.id, lineCoding, lineCoding.size, 1000
            )
            // DTR(1) + RTS(1)
            connection.controlTransfer(
                UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_OUT,
                0x22, 0x0003, it.id, null, 0, 1000
            )
        }

        conn = connection
        intfCtrl = comm
        intfData = data
        epIn = inEp
        epOut = outEp
        return true
    }

    fun close() {
        conn?.apply {
            intfData?.let { releaseInterface(it) }
            intfCtrl?.let { releaseInterface(it) }
            close()
        }
        conn = null; intfData = null; intfCtrl = null; epIn = null; epOut = null; currentDevice = null
    }

    /** 送信（ブロッキング） */
    fun write(bytes: ByteArray, timeoutMs: Int = 1000): Int {
        val c = conn ?: return -1
        val out = epOut ?: return -1
        return c.bulkTransfer(out, bytes, bytes.size, timeoutMs) // 返り値＝送信バイト数
    }

    /** 受信（ブロッキング。呼び出し側でスレッド/コルーチンに載せる） */
    fun read(buf: ByteArray, timeoutMs: Int = 100): Int {
        val c = conn ?: return -1
        val inEp = epIn ?: return -1

        return c.bulkTransfer(inEp, buf, buf.size, timeoutMs) // -1はタイムアウト
    }
}
