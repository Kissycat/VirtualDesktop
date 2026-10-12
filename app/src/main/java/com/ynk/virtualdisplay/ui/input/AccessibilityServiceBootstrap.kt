package com.ynk.virtualdisplay.ui.input

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Enables the app's Unicode text accessibility bridge through the already-authorized
 * Shizuku shell identity. The command preserves all other enabled accessibility services.
 * Shizuku itself must already have been explicitly authorized by the user.
 */
object AccessibilityServiceBootstrap {
    private const val TAG = "AccessibilityBootstrap"
    private const val SUCCESS_MARKER = "VD_ACCESSIBILITY_ENABLED"

    sealed interface Result {
        data object Enabled : Result
        data object ShizukuUnavailable : Result
        data class Failed(val reason: String) : Result
    }

    fun ensureEnabled(context: Context): Result {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return Result.ShizukuUnavailable
        }
        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) return Result.ShizukuUnavailable

        val component = ComponentName(context, RemoteTextInputAccessibilityService::class.java)
        val shortName = component.flattenToShortString()
        val fullName = component.flattenToString()
        val dollar = '$'
        val script = """
            set -e
            current=${dollar}(settings get secure enabled_accessibility_services 2>/dev/null || true)
            short_name='${shellQuote(shortName)}'
            full_name='${shellQuote(fullName)}'
            case ":${dollar}current:" in
                *":${dollar}short_name:"*|*":${dollar}full_name:"*) ;;
                *)
                    if [ -z "${dollar}current" ] || [ "${dollar}current" = "null" ]; then
                        updated="${dollar}short_name"
                    else
                        updated="${dollar}current:${dollar}short_name"
                    fi
                    settings put secure enabled_accessibility_services "${dollar}updated"
                    ;;
            esac
            if [ "${dollar}accessibility_enabled" != "1" ]; then
                settings put secure accessibility_enabled 1
            fi
            verified=${dollar}(settings get secure enabled_accessibility_services 2>/dev/null || true)
            accessibility_enabled=${dollar}(settings get secure accessibility_enabled 2>/dev/null || true)
            case ":${dollar}verified:" in
                *":${dollar}short_name:"*|*":${dollar}full_name:"*)
                    if [ "${dollar}accessibility_enabled" = "1" ]; then
                        echo $SUCCESS_MARKER
                    else
                        echo "VD_ACCESSIBILITY_VERIFY_FAILED: accessibility_enabled=${dollar}accessibility_enabled"
                        exit 24
                    fi
                    ;;
                *) echo "VD_ACCESSIBILITY_VERIFY_FAILED: ${dollar}verified"; exit 23 ;;
            esac
        """.trimIndent()

        return try {
            val output = runShizuku(script)
            if (output.lineSequence().any { it.trim() == SUCCESS_MARKER }) {
                Log.i(TAG, "Unicode accessibility service enabled/verified via Shizuku")
                Result.Enabled
            } else {
                val reason = output.trim().takeLast(500).ifEmpty { "Shizuku shell returned no verification output" }
                Log.e(TAG, "Could not enable Unicode accessibility service: $reason")
                Result.Failed(reason)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Could not enable Unicode accessibility service via Shizuku", error)
            Result.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun runShizuku(command: String): String {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", "$command 2>&1"), null, null) as Process
        return try {
            val outputTask = FutureTask<String> {
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            }
            Thread(outputTask, "vd-accessibility-bootstrap").apply { isDaemon = true }.start()
            outputTask.get(4, TimeUnit.SECONDS)
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.destroyForcibly() }
        }
    }

    private fun shellQuote(value: String): String = value.replace("'", "'\\''")
}
