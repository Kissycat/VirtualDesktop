package com.ynk.virtualdisplay.ui.display

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.text.InputType

/**
 * Dedicated, invisible IME target for remote editors.
 *
 * Do not attach the IME directly to VideoSurfaceView: making the video SurfaceView
 * focusable can change SurfaceFlinger composition/focus layers while the first IME
 * window is created. This 1x1 transparent view owns the InputConnection instead.
 */
class RemoteImeProxyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val TAG = "RemoteImeProxyView"
    }

    interface Callbacks {
        fun injectKeyEvent(event: KeyEvent): Boolean
        fun injectText(text: String): Boolean
        fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
        fun onKeyboardDismissed()
    }

    @Volatile private var keyboardRequested = false
    @Volatile private var callbacks: Callbacks? = null

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
        alpha = 0f
        setWillNotDraw(true)
    }

    fun setCallbacks(value: Callbacks?) {
        callbacks = value
    }

    fun requestKeyboardInput(enabled: Boolean) {
        keyboardRequested = enabled
        isFocusable = enabled
        isFocusableInTouchMode = enabled
        if (enabled) {
            requestFocus()
        } else {
            clearFocus()
        }
    }

    fun isKeyboardActive(): Boolean = keyboardRequested

    override fun onCheckIsTextEditor(): Boolean = keyboardRequested

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!keyboardRequested) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.packageName = context.packageName
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0

        return object : BaseInputConnection(this, false) {
            // Some IMEs compose several intermediate strings and then call finishComposingText
            // instead of commitText. Track the latest composition separately so it is never
            // lost at that boundary. An explicit commit clears this value first, preventing
            // finishComposingText from sending the same text a second time.
            private var composingText: String = ""

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composingText = text?.toString().orEmpty()
                return super.setComposingText(text, newCursorPosition)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val committed = text?.toString().orEmpty()
                if (committed.isNotEmpty() && callbacks?.injectText(committed) != true) {
                    Log.w(TAG, "IME commit could not be queued (length=${committed.length})")
                    return false
                }
                composingText = ""
                // Keep only the IME's local composition buffer in this proxy. The remote
                // application owns the real text, so never mirror committed text locally.
                super.commitText("", 1)
                getEditable()?.clear()
                return true
            }

            override fun finishComposingText(): Boolean {
                val pending = composingText
                composingText = ""
                val localResult = super.finishComposingText()
                val accepted = if (pending.isNotEmpty()) callbacks?.injectText(pending) == true else true
                getEditable()?.clear()
                if (!accepted) Log.w(TAG, "IME composing text could not be queued (length=${pending.length})")
                return localResult && accepted
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composingText.isNotEmpty()) {
                    val result = super.deleteSurroundingText(beforeLength, afterLength)
                    composingText = getEditable()?.toString().orEmpty()
                    return result
                }
                return callbacks?.deleteSurroundingText(beforeLength, afterLength) == true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (composingText.isNotEmpty()) {
                    val result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                    composingText = getEditable()?.toString().orEmpty()
                    return result
                }
                return callbacks?.deleteSurroundingText(beforeLength, afterLength) == true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.sendKeyEvent(event)
                if (event.action == KeyEvent.ACTION_MULTIPLE) {
                    val characters = event.characters
                    return !characters.isNullOrEmpty() && callbacks?.injectText(characters) == true
                }
                return callbacks?.injectKeyEvent(event) == true
            }

            override fun commitCompletion(text: CompletionInfo?): Boolean {
                return commitText(text?.text, 1)
            }

            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
                return ExtractedText().apply {
                    text = ""
                    startOffset = 0
                    partialStartOffset = -1
                    partialEndOffset = -1
                    selectionStart = 0
                    selectionEnd = 0
                    this.flags = 0
                }
            }

            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""
            override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""
            override fun getSelectedText(flags: Int): CharSequence = ""
            override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
        }
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && keyboardRequested) {
                post {
                    if (keyboardRequested) {
                        requestKeyboardInput(false)
                        callbacks?.onKeyboardDismissed()
                    }
                }
            }
            return false
        }
        if (callbacks?.injectKeyEvent(event) == true) return true
        return super.onKeyPreIme(keyCode, event)
    }
}
