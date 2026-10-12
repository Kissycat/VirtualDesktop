package com.ynk.virtualdisplay

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.di.appModule
import com.ynk.virtualdisplay.di.coreModule
import com.ynk.virtualdisplay.util.ExceptionUtils
import com.ynk.virtualdisplay.webrtc.GatewayProcessController
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * VirtualDisplay 应用入口，采用单阶段冷启动。
 *
 * [onCreate] 中一次性完成全局异常处理器、DataStore 初始化，并
 * 通过 startKoin 加载全部模块（coreModule + appModule），启动后所有
 * 组件立即可用，无需等待 Shizuku 授权。所有模块就绪后置 [isCoreBootstrapped] 为 true；
 * [bootstrapCore] 仅为兼容旧调用点而保留的空操作。
 */
class MyApplication : Application() {
    companion object {
        private const val TAG = "MyApplication"

        /** True only while an Activity on physical display 0 is resumed and owns window focus. */
        @Volatile
        var isPhysicalDisplayForeground: Boolean = false
            private set

        /** Backward-compatible alias; intentionally reflects the physical display only. */
        val isAppInForeground: Boolean
            get() = isPhysicalDisplayForeground

        private val _physicalDisplayForegroundFlow = MutableStateFlow(false)
        val physicalDisplayForegroundFlow: StateFlow<Boolean> = _physicalDisplayForegroundFlow.asStateFlow()
        private val physicalForegroundListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

        /** Register a lightweight state-change callback. Callbacks must not perform blocking work. */
        fun addPhysicalDisplayForegroundListener(listener: (Boolean) -> Unit) {
            physicalForegroundListeners.add(listener)
            runCatching { listener(isPhysicalDisplayForeground) }
        }

        fun removePhysicalDisplayForegroundListener(listener: (Boolean) -> Unit) {
            physicalForegroundListeners.remove(listener)
        }

        /**
         * Suspends without polling while both the physical UI is backgrounded and
         * the independent GO gateway is stopped. When GO is running, existing APP
         * monitoring/reconnect behavior is intentionally kept alive as before.
         */
        suspend fun awaitPhysicalDisplayForeground() {
            if (isPhysicalDisplayForeground || GatewayProcessController.isGatewayRunningKnown()) return
            combine(physicalDisplayForegroundFlow, GatewayProcessController.gatewayRunningFlow) { foreground, gatewayRunning ->
                foreground || gatewayRunning
            }.first { it }
        }
    }

    private class ActivityFocusState {
        var displayId: Int = Display.INVALID_DISPLAY
        var resumed: Boolean = false
        var hasWindowFocus: Boolean = false
        var imeBridgeActive: Boolean = false
        var imeFocusLossGrace: Boolean = false
        var attachListener: View.OnAttachStateChangeListener? = null
        var observedTree: ViewTreeObserver? = null
        var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    }

    private val activityLifecycleLock = Any()
    private val activityStates = IdentityHashMap<Activity, ActivityFocusState>()
    private val gatewayLifecycleProbeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Resolve the actual display hosting the Activity. Do not fall back to a
     * potentially process-default display while the decor is unattached; that
     * could incorrectly classify a virtual-display Activity as physical display 0.
     */
    @Suppress("DEPRECATION")
    private fun resolveActivityDisplayId(activity: Activity): Int {
        val decor = runCatching { activity.window.decorView }.getOrNull()
        if (decor?.isAttachedToWindow == true) {
            runCatching { decor.display?.displayId }.getOrNull()?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activity.display?.displayId }.getOrNull()?.let { return it }
        }
        if (decor?.isAttachedToWindow == true) {
            return runCatching { activity.windowManager.defaultDisplay.displayId }
                .getOrDefault(Display.INVALID_DISPLAY)
        }
        return Display.INVALID_DISPLAY
    }

    private fun recomputePhysicalForegroundLocked() {
        val newForeground = activityStates.values.any { state ->
            state.displayId == Display.DEFAULT_DISPLAY && (
                (state.resumed && (state.hasWindowFocus || state.imeFocusLossGrace)) || state.imeBridgeActive
            )
        }
        val changed = isPhysicalDisplayForeground != newForeground
        if (!changed) return
        Log.d(TAG, "Physical-display foreground changed: $isPhysicalDisplayForeground -> $newForeground")
        isPhysicalDisplayForeground = newForeground
        _physicalDisplayForegroundFlow.value = newForeground
        GatewayProcessController.onPhysicalDisplayForegroundChanged(newForeground)
        physicalForegroundListeners.forEach { listener ->
            runCatching { listener(newForeground) }
        }
        if (!newForeground) {
            // Check once when the physical UI leaves foreground. This catches a GO
            // gateway launched independently through ADB/Shizuku even if no video
            // controller is currently active to perform the transition probe.
            // When GO is absent this is the only extra probe; there is no background
            // timer keeping the APP awake just to ask whether GO has appeared.
            gatewayLifecycleProbeScope.launch {
                GatewayProcessController.refreshGatewayRunningFromLocalPort()
            }
        }
    }

    private fun updateActivityState(
        activity: Activity,
        displayId: Int = resolveActivityDisplayId(activity),
        resumed: Boolean? = null,
        hasWindowFocus: Boolean? = null,
        clearImeBridge: Boolean = false,
    ) {
        synchronized(activityLifecycleLock) {
            val state = activityStates.getOrPut(activity) { ActivityFocusState() }
            state.displayId = displayId
            if (resumed != null) {
                state.resumed = resumed
                if (!resumed && clearImeBridge) {
                    state.imeBridgeActive = false
                    state.imeFocusLossGrace = false
                } else if (!resumed && !state.imeBridgeActive) {
                    state.imeFocusLossGrace = false
                }
            }
            if (hasWindowFocus != null) {
                state.hasWindowFocus = hasWindowFocus
                if (hasWindowFocus) state.imeFocusLossGrace = false
            }
            recomputePhysicalForegroundLocked()
        }
    }

    /** Keep physical foreground state stable while our invisible proxy owns an IME session. */
    fun setImeBridgeActive(activity: Activity, active: Boolean) {
        synchronized(activityLifecycleLock) {
            val state = activityStates.getOrPut(activity) { ActivityFocusState() }
            val wasActive = state.imeBridgeActive
            state.displayId = resolveActivityDisplayId(activity)
            state.imeBridgeActive = active && state.displayId == Display.DEFAULT_DISPLAY
            state.imeFocusLossGrace = !active && wasActive && state.resumed &&
                state.displayId == Display.DEFAULT_DISPLAY && !state.hasWindowFocus
            if (state.hasWindowFocus) state.imeFocusLossGrace = false
            recomputePhysicalForegroundLocked()
        }
    }

    private fun installWindowFocusTracking(activity: Activity) {
        val decor = runCatching { activity.window.decorView }.getOrNull() ?: return
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                attachWindowFocusObserver(activity, view)
                updateActivityState(activity, hasWindowFocus = view.hasWindowFocus())
            }

            override fun onViewDetachedFromWindow(view: View) {
                val focusListener: ViewTreeObserver.OnWindowFocusChangeListener?
                val observedTree: ViewTreeObserver?
                synchronized(activityLifecycleLock) {
                    val state = activityStates[activity] ?: return
                    focusListener = state.focusListener
                    observedTree = state.observedTree
                    state.focusListener = null
                    state.observedTree = null
                    state.hasWindowFocus = false
                    state.displayId = Display.INVALID_DISPLAY
                    state.imeBridgeActive = false
                    state.imeFocusLossGrace = false
                    recomputePhysicalForegroundLocked()
                }
                if (focusListener != null && observedTree?.isAlive == true) {
                    runCatching { observedTree.removeOnWindowFocusChangeListener(focusListener) }
                }
            }
        }

        synchronized(activityLifecycleLock) {
            val state = activityStates.getOrPut(activity) { ActivityFocusState() }
            if (state.attachListener == null) {
                state.attachListener = attachListener
                decor.addOnAttachStateChangeListener(attachListener)
            }
        }
        if (decor.isAttachedToWindow) {
            attachWindowFocusObserver(activity, decor)
            updateActivityState(activity, hasWindowFocus = decor.hasWindowFocus())
        }
    }

    private fun attachWindowFocusObserver(activity: Activity, decor: View) {
        val tree = decor.viewTreeObserver
        synchronized(activityLifecycleLock) {
            val state = activityStates.getOrPut(activity) { ActivityFocusState() }
            if (state.observedTree === tree && state.focusListener != null && tree.isAlive) return
            val oldTree = state.observedTree
            val oldListener = state.focusListener
            if (oldListener != null && oldTree?.isAlive == true) {
                runCatching { oldTree.removeOnWindowFocusChangeListener(oldListener) }
            }
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                updateActivityState(
                    activity,
                    displayId = resolveActivityDisplayId(activity),
                    hasWindowFocus = hasFocus,
                )
            }
            state.observedTree = tree
            state.focusListener = listener
            if (tree.isAlive) tree.addOnWindowFocusChangeListener(listener)
        }
        updateActivityState(activity, hasWindowFocus = decor.hasWindowFocus())
    }

    private fun markActivityResumed(activity: Activity, resumed: Boolean, clearImeBridge: Boolean = false) {
        val displayId = resolveActivityDisplayId(activity)
        val hasFocus = resumed && runCatching { activity.window.decorView.hasWindowFocus() }.getOrDefault(false)
        updateActivityState(activity, displayId, resumed = resumed, hasWindowFocus = hasFocus, clearImeBridge = clearImeBridge)
        if (resumed) {
            // onResume can precede the actual window-focus callback, especially
            // while moving Activities between a physical and a virtual display.
            runCatching {
                activity.window.decorView.post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        updateActivityState(
                            activity,
                            displayId = resolveActivityDisplayId(activity),
                            hasWindowFocus = activity.window.decorView.hasWindowFocus(),
                        )
                    }
                }
            }
        }
    }

    private fun removeActivityState(activity: Activity) {
        val state = synchronized(activityLifecycleLock) {
            val removed = activityStates.remove(activity)
            recomputePhysicalForegroundLocked()
            removed
        } ?: return
        runCatching { state.attachListener?.let { activity.window.decorView.removeOnAttachStateChangeListener(it) } }
        if (state.focusListener != null && state.observedTree?.isAlive == true) {
            runCatching { state.observedTree?.removeOnWindowFocusChangeListener(state.focusListener) }
        }
    }

    /** 核心组件（AppSettings + 全部 Koin 模块）是否已初始化完成；在 onCreate 末尾被置为 true */
    @Volatile
    var isCoreBootstrapped: Boolean = false
        private set

    /**
     * 冷启动，加载全部模块。
     */
    override fun onCreate() {
        super.onCreate()
        ExceptionUtils.setupGlobalCrashHandler()
        Log.i(TAG, "冷启动：初始化 AppSettings 并加载所有模块")

        // Normally foreground means resumed and focused on physical display 0.
        // An active remote-IME bridge is an exception because a floating IME can take
        // focus or briefly pause this Activity while it still receives committed text.
        // onStop clears the exception so a real app switch still suspends background work.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                updateActivityState(activity)
                installWindowFocusTracking(activity)
            }

            override fun onActivityResumed(activity: Activity) = markActivityResumed(activity, true)
            override fun onActivityPaused(activity: Activity) = markActivityResumed(activity, false)
            override fun onActivityStopped(activity: Activity) = markActivityResumed(activity, false, clearImeBridge = true)
            override fun onActivityDestroyed(activity: Activity) = removeActivityState(activity)
            override fun onActivityStarted(activity: Activity) {
                updateActivityState(activity)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })

        AppSettings.init(this)

        startKoin {
            androidContext(this@MyApplication)
            modules(coreModule, appModule)
        }
        isCoreBootstrapped = true
    }

    /**
     * 兼容性保留的空操作。
     *
     * 冷启动（onCreate）已完成全部初始化，此处无需再做任何加载。
     * 保留该方法以兼容外部调用点（如 MainActivity），避免破坏既有调用方。
     */
    @Synchronized
    fun bootstrapCore() {
        Log.i(TAG, "bootstrapCore 调用已在冷启动完成，跳过")
    }
}
