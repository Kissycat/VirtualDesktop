package com.ynk.virtualdisplay.ui.display

import android.content.Context
import android.graphics.RectF
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ynk.virtualdisplay.ui.desktop.DesktopWindowInputRouter
import com.ynk.virtualdisplay.ui.desktop.DesktopWindowSession
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import com.ynk.virtualdisplay.ui.input.RemoteTextInputAccessibilityService

class InputController(
    private val repository: IDisplayRepository,
    private val displayIdProvider: () -> Int?,
    private val scope: CoroutineScope,
    context: Context,
) {

    companion object {
        private const val TAG = "InputController"
        private const val DESKTOP_PREFS = "desktop_shell_preferences"
        private const val PREF_CURSOR_SENSITIVITY = "desktop_cursor_sensitivity_percent"
        private const val PREF_SCROLL_SENSITIVITY = "desktop_scroll_sensitivity_percent"

        @Volatile
        private var desktopCursorSensitivityMultiplier = 1f

        @Volatile
        private var desktopScrollSensitivityMultiplier = 1f

        fun setDesktopCursorSensitivityPercent(percent: Int) {
            desktopCursorSensitivityMultiplier = ((100 + percent.coerceIn(-100, 100)) / 100f).coerceIn(0f, 2f)
        }

        fun setDesktopScrollSensitivityPercent(percent: Int) {
            desktopScrollSensitivityMultiplier = ((100 + percent.coerceIn(-100, 100)) / 100f).coerceIn(0f, 2f)
        }
        private const val POINTER_ID_MOUSE = -1L
        private const val ACTION_POINTER_INDEX_SHIFT = 8
    }

    private data class KeyboardTarget(
        /** Parent desktop/virtual display receiving ordinary scrcpy control messages. */
        val displayId: Int,
        /** Actual app display: a child display when a small window owns text focus. */
        val textTargetDisplayId: Int,
        val windowSession: DesktopWindowSession?,
    )

    private sealed class KeyboardCommand(open val target: KeyboardTarget) {
        data class Key(override val target: KeyboardTarget, val event: KeyEvent) : KeyboardCommand(target)
        data class Text(override val target: KeyboardTarget, val text: String) : KeyboardCommand(target)
        data class KeyCode(override val target: KeyboardTarget, val keyCode: Int) : KeyboardCommand(target)
        data class Delete(override val target: KeyboardTarget, val before: Int, val after: Int) : KeyboardCommand(target)
    }

    private val keyboardQueue = Channel<KeyboardCommand>(Channel.UNLIMITED)
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unicodeBridgeNoticeShown = AtomicBoolean(false)

    init {
        val prefs = context.getSharedPreferences(DESKTOP_PREFS, Context.MODE_PRIVATE)
        setDesktopCursorSensitivityPercent(prefs.getInt(PREF_CURSOR_SENSITIVITY, 0))
        setDesktopScrollSensitivityPercent(prefs.getInt(PREF_SCROLL_SENSITIVITY, 0))
        scope.launch(Dispatchers.IO) {
            for (command in keyboardQueue) {
                try {
                    dispatchKeyboardCommand(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.e(TAG, "Keyboard input dispatch failed", error)
                }
            }
        }
    }

    private fun captureKeyboardTarget(): KeyboardTarget? {
        val displayId = displayIdProvider() ?: return null
        // The router is process-global because overlays share a host. Always scope it
        // to this desktop display or a focused window from a different virtual desktop
        // can steal IME commits even though key input appears to work elsewhere.
        val window = if (desktopModeEnabled) DesktopWindowInputRouter.focusedSession(displayId) else null
        return KeyboardTarget(
            displayId = displayId,
            textTargetDisplayId = window?.windowDisplayId() ?: displayId,
            windowSession = window,
        )
    }

    private fun enqueueKeyboardCommand(command: KeyboardCommand): Boolean {
        val result = keyboardQueue.trySend(command).isSuccess
        if (!result) Log.w(TAG, "Keyboard input queue is closed")
        return result
    }

    private suspend fun dispatchKeyboardCommand(command: KeyboardCommand) {
        when (command) {
            is KeyboardCommand.Key -> {
                val result = command.target.windowSession?.injectKeyEvent(command.event)
                    ?: repository.injectInputWithDisplayId(command.event, command.target.displayId)
                logKeyboardResult("key action=${command.event.action} code=${command.event.keyCode}", result)
            }
            is KeyboardCommand.Text -> {
                if (command.text.isNotEmpty()) {
                    // KEYCODE mapping can carry ordinary keyboard characters only; it
                    // cannot represent arbitrary CJK/emoji. For non-ASCII commits, use
                    // the explicitly enabled accessibility bridge to insert at the
                    // focused editor selection on the exact app display. Never pretend
                    // a successfully written control packet means Chinese was inserted.
                    val needsUnicodeBridge = command.text.any { it.code > 0x7f }
                    if (needsUnicodeBridge) {
                        val targetDisplayId = command.target.textTargetDisplayId
                        // Settings.Secure changes and AccessibilityService binding are
                        // asynchronous. Give a just-enabled service a short window to bind
                        // so the first CJK commit is not dropped on app startup.
                        var bindWaitMs = 0
                        while (!RemoteTextInputAccessibilityService.isConnected() && bindWaitMs < 1200) {
                            delay(100)
                            bindWaitMs += 100
                        }
                        if (!RemoteTextInputAccessibilityService.isConnected()) {
                            notifyUnicodeBridgeFailure(targetDisplayId)
                        } else {
                            // Snapshot hint state before the ordinary SPACE event changes
                            // the editor. Browser/omnibox nodes can expose hint text through
                            // node.text; without this snapshot, ACTION_SET_TEXT can turn that
                            // hint into real content. SPACE also replaces any preselected
                            // suggestion using the target application's normal input path.
                            val hintState = RemoteTextInputAccessibilityService
                                .focusedEditorShowsHint(targetDisplayId)
                            if (hintState == null) {
                                // Do not send the sentinel if no focused editable node was
                                // found; otherwise a failed accessibility lookup could
                                // leave a stray SPACE in the target application.
                                notifyUnicodeBridgeFailure(targetDisplayId)
                            } else {
                                val spaceAccepted = injectKeyCodeToTarget(command.target, KeyEvent.KEYCODE_SPACE)
                                if (spaceAccepted) {
                                    // The scrcpy control message is asynchronous at the device
                                    // input dispatcher. Let the normal key event update editor
                                    // state before querying its accessibility node.
                                    delay(60)
                                }
                                val inserted = spaceAccepted && RemoteTextInputAccessibilityService
                                    .replaceInsertedSpaceWithText(targetDisplayId, command.text, hintState)
                                if (!inserted) notifyUnicodeBridgeFailure(targetDisplayId)
                            }
                        }
                    } else {
                        val result = command.target.windowSession?.injectText(command.text)
                            ?: repository.injectTextWithDisplayId(command.text, command.target.displayId)
                        logKeyboardResult("text length=${command.text.length}", result)
                    }
                }
            }
            is KeyboardCommand.KeyCode -> {
                val result = command.target.windowSession?.injectKeyCode(command.keyCode) ?: run {
                    val downTime = android.os.SystemClock.uptimeMillis()
                    val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, command.keyCode, 0)
                    val up = KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, command.keyCode, 0)
                    val downResult = repository.injectInputWithDisplayId(down, command.target.displayId)
                    if (downResult.isFailure) downResult else repository.injectInputWithDisplayId(up, command.target.displayId)
                }
                logKeyboardResult("keycode=${command.keyCode}", result)
            }
            is KeyboardCommand.Delete -> {
                repeat(command.before.coerceIn(0, 64)) { injectKeyCodeToTarget(command.target, KeyEvent.KEYCODE_DEL) }
                repeat(command.after.coerceIn(0, 64)) { injectKeyCodeToTarget(command.target, KeyEvent.KEYCODE_FORWARD_DEL) }
            }
        }
    }

    private suspend fun injectKeyCodeToTarget(target: KeyboardTarget, keyCode: Int): Boolean {
        val result = target.windowSession?.injectKeyCode(keyCode) ?: run {
            val downTime = android.os.SystemClock.uptimeMillis()
            val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
            val downResult = repository.injectInputWithDisplayId(down, target.displayId)
            if (downResult.isFailure) downResult else repository.injectInputWithDisplayId(up, target.displayId)
        }
        logKeyboardResult("keycode=$keyCode", result)
        return result.isSuccess && result.getOrNull() == true
    }

    private fun logKeyboardResult(operation: String, result: Result<Boolean>) {
        if (result.isFailure || result.getOrNull() != true) {
            Log.w(TAG, "Keyboard operation was rejected: $operation", result.exceptionOrNull())
        }
    }

    private fun notifyUnicodeBridgeFailure(displayId: Int) {
        Log.w(
            TAG,
            "Unicode input failed on display=$displayId; accessibility bridge connected=" +
                RemoteTextInputAccessibilityService.isConnected(),
        )
        if (!unicodeBridgeNoticeShown.compareAndSet(false, true)) return
        val message = if (RemoteTextInputAccessibilityService.isConnected()) {
            "无法找到该虚拟屏幕当前焦点的可编辑控件；此应用可能不支持无障碍文本写入。"
        } else {
            "中文和表情输入需要启用“屏幕分身 Unicode 文字输入辅助”：请到系统设置 > 无障碍中开启。未使用剪贴板。"
        }
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    enum class DisplayMode {
        FIT_CENTER,
        CENTER_CROP,
        STRETCH_FILL,
    }

    @Volatile
    private var videoWidth: Int = 0

    @Volatile
    private var videoHeight: Int = 0

    // Main virtual-display video size. Desktop-window input is handled by the
    // window overlay itself and must never hijack this controller's target.
    @Volatile
    private var mainVideoWidth: Int = 0

    @Volatile
    private var mainVideoHeight: Int = 0

    @Volatile
    private var displayMode: DisplayMode = DisplayMode.FIT_CENTER

    @Volatile
    private var videoRotation: Int = 0

    private val activePointerIds = linkedSetOf<Int>()
    private val activePointerPositions = linkedMapOf<Int, Pair<Float, Float>>()

    // Desktop-mode trackpad state. The virtual display receives absolute mouse
    // coordinates, so the phone touchpad maintains an internal cursor position
    // and converts finger deltas into absolute mouse moves.
    private var trackpadCursorX = 0f
    private var trackpadCursorY = 0f
    private var trackpadCursorInitialized = false
    private var trackpadLastX = 0f
    private var trackpadLastY = 0f
    private var trackpadDownX = 0f
    private var trackpadDownY = 0f
    private var trackpadDragArmX = 0f
    private var trackpadDragArmY = 0f
    private var trackpadDownTime = 0L
    private var trackpadMoved = false
    private var trackpadTapCancelled = false
    private var trackpadDragging = false
    private var trackpadModeEnabled = false
    @Volatile
    private var desktopModeEnabled = false
    private var trackpadLongPressStarted = false
    private var trackpadLongPressConsumed = false
    private var trackpadTwoFingerStartX = 0f
    private var trackpadTwoFingerStartY = 0f
    private var trackpadTwoFingerLastX = 0f
    private var trackpadTwoFingerLastY = 0f
    private var trackpadTwoFinger = false
    private var trackpadTwoFingerHorizontal = false
    private var trackpadTwoFingerSwipeStarted = false
    // Lock the direction of a two-finger scroll for the current gesture.
    // Small pointer-index/centering jitter must not generate an opposite scroll.
    private var trackpadTwoFingerScrollSignX = 0
    private var trackpadTwoFingerScrollSignY = 0
    private var trackpadThreeFinger = false
    private var trackpadThreeFingerStartX = 0f
    private var trackpadThreeFingerStartY = 0f
    private var trackpadThreeFingerMoved = false
    private var trackpadThreeFingerClickTriggered = false
    private var trackpadDoubleTapDragArmed = false
    private var trackpadLastTapTime = 0L
    private var trackpadLastTapX = 0f
    private var trackpadLastTapY = 0f
    private var trackpadMaxPointerCount = 0
    private var pendingTwoFingerClick = false
    private var pendingThreeFingerClick = false
    private val trackpadGestureHandler = Handler(Looper.getMainLooper())
    private val pendingTwoFingerClickRunnable = Runnable {
        if (pendingTwoFingerClick && trackpadMaxPointerCount == 2 &&
            trackpadTwoFinger && !trackpadMoved && !trackpadTwoFingerHorizontal &&
            displayIdProvider() != null) {
            injectMouseClick(displayIdProvider()!!, secondary = true)
        }
        pendingTwoFingerClick = false
        trackpadTwoFinger = false
        trackpadMaxPointerCount = 0
    }
    private val pendingThreeFingerClickRunnable = Runnable {
        if (pendingThreeFingerClick) {
            displayIdProvider()?.let { injectMouseClickButton(it, MotionEvent.BUTTON_TERTIARY) }
        }
        pendingThreeFingerClick = false
    }
    private val baseTrackpadSensitivity = 0.72f
    // Movement before the long-press deadline cancels tap/drag qualification.
    private val trackpadMoveThreshold = 26f
    // After a stationary long-press, this smaller movement starts a drag.
    private val trackpadDragStartThreshold = 8f
    private val trackpadLongPressMs = 320L
    private val trackpadThreeFingerMoveThreshold = 48f

    /** Cancel a pending lower-priority multi-finger action when a higher
     * pointer count (4+) is observed by the Activity-level dispatcher. */
    fun cancelPendingMultiFingerClick() {
        trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
        trackpadGestureHandler.removeCallbacks(pendingThreeFingerClickRunnable)
        pendingTwoFingerClick = false
        pendingThreeFingerClick = false
    }

    fun setDesktopModeEnabled(enabled: Boolean) {
        desktopModeEnabled = enabled
    }

    fun setTrackpadModeEnabled(enabled: Boolean) {
        if (trackpadModeEnabled == enabled) {
            // Even when the logical state is unchanged, explicitly clearing any
            // residual gesture state makes mode transitions deterministic.
            if (!enabled) {
                clearTrackpadGestureState()
            }
            return
        }

        if (!enabled) {
            val displayId = displayIdProvider()
            if (displayId != null && trackpadDragging) {
                injectMouseUp(displayId)
            }
            clearTrackpadGestureState()
        }
        trackpadModeEnabled = enabled
        Log.i(TAG, "trackpadModeEnabled=$enabled")
    }

    private fun clearTrackpadGestureState() {
        trackpadDragging = false
        trackpadLongPressStarted = false
        trackpadLongPressConsumed = false
        trackpadTwoFinger = false
        trackpadMoved = false
        trackpadTapCancelled = false
        trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
        trackpadGestureHandler.removeCallbacks(pendingThreeFingerClickRunnable)
        pendingTwoFingerClick = false
        pendingThreeFingerClick = false
        trackpadMaxPointerCount = 0
        trackpadDownTime = 0L
        trackpadLastX = 0f
        trackpadLastY = 0f
        trackpadDownX = 0f
        trackpadDownY = 0f
        trackpadDragArmX = 0f
        trackpadDragArmY = 0f
        trackpadTwoFingerStartX = 0f
        trackpadTwoFingerStartY = 0f
        trackpadTwoFingerLastX = 0f
        trackpadTwoFingerLastY = 0f
        trackpadTwoFingerHorizontal = false
        trackpadTwoFingerSwipeStarted = false
        trackpadThreeFinger = false
        trackpadThreeFingerStartX = 0f
        trackpadThreeFingerStartY = 0f
        trackpadThreeFingerMoved = false
        trackpadThreeFingerClickTriggered = false
        trackpadDoubleTapDragArmed = false
        trackpadLastTapTime = 0L
        trackpadLastTapX = 0f
        trackpadLastTapY = 0f
    }

    private fun invalidateContentRectCache() {
        cachedContentRect = null
        cachedRectViewWidth = -1f
        cachedRectViewHeight = -1f
        cachedRectVideoWidth = -1
        cachedRectVideoHeight = -1
        cachedRectMode = null
        cachedRectRotation = Int.MIN_VALUE
    }

    fun updateVideoSize(width: Int, height: Int) {
        mainVideoWidth = width
        mainVideoHeight = height
        if (videoWidth != width || videoHeight != height) {
            val oldWidth = videoWidth
            val oldHeight = videoHeight
            if (trackpadCursorInitialized && oldWidth > 0 && oldHeight > 0 && width > 0 && height > 0) {
                // The input controller owns the absolute remote pointer. Keep it
                // aligned with DesktopCursorState when the decoder reconnects or
                // the display changes resolution; otherwise a later MOVE snaps
                // the overlay back to this stale coordinate.
                trackpadCursorX = (trackpadCursorX * width / oldWidth).coerceIn(0f, width.toFloat())
                trackpadCursorY = (trackpadCursorY * height / oldHeight).coerceIn(0f, height.toFloat())
                DesktopCursorState.update(trackpadCursorX, trackpadCursorY, width, height)
            }
            videoWidth = width
            videoHeight = height
            invalidateContentRectCache()
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        if (displayMode != mode) {
            displayMode = mode
            invalidateContentRectCache()
        }
    }

    fun setVideoRotation(rotation: Int) {
        if (videoRotation != rotation) {
            videoRotation = rotation
            invalidateContentRectCache()
        }
    }

    fun hasVideoSize(): Boolean = videoWidth > 0 && videoHeight > 0

    fun shouldHandleRemotely(event: MotionEvent): Boolean {
        val source = event.source
        return source and InputDevice.SOURCE_CLASS_POINTER != 0 ||
            source and InputDevice.SOURCE_CLASS_POSITION != 0
    }

    fun handleMotionEvent(view: View, event: MotionEvent): Boolean {
        if (!hasVideoSize()) return false

        return when {
            isMouseEvent(event) -> handleMouseEvent(view, event)
            event.actionMasked == MotionEvent.ACTION_CANCEL -> handleCancel(view)
            else -> handleTouchEvent(view, event)
        }
    }

    /**
     * Desktop-mode touchpad. Single-finger movement controls an absolute mouse
     * cursor; tap = left click, drag = left-button drag, two-finger vertical
     * movement = wheel scroll, two-finger tap = right click.
     */
    fun handleTrackpadEvent(view: View, event: MotionEvent): Boolean {
        if (!trackpadModeEnabled) return true
        if (mainVideoWidth > 0 && mainVideoHeight > 0) {
            videoWidth = mainVideoWidth
            videoHeight = mainVideoHeight
        }
        if (!hasVideoSize()) return true
        val displayId = displayIdProvider() ?: return false

        if (!trackpadCursorInitialized) {
            val previousCursor = DesktopCursorState.snapshot()
            if (previousCursor.visible && previousCursor.width > 0 && previousCursor.height > 0) {
                // A newly-created/rebound Activity may get a fresh InputController
                // while the process-wide cursor overlay still has the user's last
                // location. Adopt that position instead of flashing to center.
                trackpadCursorX = (previousCursor.x.toFloat() / previousCursor.width * videoWidth)
                    .coerceIn(0f, videoWidth.toFloat())
                trackpadCursorY = (previousCursor.y.toFloat() / previousCursor.height * videoHeight)
                    .coerceIn(0f, videoHeight.toFloat())
            } else {
                trackpadCursorX = videoWidth * 0.5f
                trackpadCursorY = videoHeight * 0.5f
            }
            trackpadCursorInitialized = true
            DesktopCursorState.update(trackpadCursorX, trackpadCursorY, videoWidth, videoHeight)
        }

        // Multi-touch arbitration is deliberately strict:
        // 1 finger = mouse
        // 2 fingers = candidate (never click immediately)
        // 3 fingers = middle click, committed immediately
        // 4+ fingers = reserved for higher-level desktop commands
        trackpadMaxPointerCount = maxOf(trackpadMaxPointerCount, event.pointerCount)

        if (event.pointerCount >= 4) {
            trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
            pendingTwoFingerClick = false
            trackpadThreeFinger = false
            trackpadTwoFinger = false
            trackpadTapCancelled = true
            trackpadMoved = true
            if (trackpadDragging) {
                injectMouseUp(displayId)
                trackpadDragging = false
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Log.d("DesktopTrackpad", "DOWN view=${view.javaClass.simpleName} display=$displayIdProvider cursor=${trackpadCursorX},${trackpadCursorY} video=${videoWidth}x${videoHeight}")
                trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
                pendingTwoFingerClick = false
                trackpadMaxPointerCount = 1
                trackpadDownTime = event.eventTime
                val sinceLastTap = event.eventTime - trackpadLastTapTime
                val tapDx = event.x - trackpadLastTapX
                val tapDy = event.y - trackpadLastTapY
                val tapSlop = trackpadMoveThreshold * 0.75f
                trackpadDoubleTapDragArmed = trackpadLastTapTime > 0L &&
                    sinceLastTap <= android.view.ViewConfiguration.getDoubleTapTimeout() &&
                    kotlin.math.hypot(tapDx.toDouble(), tapDy.toDouble()).toFloat() <= tapSlop
                if (trackpadDoubleTapDragArmed) {
                    Log.d("DesktopTrackpad", "DOUBLE_TAP_DRAG_ARMED dt=$sinceLastTap x=${event.x} y=${event.y}")
                }
                trackpadLastX = event.x
                trackpadLastY = event.y
                trackpadDownX = event.x
                trackpadDownY = event.y
                trackpadDragArmX = event.x
                trackpadDragArmY = event.y
                trackpadMoved = false
                trackpadTapCancelled = false
                trackpadDragging = false
                trackpadLongPressStarted = false
                trackpadLongPressConsumed = false
                trackpadTwoFinger = false
                trackpadTwoFingerStartX = 0f
                trackpadTwoFingerStartY = 0f
                trackpadTwoFingerLastX = 0f
                trackpadTwoFingerLastY = 0f
                trackpadTwoFingerHorizontal = false
                trackpadTwoFingerSwipeStarted = false
                trackpadTwoFingerScrollSignX = 0
                trackpadTwoFingerScrollSignY = 0
                trackpadThreeFinger = false
                trackpadThreeFingerMoved = false
                trackpadThreeFingerClickTriggered = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                when {
                    event.pointerCount >= 4 -> return true

                    event.pointerCount == 3 -> {
                        // Third finger has absolute priority. Commit middle-click
                        // now, not on release, so horizontal finger placement and
                        // release ordering cannot accidentally become a 2-finger tap.
                        trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
                        pendingTwoFingerClick = false
                        trackpadThreeFinger = true
                        trackpadThreeFingerMoved = false
                        trackpadThreeFingerClickTriggered = false
                        trackpadTwoFinger = false
                        trackpadTwoFingerHorizontal = false
                        trackpadTwoFingerSwipeStarted = false
                        trackpadTwoFingerScrollSignX = 0
                        trackpadTwoFingerScrollSignY = 0
                        trackpadTapCancelled = true
                        trackpadMoved = true
                        if (trackpadDragging) {
                            injectMouseUp(displayId)
                            trackpadDragging = false
                        }
                        // Reserve 3-finger tap, but defer the actual middle-click
                        // briefly so a 4th pointer can still preempt it.
                        trackpadGestureHandler.removeCallbacks(pendingThreeFingerClickRunnable)
                        pendingThreeFingerClick = true
                        trackpadGestureHandler.postDelayed(pendingThreeFingerClickRunnable, 120L)
                        return true
                    }

                    event.pointerCount == 2 -> {
                        // Do not arm the right-click yet. A short grace window
                        // lets a 3rd/4th finger arrive before any click is emitted.
                        trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
                        pendingTwoFingerClick = false
                        trackpadDoubleTapDragArmed = false
                        trackpadTwoFinger = true
                        trackpadTapCancelled = true
                        trackpadMoved = false
                        val x = (event.getX(0) + event.getX(1)) * 0.5f
                        val y = (event.getY(0) + event.getY(1)) * 0.5f
                        trackpadTwoFingerStartX = x
                        trackpadTwoFingerStartY = y
                        trackpadTwoFingerLastX = x
                        trackpadTwoFingerLastY = y
                        trackpadTwoFingerScrollSignX = 0
                        trackpadTwoFingerScrollSignY = 0
                        return true
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // If the framework delivers a MOVE with 3 pointers but skipped
                // POINTER_DOWN, promote it immediately and commit middle-click.
                if (event.pointerCount >= 3) {
                    if (!trackpadThreeFinger) {
                        trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
                        pendingTwoFingerClick = false
                        trackpadThreeFinger = true
                        trackpadThreeFingerClickTriggered = false
                        trackpadTwoFinger = false
                        trackpadTwoFingerHorizontal = false
                        trackpadTapCancelled = true
                        trackpadMoved = true
                        if (trackpadDragging) {
                            injectMouseUp(displayId)
                            trackpadDragging = false
                        }
                        trackpadGestureHandler.removeCallbacks(pendingThreeFingerClickRunnable)
                        pendingThreeFingerClick = true
                        trackpadGestureHandler.postDelayed(pendingThreeFingerClickRunnable, 120L)
                    }
                    return true
                }

                if (event.pointerCount >= 2 || trackpadTwoFinger) {
                    if (event.pointerCount >= 2) {
                        val x = (event.getX(0) + event.getX(1)) * 0.5f
                        val y = (event.getY(0) + event.getY(1)) * 0.5f
                        val totalDx = x - trackpadTwoFingerStartX
                        val totalDy = y - trackpadTwoFingerStartY
                        if (!trackpadTwoFingerHorizontal &&
                            (kotlin.math.abs(totalDx) > trackpadMoveThreshold || kotlin.math.abs(totalDy) > trackpadMoveThreshold)) {
                            trackpadTwoFingerHorizontal = kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy) * 1.2f
                        }

                        if (trackpadTwoFingerHorizontal) {
                            val dx = x - trackpadTwoFingerLastX
                            if (kotlin.math.abs(dx) > 0.5f) {
                                trackpadMoved = true
                                routeTrackpadScrollOrMain(
                                    displayId,
                                    dx / 288f * desktopScrollSensitivityMultiplier,
                                    0f
                                )
                            }
                        } else {
                            val dy = y - trackpadTwoFingerLastY
                            if (kotlin.math.abs(dy) > 0.5f) {
                                trackpadMoved = true
                                routeTrackpadScrollOrMain(
                                    displayId,
                                    0f,
                                    dy / 288f * desktopScrollSensitivityMultiplier
                                )
                            }
                        }
                        trackpadTwoFingerLastX = x
                        trackpadTwoFingerLastY = y
                    }
                    return true
                }

                val dx = event.x - trackpadLastX
                val dy = event.y - trackpadLastY
                trackpadLastX = event.x
                trackpadLastY = event.y

                val fromDownX = event.x - trackpadDownX
                val fromDownY = event.y - trackpadDownY
                val fromDownDistance = kotlin.math.hypot(fromDownX.toDouble(), fromDownY.toDouble()).toFloat()
                val elapsed = event.eventTime - trackpadDownTime

                if (!trackpadLongPressStarted && !trackpadTapCancelled &&
                    fromDownDistance > trackpadMoveThreshold) {
                    trackpadTapCancelled = true
                    trackpadMoved = true
                }

                if (trackpadDoubleTapDragArmed && !trackpadTwoFinger && !trackpadDragging &&
                    fromDownDistance >= trackpadDragStartThreshold) {
                    injectPointerEvent(MotionEvent.ACTION_DOWN, POINTER_ID_MOUSE,
                        trackpadCursorX, trackpadCursorY, videoWidth, videoHeight, 1f,
                        MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, displayId,
                        InputDevice.SOURCE_MOUSE)
                    trackpadDragging = true
                    trackpadMoved = true
                    trackpadTapCancelled = true
                    Log.d("DesktopTrackpad", "DOUBLE_TAP_DRAG_START cursor=${trackpadCursorX},${trackpadCursorY}")
                }

                if (!trackpadTwoFinger && !trackpadLongPressStarted &&
                    !trackpadDoubleTapDragArmed && !trackpadTapCancelled && elapsed >= trackpadLongPressMs &&
                    fromDownDistance <= trackpadMoveThreshold) {
                    trackpadLongPressStarted = true
                    trackpadLongPressConsumed = true
                    trackpadDragArmX = event.x
                    trackpadDragArmY = event.y
                }

                if (dx != 0f || dy != 0f) {
                    trackpadCursorX = (trackpadCursorX + dx * (baseTrackpadSensitivity * desktopCursorSensitivityMultiplier)).coerceIn(0f, videoWidth.toFloat())
                    trackpadCursorY = (trackpadCursorY + dy * (baseTrackpadSensitivity * desktopCursorSensitivityMultiplier)).coerceIn(0f, videoHeight.toFloat())
                    DesktopCursorState.update(trackpadCursorX, trackpadCursorY, videoWidth, videoHeight)

                    if (!trackpadTwoFinger && trackpadLongPressStarted && !trackpadDragging) {
                        val armDx = event.x - trackpadDragArmX
                        val armDy = event.y - trackpadDragArmY
                        val armDistance = kotlin.math.hypot(armDx.toDouble(), armDy.toDouble()).toFloat()
                        if (armDistance >= trackpadDragStartThreshold) {
                            injectPointerEvent(MotionEvent.ACTION_DOWN, POINTER_ID_MOUSE,
                                trackpadCursorX, trackpadCursorY, videoWidth, videoHeight, 1f,
                                MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, displayId,
                                InputDevice.SOURCE_MOUSE)
                            trackpadDragging = true
                        }
                    }

                    injectPointerEvent(
                        if (trackpadDragging) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE,
                        POINTER_ID_MOUSE, trackpadCursorX, trackpadCursorY, videoWidth, videoHeight, 1f,
                        0, if (trackpadDragging) MotionEvent.BUTTON_PRIMARY else 0, displayId,
                        InputDevice.SOURCE_MOUSE,
                    )
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (trackpadThreeFinger) return true
                if (trackpadTwoFinger && event.pointerCount <= 2) {
                    trackpadTwoFingerLastX = 0f
                    trackpadTwoFingerLastY = 0f
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Two-finger tap is the only gesture that is intentionally delayed.
                // Keep its state alive until the 90 ms arbitration window expires so
                // a late 3rd/4th pointer can still cancel it.
                if (trackpadDragging) {
                    injectMouseUp(displayId)
                } else if (trackpadThreeFinger) {
                    // Already committed when the 3rd pointer arrived.
                } else if (trackpadMaxPointerCount == 2 && trackpadTwoFinger &&
                    !trackpadTwoFingerHorizontal && !trackpadMoved &&
                    event.actionMasked == MotionEvent.ACTION_UP) {
                    pendingTwoFingerClick = true
                    trackpadGestureHandler.postDelayed(pendingTwoFingerClickRunnable, 90L)
                    return true
                } else if (trackpadMaxPointerCount == 1 &&
                    !trackpadTapCancelled && !trackpadLongPressConsumed && !trackpadMoved &&
                    event.actionMasked == MotionEvent.ACTION_UP) {
                    injectMouseClick(displayId, secondary = false)
                    trackpadLastTapTime = event.eventTime
                    trackpadLastTapX = event.x
                    trackpadLastTapY = event.y
                } else if (trackpadMaxPointerCount == 1 && event.actionMasked == MotionEvent.ACTION_UP) {
                    if (!trackpadDragging) {
                        trackpadLastTapTime = event.eventTime
                        trackpadLastTapX = event.x
                        trackpadLastTapY = event.y
                    }
                }

                trackpadDoubleTapDragArmed = false
                trackpadTwoFinger = false
                trackpadTwoFingerScrollSignX = 0
                trackpadTwoFingerScrollSignY = 0
                trackpadDragging = false
                trackpadMoved = false
                trackpadTapCancelled = false
                trackpadLongPressStarted = false
                trackpadLongPressConsumed = false
                trackpadTwoFingerHorizontal = false
                trackpadTwoFingerSwipeStarted = false
                trackpadThreeFinger = false
                trackpadThreeFingerMoved = false
                trackpadThreeFingerClickTriggered = false
                trackpadMaxPointerCount = 0
                return true
            }
        }
        return true
    }

    private fun injectMouseClickButton(displayId: Int, button: Int) {
        injectPointerEvent(
            action = MotionEvent.ACTION_DOWN,
            pointerId = POINTER_ID_MOUSE,
            x = trackpadCursorX,
            y = trackpadCursorY,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pressure = 1f,
            actionButton = button,
            buttons = button,
            displayId = displayId,
            source = InputDevice.SOURCE_MOUSE,
        )
        injectPointerEvent(
            action = MotionEvent.ACTION_UP,
            pointerId = POINTER_ID_MOUSE,
            x = trackpadCursorX,
            y = trackpadCursorY,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pressure = 0f,
            actionButton = button,
            buttons = 0,
            displayId = displayId,
            source = InputDevice.SOURCE_MOUSE,
        )
    }

    private fun injectMouseClick(displayId: Int, secondary: Boolean) {
        val button = if (secondary) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
        Log.d("DesktopTrackpad", "CLICK display=$displayId secondary=$secondary cursor=${trackpadCursorX},${trackpadCursorY} video=${videoWidth}x${videoHeight}")
        injectPointerEvent(
            action = MotionEvent.ACTION_DOWN,
            pointerId = POINTER_ID_MOUSE,
            x = trackpadCursorX,
            y = trackpadCursorY,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pressure = 1f,
            actionButton = button,
            buttons = button,
            displayId = displayId,
            source = InputDevice.SOURCE_MOUSE,
        )
        injectPointerEvent(
            action = MotionEvent.ACTION_UP,
            pointerId = POINTER_ID_MOUSE,
            x = trackpadCursorX,
            y = trackpadCursorY,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pressure = 0f,
            actionButton = button,
            buttons = 0,
            displayId = displayId,
            source = InputDevice.SOURCE_MOUSE,
        )
    }

    private fun injectMouseUp(displayId: Int) {
        injectPointerEvent(
            action = MotionEvent.ACTION_UP,
            pointerId = POINTER_ID_MOUSE,
            x = trackpadCursorX,
            y = trackpadCursorY,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pressure = 0f,
            actionButton = MotionEvent.BUTTON_PRIMARY,
            buttons = 0,
            displayId = displayId,
            source = InputDevice.SOURCE_MOUSE,
        )
    }

    /**
     * Finish and clear any touchscreen gesture which was already active before
     * desktop/WebRTC mode took control of the input surface. This prevents the
     * old touch stream from continuing to affect the remote application.
     */
    fun cancelActiveTouch() {
        val displayId = displayIdProvider() ?: return
        if (activePointerIds.isEmpty()) return

        val pointers = activePointerIds.toList()
        for (pointerId in pointers) {
            val pos = activePointerPositions[pointerId] ?: continue
            val x = if (videoWidth > 0) pos.first.coerceIn(0f, videoWidth.toFloat()) else pos.first
            val y = if (videoHeight > 0) pos.second.coerceIn(0f, videoHeight.toFloat()) else pos.second
            injectPointerEvent(
                action = MotionEvent.ACTION_UP,
                pointerId = pointerId.toLong(),
                x = x,
                y = y,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                pressure = 0f,
                actionButton = 0,
                buttons = 0,
                displayId = displayId,
                source = InputDevice.SOURCE_TOUCHSCREEN,
            )
        }
        activePointerIds.clear()
        activePointerPositions.clear()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            val characters = event.characters
            return !characters.isNullOrEmpty() && injectText(characters)
        }
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val target = captureKeyboardTarget() ?: return false
        return enqueueKeyboardCommand(KeyboardCommand.Key(target, KeyEvent(event)))
    }

    fun injectText(text: String): Boolean {
        if (text.isEmpty()) return true
        val target = captureKeyboardTarget() ?: return false
        return enqueueKeyboardCommand(KeyboardCommand.Text(target, text))
    }

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val target = captureKeyboardTarget() ?: return false
        return enqueueKeyboardCommand(KeyboardCommand.Delete(
            target,
            beforeLength.coerceAtLeast(0),
            afterLength.coerceAtLeast(0),
        ))
    }

    fun injectKey(keyCode: Int) {
        val target = captureKeyboardTarget() ?: return
        enqueueKeyboardCommand(KeyboardCommand.KeyCode(target, keyCode))
    }

    private fun isMouseEvent(event: MotionEvent): Boolean {
        return event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == MotionEvent.ACTION_HOVER_EXIT ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun handleTouchEvent(view: View, event: MotionEvent): Boolean {
        val displayId = displayIdProvider() ?: return false
        val contentRect = getContentRect(view) ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                val rawX = event.getX(actionIndex)
                val rawY = event.getY(actionIndex)
                if (!isInsideContent(rawX, rawY, contentRect)) return false
                val targetPos = mapToVideo(rawX, rawY, contentRect)
                activePointerIds += pointerId
                activePointerPositions[pointerId] = rawX to rawY
                injectTouchPointer(event, actionIndex, MotionEvent.ACTION_DOWN, pointerId, targetPos, displayId)
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    if (pointerId !in activePointerIds) continue
                    val rawX = event.getX(i)
                    val rawY = event.getY(i)
                    activePointerPositions[pointerId] = rawX to rawY
                    val targetPos = mapToVideo(rawX, rawY, contentRect)
                    injectTouchPointer(event, i, MotionEvent.ACTION_MOVE, pointerId, targetPos, displayId)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                val rawX = event.getX(actionIndex)
                val rawY = event.getY(actionIndex)
                val targetPos = mapToVideo(rawX, rawY, contentRect)
                injectTouchPointer(event, actionIndex, MotionEvent.ACTION_UP, pointerId, targetPos, displayId)
                activePointerIds -= pointerId
                activePointerPositions.remove(pointerId)
            }
        }

        handleDisappearedPointers(event, contentRect, displayId)
        return true
    }

    private fun handleMouseEvent(view: View, event: MotionEvent): Boolean {
        val displayId = displayIdProvider() ?: return false
        val contentRect = getContentRect(view) ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
            -> {
                val (x, y) = mapToVideo(event.getX(0), event.getY(0), contentRect)
                injectPointerEvent(
                    action = event.actionMasked,
                    pointerId = POINTER_ID_MOUSE,
                    x = x,
                    y = y,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    pressure = event.getPressure(0).coerceIn(0f, 1f),
                    actionButton = event.actionButton,
                    buttons = event.buttonState,
                    displayId = displayId,
                    source = InputDevice.SOURCE_MOUSE,
                )
            }

            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                val (x, y) = mapToVideo(event.getX(0), event.getY(0), contentRect)
                injectPointerEvent(
                    action = MotionEvent.ACTION_DOWN,
                    pointerId = POINTER_ID_MOUSE,
                    x = x,
                    y = y,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    pressure = event.getPressure(0).coerceIn(0f, 1f),
                    actionButton = event.actionButton,
                    buttons = event.buttonState,
                    displayId = displayId,
                    source = InputDevice.SOURCE_MOUSE,
                )
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val (x, y) = mapToVideo(event.getX(0), event.getY(0), contentRect)
                injectPointerEvent(
                    action = MotionEvent.ACTION_UP,
                    pointerId = POINTER_ID_MOUSE,
                    x = x,
                    y = y,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    pressure = 0f,
                    actionButton = event.actionButton,
                    buttons = 0,
                    displayId = displayId,
                    source = InputDevice.SOURCE_MOUSE,
                )
            }

            MotionEvent.ACTION_SCROLL -> {
                val (x, y) = mapToVideo(event.getX(0), event.getY(0), contentRect)
                injectScrollEvent(
                    x = x,
                    y = y,
                    hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    buttons = event.buttonState,
                    displayId = displayId,
                )
            }
        }

        return true
    }

    private fun handleCancel(view: View): Boolean {
        val displayId = displayIdProvider() ?: return false
        val contentRect = getContentRect(view) ?: return false
        val toCancel = activePointerIds.toList()
        for (pointerId in toCancel) {
            val pos = activePointerPositions[pointerId] ?: continue
            val (x, y) = mapToVideo(pos.first, pos.second, contentRect)
            injectPointerEvent(
                action = MotionEvent.ACTION_UP,
                pointerId = pointerId.toLong(),
                x = x,
                y = y,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                pressure = 0f,
                actionButton = 0,
                buttons = 0,
                displayId = displayId,
                source = InputDevice.SOURCE_TOUCHSCREEN,
            )
        }
        activePointerIds.clear()
        activePointerPositions.clear()
        return true
    }

    private fun handleDisappearedPointers(event: MotionEvent, contentRect: RectF, displayId: Int) {
        if (activePointerIds.isEmpty()) return
        val iterator = activePointerIds.iterator()
        while (iterator.hasNext()) {
            val pointerId = iterator.next()
            var present = false
            for (index in 0 until event.pointerCount) {
                if (event.getPointerId(index) == pointerId) {
                    present = true
                    break
                }
            }
            if (present) continue
            val pos = activePointerPositions[pointerId] ?: continue
            val (x, y) = mapToVideo(pos.first, pos.second, contentRect)
            injectPointerEvent(
                action = MotionEvent.ACTION_UP,
                pointerId = pointerId.toLong(),
                x = x,
                y = y,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                pressure = 0f,
                actionButton = 0,
                buttons = 0,
                displayId = displayId,
                source = InputDevice.SOURCE_TOUCHSCREEN,
            )
            iterator.remove()
            activePointerPositions.remove(pointerId)
        }
    }

    private fun injectTouchPointer(
        event: MotionEvent,
        pointerIndex: Int,
        action: Int,
        pointerId: Int,
        targetPos: Pair<Float, Float>,
        displayId: Int,
    ) {
        val (x, y) = targetPos
        val pointerCount = event.pointerCount
        val pointerProperties = Array(pointerCount) { MotionEvent.PointerProperties() }
        val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }

        for (index in 0 until pointerCount) {
            event.getPointerProperties(index, pointerProperties[index])
            event.getPointerCoords(index, pointerCoords[index])
            if (index == pointerIndex || pointerProperties[index].id == pointerId) {
                pointerCoords[index].x = x
                pointerCoords[index].y = y
            } else {
                val rawX = event.getX(index)
                val rawY = event.getY(index)
                val contentRect = getContentRectForInjection() ?: continue
                val mapped = mapToVideo(rawX, rawY, contentRect)
                pointerCoords[index].x = mapped.first
                pointerCoords[index].y = mapped.second
            }
        }

        val newAction = if (pointerCount == 1) {
            action
        } else {
            when (action) {
                MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_POINTER_DOWN
                MotionEvent.ACTION_UP -> MotionEvent.ACTION_POINTER_UP
                else -> action
            }
        }

        val pointerIndexAdjusted = if (pointerCount > 1) {
            pointerProperties.indexOfFirst { it.id == pointerId }.coerceAtLeast(0)
        } else 0

        val finalAction = newAction or (pointerIndexAdjusted shl ACTION_POINTER_INDEX_SHIFT)

        val newEvent = MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            finalAction,
            pointerCount,
            pointerProperties,
            pointerCoords,
            event.metaState,
            event.buttonState,
            0f,
            0f,
            0,
            event.edgeFlags,
            InputDevice.SOURCE_TOUCHSCREEN,
            event.flags,
        )

        injectEvent(newEvent, displayId)
    }

    private var cachedContentRect: RectF? = null
    private var cachedRectViewWidth = -1f
    private var cachedRectViewHeight = -1f
    private var cachedRectVideoWidth = -1
    private var cachedRectVideoHeight = -1
    private var cachedRectMode: DisplayMode? = null
    private var cachedRectRotation = Int.MIN_VALUE

    private fun getContentRectForInjection(): RectF? = cachedContentRect

    private fun injectPointerEvent(
        action: Int,
        pointerId: Long,
        x: Float,
        y: Float,
        videoWidth: Int,
        videoHeight: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
        displayId: Int,
        source: Int,
    ) {
        val pointerProperties = arrayOf(MotionEvent.PointerProperties().apply {
            id = pointerId.toInt()
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val pointerCoords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            this.size = 1f
        })

        if (trackpadModeEnabled && source == InputDevice.SOURCE_MOUSE) {
            scope.launch(Dispatchers.Main.immediate) {
                val contextMenuHandled = runCatching {
                    com.ynk.virtualdisplay.ui.desktop.DesktopShellActivity.dispatchPointerToContextMenu(
                        displayId = displayId,
                        action = action,
                        x = x,
                        y = y,
                        actionButton = actionButton,
                        buttons = buttons,
                    )
                }.getOrDefault(false)
                if (contextMenuHandled) return@launch

                val uiHandled = runCatching {
                    com.ynk.virtualdisplay.ui.desktop.DesktopShellActivity.dispatchPointerToDrawer(
                        displayId = displayId,
                        action = action,
                        x = x,
                        y = y,
                        actionButton = actionButton,
                        buttons = buttons,
                    )
                }.getOrDefault(false)
                if (uiHandled) return@launch

                val dockHidden: Boolean = runCatching {
                    com.ynk.virtualdisplay.ui.desktop.DesktopShellActivity.hideFloatingDockOnOutsideClick(
                        displayId = displayId,
                        action = action,
                        x = x,
                        y = y,
                    )
                }.getOrDefault(false)
                if (dockHidden && action == MotionEvent.ACTION_DOWN) {
                    // A single outside click hides the forced floating Dock but
                    // continues to the normal desktop/full-screen input target.
                }

                val routed = runCatching {
                    DesktopWindowInputRouter.injectPointer(
                        displayId = displayId,
                        action = action,
                        x = x,
                        y = y,
                        actionButton = actionButton,
                        buttons = buttons,
                        pointerId = pointerId,
                        fallbackWidth = videoWidth,
                        fallbackHeight = videoHeight,
                        latch = action != MotionEvent.ACTION_HOVER_MOVE,
                    )
                }.getOrDefault(false)
                if (!routed) {
                    val event = MotionEvent.obtain(
                        SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, 1,
                        pointerProperties, pointerCoords, 0, buttons, 0f, 0f, 0, 0, source, 0
                    )
                    injectEvent(event, displayId)
                }
            }
            return
        }

        val event = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            action,
            1,
            pointerProperties,
            pointerCoords,
            0,
            buttons,
            0f,
            0f,
            0,
            0,
            source,
            0,
        )
        injectEvent(event, displayId)
    }

    private fun routeTrackpadScrollOrMain(displayId: Int, hScroll: Float, vScroll: Float) {
        if (!trackpadModeEnabled) {
            injectScrollEvent(trackpadCursorX, trackpadCursorY, hScroll, vScroll, 0, displayId)
            return
        }
        // Trackpad MOVE events already arrive on the UI thread. Consume drawer
        // scrolling synchronously before any window/fullscreen route is allowed.
        // This prevents two queued Main.immediate coroutines from fighting over
        // consecutive scroll deltas and producing the visible direction jitter.
        val drawerHandled = if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching {
                com.ynk.virtualdisplay.ui.desktop.DesktopShellActivity.dispatchScrollToDrawer(
                    displayId = displayId,
                    x = trackpadCursorX,
                    y = trackpadCursorY,
                    hScroll = hScroll,
                    vScroll = vScroll,
                )
            }.getOrDefault(false)
        } else {
            false
        }
        if (drawerHandled) return

        val route: suspend () -> Unit = {
            val routed = runCatching {
                DesktopWindowInputRouter.injectScroll(
                    displayId = displayId, x = trackpadCursorX, y = trackpadCursorY,
                    hScroll = hScroll, vScroll = vScroll, buttons = 0,
                    fallbackWidth = videoWidth, fallbackHeight = videoHeight
                )
            }.getOrDefault(false)
            if (!routed) {
                injectScrollEvent(trackpadCursorX, trackpadCursorY, hScroll, vScroll, 0, displayId)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main.immediate) { route() }
        } else {
            scope.launch(Dispatchers.Main.immediate) { route() }
        }
    }

    private fun injectScrollEvent(
        x: Float,
        y: Float,
        hScroll: Float,
        vScroll: Float,
        buttons: Int,
        displayId: Int,
    ) {
        val pointerProperties = arrayOf(MotionEvent.PointerProperties().apply {
            id = POINTER_ID_MOUSE.toInt()
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val pointerCoords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        })

        val event = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_SCROLL,
            1,
            pointerProperties,
            pointerCoords,
            0,
            buttons,
            0f,
            0f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
        injectEvent(event, displayId)
    }

    private fun injectEvent(event: MotionEvent, displayId: Int) {
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val result = repository.injectInputWithDisplayId(event, displayId)
                if (result.isFailure || result.getOrDefault(false).not()) {
                    Log.e(TAG, "injectMotionEvent failed", result.exceptionOrNull())
                }
            } finally {
                event.recycle()
            }
        }
    }

    fun getContentRect(view: View): RectF? {
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val vw = videoWidth.toFloat()
        val vh = videoHeight.toFloat()
        if (width <= 0f || height <= 0f || vw <= 0f || vh <= 0f) {
            cachedContentRect = null
            cachedRectViewWidth = -1f
            return null
        }

        val mode = displayMode
        val rotation = videoRotation
        if (cachedContentRect != null &&
            cachedRectViewWidth == width &&
            cachedRectViewHeight == height &&
            cachedRectVideoWidth == videoWidth &&
            cachedRectVideoHeight == videoHeight &&
            cachedRectMode == mode &&
            cachedRectRotation == rotation) {
            return cachedContentRect
        }

        val rect = when (mode) {
            DisplayMode.FIT_CENTER -> {
                val videoAspect = vw / vh
                val viewAspect = width / height
                if (videoAspect > viewAspect) {
                    val contentHeight = width / videoAspect
                    val top = (height - contentHeight) * 0.5f
                    RectF(0f, top, width, top + contentHeight)
                } else {
                    val contentWidth = height * videoAspect
                    val left = (width - contentWidth) * 0.5f
                    RectF(left, 0f, left + contentWidth, height)
                }
            }

            DisplayMode.CENTER_CROP -> {
                val videoAspect = vw / vh
                val viewAspect = width / height
                if (videoAspect > viewAspect) {
                    val visibleFraction = viewAspect / videoAspect
                    val cropOffset = (1f - visibleFraction) * 0.5f
                    val left = -width * cropOffset / visibleFraction
                    val right = width + width * cropOffset / visibleFraction
                    RectF(left, 0f, right, height)
                } else {
                    val visibleFraction = videoAspect / viewAspect
                    val cropOffset = (1f - visibleFraction) * 0.5f
                    val top = -height * cropOffset / visibleFraction
                    val bottom = height + height * cropOffset / visibleFraction
                    RectF(0f, top, width, bottom)
                }
            }

            DisplayMode.STRETCH_FILL -> {
                RectF(0f, 0f, width, height)
            }
        }

        cachedContentRect = rect
        cachedRectViewWidth = width
        cachedRectViewHeight = height
        cachedRectVideoWidth = videoWidth
        cachedRectVideoHeight = videoHeight
        cachedRectMode = mode
        cachedRectRotation = rotation
        return rect
    }

    private fun isInsideContent(x: Float, y: Float, rect: RectF): Boolean {
        return x in rect.left..rect.right && y in rect.top..rect.bottom
    }

    private fun mapToVideo(x: Float, y: Float, rect: RectF): Pair<Float, Float> {
        val normalizedX = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val normalizedY = ((y - rect.top) / rect.height()).coerceIn(0f, 1f)

        val mappedX: Float
        val mappedY: Float
        when (videoRotation) {
            90 -> {
                mappedX = normalizedY * videoWidth
                mappedY = (1f - normalizedX) * videoHeight
            }
            180 -> {
                mappedX = (1f - normalizedX) * videoWidth
                mappedY = (1f - normalizedY) * videoHeight
            }
            270 -> {
                mappedX = (1f - normalizedY) * videoWidth
                mappedY = normalizedX * videoHeight
            }
            else -> {
                mappedX = normalizedX * videoWidth
                mappedY = normalizedY * videoHeight
            }
        }

        return mappedX to mappedY
    }

    private fun Int.saturatingSubtract(value: Int): Int = (this - value).coerceAtLeast(0)

    private object SystemClock {
        fun uptimeMillis(): Long = android.os.SystemClock.uptimeMillis()
    }
}
