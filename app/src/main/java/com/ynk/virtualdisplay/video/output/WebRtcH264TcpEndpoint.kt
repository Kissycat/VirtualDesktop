package com.ynk.virtualdisplay.video.output

import android.os.Process
import android.util.Log
import com.ynk.virtualdisplay.video.EncodedVideoSink
import com.ynk.virtualdisplay.video.VideoStreamController
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional low-overhead H.264 output for a WebRTC gateway.
 *
 * This is intentionally NOT a browser-facing WebRTC signaling server. It exposes
 * the already-encoded H.264 stream without decoding/re-encoding. A Pion/mediasoup/
 * native WebRTC gateway can consume this stream, packetize H.264 into RTP, and
 * publish it through WebRTC/WHIP/WHEP without touching the Android decoder path.
 *
 * Wire format:
 *   connection header: magic[4] = "VDH1", version(int), displayId(int), width(int), height(int)
 *   format update:      type(byte=1), width(int), height(int)
 *   frame packet:       type(byte=2), ptsUs(long), flags(int), size(int), payload[size]
 *
 * Frame flags: bit 0 = codec-config, bit 1 = key-frame.
 *
 * Backpressure is isolated per client. H.264 inter-frame packets are never
 * skipped independently: if a client queue overflows, that client enters key-frame
 * recovery mode and drops subsequent P-frames until the next complete IDR arrives.
 * This preserves H.264 reference-frame continuity and avoids visual tearing.
 */
class WebRtcH264TcpEndpoint(
    private val videoController: VideoStreamController,
    private val displayId: Int,
    private val bindHost: String = "127.0.0.1",
    private val port: Int,
    private val clientQueueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) : EncodedVideoSink, AutoCloseable {

    companion object {
        private const val TAG = "WebRtcH264TcpEndpoint"
        private val MAGIC = "VDH1".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val TYPE_FORMAT = 1
        private const val TYPE_FRAME = 2
        // Large enough to absorb short bitrate spikes without introducing a large
        // standing latency. At 60 fps this is ~400 ms; at 120 fps ~200 ms.
        private const val DEFAULT_QUEUE_CAPACITY = 24
        private const val SOCKET_BACKLOG = 8
        private const val SOCKET_SEND_BUFFER_BYTES = 1024 * 1024
    }

    private data class FramePacket(val bytes: ByteArray, val isConfig: Boolean = false, val isKeyFrame: Boolean = false)

    private data class RawFrame(
        val ptsUs: Long,
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
        val data: ByteArray,
    )

    private class Client(
        private val socket: Socket,
        queueCapacity: Int,
        private val initialFormat: () -> Pair<Int, Int>,
        private val displayId: Int,
        initialConfig: FramePacket?,
        initialKey: FramePacket?,
    ) : AutoCloseable {

        private val queue = LinkedBlockingDeque<FramePacket>(queueCapacity.coerceAtLeast(8))
        private val wakePacket = FramePacket(ByteArray(0))
        @Volatile private var pendingConfig: FramePacket? = initialConfig
        @Volatile private var pendingKey: FramePacket? = initialKey
        @Volatile private var awaitingKeyFrame: Boolean = initialKey == null
        @Volatile private var overflowRecoveryCount: Long = 0

        private val closed = AtomicBoolean(false)
        private val writer = Thread({ runWriter() }, "webrtc-h264-writer-${socket.port}")

        init {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = SOCKET_SEND_BUFFER_BYTES
        }

        fun start() {
            writer.start()
        }

        fun offer(packet: FramePacket, critical: Boolean) {
            if (closed.get()) return

            if (packet.isConfig) {
                // A new CSD starts a new decoder configuration generation. Any queued
                // P-frames belong to the previous generation and must not be sent after it.
                pendingConfig = packet
                pendingKey = null
                queue.clear()
                awaitingKeyFrame = true
                queue.offerLast(wakePacket)
                return
            }

            if (packet.isKeyFrame) {
                // The IDR is the safe recovery boundary. Drop all older queued P-frames
                // so the stream resumes as CONFIG -> IDR -> contiguous P-frames.
                queue.clear()
                pendingKey = packet
                awaitingKeyFrame = false
                queue.offerLast(wakePacket)
                return
            }

            // Never skip an arbitrary P-frame. Once one P-frame has been dropped,
            // all following P-frames are unsafe until a fresh IDR is available.
            if (awaitingKeyFrame) {
                return
            }

            if (!queue.offerLast(packet)) {
                queue.clear()
                awaitingKeyFrame = true
                overflowRecoveryCount++
                if (overflowRecoveryCount <= 5 || overflowRecoveryCount % 50L == 0L) {
                    Log.w(TAG, "H.264 client queue overflow; entering IDR recovery #$overflowRecoveryCount")
                }
            }
        }

        fun offerFormat(width: Int, height: Int) {
            if (closed.get()) return
            // A resize/reset starts a new decoder session. Never replay CSD/IDR
            // from the previous resolution before the new format metadata.
            pendingConfig = null
            pendingKey = null
            awaitingKeyFrame = true
            queue.clear()
            val out = java.io.ByteArrayOutputStream(9)
            DataOutputStream(out).use { data ->
                data.writeByte(TYPE_FORMAT)
                data.writeInt(width)
                data.writeInt(height)
            }
            queue.offerLast(FramePacket(out.toByteArray()))
        }

        private fun runWriter() {
            try {
                val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                val data = DataOutputStream(out)
                val (headerWidth, headerHeight) = initialFormat()
                data.write(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(displayId)
                data.writeInt(headerWidth)
                data.writeInt(headerHeight)
                data.flush()

                while (!closed.get()) {
                    val config = pendingConfig
                    if (config != null) {
                        pendingConfig = null
                        data.write(config.bytes)
                        data.flush()
                        continue
                    }

                    val key = pendingKey
                    if (key != null) {
                        pendingKey = null
                        data.write(key.bytes)
                        data.flush()
                        continue
                    }

                    // Blocking take avoids periodic 100ms wake-ups when no frames arrive.
                    // CONFIG/IDR changes enqueue a zero-byte wake packet so pending* is
                    // consumed immediately without serializing a fake wire packet.
                    val packet = queue.takeFirst()
                    if (packet.bytes.isEmpty()) continue
                    data.write(packet.bytes)
                    data.flush()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: IOException) {
                // Normal when the remote WebRTC gateway disconnects.
            } finally {
                close()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            pendingConfig = null
            pendingKey = null
            queue.clear()
            writer.interrupt()
            runCatching { socket.close() }
        }
    }

    // Never serialize or fan-out WebRTC packets on H264StreamDecoder.runInputLoop().
    private val rawFrameQueue = java.util.concurrent.ArrayBlockingQueue<RawFrame>(12)
    private val outputWorker = Thread({ runOutputWorker() }, "webrtc-h264-output-$port")
    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArraySet<Client>()
    private val serverLock = Any()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var latestConfig: FramePacket? = null
    @Volatile private var latestKeyFrame: FramePacket? = null

    fun start() {
        synchronized(serverLock) {
            if (running.get()) return
            require(port in 1..65535) { "Invalid endpoint port: $port" }
            require(clientQueueCapacity >= 2) { "clientQueueCapacity must be >= 2" }

            val server = ServerSocket(port, SOCKET_BACKLOG, InetAddress.getByName(bindHost)).apply {
                // accept() blocks until a client arrives or close() is called; no timeout polling.
                reuseAddress = true
            }
            serverSocket = server
            running.set(true)
            videoController.addEncodedVideoSink(this)
            outputWorker.start()
            acceptThread = Thread({ acceptLoop(server) }, "webrtc-h264-accept-$port").also { it.start() }
            Log.i(TAG, "H.264 WebRTC bridge endpoint listening on $bindHost:$port for display=$displayId")
        }
    }

    override fun onFormat(codec: String, width: Int, height: Int) {
        if (!codec.equals("H.264", ignoreCase = true)) return
        this.width = width
        this.height = height
        // A format change starts a new H.264 decoder session; old CSD/IDR must
        // never be replayed before the new encoder publishes fresh values.
        latestConfig = null
        latestKeyFrame = null
        clients.forEach { it.offerFormat(width, height) }
        Log.i(TAG, "H.264 format updated: ${width}x${height}")
    }

    override fun onFrame(ptsUs: Long, isConfig: Boolean, isKeyFrame: Boolean, data: ByteArray, size: Int) {
        if (!running.get() || size <= 0) return

        // The decoder owns/reuses this buffer. Make one bounded copy and return
        // immediately; all packetization/network work happens on outputWorker.
        val frame = RawFrame(ptsUs, isConfig, isKeyFrame, data.copyOf(size))
        if (isConfig || isKeyFrame) {
            while (!rawFrameQueue.offer(frame)) rawFrameQueue.poll()
        } else {
            // Never block or apply back-pressure to the video decoder for WebRTC.
            rawFrameQueue.offer(frame)
        }
    }

    private fun runOutputWorker() {
        try {
            // Slightly favor the output pump without taking foreground/display priority
            // away from the decoder/UI threads.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE) }
            while (running.get() && !Thread.currentThread().isInterrupted) {
                // No timed polling: when idle, wait until an encoded frame is enqueued.
                processRawFrame(rawFrameQueue.take())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            if (running.get()) Log.e(TAG, "WebRTC output worker failed", t)
        }
    }

    private fun processRawFrame(frame: RawFrame) {
        val size = frame.data.size
        val stream = java.io.ByteArrayOutputStream(1 + 8 + 4 + 4 + size)
        DataOutputStream(stream).use { out ->
            out.writeByte(TYPE_FRAME)
            out.writeLong(frame.ptsUs)
            var flags = 0
            if (frame.isConfig) flags = flags or 1
            if (frame.isKeyFrame) flags = flags or 2
            out.writeInt(flags)
            out.writeInt(size)
            out.write(frame.data)
        }

        val packet = FramePacket(
            bytes = stream.toByteArray(),
            isConfig = frame.isConfig,
            isKeyFrame = frame.isKeyFrame
        )

        if (frame.isConfig) {
            latestConfig = packet
            latestKeyFrame = null
            Log.i(TAG, "H.264 CONFIG received from encoder: pts=${frame.ptsUs} size=$size head=${hexPrefix(frame.data, 32)}")
        }
        if (frame.isKeyFrame) {
            latestKeyFrame = packet
            Log.i(TAG, "H.264 KEY received from encoder: pts=${frame.ptsUs} size=$size")
        }

        if (clients.isEmpty()) return
        clients.forEach { it.offer(packet, critical = frame.isConfig || frame.isKeyFrame) }
    }

    private fun hexPrefix(bytes: ByteArray, max: Int): String {
        val n = minOf(bytes.size, max)
        return buildString(n * 3) {
            for (i in 0 until n) {
                if (i > 0) append(' ')
                append("%02x".format(bytes[i].toInt() and 0xff))
            }
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            try {
                val socket = server.accept()
                val client = Client(
                    socket = socket,
                    queueCapacity = clientQueueCapacity,
                    initialFormat = { width to height },
                    displayId = displayId,
                    initialConfig = latestConfig,
                    initialKey = latestKeyFrame,
                )
                clients.add(client)
                try {
                    client.start()
                    Log.i(TAG, "H.264 WebRTC bridge client connected from ${socket.inetAddress.hostAddress}:${socket.port}")
                } catch (e: IOException) {
                    clients.remove(client)
                    client.close()
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Periodically check running flag.
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Accept loop failed", e)
            }
        }
    }


    fun status(): WebRtcH264OutputStatus = WebRtcH264OutputStatus(
        running = running.get(),
        displayId = displayId,
        bindHost = bindHost.takeIf { running.get() },
        port = port.takeIf { running.get() },
        clientCount = clients.size
    )

    override fun close() {
        synchronized(serverLock) {
            if (!running.compareAndSet(true, false)) return
            videoController.removeEncodedVideoSink(this)
            runCatching { serverSocket?.close() }
            serverSocket = null
            clients.forEach { it.close() }
            clients.clear()
            rawFrameQueue.clear()
            latestConfig = null
            latestKeyFrame = null
            outputWorker.interrupt()
            acceptThread?.interrupt()
            acceptThread = null
            Log.i(TAG, "H.264 WebRTC bridge endpoint stopped")
        }
    }
}
