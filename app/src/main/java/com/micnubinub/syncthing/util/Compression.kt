package com.micnubinub.syncthing.util

import android.content.Context
import com.micnubinub.syncthing.R

/**
 * Device compression attribute helper. This unifies operations between string values as expected by
 * Syncthing with string values as displayed to the user and int ordinals as expected by the dialog
 * click interface.
 */
enum class Compression(val index: Int) {
    NONE(0),
    METADATA(1),
    ALWAYS(2);

    fun getValue(context: Context): String? {
        return context.getResources().getStringArray(R.array.compress_values)[index]
    }

    fun getTitle(context: Context): String? {
        return context.getResources().getStringArray(R.array.compress_entries)[index]
    }

    companion object {
        fun fromIndex(index: Int): Compression {
            when (index) {
                0 -> return Compression.NONE
                2 -> return Compression.ALWAYS
                else -> return Compression.METADATA
            }
        }

        fun fromValue(context: Context, value: String): Compression {
            var index = 0
            val values = context.getResources().getStringArray(R.array.compress_values)
            for (i in values.indices) {
                if (values[i] == value) {
                    index = i
                }
            }

            return fromIndex(index)
        }
    }
}
