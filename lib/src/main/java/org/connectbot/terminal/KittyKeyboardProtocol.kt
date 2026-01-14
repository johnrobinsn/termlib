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
 * Implementation of the Kitty Keyboard Protocol for enhanced keyboard handling.
 *
 * This protocol allows applications to receive full modifier information for keys
 * that traditionally don't report modifiers (Enter, Tab, Backspace, etc.).
 *
 * Specification: https://sw.kovidgoyal.net/kitty/keyboard-protocol/
 *
 * Key features:
 * - CSI u encoding: `CSI unicode-key-code ; modifiers u`
 * - Push/pop mode stack for apps to enable/disable
 * - Progressive enhancement flags
 *
 * Example: Shift+Enter = ESC[13;2u (13=Enter codepoint, 2=Shift modifier+1)
 */
class KittyKeyboardProtocol {

    companion object {
        private const val TAG = "KittyKeyboard"

        // Progressive enhancement flags
        const val FLAG_DISAMBIGUATE = 1           // 0b00001 - Disambiguate escape codes
        const val FLAG_REPORT_EVENT_TYPES = 2     // 0b00010 - Report key press/repeat/release
        const val FLAG_REPORT_ALTERNATE_KEYS = 4  // 0b00100 - Report shifted/base layout keys
        const val FLAG_REPORT_ALL_KEYS = 8        // 0b01000 - Report all keys as escape codes
        const val FLAG_REPORT_TEXT = 16           // 0b10000 - Report associated text

        // Modifier bits (as used in the protocol, before +1 adjustment)
        const val MOD_SHIFT = 1
        const val MOD_ALT = 2
        const val MOD_CTRL = 4
        const val MOD_SUPER = 8
        const val MOD_HYPER = 16
        const val MOD_META = 32

        // Key codes for special keys (Unicode codepoints or special values)
        const val KEY_ENTER = 13
        const val KEY_TAB = 9
        const val KEY_BACKSPACE = 127
        const val KEY_ESCAPE = 27
        const val KEY_SPACE = 32

        // Function key base (F1 = 57344 + 0, F2 = 57344 + 1, etc. in Kitty spec)
        // But we'll use simpler encoding with tilde format for function keys
        private const val FUNCTIONAL_KEY_BASE = 57344

        // CSI escape sequence prefix
        private val CSI = byteArrayOf(0x1B, 0x5B) // ESC [

        // Maximum stack size to prevent DoS
        private const val MAX_STACK_SIZE = 16
    }

    // Mode stack for main screen
    private val mainScreenStack = ArrayDeque<Int>()

    // Mode stack for alternate screen
    private val altScreenStack = ArrayDeque<Int>()

    // Whether we're on the alternate screen
    var isAlternateScreen: Boolean = false

    // User preference - must be enabled for protocol to be active
    var isEnabledByUser: Boolean = false

    // Current stack based on screen
    private val currentStack: ArrayDeque<Int>
        get() = if (isAlternateScreen) altScreenStack else mainScreenStack

    // Current flags (top of stack, or 0 if empty)
    val currentFlags: Int
        get() = currentStack.lastOrNull() ?: 0

    // Protocol is active if user enabled AND app has pushed mode
    val isActive: Boolean
        get() = isEnabledByUser && currentStack.isNotEmpty()

    /**
     * Push mode flags onto the stack (handles CSI > flags u from app).
     */
    fun pushMode(flags: Int) {
        if (currentStack.size >= MAX_STACK_SIZE) {
            Log.w(TAG, "Kitty keyboard mode stack full, ignoring push")
            return
        }
        currentStack.addLast(flags)
        Log.d(TAG, "Pushed mode flags=$flags, stack size=${currentStack.size}")
    }

    /**
     * Pop mode(s) from the stack (handles CSI < count u from app).
     * @param count Number of entries to pop (default 1)
     */
    fun popMode(count: Int = 1) {
        repeat(count.coerceAtMost(currentStack.size)) {
            currentStack.removeLastOrNull()
        }
        Log.d(TAG, "Popped $count mode(s), stack size=${currentStack.size}")
    }

    /**
     * Generate response for mode query (CSI ? u).
     * Returns: CSI ? flags u
     */
    fun generateQueryResponse(): ByteArray {
        val response = "\u001B[?${currentFlags}u"
        Log.d(TAG, "Query response: flags=$currentFlags")
        return response.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Check if a key with modifiers should be encoded using Kitty protocol.
     *
     * @param vTermKey The VTermKey code
     * @param modifiers Modifier mask (Shift=1, Alt=2, Ctrl=4)
     * @return true if this key/modifier combo should use Kitty encoding
     */
    fun shouldEncode(vTermKey: Int, modifiers: Int): Boolean {
        if (!isActive) return false

        // With FLAG_REPORT_ALL_KEYS, encode everything
        if ((currentFlags and FLAG_REPORT_ALL_KEYS) != 0) {
            return true
        }

        // With FLAG_DISAMBIGUATE, encode keys that would otherwise be ambiguous
        if ((currentFlags and FLAG_DISAMBIGUATE) != 0) {
            // These keys need encoding when they have modifiers
            return when (vTermKey) {
                VTermKey.ENTER, VTermKey.TAB, VTermKey.BACKSPACE, VTermKey.ESCAPE -> modifiers != 0
                else -> false
            }
        }

        return false
    }

    /**
     * Encode a special key (VTermKey) with modifiers to Kitty CSI u format.
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
            else -> return null // Not a key we handle with CSI u
        }

        return encodeKeyCode(keyCode, modifiers)
    }

    /**
     * Encode a character with modifiers to Kitty CSI u format.
     *
     * @param codepoint Unicode codepoint
     * @param modifiers Modifier mask (Shift=1, Alt=2, Ctrl=4)
     * @return ByteArray containing the escape sequence
     */
    fun encodeCharacter(codepoint: Int, modifiers: Int): ByteArray {
        return encodeKeyCode(codepoint, modifiers)
    }

    /**
     * Core encoding function: generates CSI keycode ; modifiers u
     *
     * Format: CSI unicode-key-code ; modifiers u
     * Where modifiers = modifier_bits + 1
     */
    private fun encodeKeyCode(keyCode: Int, modifiers: Int): ByteArray {
        // Kitty protocol transmits modifiers as (modifier_bits + 1)
        // So Shift (bit 0 = 1) becomes 2, Ctrl (bit 2 = 4) becomes 5, etc.
        val modifierValue = modifiers + 1

        val sequence = if (modifierValue > 1) {
            "\u001B[$keyCode;${modifierValue}u"
        } else {
            "\u001B[${keyCode}u"
        }

        Log.d(TAG, "Encoded key=$keyCode, mods=$modifiers -> ${sequence.replace("\u001B", "ESC")}")
        return sequence.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Reset protocol state (e.g., on disconnect).
     */
    fun reset() {
        mainScreenStack.clear()
        altScreenStack.clear()
        Log.d(TAG, "Protocol state reset")
    }
}
