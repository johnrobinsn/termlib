/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import android.util.Log

/**
 * Implementation of xterm's modifyOtherKeys protocol for enhanced keyboard handling.
 *
 * This is the older but widely supported protocol used by tmux and many other applications.
 * It allows applications to receive modifier information for keys that traditionally
 * don't report modifiers.
 *
 * Protocol:
 * - Enable mode 1: CSI > 4 ; 1 m (some keys report modifiers)
 * - Enable mode 2: CSI > 4 ; 2 m (all keys report modifiers)
 * - Disable: CSI > 4 ; 0 m or CSI > 4 m
 *
 * Key encoding format: CSI 27 ; modifier ; keycode ~
 * Where modifier = 1 + (shift?1:0) + (alt?2:0) + (ctrl?4:0) + (meta?8:0)
 *
 * Example: Shift+Enter = ESC[27;2;13~ (27=marker, 2=Shift+1, 13=Enter)
 */
class ModifyOtherKeysProtocol {

    companion object {
        private const val TAG = "ModifyOtherKeys"

        // Mode values
        const val MODE_DISABLED = 0
        const val MODE_1 = 1  // Some keys report modifiers
        const val MODE_2 = 2  // All keys report modifiers

        // Modifier bits (before +1 adjustment)
        const val MOD_SHIFT = 1
        const val MOD_ALT = 2
        const val MOD_CTRL = 4
        const val MOD_META = 8

        // Key codes (ASCII values)
        const val KEY_ENTER = 13
        const val KEY_TAB = 9
        const val KEY_BACKSPACE = 127
        const val KEY_ESCAPE = 27
        const val KEY_SPACE = 32
    }

    // Current mode for main screen
    private var mainScreenMode: Int = MODE_DISABLED

    // Current mode for alternate screen
    private var altScreenMode: Int = MODE_DISABLED

    // Whether we're on the alternate screen
    var isAlternateScreen: Boolean = false

    // Current mode based on screen
    val currentMode: Int
        get() = if (isAlternateScreen) altScreenMode else mainScreenMode

    // Protocol is active if mode > 0
    val isActive: Boolean
        get() = currentMode > MODE_DISABLED

    /**
     * Set the modifyOtherKeys mode (handles CSI > 4 ; mode m from app).
     */
    fun setMode(mode: Int) {
        val effectiveMode = mode.coerceIn(MODE_DISABLED, MODE_2)
        if (isAlternateScreen) {
            altScreenMode = effectiveMode
        } else {
            mainScreenMode = effectiveMode
        }
        Log.d(TAG, "Set mode=$effectiveMode (altScreen=$isAlternateScreen)")
    }

    /**
     * Check if a key with modifiers should be encoded using modifyOtherKeys.
     *
     * @param vTermKey The VTermKey code
     * @param modifiers Modifier mask (Shift=1, Alt=2, Ctrl=4)
     * @return true if this key/modifier combo should use modifyOtherKeys encoding
     */
    fun shouldEncode(vTermKey: Int, modifiers: Int): Boolean {
        if (!isActive) return false

        // Mode 2: encode all keys with modifiers
        if (currentMode == MODE_2 && modifiers != 0) {
            return true
        }

        // Mode 1: encode specific keys that would otherwise be ambiguous
        if (currentMode == MODE_1 && modifiers != 0) {
            return when (vTermKey) {
                VTermKey.ENTER, VTermKey.TAB, VTermKey.BACKSPACE, VTermKey.ESCAPE -> true
                else -> false
            }
        }

        return false
    }

    /**
     * Encode a special key (VTermKey) with modifiers to modifyOtherKeys format.
     *
     * Format: CSI 27 ; modifier ; keycode ~
     *
     * @param vTermKey The VTermKey code
     * @param modifiers Modifier mask (Shift=1, Alt=2, Ctrl=4)
     * @return ByteArray containing the escape sequence, or null if not applicable
     */
    fun encodeKey(vTermKey: Int, modifiers: Int): ByteArray? {
        val keyCode = when (vTermKey) {
            VTermKey.ENTER -> KEY_ENTER
            VTermKey.TAB -> KEY_TAB
            VTermKey.BACKSPACE -> KEY_BACKSPACE
            VTermKey.ESCAPE -> KEY_ESCAPE
            else -> return null
        }

        return encodeKeyCode(keyCode, modifiers)
    }

    /**
     * Encode a character with modifiers to modifyOtherKeys format.
     *
     * @param codepoint Unicode codepoint (ASCII value for common keys)
     * @param modifiers Modifier mask (Shift=1, Alt=2, Ctrl=4)
     * @return ByteArray containing the escape sequence
     */
    fun encodeCharacter(codepoint: Int, modifiers: Int): ByteArray {
        return encodeKeyCode(codepoint, modifiers)
    }

    /**
     * Core encoding function: generates CSI 27 ; modifier ; keycode ~
     *
     * The modifier value is (modifier_bits + 1), same as Kitty protocol.
     */
    private fun encodeKeyCode(keyCode: Int, modifiers: Int): ByteArray {
        // modifyOtherKeys transmits modifiers as (modifier_bits + 1)
        val modifierValue = modifiers + 1

        val sequence = "\u001B[27;$modifierValue;${keyCode}~"

        Log.d(TAG, "Encoded key=$keyCode, mods=$modifiers -> ${sequence.replace("\u001B", "ESC")}")
        return sequence.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Reset protocol state (e.g., on disconnect).
     */
    fun reset() {
        mainScreenMode = MODE_DISABLED
        altScreenMode = MODE_DISABLED
        Log.d(TAG, "Protocol state reset")
    }
}
