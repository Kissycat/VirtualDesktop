package com.ynk.virtualdisplay.webrtc

import android.content.Context
import android.os.Build
import android.util.Log
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.PrivilegeMode
import rikka.shizuku.Shizuku
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.ynk.virtualdisplay.MyApplication

/**
 * Starts the bundled WebRTC gateway through Shizuku/root from /data/local/tmp.
 *
 * The gateway connects directly to the running scrcpy daemon using its
 * ROLE_NEGOTIATION + ROLE_VIDEO protocol. No second H.264 endpoint is created.
 *
 * We never use Process.waitFor()/exitValue() for the long-running gateway.
 * Shizuku Process wrappers are not a reliable lifecycle source for a daemon.
 */
class GatewayProcessController(private val context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "GatewayProcessController"
        private const val BINARY_NAME = "virtualdisplay-webrtc-gateway"
        private const val REMOTE_PATH = "/data/local/tmp/$BINARY_NAME"
        const val DEFAULT_GATEWAY_PORT = 19000
        const val DEFAULT_CONTROL_PORT = 18081
        private const val LOG_PATH = "/data/local/tmp/virtualdisplay-webrtc-gateway.log"
        private const val PID_PATH = "/data/local/tmp/virtualdisplay-webrtc-gateway.pid"
        private const val START_TIMEOUT_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 300

        /** Shared process state used by background-quiescence policy across all video controllers. */
        private val _gatewayRunningFlow = MutableStateFlow(false)
        val gatewayRunningFlow: StateFlow<Boolean> = _gatewayRunningFlow.asStateFlow()

        @Volatile
        private var gatewayRunningKnown = false
        private val backgroundStateMonitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val backgroundStateMonitorLock = Any()
        @Volatile
        private var backgroundStateMonitorJob: Job? = null

        fun isGatewayRunningKnown(): Boolean = gatewayRunningKnown

        private fun publishGatewayRunningState(running: Boolean) {
            val changed = gatewayRunningKnown != running
            gatewayRunningKnown = running
            _gatewayRunningFlow.value = running
            if (running && !MyApplication.isPhysicalDisplayForeground) {
                if (changed || backgroundStateMonitorJob?.isActive != true) {
                    startBackgroundStateMonitor()
                }
            } else if (!running && (changed || backgroundStateMonitorJob != null)) {
                stopBackgroundStateMonitor()
            }
        }

        /** Stop probing once the APP returns to display 0; foreground monitors resume then. */
        fun onPhysicalDisplayForegroundChanged(foreground: Boolean) {
            if (foreground) {
                stopBackgroundStateMonitor()
            } else if (gatewayRunningKnown) {
                startBackgroundStateMonitor()
            }
        }

        private fun startBackgroundStateMonitor() {
            if (MyApplication.isPhysicalDisplayForeground || !gatewayRunningKnown) return
            synchronized(backgroundStateMonitorLock) {
                if (backgroundStateMonitorJob?.isActive == true) return
                backgroundStateMonitorJob = backgroundStateMonitorScope.launch {
                    // While GO is active, keep the app in its normal mode and watch
                    // for an independent stop (e.g. `pkill`/external management).
                    // The watch exists only while GO is known alive and terminates as
                    // soon as the port closes; no timer remains when GO is stopped.
                    while (isActive) {
                        delay(1_500L)
                        if (MyApplication.isPhysicalDisplayForeground || !gatewayRunningKnown) break
                        val stillListening = isLocalPortOpen(DEFAULT_GATEWAY_PORT, PROBE_TIMEOUT_MS)
                        publishGatewayRunningState(stillListening)
                        if (!stillListening) break
                    }
                }
            }
        }

        private fun stopBackgroundStateMonitor() {
            synchronized(backgroundStateMonitorLock) {
                backgroundStateMonitorJob?.cancel()
                backgroundStateMonitorJob = null
            }
        }

        /**
         * Low-cost liveness check for lifecycle transitions. The gateway binds its
         * HTTP/WebRTC listener on localhost even when launched independently of the APP.
         * Probe once on entering background. A lightweight 1.5-second watcher exists
         * only while GO is known active so an external GO shutdown re-enables quiescence;
         * it terminates as soon as the port closes.
         */
        fun refreshGatewayRunningFromLocalPort(): Boolean {
            val running = isLocalPortOpen(DEFAULT_GATEWAY_PORT, PROBE_TIMEOUT_MS)
            publishGatewayRunningState(running)
            return running
        }

        private fun isLocalPortOpen(port: Int, timeoutMs: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Volatile private var pid: Int = -1

    fun isRunning(): Boolean {
        // The gateway is a privileged process. Determine its lifecycle from the
        // privileged PID/process table first, and use the localhost TCP probe only
        // as a fallback. This keeps the UI state independent from the app's UID.
        val running = runCatching { remoteGatewayProcessAlive() }.getOrDefault(false) ||
            isPortOpen(DEFAULT_GATEWAY_PORT)
        publishGatewayRunningState(running)
        return running
    }

    /** Starts the bundled gateway and makes it consume the existing scrcpy daemon stream. */
    @Synchronized
    fun start(
        displayId: Int,
        gatewayPort: Int = DEFAULT_GATEWAY_PORT,
        bindHost: String = "0.0.0.0",
        expectedWidth: Int = 0,
        expectedHeight: Int = 0,
    ): Result<String> {
        if (displayId < 0) return Result.failure(IllegalArgumentException("Invalid displayId=$displayId"))

        val mode = AppSettings.getPrivilegeModeSync()
        if (mode == PrivilegeMode.NONE) {
            return Result.failure(IllegalStateException("WebRTC Gateway requires Shizuku or root privilege"))
        }
        if (mode == PrivilegeMode.SHIZUKU &&
            (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED)
        ) {
            return Result.failure(IllegalStateException("Shizuku is not available or not authorized"))
        }

        return runCatching {
            // Never reuse a gateway that is attached to another display. The
            // gateway owns one scrcpy ROLE_VIDEO stream, so reusing it is exactly
            // how a previous physical-screen stream can leak into the new display.
            if (isPortOpen(gatewayPort)) {
                val existingDisplay = readGatewayDisplayId(gatewayPort)
                if (existingDisplay == null || existingDisplay != displayId) {
                    Log.i(
                        TAG,
                        "Gateway port $gatewayPort belongs to display=$existingDisplay; " +
                            "requested display=$displayId, restarting gateway"
                    )
                    stop()
                } else {
                    Log.i(TAG, "Reusing gateway already attached to display=$displayId")
                }
            }

            if (!isPortOpen(gatewayPort)) {
                installBinary()

                val node = AppSettings.getCurrentServerNodeSync()
                val daemonHost = when {
                    node.host == "0.0.0.0" || node.host == "localhost" -> "127.0.0.1"
                    else -> node.host
                }
                val daemonAddr = "$daemonHost:${node.port}"

                launchGateway(
                    daemonAddr = daemonAddr,
                    daemonToken = node.password,
                    displayId = displayId,
                    gatewayPort = gatewayPort,
                    expectedWidth = expectedWidth,
                    expectedHeight = expectedHeight,
                )
                if (!waitForPort("127.0.0.1", gatewayPort, START_TIMEOUT_MS)) {
                    val log = readGatewayLog().take(4096)
                    throw IOException("Gateway did not open port $gatewayPort${if (log.isBlank()) "" else ": $log"}")
                }
                // A listening gateway is a successful process start. Do not turn a
                // temporary/no-frame condition into a failed start: the scrcpy
                // subscriber can become ready slightly later, especially when another
                // local subscriber already owns the shared encoder.
                val sourceReady = waitForGatewaySourceReady(gatewayPort, displayId, 3_000L)
                if (!sourceReady) {
                    Log.w(
                        TAG,
                        "Gateway is running but source display=$displayId is not ready yet; " +
                            "continuing startup. Recent gateway log: ${readGatewayLog().take(2048)}"
                    )
                }
                Log.i(
                    TAG,
                    "Gateway launched: daemon=$daemonAddr displayId=$displayId " +
                        "size=${expectedWidth}x${expectedHeight} gatewayPort=$gatewayPort sourceReady=$sourceReady"
                )
            }

            publishGatewayRunningState(
                isPortOpen(DEFAULT_GATEWAY_PORT) ||
                    (gatewayPort == DEFAULT_GATEWAY_PORT && runCatching { remoteGatewayProcessAlive() }.getOrDefault(false))
            )
            browserUrl(gatewayPort)
        }
    }

    /** Stops only the bundled WebRTC gateway process. */
    @Synchronized
    fun stop() {
        stopRemoteGateway()
        pid = -1
        runCatching {
            runPrivilegedShell("rm -f $REMOTE_PATH $LOG_PATH $PID_PATH")
        }
        val stillRunning = runCatching { remoteGatewayProcessAlive() }.getOrDefault(false) ||
            isPortOpen(DEFAULT_GATEWAY_PORT)
        publishGatewayRunningState(stillRunning)
    }

    private fun installBinary() {
        val abi = selectAbi()
        val assetPath = "gateway/$abi/$BINARY_NAME"
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        if (bytes.isEmpty()) throw IOException("Gateway binary asset is empty: $assetPath")

        // Do not wait for the Shizuku Process to report shell termination. On some
        // Shizuku implementations the returned Process wrapper may stay non-terminal
        // even though the privileged writer has already created the file successfully.
        // Instead, the shell emits an explicit completion marker and we fall back to a
        // privileged file-size check.
        val command = "cat > '$REMOTE_PATH' && chmod 700 '$REMOTE_PATH' && echo VDGW_INSTALL_OK"
        val writer = executePrivileged(arrayOf("sh", "-c", command))
            ?: throw IOException("Failed to create privileged writer")
        try {
            writer.outputStream.use { it.write(bytes); it.flush() }

            val stdoutFuture = java.util.concurrent.FutureTask<String> {
                writer.inputStream.bufferedReader().readText()
            }
            Thread(stdoutFuture, "gateway-install-output").apply { isDaemon = true }.start()

            val marker = runCatching { stdoutFuture.get(4, TimeUnit.SECONDS) }.getOrNull().orEmpty()
            val ok = marker.contains("VDGW_INSTALL_OK") || remoteFileLooksValid(bytes.size)
            if (!ok) {
                val err = runCatching {
                    writer.errorStream.bufferedReader().readText()
                }.getOrDefault("")
                throw IOException("Gateway binary installation could not be verified${if (err.isBlank()) "" else ": $err"}")
            }
        } finally {
            runCatching { writer.destroy() }
            runCatching { writer.inputStream.close() }
            runCatching { writer.errorStream.close() }
        }
    }


    private fun remoteFileLooksValid(expectedSize: Int): Boolean {
        val proc = executePrivileged(arrayOf("sh", "-c", "test -s '$REMOTE_PATH' && wc -c < '$REMOTE_PATH' || echo 0")) ?: return false
        return try {
            val future = java.util.concurrent.FutureTask<String> { proc.inputStream.bufferedReader().readText().trim() }
            Thread(future, "gateway-install-verify").apply { isDaemon = true }.start()
            val size = runCatching { future.get(2, TimeUnit.SECONDS).toLongOrNull() }.getOrNull() ?: return false
            size == expectedSize.toLong()
        } finally {
            runCatching { proc.destroy() }
        }
    }

    private fun launchGateway(
        daemonAddr: String,
        daemonToken: String,
        displayId: Int,
        gatewayPort: Int,
        expectedWidth: Int,
        expectedHeight: Int,
    ) {
        val command = buildString {
            append("rm -f '").append(PID_PATH).append("'; ")
            append("export DAEMON_ADDR=").append(quoteShell(daemonAddr)).append("; ")
            append("export DAEMON_TOKEN=").append(quoteShell(daemonToken)).append("; ")
            append("export DISPLAY_ID='").append(displayId).append("'; ")
            append("export EXPECTED_WIDTH='").append(expectedWidth).append("'; ")
            append("export EXPECTED_HEIGHT='").append(expectedHeight).append("'; ")
            append("export LISTEN='0.0.0.0:").append(gatewayPort).append("'; ")
            append("export CURSOR_URL='http://127.0.0.1:").append(DEFAULT_CONTROL_PORT).append("/api/webrtc/cursor'; ")
            append("nohup '").append(REMOTE_PATH).append("' </dev/null >'").append(LOG_PATH).append("' 2>&1 & ")
            append("echo \$! > '").append(PID_PATH).append("'; ")
            append("sleep 0.2; cat '").append(PID_PATH).append("' 2>/dev/null || true")
        }
        val launcher = executePrivileged(arrayOf("sh", "-c", command))
            ?: throw IOException("Failed to launch privileged gateway command")
        try {
            val output = launcher.inputStream.bufferedReader().readText().trim()
            pid = output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull() ?: -1
            // This shell is only a launcher; it is expected to exit quickly. Never
            // use its exit status as proof that the long-lived gateway is alive.
            runCatching { launcher.waitFor(2, TimeUnit.SECONDS) }
            Log.i(TAG, "Gateway launcher output=$output pid=$pid")
        } finally {
            runCatching { launcher.destroy() }
            runCatching { launcher.inputStream.close() }
            runCatching { launcher.errorStream.close() }
        }
    }

private fun stopRemoteGateway() {
    runCatching {
        val localPid = pid
        val killCmd = buildString {
            if (localPid > 0) {
                append("kill -9 $localPid 2>/dev/null; ")
            }
            append("pkill -9 -f '$BINARY_NAME' 2>/dev/null; ")
            append("rm -f '$PID_PATH' '$LOG_PATH' 2>/dev/null")
        }

        // 直接调用 executePrivileged
        val proc = executePrivileged(arrayOf("sh", "-c", killCmd))
        if (proc != null) {
            try {
                // 读取流会阻塞等待 Shell 执行完闭合，保证 kill 命令能完整运行
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
                proc.waitFor(2, TimeUnit.SECONDS)
                Log.i(TAG, "Stop command finished. stdout='$stdout', stderr='$stderr'")
            } finally {
                runCatching { proc.destroy() }
                runCatching { proc.inputStream.close() }
                runCatching { proc.errorStream.close() }
            }
        }

        // 验证进程和端口是否已清除
        val deadline = System.currentTimeMillis() + 2500L
        while (System.currentTimeMillis() < deadline &&
            (remoteGatewayProcessAlive() || isPortOpen(DEFAULT_GATEWAY_PORT))) {
            Thread.sleep(100)
        }
        if (remoteGatewayProcessAlive() || isPortOpen(DEFAULT_GATEWAY_PORT)) {
            Log.w(TAG, "Gateway is still alive after stop attempt; recordedPid=$localPid")
        } else {
            Log.i(TAG, "Gateway stopped successfully; recordedPid=$localPid")
        }
    }.onFailure { Log.e(TAG, "Gateway stop failed", it) }
}

    private fun remoteGatewayProcessAlive(): Boolean {
        val proc = executePrivileged(arrayOf("sh", "-c",
            "found=0; " +
                "p=\$(cat '$PID_PATH' 2>/dev/null || true); " +
                "case \"\$p\" in ''|*[!0-9]*) ;; *) kill -0 \"\$p\" 2>/dev/null && found=1 ;; esac; " +
                "if [ \"\$found\" -eq 0 ]; then " +
                "  for x in \$(pidof '$BINARY_NAME' 2>/dev/null || true); do " +
                "    kill -0 \"\$x\" 2>/dev/null && found=1 && break; " +
                "  done; " +
                "fi; " +
                "[ \"\$found\" -eq 1 ]"
        )) ?: return false
        return try {
            proc.waitFor(800, TimeUnit.MILLISECONDS) && proc.exitValue() == 0
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { proc.destroy() }
        }
    }

    private fun runPrivilegedShell(command: String): Boolean {
        val proc = executePrivileged(arrayOf("sh", "-c", command)) ?: return false
        return try {
            runCatching { proc.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        } finally {
            runCatching { proc.destroy() }
        }
    }

    private fun readGatewayLog(): String {
        val proc = executePrivileged(arrayOf("sh", "-c", "tail -c 4096 '$LOG_PATH' 2>/dev/null || true")) ?: return ""
        return try {
            val future = java.util.concurrent.FutureTask<String> { proc.inputStream.bufferedReader().readText() }
            Thread(future, "gateway-log-read").apply { isDaemon = true }.start()
            runCatching { future.get(1500, TimeUnit.MILLISECONDS) }.getOrNull().orEmpty()
        } finally {
            runCatching { proc.destroy() }
        }
    }

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun browserUrl(port: Int): String = "http://127.0.0.1:$port/"

    private fun selectAbi(): String {
        val supported = Build.SUPPORTED_ABIS.map(String::lowercase)
        return when {
            supported.contains("arm64-v8a") -> "arm64-v8a"
            supported.contains("armeabi-v7a") -> "armeabi-v7a"
            supported.contains("x86_64") -> "x86_64"
            supported.contains("x86") -> "x86"
            else -> throw UnsupportedOperationException("Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }

    private fun executePrivileged(cmd: Array<String>): Process? {
        return when (AppSettings.getPrivilegeModeSync()) {
            PrivilegeMode.SHIZUKU -> invokeShizuku(cmd)
            PrivilegeMode.ROOT -> {
                val shell = cmd.joinToString(" ") { quoteShell(it) }
                runCatching { ProcessBuilder("su", "-c", shell).redirectErrorStream(true).start() }.getOrNull()
            }
            PrivilegeMode.NONE -> null
        }
    }

    private fun invokeShizuku(cmd: Array<String>): Process? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        method.invoke(null, cmd, null, null) as? Process
    }.getOrElse {
        Log.e(TAG, "Shizuku newProcess failed", it)
        null
    }

    private fun readGatewayDisplayId(port: Int): Int? {
        return runCatching {
            val connection = (URL("http://127.0.0.1:$port/debug/status").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 500
                readTimeout = 700
                requestMethod = "GET"
            }
            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = org.json.JSONObject(body)
                val source = root.optJSONObject("source") ?: root
                source.optInt("displayId", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun isPortOpen(port: Int, host: String = "127.0.0.1"): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: Throwable) { false }


    private fun waitForGatewaySourceReady(port: Int, displayId: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = runCatching {
                val connection = (URL("http://127.0.0.1:$port/debug/status").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 400
                    readTimeout = 700
                    requestMethod = "GET"
                }
                try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            if (status != null) {
                runCatching {
                    val root = org.json.JSONObject(status)
                    // Gateway /debug/status wraps source state under { "source": {...} }.
                    // The previous version incorrectly read these fields from the root,
                    // causing a healthy gateway to be reported as "no video frames".
                    val json = root.optJSONObject("source") ?: root
                    val actualDisplay = json.optInt("displayId", -1)
                    val connected = json.optBoolean("connected", false)
                    val frames = json.optLong("frames", 0L)
                    val width = json.optInt("width", 0)
                    val height = json.optInt("height", 0)
                    if (actualDisplay == displayId && connected && frames > 0 && width > 0 && height > 0) {
                        return true
                    }
                }
            }
            Thread.sleep(150)
        }
        return false
    }

    private fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(port, host)) return true
            Thread.sleep(100)
        }
        return false
    }

    override fun close() = Unit

    private fun quoteShell(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
