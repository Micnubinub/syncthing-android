package com.micnubinub.syncthing.ui.screens

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.ui.viewModels.WebViewActivityAction
import com.micnubinub.syncthing.ui.viewModels.WebViewActivityState
import com.micnubinub.syncthing.ui.viewModels.WebViewActivityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showSystemUi = true)
@Composable
fun WebViewActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: WebViewActivityViewModel = WebViewActivityViewModel(),
    onOpenInBrowser: () -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    WebViewActivityContent(
        state = state,
        viewModel = viewModel,
        modifier = modifier,
        onOpenInBrowser = onOpenInBrowser,
        onNavigateBack = onNavigateBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewActivityContent(
    state: WebViewActivityState,
    viewModel: WebViewActivityViewModel,
    modifier: Modifier = Modifier,
    onOpenInBrowser: () -> Unit,
    onNavigateBack: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lastLoaded by remember { mutableStateOf<String?>(null) }

    val webViewClient = remember {
        object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                if (handler == null || error == null) return
                val key = viewModel.sslExceptionKey(error)
                if (viewModel.isSslExceptionAccepted(key)) {
                    handler.proceed()
                    return
                }
                viewModel.onAction(WebViewActivityAction.SslErrorReceived(handler, key))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                viewModel.onAction(WebViewActivityAction.PageFinished)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val currentWebView = webView
            if (currentWebView == null) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    currentWebView.onResume()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    currentWebView.onPause()
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView?.destroy()
            webView = null
        }
    }

    BackHandler {
        val currentWebView = webView
        if (currentWebView?.canGoBack() == true) {
            currentWebView.goBack()
        } else {
            onNavigateBack()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.web_gui_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (!state.isRunningOnTV) {
                        IconButton(onClick = onOpenInBrowser) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.open_in_browser)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            this.webViewClient = webViewClient
                            clearCache(true)
                        }
                    },
                    update = { webViewInstance ->
                        state.url?.let { url ->
                            // Only (re)load the page when the URL actually
                            // changed; recomposition must not reload it.
                            if (lastLoaded != url) {
                                lastLoaded = url
                                webViewInstance.loadUrl(url)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (state.isLoading) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(
                                    R.string.web_page_loading,
                                    state.url.orEmpty()
                                ),
                                modifier = Modifier.padding(top = 16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
