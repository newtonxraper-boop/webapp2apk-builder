package com.webapp2apk.generated;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.webkit.URLUtil;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.snackbar.Snackbar;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private LinearLayout bottomTabBar;
    private View bottomTabBarContainer;
    private View tabIndicator;
    private View tabNotch;
    private TextView offlineBanner;
    private TextView updateBanner;
    private View shareButton;

    private String homeUrl;
    private boolean filecameraEnabled;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<String[]> cameraMicPermissionLauncher;
    private ActivityResultLauncher<String> storagePermissionLauncher;
    private PermissionRequest pendingWebPermissionRequest;
    private DownloadManager.Request pendingDownloadRequest;
    private String pendingDownloadFileName;

    // Exit confirmation - back press only exits if pressed twice within 2s.
    private long backPressedAt = 0;

    // Offline banner tracking, debounced so a flaky connection doesn't flicker it.
    private ConnectivityManager.NetworkCallback networkCallback;
    private final android.os.Handler bannerDebounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingBannerUpdate;

    // Set when shouldInterceptRequest already confirmed nothing is cached for
    // a same-origin URL, so onReceivedError can skip a second, pointless
    // cache lookup and go straight to the offline page.
    private String lastConfirmedCacheMissUrl;

    // Bottom nav tab bookkeeping - lets us re-tint icons/labels when the
    // active tab changes, without rebuilding the whole bar.
    private final List<LinearLayout> tabContainers = new ArrayList<>();
    private final List<String> tabUrls = new ArrayList<>();
    private final List<GradientDrawable> tabBadgeGlow = new ArrayList<>();
    private String currentActiveUrl;

    // --- Offline page cache -------------------------------------------------
    // Every full-page navigation (not images/CSS/JS/API calls) gets saved to
    // app-private storage. Next time that exact page is opened, we always try
    // the network first (so you get the freshest version whenever there IS a
    // connection), and only fall back to the last saved copy when the network
    // request truly fails. This is what makes pages "stay on the phone" until
    // there's an actual update.
    private static final long MAX_CACHE_BYTES_CAP = 100L * 1024 * 1024; // never exceed 100 MB regardless of free space
    private static final long MAX_CACHE_BYTES_FLOOR = 10L * 1024 * 1024; // always allow at least 10 MB
    private long maxCacheBytes = 25L * 1024 * 1024; // sensible default until computed from real free space
    private android.animation.ObjectAnimator progressPulseAnimator;
    private File offlineCacheDir;

    private android.content.SharedPreferences prefs;
    private JSONArray cachedNavItems;
    private volatile boolean isCurrentlyOnline = true;
    private int cacheWriteCountSinceLastScan = 0;
    private boolean connectivityCallbackRegistered = false;
    private boolean shortcutsRegistered = false;
    private boolean homePrefetchTriggered = false;

    private long lastCookieFlushTime = 0;
    private static final long COOKIE_FLUSH_MIN_INTERVAL_MS = 2000;

    private String lastDownloadUrl;
    private long lastDownloadTime = 0;
    private static final long DOWNLOAD_DEBOUNCE_MS = 2000;

    private static final long UPDATE_CHECK_MIN_INTERVAL_MS = 60 * 60 * 1000; // 1 hour

    private float cachedDensity = 0f;
    private static final int MAX_CACHE_FILE_COUNT = 500;
    private long lastDragUpdateTime = 0;
    private static final long DRAG_UPDATE_THROTTLE_MS = 16; // ~60fps
    private java.util.concurrent.ExecutorService prefetchExecutor;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyImmersiveTheming();

        webView = findViewById(R.id.webview);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressBar = findViewById(R.id.progressBar);
        bottomTabBar = findViewById(R.id.bottomTabBar);
        bottomTabBarContainer = findViewById(R.id.bottomTabBarContainer);
        tabIndicator = findViewById(R.id.tabIndicator);
        tabNotch = findViewById(R.id.tabNotch);
        offlineBanner = findViewById(R.id.offlineBanner);
        updateBanner = findViewById(R.id.updateBanner);
        shareButton = findViewById(R.id.shareButton);

        JSONObject config = App.appConfig;
        homeUrl = config.optString("app_url", getString(R.string.app_url));
        filecameraEnabled = config.optBoolean("filecamera_enabled", true);

        // A home-screen shortcut launches MainActivity with this extra set to
        // jump straight to that tab's URL instead of the usual home page.
        String shortcutUrl = getIntent() != null ? getIntent().getStringExtra("shortcut_url") : null;
        String startUrl = (shortcutUrl != null && !shortcutUrl.isEmpty()) ? shortcutUrl : homeUrl;
        currentActiveUrl = startUrl;

        offlineCacheDir = new File(getFilesDir(), "webcache");
        if (!offlineCacheDir.exists()) offlineCacheDir.mkdirs();
        maxCacheBytes = computeCacheSizeLimit();
        prefs = getSharedPreferences("webapp2apk_prefs", MODE_PRIVATE);

        setupActivityResultLaunchers();
        setupWebView();
        setupDownloadListener();
        setupShareButton();
        setupConnectivityBanner();
        setupSwipeRefresh();
        setupBottomTabs();
        checkForAppUpdate();

        webView.loadUrl(startUrl);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stops JS timers, animations, and any playing video/audio while
        // backgrounded instead of letting the WebView keep running invisibly -
        // real battery/CPU savings, not just a formality.
        webView.onPause();
        webView.pauseTimers();
        // Final safety net so a session cookie set right before the user
        // backgrounds or closes the app is durably saved, not just held in
        // memory waiting for the system's own periodic flush.
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.resumeTimers();
        if (prefs != null) prefs.edit().putInt("unread_notification_count", 0).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkCallback != null && connectivityCallbackRegistered) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(networkCallback);
                } catch (Exception ignored) {
                }
            }
            connectivityCallbackRegistered = false;
        }

        if (progressPulseAnimator != null) {
            progressPulseAnimator.cancel();
            progressPulseAnimator = null;
        }
        if (pendingBannerUpdate != null) {
            bannerDebounceHandler.removeCallbacksAndMessages(null);
            pendingBannerUpdate = null;
        }
        if (webView != null) {
            webView.animate().cancel();
        }
        if (shareButton != null) {
            shareButton.animate().cancel();
        }
        if (prefetchExecutor != null) {
            prefetchExecutor.shutdownNow();
            prefetchExecutor = null;
        }
    }

    /**
     * Tints the phone's status bar and system navigation bar to match the
     * app's own theme color instead of leaving them plain black/white. This
     * is what makes the app boundary feel seamless with your site's own
     * header/footer, the way every polished native app does, rather than
     * looking like a plain browser window with mismatched OS chrome above
     * and below it.
     */
    private void applyImmersiveTheming() {
        int chromeColor = ContextCompat.getColor(this, R.color.primary_dark_color);
        Window window = getWindow();
        window.setStatusBarColor(chromeColor);
        window.setNavigationBarColor(chromeColor);

        boolean lightBackground = isColorLight(chromeColor);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(lightBackground);
        controller.setAppearanceLightNavigationBars(lightBackground);
    }

    private static boolean isColorLight(int color) {
        double luminance = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.6;
    }

    private void setupActivityResultLaunchers() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) return;
                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri single = result.getData().getData();
                        if (single != null) results = new Uri[]{single};
                    }
                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                });

        cameraMicPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                grants -> {
                    if (pendingWebPermissionRequest == null) return;
                    boolean allGranted = true;
                    for (Boolean g : grants.values()) allGranted = allGranted && Boolean.TRUE.equals(g);
                    if (allGranted) {
                        pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
                    } else {
                        pendingWebPermissionRequest.deny();
                    }
                    pendingWebPermissionRequest = null;
                });

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted && pendingDownloadRequest != null) {
                        enqueueDownload(pendingDownloadRequest, pendingDownloadFileName);
                    } else if (!granted) {
                        showSnackbar("Download needs storage permission");
                    }
                    pendingDownloadRequest = null;
                    pendingDownloadFileName = null;
                });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        applySystemThemeBackground();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Tells Android not to deprioritize this WebView's renderer under
            // memory pressure while the app is in the foreground.
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }

        // Pre-rasters content just outside the visible viewport for smoother
        // scrolling, at the cost of a little extra memory - a good trade for
        // a foreground app.
        settings.setOffscreenPreRaster(true);

        // Hardware-accelerate the WebView explicitly rather than relying on
        // whatever layer type the platform defaults to.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Makes sure a logged-in session actually survives closing and
        // reopening the app, instead of asking the user to sign in every
        // time. Cookies persist to disk by default, but are only flushed to
        // disk periodically by the system - explicit flushes (below, and in
        // onPause()) make sure a freshly-established login session is saved
        // promptly rather than risking loss if the app process gets killed.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent_color));

        webView.setWebViewClient(new WebViewClient() {
            // Secondary safety net: if a page still fails after our own
            // shouldInterceptRequest cache attempt (e.g. this was a non-GET or
            // cross-origin request WebView handled itself), try once more with
            // WebView's own disk cache before giving up entirely.
            private boolean cacheRetryInProgress = false;

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return null;
                return maybeServeFromOfflineCache(request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null) return false;

                if (!scheme.equals("http") && !scheme.equals("https")) {
                    // tel:, mailto:, whatsapp:, intent:, market:, upi:, etc. -
                    // always hand off to whatever app the OS has for it.
                    return openExternally(uri);
                }

                Uri homeUri = Uri.parse(homeUrl);
                if (homeUri.getHost() != null && homeUri.getHost().equalsIgnoreCase(uri.getHost())) {
                    return false; // same site - handle it inside the app as normal
                }

                // A different domain - most commonly a Google/OAuth login screen
                // or a Mobile Money payment gateway redirect, both of which are
                // frequently blocked or broken inside a plain embedded WebView.
                // Hand off to the phone's real browser/app instead of trapping
                // the user on a page that may not even let them proceed.
                return openExternally(uri);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                startProgressPulse();
                view.animate().cancel();
                view.setAlpha(0.3f);

                // Deferred here (rather than during onCreate) so shortcut
                // registration never competes with the WebView for CPU time
                // while the critical first page is loading.
                if (!shortcutsRegistered) {
                    shortcutsRegistered = true;
                    setupHomeScreenShortcuts();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (!request.isForMainFrame()) return;

                String failedUrl = request.getUrl().toString();
                boolean alreadyConfirmedMissing = failedUrl.equals(lastConfirmedCacheMissUrl);

                if (!cacheRetryInProgress && !alreadyConfirmedMissing) {
                    cacheRetryInProgress = true;
                    view.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ONLY);
                    view.loadUrl(failedUrl);
                } else {
                    cacheRetryInProgress = false;
                    view.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                    view.loadUrl("file:///android_asset/offline.html");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);
                maybeFlushCookies();

                // postVisualStateCallback fires once the page is actually
                // painted on screen, which can be a moment after
                // onPageFinished (DOM-complete) fires - hiding the spinner
                // here instead avoids revealing a still-blank frame. A
                // fallback timeout guards against the callback never firing
                // on some WebView versions, so the spinner can't get stuck.
                final long visualStateRequestId = System.currentTimeMillis();
                final boolean[] revealed = {false};
                Runnable reveal = () -> {
                    if (revealed[0]) return;
                    revealed[0] = true;
                    progressBar.setVisibility(View.GONE);
                    stopProgressPulse();
                    view.animate().alpha(1f).setDuration(250).start();
                };
                view.postVisualStateCallback(visualStateRequestId, new WebView.VisualStateCallback() {
                    @Override
                    public void onComplete(long requestId) {
                        reveal.run();
                    }
                });
                view.postDelayed(reveal, 1200);

                if (!homePrefetchTriggered && homeUrl.equals(url)) {
                    homePrefetchTriggered = true;
                    prefetchNavTabs();
                }

                if (isCurrentlyOnline && url != null) {
                    cachePageInBackground(url);
                }

                if (cacheRetryInProgress) {
                    cacheRetryInProgress = false;
                    view.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // Loading feedback is shown as a centered spinner via
                // WebViewClient.onPageStarted/onPageFinished instead of a
                // percentage bar, so there's nothing to update here besides
                // making sure the pull-to-refresh spinner stops.
                if (newProgress >= 100) swipeRefresh.setRefreshing(false);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!filecameraEnabled) return false;
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                fileChooserLauncher.launch(Intent.createChooser(intent, "Choose file"));
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (!filecameraEnabled) {
                    request.deny();
                    return;
                }
                List<String> needed = new ArrayList<>();
                needed.add(Manifest.permission.CAMERA);
                needed.add(Manifest.permission.RECORD_AUDIO);
                List<String> toRequest = new ArrayList<>();
                for (String perm : needed) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, perm) != PackageManager.PERMISSION_GRANTED) {
                        toRequest.add(perm);
                    }
                }
                if (toRequest.isEmpty()) {
                    request.grant(request.getResources());
                } else {
                    pendingWebPermissionRequest = request;
                    cameraMicPermissionLauncher.launch(toRequest.toArray(new String[0]));
                }
            }
        });
    }

    /**
     * Tries the network first (always, so content is fresh whenever there's a
     * connection) and saves a successful response to disk. If the network
     * request fails, falls back to the last saved copy of that exact page, if
     * one exists. Returns null if nothing can be served (lets WebView's normal
     * error handling / offline.html fallback take over).
     */
    private WebResourceResponse maybeServeFromOfflineCache(WebResourceRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return null;

        Uri uri = request.getUrl();
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return null;

        Uri homeUri = Uri.parse(homeUrl);
        if (homeUri.getHost() == null || !homeUri.getHost().equalsIgnoreCase(uri.getHost())) {
            // Only cache pages on the app's own domain.
            return null;
        }

        if (isSensitiveUrl(uri)) {
            // Login/checkout/payment-style pages shouldn't be cached or
            // replayed offline - let WebView handle these completely normally.
            return null;
        }

        // Only take over the request when genuinely offline. When online,
        // this returns null and WebView's own native networking handles the
        // request completely normally - identical to a real browser, with
        // zero risk of interfering with cookies or session state. The page
        // still gets cached for offline use afterward, just passively in the
        // background (see onPageFinished) rather than by hijacking the live
        // request itself.
        if (!isCurrentlyOnline) {
            String cacheKey = sha256(uri.toString());
            File bodyFile = new File(offlineCacheDir, cacheKey + ".body.gz");
            File metaFile = new File(offlineCacheDir, cacheKey + ".meta");
            return serveFromCacheFile(bodyFile, metaFile, uri);
        }

        return null;
    }

    private static final String[] SENSITIVE_URL_KEYWORDS = {
            "login", "logout", "signin", "signup", "checkout", "payment", "cart"
    };

    private boolean isSensitiveUrl(Uri uri) {
        String path = uri.getPath();
        if (path == null) return false;
        String lower = path.toLowerCase();
        for (String keyword : SENSITIVE_URL_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * Fetches a URL fresh over the network (sending along any previously
     * cached ETag/Last-Modified so an unchanged page gets back a cheap 304
     * instead of the full body again), writes the result to the offline
     * cache, and returns it as a WebResourceResponse. Falls back to whatever
     * is already cached if the network attempt fails outright. Also used
     * for background tab prefetching, where the returned response is simply
     * discarded - the point there is only the cache-write side effect.
     */
    private WebResourceResponse fetchAndCache(Uri uri, File bodyFile, File metaFile) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; WebApp2Apk offline cache)");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("Connection", "keep-alive");

            CookieManager cookieManager = CookieManager.getInstance();
            String cookie = cookieManager.getCookie(uri.toString());
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);

            String[] existingMeta = readMetaParts(metaFile);
            if (existingMeta != null) {
                if (!existingMeta[2].isEmpty()) conn.setRequestProperty("If-None-Match", existingMeta[2]);
                if (!existingMeta[3].isEmpty()) conn.setRequestProperty("If-Modified-Since", existingMeta[3]);
            }

            int status = conn.getResponseCode();

            if (status == 304 && bodyFile.exists()) {
                // Server confirms nothing changed - reuse the cached body
                // instead of downloading it again, and touch its timestamp so
                // LRU cache eviction still treats it as recently used.
                conn.disconnect();
                bodyFile.setLastModified(System.currentTimeMillis());
                return serveFromCacheFile(bodyFile, metaFile, uri);
            }

            if (status >= 200 && status < 300) {
                Map<String, List<String>> headers = conn.getHeaderFields();
                List<String> setCookies = headers.get("Set-Cookie");
                if (setCookies != null) {
                    for (String sc : setCookies) cookieManager.setCookie(uri.toString(), sc);
                    maybeFlushCookies();
                }

                String contentType = conn.getContentType();
                String mimeType = "text/html";
                String encoding = "UTF-8";
                if (contentType != null) {
                    String[] parts = contentType.split(";");
                    mimeType = parts[0].trim();
                    for (String p : parts) {
                        p = p.trim();
                        if (p.toLowerCase().startsWith("charset=")) {
                            encoding = p.substring(8).trim();
                        }
                    }
                }

                String etag = conn.getHeaderField("ETag");
                String lastModified = conn.getHeaderField("Last-Modified");

                boolean serverGzipped = "gzip".equalsIgnoreCase(conn.getContentEncoding());
                InputStream responseStream = conn.getInputStream();
                if (serverGzipped) responseStream = new java.util.zip.GZIPInputStream(responseStream);

                byte[] data = readAllBytes(responseStream);
                conn.disconnect();

                writeGzipFileQuietly(bodyFile, data);
                writeFileQuietly(metaFile, buildMetaString(mimeType, encoding, etag, lastModified).getBytes("UTF-8"));
                maybeEnforceCacheSizeLimit();

                return new WebResourceResponse(mimeType, encoding, new ByteArrayInputStream(data));
            } else {
                conn.disconnect();
            }
        } catch (Exception networkFailed) {
            // fall through to the cached copy below
        }

        return serveFromCacheFile(bodyFile, metaFile, uri);
    }

    private String[] readMetaParts(File metaFile) {
        if (!metaFile.exists()) return null;
        try {
            String meta = new String(readAllBytes(new FileInputStream(metaFile)), "UTF-8");
            String[] parts = meta.split("\\|", -1);
            String[] result = new String[4]; // mimeType, encoding, etag, lastModified
            for (int i = 0; i < 4; i++) result[i] = i < parts.length ? parts[i] : "";
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildMetaString(String mimeType, String encoding, String etag, String lastModified) {
        return mimeType + "|" + encoding + "|" + (etag != null ? etag : "") + "|" + (lastModified != null ? lastModified : "");
    }

    private WebResourceResponse serveFromCacheFile(File bodyFile, File metaFile, Uri uri) {
        if (bodyFile.exists() && metaFile.exists()) {
            try {
                String[] meta = readMetaParts(metaFile);
                String mimeType = meta != null && !meta[0].isEmpty() ? meta[0] : "text/html";
                String encoding = meta != null && !meta[1].isEmpty() ? meta[1] : "UTF-8";
                // Streamed straight from disk rather than buffered fully into
                // memory first - matters most on low-RAM phones with larger
                // cached pages.
                InputStream stream = new java.util.zip.GZIPInputStream(new FileInputStream(bodyFile));
                return new WebResourceResponse(mimeType, encoding, stream);
            } catch (Exception ignored) {
                lastConfirmedCacheMissUrl = uri.toString();
                return null;
            }
        }

        lastConfirmedCacheMissUrl = uri.toString();
        return null;
    }

    private static byte[] readAllBytes(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
        in.close();
        return buffer.toByteArray();
    }

    private static void writeFileQuietly(File file, byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        } catch (Exception ignored) {
        }
    }

    /**
     * Cached pages are stored gzip-compressed on disk (HTML/JSON/text
     * typically shrinks 60-80%), so the same storage budget holds noticeably
     * more pages and each read is a smaller disk operation.
     */
    private static void writeGzipFileQuietly(File file, byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(file);
             java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(fos)) {
            gzos.write(data);
        } catch (Exception ignored) {
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    /**
     * Scanning the whole cache directory on every single write is wasteful -
     * only actually check/enforce the size limit every 5th write.
     */
    private void maybeEnforceCacheSizeLimit() {
        cacheWriteCountSinceLastScan++;
        if (cacheWriteCountSinceLastScan < 5) return;
        cacheWriteCountSinceLastScan = 0;
        enforceCacheSizeLimit();
    }

    private void enforceCacheSizeLimit() {
        File[] files = offlineCacheDir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();

        boolean overSize = total > maxCacheBytes;
        boolean overCount = files.length > MAX_CACHE_FILE_COUNT;
        if (!overSize && !overCount) return;

        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int remaining = files.length;
        for (File f : files) {
            boolean stillOverSize = total > maxCacheBytes;
            boolean stillOverCount = remaining > MAX_CACHE_FILE_COUNT;
            if (!stillOverSize && !stillOverCount) break;
            total -= f.length();
            remaining--;
            f.delete();
        }
    }

    /**
     * Uses 5% of the device's free internal storage as the offline cache
     * budget, bounded between 10MB and 100MB, instead of one fixed number
     * that could be wasteful on a spacious phone or too greedy on a nearly-full one.
     */
    private long computeCacheSizeLimit() {
        try {
            android.os.StatFs stat = new android.os.StatFs(getFilesDir().getPath());
            long freeBytes = stat.getAvailableBytes();
            long budget = freeBytes / 20; // 5%
            return Math.max(MAX_CACHE_BYTES_FLOOR, Math.min(MAX_CACHE_BYTES_CAP, budget));
        } catch (Exception e) {
            return MAX_CACHE_BYTES_FLOOR;
        }
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener(() -> webView.reload());
    }

    /**
     * Cookies get flushed to disk on essentially every page load; throttling
     * that avoids redundant disk writes when a user is navigating quickly
     * from page to page. onPause() still always flushes unconditionally as a
     * final safety net regardless of this throttle.
     */
    private void maybeFlushCookies() {
        long now = System.currentTimeMillis();
        if (now - lastCookieFlushTime < COOKIE_FLUSH_MIN_INTERVAL_MS) return;
        lastCookieFlushTime = now;
        CookieManager.getInstance().flush();
    }

    /**
     * Called after a page has already loaded natively (successfully, with
     * correct cookies/session handling since WebView did it itself). Fetches
     * a copy of that same URL again in the background purely to populate the
     * offline cache for later - completely separate from, and after, what the
     * user is currently looking at, so it can never affect the live page.
     *
     * Deliberately NOT gated behind shouldDoBackgroundWork() (unlike
     * prefetchNavTabs) - this is the core offline-caching mechanism, and
     * skipping it on cellular would mean offline access barely works at all
     * for anyone who isn't usually on WiFi. It costs roughly the same data as
     * the page the user already just loaded, not "extra" pages they may
     * never visit.
     */
    private void cachePageInBackground(String urlString) {
        try {
            Uri uri = Uri.parse(urlString);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return;

            Uri homeUri = Uri.parse(homeUrl);
            if (homeUri.getHost() == null || !homeUri.getHost().equalsIgnoreCase(uri.getHost())) return;
            if (isSensitiveUrl(uri)) return;

            new Thread(() -> {
                String cacheKey = sha256(uri.toString());
                File bodyFile = new File(offlineCacheDir, cacheKey + ".body.gz");
                File metaFile = new File(offlineCacheDir, cacheKey + ".meta");
                fetchAndCache(uri, bodyFile, metaFile);
            }).start();
        } catch (Exception ignored) {
        }
    }

    /**
     * Background caching/prefetch only runs on an unmetered (WiFi) connection
     * and when the phone isn't in battery saver mode - it's a nice-to-have,
     * not worth spending someone's mobile data allowance or battery on.
     */
    private boolean shouldDoBackgroundWork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        boolean unmetered = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        if (!unmetered) return false;

        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean batterySaver = pm != null && pm.isPowerSaveMode();
        return !batterySaver;
    }

    /**
     * Once the home page has loaded, quietly warms the offline cache for the
     * other bottom-nav tabs in the background (on WiFi only, battery saver
     * permitting), so switching to them feels instant instead of triggering
     * a fresh load the first time each is tapped. Runs on a small thread
     * pool so multiple tabs warm up concurrently rather than one at a time.
     */
    private void prefetchNavTabs() {
        if (tabUrls.isEmpty() || !shouldDoBackgroundWork()) return;
        final List<String> urlsToPrefetch = new ArrayList<>(tabUrls);

        if (prefetchExecutor == null || prefetchExecutor.isShutdown()) {
            prefetchExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
        }

        for (String tabUrl : urlsToPrefetch) {
            if (tabUrl.equals(homeUrl)) continue;
            prefetchExecutor.submit(() -> {
                try {
                    Uri uri = Uri.parse(tabUrl);
                    if (isSensitiveUrl(uri)) return;
                    String cacheKey = sha256(uri.toString());
                    File bodyFile = new File(offlineCacheDir, cacheKey + ".body.gz");
                    File metaFile = new File(offlineCacheDir, cacheKey + ".meta");
                    fetchAndCache(uri, bodyFile, metaFile);
                } catch (Exception ignored) {
                    // A prefetch failing for one tab shouldn't affect the others.
                }
            });
        }
    }

    /**
     * A gentle breathing effect on the loading spinner while a page loads -
     * a lightweight stand-in for a true content-shaped skeleton screen, which
     * isn't really possible generically since every site's layout is different.
     */
    private void startProgressPulse() {
        if (progressPulseAnimator != null) progressPulseAnimator.cancel();
        progressPulseAnimator = android.animation.ObjectAnimator.ofFloat(progressBar, "alpha", 1f, 0.35f, 1f);
        progressPulseAnimator.setDuration(900);
        progressPulseAnimator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        progressPulseAnimator.start();
    }

    private void stopProgressPulse() {
        if (progressPulseAnimator != null) {
            progressPulseAnimator.cancel();
            progressPulseAnimator = null;
        }
        progressBar.setAlpha(1f);
    }

    /**
     * WebView can't download files on its own (PDFs, receipts, certificates
     * a site links to just silently fail without this) - route them through
     * Android's own DownloadManager instead, which shows a real system
     * notification and saves to the phone's Downloads folder.
     */
    private void setupDownloadListener() {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            long now = System.currentTimeMillis();
            if (url.equals(lastDownloadUrl) && (now - lastDownloadTime) < DOWNLOAD_DEBOUNCE_MS) {
                return; // duplicate tap on the same link - ignore
            }
            lastDownloadUrl = url;
            lastDownloadTime = now;

            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) request.addRequestHeader("Cookie", cookie);
                request.addRequestHeader("User-Agent", userAgent);

                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setMimeType(mimeType);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setTitle(fileName);

                boolean needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED;

                if (needsPermission) {
                    pendingDownloadRequest = request;
                    pendingDownloadFileName = fileName;
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                } else {
                    enqueueDownload(request, fileName);
                }
            } catch (Exception e) {
                showSnackbar("Could not start download");
            }
        });
    }

    private void enqueueDownload(DownloadManager.Request request, String fileName) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                showSnackbar("Downloading " + fileName);
            }
        } catch (Exception e) {
            showSnackbar("Could not start download");
        }
    }

    /**
     * The share button is draggable so it can be moved out of the way of any
     * floating buttons the website itself already has in that corner (chat
     * widgets, "add to cart" bubbles, etc.) - a tap still shares the page;
     * only a real drag moves it. Position is remembered between app opens.
     */
    private void setupShareButton() {
        restoreShareButtonPosition();

        shareButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            String url = webView.getUrl();
            if (url == null || url.startsWith("file:///android_asset/")) url = homeUrl;
            String title = webView.getTitle();

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            if (title != null && !title.isEmpty()) {
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
            }
            shareIntent.putExtra(Intent.EXTRA_TEXT, url);
            startActivity(Intent.createChooser(shareIntent, "Share via"));
        });

        final float[] touchOffsetX = new float[1];
        final float[] touchOffsetY = new float[1];
        final float[] downRawX = new float[1];
        final float[] downRawY = new float[1];
        final boolean[] isDragging = {false};

        shareButton.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchOffsetX[0] = v.getX() - event.getRawX();
                    touchOffsetY[0] = v.getY() - event.getRawY();
                    downRawX[0] = event.getRawX();
                    downRawY[0] = event.getRawY();
                    isDragging[0] = false;
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(100).start();
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float moved = Math.abs(event.getRawX() - downRawX[0]) + Math.abs(event.getRawY() - downRawY[0]);
                    if (moved > dpToPx(8)) isDragging[0] = true;

                    long now = System.currentTimeMillis();
                    if (now - lastDragUpdateTime < DRAG_UPDATE_THROTTLE_MS) return true;
                    lastDragUpdateTime = now;

                    View parent = (View) v.getParent();
                    float newX = event.getRawX() + touchOffsetX[0];
                    float newY = event.getRawY() + touchOffsetY[0];
                    newX = Math.max(0, Math.min(newX, parent.getWidth() - v.getWidth()));
                    newY = Math.max(0, Math.min(newY, parent.getHeight() - v.getHeight()));
                    v.setX(newX);
                    v.setY(newY);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (isDragging[0]) {
                        saveShareButtonPosition(v.getX(), v.getY());
                    } else {
                        v.performClick();
                    }
                    return true;

                default:
                    return false;
            }
        });
    }

    private void restoreShareButtonPosition() {
        if (!prefs.contains("share_btn_x")) return; // keep the default XML corner position

        shareButton.post(() -> {
            float x = prefs.getFloat("share_btn_x", shareButton.getX());
            float y = prefs.getFloat("share_btn_y", shareButton.getY());
            shareButton.setX(x);
            shareButton.setY(y);
        });
    }

    private void saveShareButtonPosition(float x, float y) {
        prefs.edit()
                .putFloat("share_btn_x", x)
                .putFloat("share_btn_y", y)
                .apply();
    }

    private boolean openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sets the WebView's background to match the phone's system dark/light
     * mode before any page loads, so there's no jarring white flash while
     * the real page content is still loading in.
     */
    private void applySystemThemeBackground() {
        int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDarkMode = uiMode == Configuration.UI_MODE_NIGHT_YES;
        webView.setBackgroundColor(isDarkMode ? Color.parseColor("#171A21") : Color.WHITE);
    }

    /**
     * Builds home-screen long-press shortcuts from the same nav_items.json
     * used for the bottom tab bar, so users can jump straight to a section
     * without opening the app to its home page first.
     */
    private void setupHomeScreenShortcuts() {
        try {
            JSONArray navItems = loadNavItems();
            if (navItems == null || navItems.length() == 0) return;

            List<ShortcutInfoCompat> shortcuts = new ArrayList<>();
            int max = Math.min(navItems.length(), 4);
            for (int i = 0; i < max; i++) {
                JSONObject item = navItems.optJSONObject(i);
                if (item == null) continue;
                String label = item.optString("label", "Tab" + i);
                String url = item.optString("url", homeUrl);

                Intent shortcutIntent = new Intent(this, MainActivity.class);
                shortcutIntent.setAction(Intent.ACTION_VIEW);
                shortcutIntent.putExtra("shortcut_url", url);
                shortcutIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(this, "tab_" + i)
                        .setShortLabel(label)
                        .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                        .setIntent(shortcutIntent)
                        .build();
                shortcuts.add(shortcut);
            }

            if (!shortcuts.isEmpty()) {
                ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts);
            }
        } catch (Exception ignored) {
            // Shortcuts are a nice-to-have - never let a failure here affect the app itself.
        }
    }

    /**
     * Optionally checks {yourdomain}/version.json for a newer build. Fully
     * opt-in: if that file doesn't exist, this silently does nothing - there
     * is no other backend requirement for the rest of the app to work.
     * Publishing {"version_code": N, "apk_url": "..."} there is enough to
     * turn it on for any given app.
     */
    private void checkForAppUpdate() {
        long lastCheck = prefs.getLong("last_update_check", 0);
        if (System.currentTimeMillis() - lastCheck < UPDATE_CHECK_MIN_INTERVAL_MS) return;
        prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply();

        new Thread(() -> {
            try {
                Uri homeUri = Uri.parse(homeUrl);
                String versionUrl = homeUri.getScheme() + "://" + homeUri.getHost() + "/version.json";

                HttpURLConnection conn = (HttpURLConnection) new URL(versionUrl).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int status = conn.getResponseCode();
                if (status != 200) {
                    conn.disconnect();
                    return;
                }

                byte[] data = readAllBytes(conn.getInputStream());
                conn.disconnect();
                JSONObject versionInfo = new JSONObject(new String(data, "UTF-8"));

                long latestVersionCode = versionInfo.optLong("version_code", -1);
                String apkUrl = versionInfo.optString("apk_url", "");
                if (latestVersionCode <= 0 || apkUrl.isEmpty()) return;

                PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                long currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo);

                if (latestVersionCode > currentVersionCode) {
                    runOnUiThread(() -> showUpdateBanner(apkUrl));
                }
            } catch (Exception ignored) {
                // No version.json published, or unreachable - this feature is
                // fully optional and should never interrupt normal use.
            }
        }).start();
    }

    private void showUpdateBanner(String apkUrl) {
        updateBanner.setVisibility(View.VISIBLE);
        updateBanner.setOnClickListener(v -> openExternally(Uri.parse(apkUrl)));
    }

    /**
     * Shows a slim "No internet connection" strip whenever there's no active
     * connection - separate from (and in addition to) the full-page offline
     * cache, so there's always a clear, immediate signal even on pages that
     * were never cached.
     */
    private void setupConnectivityBanner() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;

        isCurrentlyOnline = isOnline(cm);
        updateOfflineBanner(isCurrentlyOnline);

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                boolean wasOffline = !isCurrentlyOnline;
                isCurrentlyOnline = true;
                runOnUiThread(() -> {
                    updateOfflineBanner(true);
                    if (wasOffline) showSnackbar("Back online");
                });
            }

            @Override
            public void onLost(Network network) {
                isCurrentlyOnline = isOnline(cm);
                runOnUiThread(() -> updateOfflineBanner(isCurrentlyOnline));
            }
        };

        if (!connectivityCallbackRegistered) {
            try {
                cm.registerNetworkCallback(request, networkCallback);
                connectivityCallbackRegistered = true;
            } catch (Exception ignored) {
                networkCallback = null;
            }
        }
    }

    private boolean isOnline(ConnectivityManager cm) {
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void updateOfflineBanner(boolean online) {
        if (pendingBannerUpdate != null) bannerDebounceHandler.removeCallbacks(pendingBannerUpdate);
        pendingBannerUpdate = () -> offlineBanner.setVisibility(online ? View.GONE : View.VISIBLE);
        // Going offline is shown right away (immediate feedback matters more);
        // coming back online waits briefly in case it's just a flicker.
        bannerDebounceHandler.postDelayed(pendingBannerUpdate, online ? 600 : 0);
    }

    private void showSnackbar(String message) {
        View root = findViewById(android.R.id.content);
        Snackbar snackbar = Snackbar.make(root, message, Snackbar.LENGTH_SHORT);
        snackbar.setBackgroundTint(ContextCompat.getColor(this, R.color.primary_dark_color));
        snackbar.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        snackbar.show();
    }

    private int dpToPx(int dp) {
        if (cachedDensity == 0f) cachedDensity = getResources().getDisplayMetrics().density;
        return Math.round(dp * cachedDensity);
    }

    private void applyRippleForeground(View view) {
        try {
            view.setForeground(ContextCompat.getDrawable(this, R.drawable.nav_ripple));
        } catch (Exception e) {
            TypedValue outValue = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            if (outValue.resourceId != 0) {
                view.setForeground(ContextCompat.getDrawable(this, outValue.resourceId));
            }
        }
    }

    private void setupBottomTabs() {
        JSONArray navItems = loadNavItems();
        if (navItems == null || navItems.length() == 0) {
            bottomTabBarContainer.setVisibility(View.GONE);
            return;
        }

        bottomTabBarContainer.setVisibility(View.VISIBLE);
        bottomTabBar.removeAllViews();
        tabContainers.clear();
        tabUrls.clear();
        tabBadgeGlow.clear();

        for (int i = 0; i < navItems.length(); i++) {
            JSONObject item = navItems.optJSONObject(i);
            if (item == null) continue;
            String label = item.optString("label", "Tab");
            String icon = item.optString("icon", "").trim();
            String url = item.optString("url", homeUrl);

            LinearLayout tabContainer = new LinearLayout(this);
            tabContainer.setOrientation(LinearLayout.VERTICAL);
            tabContainer.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            tabContainer.setLayoutParams(containerParams);
            tabContainer.setClickable(true);
            tabContainer.setFocusable(true);
            applyRippleForeground(tabContainer);

            TextView iconView = new TextView(this);
            iconView.setGravity(Gravity.CENTER);
            boolean hasRealIcon = !icon.isEmpty() && !icon.equals("\u25CF");
            GradientDrawable badgeGlow = null;

            if (hasRealIcon) {
                iconView.setText(icon);
                iconView.setTextSize(20);
                iconView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            } else {
                // No icon was chosen for this tab - instead of a plain bullet
                // dot, show the tab's first letter inside a small tinted
                // circular badge, which reads as an intentional design rather
                // than a missing icon.
                String letter = label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase();
                iconView.setText(letter);
                iconView.setTextSize(13);
                iconView.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dpToPx(22), dpToPx(22));
                iconView.setLayoutParams(badgeParams);

                badgeGlow = new GradientDrawable();
                badgeGlow.setShape(GradientDrawable.OVAL);
                badgeGlow.setColor(ContextCompat.getColor(this, R.color.accent_color));
                badgeGlow.setAlpha(50);
                iconView.setBackground(badgeGlow);
            }
            tabBadgeGlow.add(badgeGlow);

            TextView labelView = new TextView(this);
            labelView.setText(label);
            labelView.setGravity(Gravity.CENTER);
            labelView.setTextSize(10.5f);
            labelView.setPadding(0, dpToPx(3), 0, 0);
            labelView.setMaxLines(1);

            tabContainer.addView(iconView);
            tabContainer.addView(labelView);
            tabContainer.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                iconView.animate().cancel();
                iconView.setScaleX(0.7f);
                iconView.setScaleY(0.7f);
                iconView.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(new android.view.animation.OvershootInterpolator())
                        .setDuration(280).start();
                currentActiveUrl = url;
                webView.loadUrl(url);
                refreshTabHighlighting();
                moveIndicatorToActiveTab();
            });

            bottomTabBar.addView(tabContainer);
            tabContainers.add(tabContainer);
            tabUrls.add(url);
        }

        refreshTabHighlighting();
        moveIndicatorToActiveTab();
    }

    /**
     * Slides the thin accent-colored strip along the top of the bottom nav
     * bar to sit under whichever tab is currently active, instead of just
     * swapping icon/label colors with no motion. Also floats a soft circular
     * highlight up behind the active tab's icon.
     */
    private void moveIndicatorToActiveTab() {
        if (tabIndicator == null || tabUrls.isEmpty()) return;
        int activeIndex = tabUrls.indexOf(currentActiveUrl);
        if (activeIndex < 0) activeIndex = 0;
        final int idx = activeIndex;

        bottomTabBar.post(() -> {
            int tabWidth = bottomTabBar.getWidth() / Math.max(1, tabUrls.size());
            if (tabWidth <= 0) return;
            android.view.ViewGroup.LayoutParams params = tabIndicator.getLayoutParams();
            params.width = tabWidth;
            tabIndicator.setLayoutParams(params);
            tabIndicator.animate().x(tabWidth * idx).setDuration(200).start();

            if (tabNotch != null) {
                float notchX = tabWidth * idx + (tabWidth - tabNotch.getWidth()) / 2f;
                tabNotch.animate().x(notchX).alpha(0.18f).setDuration(200).start();
            }
        });
    }

    private void refreshTabHighlighting() {
        int activeColor = ContextCompat.getColor(this, R.color.accent_color);
        int inactiveColor = ContextCompat.getColor(this, R.color.tab_inactive);

        for (int i = 0; i < tabContainers.size(); i++) {
            LinearLayout container = tabContainers.get(i);
            boolean active = tabUrls.get(i).equals(currentActiveUrl);
            int color = active ? activeColor : inactiveColor;
            for (int j = 0; j < container.getChildCount(); j++) {
                View child = container.getChildAt(j);
                if (child instanceof TextView) {
                    ((TextView) child).setTextColor(color);
                }
            }

            if (i < tabBadgeGlow.size() && tabBadgeGlow.get(i) != null) {
                GradientDrawable glow = tabBadgeGlow.get(i);
                if (active) {
                    glow.setStroke(dpToPx(2), activeColor);
                } else {
                    glow.setStroke(0, Color.TRANSPARENT);
                }
            }
        }
    }

    private JSONArray loadNavItems() {
        if (cachedNavItems != null) return cachedNavItems;

        try (InputStream is = getAssets().open("nav_items.json")) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            cachedNavItems = new JSONArray(sb.toString());
        } catch (Exception e) {
            cachedNavItems = new JSONArray();
        }
        return cachedNavItems;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (backPressedAt + 2000 > System.currentTimeMillis()) {
            super.onBackPressed();
            return;
        }
        backPressedAt = System.currentTimeMillis();
        showSnackbar("Press back again to exit");
    }
}
