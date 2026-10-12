package com.ynk.virtualdisplay.ui.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Accessibility bridge for committed Unicode text. MainActivity enables this service
 * through Shizuku after Shizuku itself has been explicitly authorized by the user.
 *
 * Android has no general InputEvent that inserts arbitrary Unicode into another app.
 * The scrcpy KeyCharacterMap path therefore cannot reliably type CJK or emoji. This
 * service only acts when InputController explicitly asks it to insert a committed
 * string, and only into a focused editable node on the requested display. It does not
 * observe/store text-change event contents and never uses or changes the clipboard.
 */
class RemoteTextInputAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "RemoteTextBridge"
        private const val MAIN_THREAD_TIMEOUT_MS = 1800L

        @Volatile
        private var connectedService: RemoteTextInputAccessibilityService? = null

        fun isConnected(): Boolean = connectedService != null

        /**
         * Detect placeholders before the ordinary SPACE key is sent. Some browser
         * editable nodes expose their hint as node.text, even while the edit buffer is
         * empty. Remembering this state prevents the hint from being carried into the
         * subsequent ACTION_SET_TEXT replacement.
         */
        fun focusedEditorShowsHint(displayId: Int): Boolean? =
            callOnServiceThread<Boolean?>(null) { it.focusedEditorShowsHintNow(displayId) }

        /** Replace the SPACE inserted through the normal key-event path with committed Unicode. */
        fun replaceInsertedSpaceWithText(
            displayId: Int,
            text: String,
            replaceWholeFieldForHint: Boolean,
        ): Boolean {
            if (text.isEmpty()) return true
            return callOnServiceThread(false) {
                it.replaceInsertedSpaceWithTextNow(displayId, text, replaceWholeFieldForHint)
            }
        }

        private fun <T> callOnServiceThread(defaultValue: T, operation: (RemoteTextInputAccessibilityService) -> T): T {
            val service = connectedService ?: return defaultValue
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return runCatching { operation(service) }
                    .onFailure { Log.w(TAG, "Accessibility operation failed", it) }
                    .getOrDefault(defaultValue)
            }
            val latch = CountDownLatch(1)
            var result = defaultValue
            val posted = service.mainHandler.post {
                try {
                    result = operation(service)
                } catch (error: Throwable) {
                    Log.w(TAG, "Accessibility operation failed", error)
                } finally {
                    latch.countDown()
                }
            }
            if (!posted) return defaultValue
            return try {
                if (latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result else defaultValue
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                defaultValue
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.i(TAG, "Unicode text bridge connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (connectedService === this) connectedService = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately do not inspect or persist event text. We query the focused
        // editable node only in response to an explicit committed-text request.
    }

    override fun onInterrupt() = Unit

    private fun focusedEditorShowsHintNow(displayId: Int): Boolean? {
        val node = findFocusedEditable(displayId) ?: return null
        return try {
            runCatching { node.refresh() }
            if (!node.isEditable || !node.isEnabled || node.isPassword) return null
            val exposedText = node.text?.toString().orEmpty()
            val hint = node.hintText?.toString()
            node.isShowingHintText || (!hint.isNullOrEmpty() && hint == exposedText)
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun replaceInsertedSpaceWithTextNow(
        displayId: Int,
        text: String,
        replaceWholeFieldForHint: Boolean,
    ): Boolean {
        val node = findFocusedEditable(displayId) ?: run {
            Log.w(TAG, "No focused editable node after SPACE on display=$displayId; text length=${text.length}")
            return false
        }
        try {
            runCatching { node.refresh() }
            if (!node.isEditable || !node.isEnabled || node.isPassword) {
                Log.w(TAG, "Focused node is not a supported editable field on display=$displayId")
                return false
            }

            val oldText = node.text?.toString().orEmpty()
            val hint = node.hintText?.toString()
            val showingHintNow = node.isShowingHintText || (!hint.isNullOrEmpty() && hint == oldText)
            val rawStart = node.textSelectionStart
            val rawEnd = node.textSelectionEnd

            val start: Int
            val end: Int
            if (replaceWholeFieldForHint || showingHintNow) {
                // The field was empty and the visible node text was only a hint. Never
                // concatenate that UI label with the user's actual committed text.
                start = 0
                end = oldText.length
            } else {
                // Normal SPACE injection should leave a collapsed caret directly after
                // the sentinel. Replace exactly that character, preserving text either
                // side of the caret and any selection that the native SPACE event just
                // replaced. If we cannot prove where the sentinel landed, fail closed
                // instead of corrupting pre-existing editor text.
                val cursor = when {
                    rawStart in 0..oldText.length && rawStart == rawEnd -> rawStart
                    rawEnd in 0..oldText.length && rawStart < 0 -> rawEnd
                    else -> oldText.length
                }
                if (cursor <= 0 || cursor > oldText.length || oldText[cursor - 1] != ' ') {
                    Log.w(TAG, "SPACE sentinel not found at caret on display=$displayId; text length=${text.length}")
                    return false
                }
                start = cursor - 1
                end = cursor
            }

            val replacement = buildString(oldText.length - (end - start) + text.length) {
                append(oldText, 0, start)
                append(text)
                append(oldText, end, oldText.length)
            }
            val setTextArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
            }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
                Log.w(TAG, "ACTION_SET_TEXT rejected on display=$displayId (length=${text.length})")
                return false
            }

            val cursor = (start + text.length).coerceIn(0, replacement.length)
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
            Log.d(TAG, "Replaced normal SPACE with Unicode text on display=$displayId (length=${text.length}, hint=$replaceWholeFieldForHint)")
            return true
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun findFocusedEditable(displayId: Int): AccessibilityNodeInfo? {
        val targetWindows: List<AccessibilityWindowInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val allDisplays = getWindowsOnAllDisplays()
            val windows = allDisplays[displayId] ?: return null
            windows.sortedByDescending { it.isFocused }
        } else {
            // Android 10 exposes only default-display windows through the public API.
            // Never route text from a virtual display to the physical screen by mistake.
            if (displayId != Display.DEFAULT_DISPLAY) return null
            windows.sortedByDescending { it.isFocused }
        }

        for (window in targetWindows) {
            val root = try {
                window.root
            } catch (_: RuntimeException) {
                null
            } ?: continue
            try {
                val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                if (focused != null) {
                    if (focused.isEditable && focused.isEnabled) return focused
                    @Suppress("DEPRECATION")
                    focused.recycle()
                }
                val treeFocused = findFocusedEditableInTree(root, 0, intArrayOf(320))
                if (treeFocused != null) return treeFocused
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
        return null
    }

    private fun findFocusedEditableInTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: IntArray,
    ): AccessibilityNodeInfo? {
        if (budget[0]-- <= 0 || depth > 32) return null
        if (node.isEditable && node.isFocused && node.isEnabled) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = try {
                node.getChild(index)
            } catch (_: RuntimeException) {
                null
            } ?: continue
            val found = try {
                findFocusedEditableInTree(child, depth + 1, budget)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
            if (found != null) return found
        }
        return null
    }
}
