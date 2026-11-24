package com.flatcam.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object Proto {
    const val FRAME = 1; const val STATE = 2; const val CMD = 3; const val HELLO = 4; const val OK = 5; const val DENY = 6
    const val PORT = 8787; const val BEACON_PORT = 8788
    val BT_UUID: UUID = UUID.fromString("7c1d2f10-5a3e-4b8f-9d11-46696c546172")
    fun write(out: DataOutputStream, type: Int, data: ByteArray) {
        synchronized(out) { out.writeByte(type); out.writeInt(data.size); out.write(data); out.flush() }
    }
    fun read(inp: DataInputStream, maxLen: Int = 6_000_000): Pair<Int, ByteArray> {
        val t = inp.readUnsignedByte(); val n = inp.readInt()
        if (n < 0 || n > maxLen) throw java.io.IOException("bad frame")
        val b = ByteArray(n); inp.readFully(b)
        return Pair(t, b)
    }
}

/** FilTarPro side of the monitor link: accepts one FilTarView over Wi-Fi / hotspot (TCP) or Bluetooth (RFCOMM). */
class RemoteServer(private val ctx: Context, val pin: String, private val onCmd: (String) -> Unit, private val onChange: () -> Unit) {
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var curSock: Closeable2? = null
    @Volatile var clientDesc: String? = null
    @Volatile private var viaBt = false
    private val sending = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var tcp: ServerSocket? = null
    private var btServer: BluetoothServerSocket? = null
    @Volatile var btOn = false

    private class Closeable2(val c: () -> Unit)

    fun hasClient() = out != null
    fun canSendFrame() = out != null && !sending.get()
    fun lowBandwidth() = viaBt

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val s = ServerSocket(Proto.PORT); s.reuseAddress = true; tcp = s
                while (running) {
                    val c = s.accept(); c.tcpNoDelay = true
                    Thread { serve(c.getInputStream(), c.getOutputStream(), "Wi-Fi ${c.inetAddress.hostAddress}", false) { try { c.close() } catch (_: Exception) {} } }.start()
                }
            } catch (_: Exception) {}
        }.start()
        Thread { beacon() }.start()
    }

    private fun beacon() {
        try {
            val ds = DatagramSocket(); ds.broadcast = true
            val msg = "FILTAR|${android.os.Build.MODEL}|${Proto.PORT}".toByteArray()
            while (running) {
                try {
                    ds.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), Proto.BEACON_PORT))
                    for (ni in NetworkInterface.getNetworkInterfaces()) {
                        if (!ni.isUp || ni.isLoopback) continue
                        for (ia in ni.interfaceAddresses) { val b = ia.broadcast ?: continue; ds.send(DatagramPacket(msg, msg.size, b, Proto.BEACON_PORT)) }
                    }
                } catch (_: Exception) {}
                Thread.sleep(1500)
            }
            ds.close()
        } catch (_: Exception) {}
    }

    @Suppress("MissingPermission")
    fun startBluetooth(): Boolean {
        if (btOn) return true
        val ad: BluetoothAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        if (!ad.isEnabled) return false
        return try {
            val ss = ad.listenUsingRfcommWithServiceRecord("FilTarPro", Proto.BT_UUID)
            btServer = ss; btOn = true
            Thread {
                try { while (btOn) { val c = ss.accept(); Thread { serve(c.inputStream, c.outputStream, "Bluetooth ${c.remoteDevice?.name ?: ""}", true) { try { c.close() } catch (_: Exception) {} } }.start() } } catch (_: Exception) {}
                btOn = false
            }.start()
            true
        } catch (_: Exception) { false }
    }

    fun stopBluetooth() { btOn = false; try { btServer?.close() } catch (_: Exception) {}; btServer = null }

    private fun serve(i: InputStream, o: OutputStream, desc: String, bt: Boolean, close: () -> Unit) {
        val din = DataInputStream(java.io.BufferedInputStream(i))
        val dout = DataOutputStream(java.io.BufferedOutputStream(o))
        try {
            val h = Proto.read(din, 200)
            if (h.first != Proto.HELLO || String(h.second) != pin) { Proto.write(dout, Proto.DENY, ByteArray(0)); close(); return }
            Proto.write(dout, Proto.OK, ByteArray(0))
            try { curSock?.c?.invoke() } catch (_: Exception) {}
            curSock = Closeable2(close); out = dout; clientDesc = desc; viaBt = bt
            onChange()
            while (true) {
                val m = Proto.read(din, 100_000)
                if (m.first == Proto.CMD) onCmd(String(m.second))
            }
        } catch (_: Exception) {
        } finally {
            if (out === dout) { out = null; clientDesc = null; onChange() }
            close()
        }
    }

    fun disconnect() { try { curSock?.c?.invoke() } catch (_: Exception) {}; out = null; clientDesc = null; onChange() }

    fun sendState(s: String) {
        val o = out ?: return
        exec.execute { try { Proto.write(o, Proto.STATE, s.toByteArray()) } catch (_: Exception) {} }
    }

    /** Caller must check canSendFrame() first. The bitmap must stay untouched until the send finishes (sending flag). */
    fun sendFrame(bmp: Bitmap) {
        val o = out ?: return
        sending.set(true)
        exec.execute {
            try {
                var b = bmp
                val low = viaBt
                if (low && bmp.width > 360) b = Bitmap.createScaledBitmap(bmp, 360, (bmp.height * 360f / bmp.width).toInt().coerceAtLeast(2), true)
                val bo = ByteArrayOutputStream(48_000)
                b.compress(Bitmap.CompressFormat.JPEG, if (low) 45 else 68, bo)
                Proto.write(o, Proto.FRAME, bo.toByteArray())
            } catch (_: Exception) {} finally { sending.set(false) }
        }
    }

    fun close() { running = false; stopBluetooth(); try { tcp?.close() } catch (_: Exception) {}; disconnect(); exec.shutdown() }

    companion object {
        fun localIps(): List<String> {
            val l = ArrayList<String>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) { val h = a.hostAddress ?: continue; if (a is java.net.Inet4Address) l.add(h) }
                }
            } catch (_: Exception) {}
            return l
        }
    }
}
