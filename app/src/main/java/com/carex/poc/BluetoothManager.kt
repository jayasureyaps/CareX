package com.carex.poc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Manages a Bluetooth Classic (SPP) connection to the ESP32 sensor device.
 *
 * How it works:
 *   1. [connect] opens an RFCOMM socket using the standard SPP UUID.
 *   2. [readLines] returns a cold Flow<String> that reads newline-terminated CSV lines
 *      from the socket's input stream on Dispatchers.IO. Each emitted string is one
 *      line of sensor data like "0.1234,-0.4321,9.7123,0.0123,-0.0234,0.0345".
 *   3. [disconnect] closes the socket. The read loop in [readLines] will see the
 *      IOException from the closed stream and terminate naturally.
 *
 * Threading: All blocking socket operations happen on Dispatchers.IO.
 * The caller (ViewModel) collects the Flow on its own scope.
 */
class BluetoothManager {

    companion object {
        private const val TAG = "BluetoothManager"

        /** Standard Serial Port Profile UUID — used by virtually all SPP devices. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null

    /** True while a socket is connected. */
    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Connect to the given Bluetooth device via SPP.
     *
     * This is a **blocking** call — must be invoked from Dispatchers.IO or a background thread.
     * It creates an RFCOMM socket, then calls connect() which blocks until the connection
     * is established or fails with an IOException.
     *
     * @throws IOException if the connection fails (device out of range, not paired, etc.)
     * @throws SecurityException if BLUETOOTH_CONNECT permission is not granted.
     */
    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(device: BluetoothDevice) {
        // Close any existing connection first
        disconnect()

        Log.i(TAG, "Connecting to ${device.name} (${device.address})...")

        // Create an RFCOMM socket to the device's SPP service.
        // createRfcommSocketToServiceRecord performs SDP lookup to find the channel.
        val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
        socket = sock

        try {
            // connect() blocks until the connection is established.
            // Typical latency is 1–5 seconds depending on the BT stack and device.
            sock.connect()
            Log.i(TAG, "Connected to ${device.name}")
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed: ${e.message}")
            sock.close()
            socket = null
            throw e
        }
    }

    /**
     * Returns a cold Flow that emits each newline-terminated string read from the
     * Bluetooth socket's input stream.
     *
     * How the read loop works:
     *   - We wrap the raw InputStream in a BufferedReader for efficient line-based reading.
     *   - readLine() blocks until a full line is available (terminated by \n or \r\n).
     *   - If readLine() returns null, the stream has ended (device disconnected).
     *   - If an IOException occurs (e.g., socket closed by [disconnect]), we catch it
     *     and let the flow complete naturally.
     *   - The flow checks coroutineContext.isActive on each iteration so it can be
     *     cancelled cleanly when the ViewModel's scope is cancelled.
     *
     * The flow runs on Dispatchers.IO so the blocking reads don't freeze the UI.
     *
     * Expected data format from ESP32:
     *   "ax,ay,az,gx,gy,gz\n"
     *   e.g. "0.1234,-0.4321,9.7123,0.0123,-0.0234,0.0345\n"
     *
     * The first line may be a CSV header ("ax,ay,az,gx,gy,gz") — the ViewModel's
     * parser will skip lines that don't parse as 6 floats.
     */
    fun readLines(): Flow<String> = flow {
        val sock = socket ?: return@flow
        val reader = BufferedReader(InputStreamReader(sock.inputStream), 1024)

        try {
            while (coroutineContext.isActive) {
                // readLine() blocks until a complete line is received.
                // Returns null when the stream is closed / EOF.
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    emit(trimmed)
                }
            }
        } catch (e: IOException) {
            // Expected when disconnect() closes the socket while we're blocking on read.
            Log.d(TAG, "Read loop ended: ${e.message}")
        } finally {
            Log.d(TAG, "Read flow completed")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Disconnect and release the Bluetooth socket.
     *
     * Safe to call multiple times or when already disconnected.
     * This will cause any active [readLines] flow to terminate with an IOException
     * on its next read attempt.
     */
    fun disconnect() {
        try {
            socket?.close()
            Log.i(TAG, "Disconnected")
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        } finally {
            socket = null
        }
    }
}
