package com.micnubinub.syncthing.ui.viewModels

import android.content.SharedPreferences
import android.net.http.SslError
import android.webkit.SslErrorHandler
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import com.micnubinub.syncthing.service.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WebViewActivityState(
    val url: String? = null,
    val isLoading: Boolean = true,
    val isRunningOnTV: Boolean = false,
    val showSecurityNotice: Boolean = false
)

sealed interface WebViewActivityAction {
    data class Initialize(val url: String?, val isRunningOnTV: Boolean) : WebViewActivityAction
    data object PageFinished : WebViewActivityAction
    data object SecurityNoticeShown : WebViewActivityAction
    data class SecurityNoticeAccepted(val key: String?) : WebViewActivityAction
    data object SecurityNoticeDismissed : WebViewActivityAction
    data class SslErrorReceived(
        val handler: SslErrorHandler,
        val key: String
    ) : WebViewActivityAction
}

class WebViewActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(WebViewActivityState())
    val state: StateFlow<WebViewActivityState> = _state.asStateFlow()

    /**
     * Host/certificate combinations for which the user accepted the SSL security
     * notice. Certificate errors are only skipped without prompting for the exact
     * host + certificate the user approved; any other error prompts again.
     * Persisted to [SharedPreferences] so accepted exceptions survive process death.
     */
    private val acceptedSslExceptions = mutableSetOf<String>()

    private var preferences: SharedPreferences? = null

    fun setPreferences(preferences: SharedPreferences?) {
        if (this.preferences == preferences) {
            return
        }
        this.preferences = preferences
        val persisted = preferences?.getStringSet(Constants.PREF_ACCEPTED_SSL_EXCEPTIONS, null)
        if (persisted != null) {
            acceptedSslExceptions.clear()
            acceptedSslExceptions.addAll(persisted)
        }
    }

    /**
     * Pending SSL handler waiting for user response. Only one SSL error can be
     * pending at a time; a new error cancels any previous unhandled one.
     */
    private var pendingSslHandler: SslErrorHandler? = null

    /**
     * Unique key identifying the host + certificate combination that triggered
     * the pending SSL error.
     */
    private var pendingSslKey: String? = null

    fun isSslExceptionAccepted(key: String?): Boolean =
        key != null && acceptedSslExceptions.contains(key)

    /**
     * Build a key from [SslError] that uniquely identifies the host and
     * certificate so that accepting one specific error does not silently
     * accept unrelated certificate problems on the same host.
     */
    fun sslExceptionKey(error: SslError): String {
        val host = try {
            error.url?.toUri()?.host
        } catch (_: Exception) {
            null
        }
        val primary = error.primaryError
        return "$host:$primary"
    }

    private fun cancelPendingSsl() {
        pendingSslHandler?.cancel()
        pendingSslHandler = null
        pendingSslKey = null
    }

    fun onAction(action: WebViewActivityAction) {
        when (action) {
            is WebViewActivityAction.Initialize -> _state.update {
                it.copy(url = action.url, isRunningOnTV = action.isRunningOnTV)
            }

            WebViewActivityAction.PageFinished -> _state.update { it.copy(isLoading = false) }

            WebViewActivityAction.SecurityNoticeShown -> _state.update {
                it.copy(showSecurityNotice = true)
            }

            is WebViewActivityAction.SecurityNoticeAccepted -> {
                action.key?.let {
                    acceptedSslExceptions.add(it)
                    preferences?.edit {
                        putStringSet(
                            Constants.PREF_ACCEPTED_SSL_EXCEPTIONS,
                            acceptedSslExceptions
                        )
                    }
                }
                pendingSslHandler?.proceed()
                pendingSslHandler = null
                pendingSslKey = null
                _state.update { it.copy(showSecurityNotice = false) }
            }

            WebViewActivityAction.SecurityNoticeDismissed -> {
                cancelPendingSsl()
                _state.update { it.copy(showSecurityNotice = false) }
            }

            is WebViewActivityAction.SslErrorReceived -> {
                // The WebView holds the previous request until its handler is called, so a
                // replacement has to cancel it rather than just drop the reference.
                cancelPendingSsl()
                pendingSslHandler = action.handler
                pendingSslKey = action.key
                _state.update { it.copy(showSecurityNotice = true) }
            }
        }
    }
}
