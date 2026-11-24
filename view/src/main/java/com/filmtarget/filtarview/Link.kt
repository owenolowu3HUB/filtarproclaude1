package com.filmtarget.filtarview

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

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

class Link(val din: DataInputStream, val dout: DataOutputStream, private val closer: () -> Unit) {
    fun send(cmd: String) { Proto.write(dout, Proto.CMD, cmd.toByteArray()) }
    fun close() { try { closer() } catch (_: Exception) {} }
}

object Net {
    private fun handshake(s: java.io.InputStream, o: java.io.OutputStream, pin: String, close: () -> Unit): Link {
        val din = DataInputStream(BufferedInputStream(s))
        val dout = DataOutputStream(BufferedOutputStream(o))
        Proto.write(dout, Proto.HELLO, pin.toByteArray())
        val r = Proto.read(din, 100)
        if (r.first != Proto.OK) { close(); throw java.io.IOException("Wrong PIN") }
        return Link(din, dout, close)
    }

    /** Blocking: call from a worker thread. */
    fun connectTcp(host: String, pin: String): Link {
        val s = Socket()
        s.connect(InetSocketAddress(host, Proto.PORT), 4000)
        s.tcpNoDelay = true
        return handshake(s.getInputStream(), s.getOutputStream(), pin) { try { s.close() } catch (_: Exception) {} }
    }

    @SuppressLint("MissingPermission")
    fun connectBt(dev: BluetoothDevice, pin: String): Link {
        val s = dev.createRfcommSocketToServiceRecord(Proto.BT_UUID)
        s.connect()
        return handshake(s.inputStream, s.outputStream, pin) { try { s.close() } catch (_: Exception) {} }
    }

    /** Listens for FilTarPro beacons on the local network. Returns a stopper. */
    fun discover(onFound: (name: String, ip: String) -> Unit): () -> Unit {
        var run = true
        var sock: DatagramSocket? = null
        Thread {
            try {
                val ds = DatagramSocket(null); ds.reuseAddress = true; ds.bind(InetSocketAddress(Proto.BEACON_PORT)); sock = ds
                val buf = ByteArray(256)
                while (run) {
                    val p = DatagramPacket(buf, buf.size)
                    ds.receive(p)
                    val t = String(p.data, 0, p.length).split('|')
                    if (t.size >= 2 && t[0] == "FILTAR") onFound(t[1], p.address.hostAddress ?: continue)
                }
            } catch (_: Exception) {}
        }.start()
        return { run = false; try { sock?.close() } catch (_: Exception) {} }
    }
}
