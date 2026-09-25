package com.sideload.splitinstaller.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.sources.DownloadItem
import com.sideload.splitinstaller.core.sources.DownloadRequest
import com.sideload.splitinstaller.core.sources.DownloadStatus
import java.net.URI

class BrowserActions(
    val onClose: () -> Unit = {},
    val onUrl: (String) -> Unit = {},
    val onDownload: (DownloadRequest) -> Unit = {},
    val onOpenExternal: (String) -> Unit = {},
    val onInstall: (DownloadItem) -> Unit = {},
    val onPinCurrent: (String) -> Unit = {},
    val onCancelPin: () -> Unit = {},
)

/**
 * A plain browser, pointed at a bundle source.
 *
 * The site is used the way its owner serves it — its pages, its search, its download
 * button. The only thing the app adds is catching that download: the file is handed to
 * the system downloader with this page's cookies, then opened for installing when done.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    target: BrowserTarget,
    resumeUrl: String?,
    downloads: List<DownloadItem>,
    actions: BrowserActions,
    pinFor: String? = null,
    pinLabel: String? = null,
) {
    val context = LocalContext.current
    var title by remember(target.session) { mutableStateOf(target.title) }
    var url by remember(target.session) { mutableStateOf(resumeUrl ?: target.url) }
    var progress by remember(target.session) { mutableIntStateOf(0) }
    var canGoBack by remember(target.session) { mutableStateOf(false) }

    val webView = remember(target.session) {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true            // every source's search and download pages need it
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)    // "open in new tab" links load here instead
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Web pages only. intent://, market:// and friends are how a page tries to
                    // hand you to another app; nothing a bundle source needs.
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "https" && scheme != "http"
                }

                override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                    url = u
                    canGoBack = view.canGoBack()
                }

                override fun onPageFinished(view: WebView, u: String) {
                    url = u
                    canGoBack = view.canGoBack()
                    actions.onUrl(u)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }

                override fun onReceivedTitle(view: WebView, t: String?) {
                    if (!t.isNullOrBlank()) title = t
                }
            }
            setDownloadListener { dUrl, userAgent, contentDisposition, mimeType, _ ->
                if (dUrl.startsWith("https://") || dUrl.startsWith("http://")) {
                    actions.onDownload(DownloadRequest(dUrl, userAgent, contentDisposition, mimeType, this.url))
                }
            }
            loadUrl(resumeUrl ?: target.url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else actions.onClose()
    }

    // The newest download started while this page was open.
    val latest = downloads.firstOrNull { it.active } ?: downloads.firstOrNull {
        it.status == DownloadStatus.DONE && it.isBundle && System.currentTimeMillis() - it.time < 5 * 60_000
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = actions.onClose) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.browser_close))
                        }
                    },
                    title = {
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                hostOf(url),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    actions = {
                        if (canGoBack) {
                            IconButton(onClick = { webView.goBack() }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                            }
                        }
                        IconButton(onClick = { webView.reload() }) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.browser_reload))
                        }
                        IconButton(onClick = { actions.onOpenExternal(url) }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.action_open_external))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
            }
        },
        bottomBar = {
            Column {
                if (pinFor != null) {
                    PinBar(pinLabel ?: pinFor, url, actions.onPinCurrent, actions.onCancelPin)
                }
                AnimatedVisibility(latest != null) {
                    latest?.let { DownloadBar(it, actions) }
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        // Edge to edge: keep the page clear of the navigation bar and out from under the keyboard.
        val bottom = if (latest == null) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier
        Box(Modifier.fillMaxSize().padding(padding).then(bottom).imePadding()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DownloadBar(item: DownloadItem, actions: BrowserActions) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatTile(formatOf(item.fileName), 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        downloadStatus(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (item.status == DownloadStatus.DONE && item.uri != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { actions.onInstall(item) }, contentPadding = PaddingValues(horizontal = 18.dp)) {
                        Text(stringResource(R.string.action_install_short))
                    }
                }
            }
            if (item.active) {
                val f = item.fraction
                val mod = Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(CircleShape)
                if (f != null) {
                    LinearProgressIndicator(progress = { f }, modifier = mod, gapSize = 0.dp, drawStopIndicator = {})
                } else {
                    LinearProgressIndicator(mod)
                }
            }
        }
    }
}

private fun hostOf(url: String): String =
    runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

/** Shown while the browser is open to choose an update page for a package. */
@Composable
private fun PinBar(label: String, url: String, onPin: (String) -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PushPin, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.browser_pin_title, label), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(hostOf(url), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = { onPin(url) }) { Text(stringResource(R.string.browser_pin_action)) }
        }
    }
}
