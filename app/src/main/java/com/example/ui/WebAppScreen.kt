package com.example.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.network.NetworkMonitor

const val APP_HOME_URL = "https://music-app-3wj.pages.dev"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppScreen(
  networkMonitor: NetworkMonitor,
  onInitialLoadFinished: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val isOnline by networkMonitor.isOnline.collectAsStateWithLifecycle()

  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var canGoBack by remember { mutableStateOf(false) }
  var showOfflineFallback by remember { mutableStateOf(false) }
  var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
  var retriedOfflineCache by remember { mutableStateOf(false) }
  val backgroundColorInt = MaterialTheme.colorScheme.background.toArgb()

  val fileChooserLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val uris =
        if (result.resultCode == Activity.RESULT_OK) {
          WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
          null
        }
      fileChooserCallback?.onReceiveValue(uris)
      fileChooserCallback = null
    }

  BackHandler(enabled = canGoBack) {
    webViewRef?.let { webView ->
      if (webView.canGoBack()) {
        webView.goBack()
      }
    }
  }

  LaunchedEffect(isOnline) {
    val cacheMode =
      if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
    webViewRef?.settings?.cacheMode = cacheMode
    runCatching {
      ServiceWorkerController.getInstance().serviceWorkerWebSettings.cacheMode = cacheMode
    }
    if (isOnline && showOfflineFallback) {
      showOfflineFallback = false
      retriedOfflineCache = false
      webViewRef?.loadUrl(APP_HOME_URL)
    }
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> webViewRef?.onResume()
        Lifecycle.Event.ON_STOP -> CookieManager.getInstance().flush()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      CookieManager.getInstance().flush()
      webViewRef?.apply {
        stopLoading()
        destroy()
      }
      webViewRef = null
    }
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    AndroidView(
      modifier = Modifier.fillMaxSize().testTag("music_webview"),
      factory = { ctx ->
        configureServiceWorker(isOnline)
        WebView(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          setBackgroundColor(backgroundColorInt)
          isHorizontalScrollBarEnabled = false
          isVerticalScrollBarEnabled = false
          overScrollMode = WebView.OVER_SCROLL_NEVER

          val currentWebView = this
          CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(currentWebView, true)
          }

          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode =
              if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
          }

          setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(ctx, url, userAgent, contentDisposition, mimeType)
          }

          webChromeClient =
            object : WebChromeClient() {
              override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 85) {
                  onInitialLoadFinished()
                }
              }

              override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
              ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val intent =
                  fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                      addCategory(Intent.CATEGORY_OPENABLE)
                      type = "*/*"
                    }
                return try {
                  fileChooserLauncher.launch(intent)
                  true
                } catch (_: ActivityNotFoundException) {
                  fileChooserCallback = null
                  false
                }
              }

              override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
              ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempWebView = WebView(ctx)
                tempWebView.webViewClient =
                  object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                      popupView: WebView?,
                      request: WebResourceRequest?,
                    ): Boolean {
                      val targetUrl = request?.url ?: return false
                      handleExternalOrInternalUri(ctx, view, targetUrl)
                      tempWebView.destroy()
                      return true
                    }
                  }
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
              }
            }

          webViewClient =
            object : WebViewClient() {
              override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
              ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                  return false
                }
                return openExternalScheme(ctx, uri)
              }

              override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                canGoBack = view?.canGoBack() ?: false
              }

              override fun onPageFinished(view: WebView?, url: String?) {
                canGoBack = view?.canGoBack() ?: false
                CookieManager.getInstance().flush()
                onInitialLoadFinished()
              }

              override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
              ) {
                if (request?.isForMainFrame == true) {
                  onInitialLoadFinished()
                  if (!isOnline && !retriedOfflineCache) {
                    retriedOfflineCache = true
                    view?.settings?.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    view?.loadUrl(request.url.toString())
                  } else if (!isOnline) {
                    showOfflineFallback = true
                  }
                }
              }
            }

          webViewRef = this
          loadUrl(APP_HOME_URL)
        }
      },
      update = { webView ->
        webView.setBackgroundColor(backgroundColorInt)
      },
    )

    if (showOfflineFallback) {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = stringResource(R.string.offline_title),
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onBackground,
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.offline_message),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
          onClick = {
            showOfflineFallback = false
            retriedOfflineCache = false
            webViewRef?.settings?.cacheMode =
              if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
            webViewRef?.loadUrl(APP_HOME_URL)
          },
          modifier = Modifier.testTag("offline_retry_button"),
        ) {
          Text(text = stringResource(R.string.offline_retry))
        }
      }
    }
  }
}

private fun configureServiceWorker(isOnline: Boolean) {
  runCatching {
    val controller = ServiceWorkerController.getInstance()
    controller.serviceWorkerWebSettings.apply {
      allowContentAccess = true
      allowFileAccess = true
      blockNetworkLoads = false
      cacheMode =
        if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
    }
    controller.setServiceWorkerClient(
      object : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
          null
      },
    )
  }
}

private fun handleExternalOrInternalUri(context: Context, mainWebView: WebView?, uri: Uri) {
  val scheme = uri.scheme?.lowercase()
  if ((scheme == "http" || scheme == "https") &&
    uri.host?.endsWith("music-app-3wj.pages.dev") == true
  ) {
    mainWebView?.loadUrl(uri.toString())
  } else {
    openExternalScheme(context, uri)
  }
}

private fun openExternalScheme(context: Context, uri: Uri): Boolean {
  return try {
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    true
  } catch (_: ActivityNotFoundException) {
    true
  }
}

private fun enqueueDownload(
  context: Context,
  url: String,
  userAgent: String,
  contentDisposition: String,
  mimeType: String,
) {
  runCatching {
    val uri = Uri.parse(url)
    if (uri.scheme != "http" && uri.scheme != "https") return
    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
    val request =
      DownloadManager.Request(uri).apply {
        setMimeType(mimeType)
        addRequestHeader("User-Agent", userAgent)
        val cookies = CookieManager.getInstance().getCookie(url)
        if (!cookies.isNullOrBlank()) {
          addRequestHeader("Cookie", cookies)
        }
        setTitle(fileName)
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
      }
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    dm.enqueue(request)
  }
}
