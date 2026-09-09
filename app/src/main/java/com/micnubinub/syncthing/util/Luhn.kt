package com.micnubinub.syncthing.util

// import android.util.Log;

class Luhn {
    /**
     * 
     * generate returns a check digit for the string s, which should be composed
     * of characters from the Alphabet a.
     * Doesn't follow the actual Luhn algorithm
     * see https://forum.syncthing.net/t/v0-9-0-new-node-id-format/478/6 for more.
     */
    fun generate(s: ByteArray): String? {
        var factor = 1
        var sum = 0
        val n: Int = LUHN_ALPHABET.length

        for (i in s.indices) {
            val codepoint: Int = LUHN_ALPHABET.indexOf(Char(s[i].toUShort()))
            // Log.v(TAG, "generate: codepoint = " + codepoint);
            if (codepoint == -1) {
                // Error "Digit %q not valid in alphabet %q", s[i], a
                return null
            }
            var addend = factor * codepoint
            factor = (if (factor == 2) 1 else 2)
            addend = (addend / n) + (addend % n)
            sum += addend
        }
        val remainder = sum % n
        val checkCodepoint = (n - remainder) % n
        // Log.v(TAG, "generate: checkCodepoint = " + checkCodepoint);
        return LUHN_ALPHABET.substring(checkCodepoint, checkCodepoint + 1)
    }

    companion object {
        private const val TAG = "Luhn"

        /**
         * An alphabet is a string of N characters, representing the digits of a given
         * base N.
         */
        private const val LUHN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}
