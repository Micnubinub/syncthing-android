package com.micnubinub.syncthing.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Provides preference getters and setters.
 */
object AppPrefs {
    private const val TAG = "AppPrefs"
    private const val PREF_VERBOSE_LOG_DEFAULT = false

    @JvmStatic
    fun getPrefVerboseLog(context: Context?): Boolean {
        if (context == null) {
            Log.e(TAG, "getPrefVerboseLog: context == null")
            return PREF_VERBOSE_LOG_DEFAULT
        }
        return getPrefVerboseLog(PreferenceManager.getDefaultSharedPreferences(context))
    }

    @JvmStatic
    fun getPrefVerboseLog(sharedPreferences: SharedPreferences?): Boolean {
        if (sharedPreferences == null) {
            Log.e(TAG, "getPrefVerboseLog: sharedPreferences == null")
            return PREF_VERBOSE_LOG_DEFAULT
        }
        return sharedPreferences.getBoolean(Constants.PREF_VERBOSE_LOG, PREF_VERBOSE_LOG_DEFAULT)
    }

    fun getStartServiceOnBoot(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false)
    }

    /**
     * Whether the user has already answered the anonymous usage reporting dialog,
     * persisted locally so the prompt never reappears regardless of whether the
     * Syncthing config round-trip commits the choice.
     */
    @JvmStatic
    fun isUsageReportingDialogAnswered(sharedPreferences: SharedPreferences?): Boolean {
        if (sharedPreferences == null) {
            Log.e(TAG, "isUsageReportingDialogAnswered: sharedPreferences == null")
            return false
        }
        return sharedPreferences.getBoolean(
            Constants.PREF_USAGE_REPORTING_DIALOG_ANSWERED,
            false
        )
    }

    @JvmStatic
    fun setUsageReportingDialogAnswered(sharedPreferences: SharedPreferences?) {
        if (sharedPreferences == null) {
            Log.e(TAG, "setUsageReportingDialogAnswered: sharedPreferences == null")
            return
        }
        sharedPreferences.edit()
            .putBoolean(Constants.PREF_USAGE_REPORTING_DIALOG_ANSWERED, true)
            .apply()
    }

    /**
     * Returns a per-install random token, generating and persisting it on first
     * use. Notification PendingIntents that carry MainActivity's special extras
     * embed this token; MainActivity rejects any such intent that lacks a
     * matching token, so a third-party app cannot trigger those actions.
     */
    fun getInternalIntentToken(sharedPreferences: SharedPreferences): String {
        sharedPreferences.getString(Constants.PREF_INTERNAL_INTENT_TOKEN, null)?.let {
            return it
        }
        val token = java.util.UUID.randomUUID().toString()
        sharedPreferences.edit().putString(Constants.PREF_INTERNAL_INTENT_TOKEN, token).apply()
        return token
    }
}
