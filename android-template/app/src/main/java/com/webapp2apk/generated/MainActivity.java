package com.webapp2apk.generated;

import android.Manifest;
import android.app.PendingIntent;
import android.os.Parcelable;
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

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // Set in onResume, cleared in onPause - lets GeofenceBroadcastReceiver
    // forward a geofence event live to this Activity's WebView when it
    // happens to be open, without holding a permanent reference anywhere
    // that could leak the Activity.
    private static volatile MainActivity activeInstance;

    static MainActivity getActiveInstance() {
        return activeInstance;
    }

    private WebView webView;
    private ProgressBar progressBar;
    private View progressBarIcon;
    private LinearLayout bottomTabBar;
    private View bottomTabBarContainer;
    private View tabIndicator;
    private View tabNotch;
    private TextView offlineBanner;
    private TextView updateBanner;
    private View shareButton;
    private View refreshButton;
    private View settingsButton;
    private View syncPendingBanner;
    private TextView syncPendingText;
    private View syncPendingSendNowButton;

    private String homeUrl;
    private boolean filecameraEnabled;
    private boolean kioskEnabled;

    // NFC tap bridge state (see NfcBridge.java / nfc lifecycle methods below).
    private NfcAdapter nfcAdapter;
    private volatile String pendingNfcWriteText;

    // Location & geofencing bridge state (see LocationBridge.java).
    private FusedLocationProviderClient fusedLocationClient;
    private GeofencingClient geofencingClient;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;
    private ActivityResultLauncher<ScanOptions> qrScanLauncher;
    private Runnable pendingLocationAction;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<String[]> cameraMicPermissionLauncher;
    private ActivityResultLauncher<String> storagePermissionLauncher;
    private ActivityResultLauncher<Intent> lockLauncher;
    private boolean lockScreenShowing = false;
    private PermissionRequest pendingWebPermissionRequest;
    private DownloadManager.Request pendingDownloadRequest;
    private String pendingDownloadFileName;

    private long backPressedAt = 0;

    private ConnectivityManager.NetworkCallback networkCallback;
    private final android.os.Handler bannerDebounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingBannerUpdate;

    private String lastConfirmedCacheMissUrl;

    private final android.os.Handler watchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable watchdogTick;
    private int watchdogMissedBeats = 0;
    private long watchdogSnackbarShownAt = 0;

    private final List<LinearLayout> tabContainers = new ArrayList<>();
    private final List<String> tabUrls = new ArrayList<>();
    private final List<GradientDrawable> tabBadgeGlow = new ArrayList<>();
    private String currentActiveUrl;

    private static final long MAX_CACHE_BYTES_CAP = 100L * 1024 * 1024;
    private static final long MAX_CACHE_BYTES_FLOOR = 10L * 1024 * 1024;
    private long maxCacheBytes = 25L * 1024 * 1024;
    private android.animation.ObjectAnimator progressPulseAnimator;
    private File offlineCacheDir;

    private android.content.SharedPreferences prefs;
    private JSONArray cachedNavItems;
    private volatile boolean isCurrentlyOnline = true;
    private int cacheWriteCountSinceLastScan = 0;
    private boolean connectivityCallbackRegistered = false;
    private boolean shortcutsRegistered = false;
    private boolean homePrefetchTriggered = false;

    private final android.os.Handler queueRetryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingQueueRetry;
    private int flushRetryAttempt = 0;
    private static final long[] FLUSH_RETRY_DELAYS_MS = {5_000L, 15_000L, 30_000L, 60_000L};

    private long lastCookieFlushTime = 0;
    private String lastPageUrl;
    private String[] pendingCredentials;
    private volatile boolean skipAutoLoginOnce = false;
    private static final long COOKIE_FLUSH_MIN_INTERVAL_MS = 2000;

    private String lastDownloadUrl;
    private long lastDownloadTime = 0;
    private static final long DOWNLOAD_DEBOUNCE_MS = 2000;

    private static final long UPDATE_CHECK_MIN_INTERVAL_MS = 60 * 60 * 1000;

    private float cachedDensity = 0f;
    private static final int MAX_CACHE_FILE_COUNT = 500;
    private long lastDragUpdateTime = 0;
    private static final long DRAG_UPDATE_THROTTLE_MS = 16;
    private java.util.concurrent.ExecutorService prefetchExecutor;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyImmersiveTheming();

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);
        progressBarIcon = findViewById(R.id.progressBarIcon);
        bottomTabBar = findViewById(R.id.bottomTabBar);
        bottomTabBarContainer = findViewById(R.id.bottomTabBarContainer);
        tabIndicator = findViewById(R.id.tabIndicator);
        tabNotch = findViewById(R.id.tabNotch);
        offlineBanner = findViewById(R.id.offlineBanner);
        offlineBanner.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            attemptRealRefresh();
        });
        updateBanner = findViewById(R.id.updateBanner);
        shareButton = findViewById(R.id.shareButton);
        refreshButton = findViewById(R.id.refreshButton);
        settingsButton = findViewById(R.id.settingsButton);
        syncPendingBanner = findViewById(R.id.syncPendingBanner);
        syncPendingText = findViewById(R.id.syncPendingText);
        syncPendingSendNowButton = findViewById(R.id.syncPendingSendNowButton);
        if (syncPendingSendNowButton != null) {
            syncPendingSendNowButton.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                forceSendQueueNow();
            });
        }

        JSONObject config = App.appConfig;
        homeUrl = config.optString("app_url", getString(R.string.app_url));
        filecameraEnabled = config.optBoolean("filecamera_enabled", true);
        kioskEnabled = config.optBoolean("kiosk_enabled", false);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        geofencingClient = LocationServices.getGeofencingClient(this);

        String shortcutUrl = getIntent() != null ? getIntent().getStringExtra("shortcut_url") : null;
        String deepLinkUrl = resolveDeepLinkUrl(getIntent());
        String startUrl = homeUrl;
        if (deepLinkUrl != null) {
            startUrl = deepLinkUrl;
        } else if (shortcutUrl != null && !shortcutUrl.isEmpty()) {
            startUrl = shortcutUrl;
        }
        currentActiveUrl = startUrl;

        offlineCacheDir = new File(getFilesDir(), "webcache");
        if (!offlineCacheDir.exists()) offlineCacheDir.mkdirs();
        maxCacheBytes = computeCacheSizeLimit();
        prefs = getSharedPreferences("webapp2apk_prefs", MODE_PRIVATE);

        setupActivityResultLaunchers();
        setupWebView();
        updateSyncBanner(getQueueSize());
        setupDownloadListener();
        setupShareButton();
        setupRefreshButton();
        setupSettingsButton();
        setupConnectivityBanner();
        setupBottomTabs();
        checkForAppUpdate();
        maybeShowLockScreen();
        PeriodicRefreshWorker.scheduleIfNeeded(getApplicationContext());

        webView.loadUrl(startUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String deepLinkUrl = resolveDeepLinkUrl(intent);
        if (deepLinkUrl != null && webView != null) {
            currentActiveUrl = deepLinkUrl;
            webView.loadUrl(deepLinkUrl);
        }
        handleNfcIntentIfAny(intent);
    }

    /**
     * A VIEW Intent whose data Uri's host matches the web app's own domain
     * (see the AndroidManifest App Links intent-filter) is a deep link -
     * someone tapped a link to this app's site from outside the app
     * (WhatsApp, SMS, a search result) and Android routed it straight here
     * instead of a browser. Anything else (a plain launcher tap, the
     * dynamic-shortcut extra, a notification tap) has no data Uri and
     * this simply returns null so the normal home/shortcut URL logic
     * applies unchanged.
     */
    private String resolveDeepLinkUrl(Intent intent) {
        if (intent == null || intent.getData() == null) return null;
        Uri data = intent.getData();
        try {
            Uri homeUri = Uri.parse(homeUrl);
            if (homeUri.getHost() != null && homeUri.getHost().equalsIgnoreCase(data.getHost())) {
                return data.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        activeInstance = null;
        disableNfcForegroundDispatch();
        webView.onPause();
        webView.pauseTimers();
        CookieManager.getInstance().flush();
        OfflineQueueWorker.scheduleIfNeeded(getApplicationContext());
        stopWebViewWatchdog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeInstance = this;
        enableNfcForegroundDispatch();
        maybeEnterKioskMode();
        maybeShowLockScreen();
        webView.onResume();
        webView.resumeTimers();
        watchdogMissedBeats = 0;
        startWebViewWatchdog();
        if (prefs != null) prefs.edit().putInt("unread_notification_count", 0).apply();
        ConnectivityManager resumeCm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (resumeCm != null) {
            boolean nowOnline = isOnline(resumeCm);
            if (nowOnline != isCurrentlyOnline) {
                isCurrentlyOnline = nowOnline;
                updateOfflineBanner(isCurrentlyOnline);
            }
            if (isCurrentlyOnline && getQueueSize() > 0) {
                flushOfflineQueue();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (activeInstance == this) activeInstance = null;
        stopWebViewWatchdog();
        cancelQueueRetry();
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
        if (refreshButton != null) {
            refreshButton.animate().cancel();
        }
        if (prefetchExecutor != null) {
            prefetchExecutor.shutdownNow();
            prefetchExecutor = null;
        }
    }

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

        lockLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // No explicit handling needed either way: App.sessionUnlocked is
                    // what LockActivity actually sets on success, and the next
                    // onResume() re-checks AppLockManager.needsUnlock() and will show
                    // the lock screen again on its own if the person backed out of it
                    // without unlocking.
                    lockScreenShowing = false;
                });

        qrScanLauncher = registerForActivityResult(new ScanContract(), result -> {
            ScanIntentResult scanResult = result;
            if (scanResult.getContents() != null) {
                deliverQrResult(scanResult.getContents(), null);
            } else {
                deliverQrResult(null, "cancelled");
            }
        });

        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                grants -> {
                    boolean granted = Boolean.TRUE.equals(grants.get(Manifest.permission.ACCESS_FINE_LOCATION))
                            || Boolean.TRUE.equals(grants.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                    if (granted) {
                        runPendingLocationAction();
                    } else {
                        deliverLocationError("permission_denied");
                        pendingLocationAction = null;
                    }
                });
    }

    /**
     * Shows the App Lock gate on top of the WebView content when App Lock is
     * turned on and this session hasn't been unlocked yet. Safe to call from
     * both onCreate() and onResume() - lockScreenShowing prevents the two
     * from launching it twice for the same lock event, and AppLockManager
     * itself only asks for a real unlock once per "session" (see
     * App.sessionUnlocked).
     */
    private void maybeShowLockScreen() {
        if (lockScreenShowing) return;
        if (AppLockManager.needsUnlock(this)) {
            lockScreenShowing = true;
            lockLauncher.launch(new Intent(this, LockActivity.class));
        }
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
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }

        settings.setOffscreenPreRaster(true);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        webView.addJavascriptInterface(new OfflineQueueBridge(), "AndroidOfflineQueue");
        webView.addJavascriptInterface(new RetryBridge(), "AndroidRetry");
        webView.addJavascriptInterface(new CredentialBridge(), "AndroidCredentials");
        // Lets the web app itself trigger Android's native share sheet with
        // real content (an invoice line, a receipt PDF) - not just the
        // "share this page's URL" the floating share button already does.
        // Call from the page as: AndroidShare.shareText("...") or
        // AndroidShare.shareFileBase64(base64Data, "receipt.pdf", "application/pdf").
        webView.addJavascriptInterface(new ShareBridge(this), "AndroidShare");
        // Print bridge - window.print() on the site gets overridden (see
        // onPageFinished below) to call this instead.
        webView.addJavascriptInterface(new PrintBridge(this, webView), "AndroidPrint");
        // QR/barcode scanner - AndroidScanQR.scan() from the page's JS.
        webView.addJavascriptInterface(new QrScanBridge(this), "AndroidScanQR");
        // NFC tap bridge - AndroidNfc.isAvailable() / writeTextOnNextTap(...).
        webView.addJavascriptInterface(new NfcBridge(this), "AndroidNfc");
        // Device-only scheduled reminders, no server/push needed.
        webView.addJavascriptInterface(new LocalNotifyBridge(this), "AndroidNotify");
        // One-shot location + geofencing.
        webView.addJavascriptInterface(new LocationBridge(this), "AndroidLocation");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);


        webView.setWebViewClient(new WebViewClient() {
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
                    return openExternally(uri);
                }

                String path = uri.getPath();
                if (path != null && path.toLowerCase().contains("logout")) {
                    CredentialVault.clear(getApplicationContext());
                    pendingCredentials = null;
                    skipAutoLoginOnce = true;
                }

                Uri homeUri = Uri.parse(homeUrl);
                if (homeUri.getHost() != null && homeUri.getHost().equalsIgnoreCase(uri.getHost())) {
                    return false;
                }

                return openExternally(uri);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                progressBarIcon.setVisibility(View.VISIBLE);
                startProgressPulse();
                view.animate().cancel();
                view.setAlpha(0.3f);

                if (!shortcutsRegistered) {
                    shortcutsRegistered = true;
                    setupHomeScreenShortcuts();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (!request.isForMainFrame()) return;

                ConnectivityManager cmCheck = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                boolean actuallyOffline = cmCheck == null || !isOnline(cmCheck);

                if (!actuallyOffline) {
                    return;
                }

                if (isCurrentlyOnline) {
                    isCurrentlyOnline = false;
                    updateOfflineBanner(false);
                }

                // Read the cached copy directly and render it immediately,
                // rather than retrying the navigation through the network
                // stack again and hoping shouldInterceptRequest catches the
                // second attempt in time. That retry-based approach depended
                // on a second real network round trip landing before another
                // timeout/failure, which only won the race intermittently -
                // observed succeeding roughly 1 in 7 attempts - and otherwise
                // left Chromium's own "ERR_NAME_NOT_RESOLVED" page on screen.
                // Loading the bytes we already have on disk here removes
                // that race entirely: either the page is cached and appears
                // instantly, or it isn't and we go straight to offline.html.
                String failedUrl = request.getUrl().toString();
                String[] cachedPage = loadCachedPageDirect(Uri.parse(failedUrl));
                if (cachedPage != null) {
                    view.loadDataWithBaseURL(failedUrl, cachedPage[0], cachedPage[1], cachedPage[2], failedUrl);
                } else {
                    view.loadUrl("file:///android_asset/offline.html");
                }
            }

            /**
             * Synchronous, no-network read of whatever this app already has
             * cached for the given URL. Returns {html, mimeType, encoding},
             * or null if nothing is cached for it. Deliberately separate from
             * serveFromCacheFile (which returns a WebResourceResponse stream
             * for shouldInterceptRequest) since onReceivedError needs the
             * content as a String to hand to loadDataWithBaseURL instead.
             */
            private String[] loadCachedPageDirect(Uri uri) {
                String cacheKey = sha256(uri.toString());
                File bodyFile = new File(offlineCacheDir, cacheKey + ".body.gz");
                File metaFile = new File(offlineCacheDir, cacheKey + ".meta");
                if (!bodyFile.exists() || !metaFile.exists()) return null;

                try {
                    String[] meta = readMetaParts(metaFile);
                    String mimeType = meta != null && !meta[0].isEmpty() ? meta[0] : "text/html";
                    String encoding = meta != null && !meta[1].isEmpty() ? meta[1] : "UTF-8";
                    byte[] data = readAllBytes(new java.util.zip.GZIPInputStream(new FileInputStream(bodyFile)));
                    String html = new String(data, encoding);
                    return new String[]{html, mimeType, encoding};
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                Uri currentUri = url != null ? Uri.parse(url) : null;
                boolean cameFromLogin = lastPageUrl != null && isSensitiveUrl(Uri.parse(lastPageUrl));
                boolean nowOnNonLoginPage = currentUri == null || !isSensitiveUrl(currentUri);
                if (cameFromLogin && nowOnNonLoginPage) {
                    CookieManager.getInstance().flush();
                    lastCookieFlushTime = System.currentTimeMillis();
                    if (pendingCredentials != null) {
                        CredentialVault.save(getApplicationContext(), pendingCredentials[0], pendingCredentials[1]);
                        pendingCredentials = null;
                    }
                } else {
                    maybeFlushCookies();
                }
                lastPageUrl = url;

                injectOfflineQueueScript(view, url);
                injectPrintOverrideScript(view);

                final long visualStateRequestId = System.currentTimeMillis();
                final boolean[] revealed = {false};
                Runnable reveal = () -> {
                    if (revealed[0]) return;
                    revealed[0] = true;
                    progressBar.setVisibility(View.GONE);
                    progressBarIcon.setVisibility(View.GONE);
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

                cacheRetryInProgress = false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // Pull-to-refresh removed - nothing to update here anymore.
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

    private WebResourceResponse maybeServeFromOfflineCache(WebResourceRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return null;

        Uri uri = request.getUrl();
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return null;

        Uri homeUri = Uri.parse(homeUrl);
        if (homeUri.getHost() == null || !homeUri.getHost().equalsIgnoreCase(uri.getHost())) {
            return null;
        }

        if (isSensitiveUrl(uri)) {
            return null;
        }

        String cacheKey = sha256(uri.toString());
        File bodyFile = new File(offlineCacheDir, cacheKey + ".body.gz");
        File metaFile = new File(offlineCacheDir, cacheKey + ".meta");

        if (!isCurrentlyOnline) {
            return serveFromCacheFile(bodyFile, metaFile, uri);
        }

        // The OS can report a validated network even when a carrier is
        // transparently redirecting every request to its own page (e.g.
        // MTN's "no data bundle" walled garden) - that looks like a normal
        // successful page load to WebView's own network stack, since it's a
        // real 200 response, just from the wrong host. For the main page
        // navigation, route through our own connection (which follows
        // redirects itself) so we can check where it actually landed,
        // instead of trusting WebView's built-in fetch blindly.
        if (request.isForMainFrame()) {
            return fetchAndCache(uri, bodyFile, metaFile);
        }

        return null;
    }

    /**
     * True if actualHost is the same site as requestedHost, or a subdomain
     * of it (or vice versa) - e.g. "cdn.example.com" counts as the same
     * site as "example.com". Anything else (like a carrier's own domain) is
     * treated as an off-site redirect.
     */
    private boolean isSameSiteHost(String requestedHost, String actualHost) {
        if (requestedHost == null || actualHost == null) return false;
        if (requestedHost.equalsIgnoreCase(actualHost)) return true;
        String reqLower = requestedHost.toLowerCase();
        String actLower = actualHost.toLowerCase();
        return actLower.endsWith("." + reqLower) || reqLower.endsWith("." + actLower);
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

            // conn.getURL() reflects the final URL after HttpURLConnection
            // followed any redirects. If that landed on a completely
            // different site than what was requested, this almost
            // certainly isn't a real page from the app's own server - it's
            // a carrier/proxy walled-garden page (e.g. "buy a data bundle")
            // masquerading as a normal 200 response. Refuse it and serve
            // the cached copy instead of ever showing it.
            String finalHost = conn.getURL() != null ? conn.getURL().getHost() : null;
            if (!isSameSiteHost(uri.getHost(), finalHost)) {
                conn.disconnect();
                return serveFromCacheFile(bodyFile, metaFile, uri);
            }

            if (status == 304 && bodyFile.exists()) {
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

                Map<String, String> responseHeaders = new HashMap<>();
                responseHeaders.put("Cache-Control", "no-store");
                return new WebResourceResponse(mimeType, encoding, 200, "OK",
                        responseHeaders, new ByteArrayInputStream(data));
            } else {
                conn.disconnect();
            }
        } catch (Exception networkFailed) {
        }

        return serveFromCacheFile(bodyFile, metaFile, uri);
    }

    private String[] readMetaParts(File metaFile) {
        if (!metaFile.exists()) return null;
        try {
            String meta = new String(readAllBytes(new FileInputStream(metaFile)), "UTF-8");
            String[] parts = meta.split("\\|", -1);
            String[] result = new String[4];
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
                InputStream stream = new java.util.zip.GZIPInputStream(new FileInputStream(bodyFile));
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-store");
                return new WebResourceResponse(mimeType, encoding, 200, "OK", headers, stream);
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

    private long computeCacheSizeLimit() {
        try {
            android.os.StatFs stat = new android.os.StatFs(getFilesDir().getPath());
            long freeBytes = stat.getAvailableBytes();
            long budget = freeBytes / 20;
            return Math.max(MAX_CACHE_BYTES_FLOOR, Math.min(MAX_CACHE_BYTES_CAP, budget));
        } catch (Exception e) {
            return MAX_CACHE_BYTES_FLOOR;
        }
    }


    private static final long MAX_QUEUEABLE_FILE_BYTES = 4L * 1024 * 1024;

    private void injectOfflineQueueScript(WebView view, String url) {
        if (url == null) return;
        try {
            Uri uri = Uri.parse(url);
            Uri homeUri = Uri.parse(homeUrl);
            if (homeUri.getHost() == null || !homeUri.getHost().equalsIgnoreCase(uri.getHost())) return;
        } catch (Exception e) {
            return;
        }

        String script =
                "(function(){" +
                "if(window.__w2aQueueInstalled)return;" +
                "window.__w2aQueueInstalled=true;" +
                "var MAX_FILE_BYTES=" + MAX_QUEUEABLE_FILE_BYTES + ";" +
                "var ONLINE_COMPRESS_THRESHOLD_BYTES=600*1024;" +
                "var ONLINE_TARGET_BYTES=900*1024;" +
                "function fileToBase64(blob){" +
                "  return new Promise(function(resolve,reject){" +
                "    var reader=new FileReader();" +
                "    reader.onload=function(){resolve(reader.result.split(',')[1]);};" +
                "    reader.onerror=reject;" +
                "    reader.readAsDataURL(blob);" +
                "  });" +
                "}" +
                "function compressImageOnce(file,maxDim,quality){" +
                "  return new Promise(function(resolve){" +
                "    var url;" +
                "    try{url=URL.createObjectURL(file);}catch(e){resolve(file);return;}" +
                "    var img=new Image();" +
                "    img.onload=function(){" +
                "      try{" +
                "        var w=img.naturalWidth||img.width,h=img.naturalHeight||img.height;" +
                "        var scale=Math.min(1,maxDim/Math.max(w,h));" +
                "        var cw=Math.max(1,Math.round(w*scale)),ch=Math.max(1,Math.round(h*scale));" +
                "        var canvas=document.createElement('canvas');" +
                "        canvas.width=cw;canvas.height=ch;" +
                "        var ctx=canvas.getContext('2d');" +
                "        ctx.drawImage(img,0,0,cw,ch);" +
                "        URL.revokeObjectURL(url);" +
                "        canvas.toBlob(function(blob){resolve(blob||file);},'image/jpeg',quality);" +
                "      }catch(e){URL.revokeObjectURL(url);resolve(file);}" +
                "    };" +
                "    img.onerror=function(){URL.revokeObjectURL(url);resolve(file);};" +
                "    img.src=url;" +
                "  });" +
                "}" +
                "function compressImageUntilFits(file,maxBytes){" +
                "  var attempts=[[1600,0.82],[1280,0.72],[1024,0.62],[800,0.55]];" +
                "  var i=0,best=file;" +
                "  function step(){" +
                "    if(best.size<=maxBytes||i>=attempts.length)return Promise.resolve(best);" +
                "    var opt=attempts[i++];" +
                "    return compressImageOnce(file,opt[0],opt[1]).then(function(blob){" +
                "      if(blob&&blob.size<best.size)best=blob;" +
                "      return step();" +
                "    });" +
                "  }" +
                "  return step();" +
                "}" +
                "function serializeFormData(formData){" +
                "  var entries=[];" +
                "  formData.forEach(function(value,key){entries.push([key,value]);});" +
                "  return Promise.all(entries.map(function(pair){" +
                "    var key=pair[0],value=pair[1];" +
                "    if(value instanceof File){" +
                "      if(value.size===0)return null;" +
                "      var isImage=value.type&&value.type.indexOf('image/')===0;" +
                "      var prep=(isImage&&value.size>MAX_FILE_BYTES)" +
                "        ?compressImageUntilFits(value,MAX_FILE_BYTES)" +
                "        :Promise.resolve(value);" +
                "      return prep.then(function(finalBlob){" +
                "        if(!finalBlob||finalBlob.size>MAX_FILE_BYTES){" +
                "          return {key:key,type:'file_too_large',name:value.name};" +
                "        }" +
                "        return fileToBase64(finalBlob).then(function(b64){" +
                "          return {key:key,type:'file',name:value.name,mime:(finalBlob.type||value.type),data:b64};" +
                "        });" +
                "      });" +
                "    }" +
                "    return {key:key,type:'text',value:String(value)};" +
                "  })).then(function(list){return list.filter(function(x){return x;});});" +
                "}" +
                "function queueSubmission(url,method,fields,enctype){" +
                // Guards against exactly the kind of bug that produces a
                // permanently-stuck sync item: some page code accidentally
                // passing a stringified object ("{}", "[object Object]")
                // instead of a real URL. A garbage string like "{}" is
                // NOT caught by just try/catching new URL() - resolved
                // against the current page it's a perfectly legal relative
                // path ("https://site/%7B%7D") and would silently queue an
                // item that can never actually be delivered. Checking the
                // raw value is a string that doesn't start with '{' or '['
                // catches that case before it ever reaches the queue.
                "  if(typeof url!=='string'||!url||url.charAt(0)==='{'||url.charAt(0)==='['){" +
                "    console.warn('w2a offline queue: refusing to queue invalid url',url);" +
                "    return false;" +
                "  }" +
                "  var resolved;" +
                "  try{resolved=new URL(url,location.href).href;}catch(e){resolved=null;}" +
                "  if(!resolved){" +
                "    console.warn('w2a offline queue: refusing to queue invalid url',url);" +
                "    return false;" +
                "  }" +
                "  var payload=JSON.stringify({url:resolved,method:method,enctype:enctype,fields:fields,ts:Date.now()});" +
                "  if(window.AndroidOfflineQueue&&window.AndroidOfflineQueue.enqueue){" +
                "    window.AndroidOfflineQueue.enqueue(payload);" +
                "    return true;" +
                "  }" +
                "  return false;" +
                "}" +
                "function w2aIsOffline(){" +
                "  if(window.AndroidOfflineQueue&&window.AndroidOfflineQueue.isOnline){" +
                "    try{return !window.AndroidOfflineQueue.isOnline();}catch(e){}" +
                "  }" +
                "  return !navigator.onLine;" +
                "}" +
                "document.addEventListener('submit',function(e){" +
                "  var form=e.target;" +
                "  if(!(form instanceof HTMLFormElement))return;" +
                "  if(w2aIsOffline()){" +
                "    e.preventDefault();" +
                "    var formData=new FormData(form);" +
                "    var method=(form.method||'POST').toUpperCase();" +
                "    var url=form.action||window.location.href;" +
                "    var enctype=form.enctype||'application/x-www-form-urlencoded';" +
                "    serializeFormData(formData).then(function(fields){" +
                "      var queued=queueSubmission(url,method,fields,enctype);" +
                "      if(queued&&window.AndroidOfflineQueue&&window.AndroidOfflineQueue.onQueued){" +
                "        window.AndroidOfflineQueue.onQueued();" +
                "      }else if(!queued&&window.AndroidOfflineQueue&&window.AndroidOfflineQueue.onQueueFailed){" +
                "        window.AndroidOfflineQueue.onQueueFailed();" +
                "      }" +
                "    });" +
                "    return;" +
                "  }" +
                "  var fileInputs=form.querySelectorAll('input[type=file]');" +
                "  var toCompress=[];" +
                "  fileInputs.forEach(function(input){" +
                "    if(!input.files||!input.files.length)return;" +
                "    for(var i=0;i<input.files.length;i++){" +
                "      var f=input.files[i];" +
                "      if(f.type&&f.type.indexOf('image/')===0&&f.size>ONLINE_COMPRESS_THRESHOLD_BYTES){" +
                "        toCompress.push({input:input,index:i,file:f});" +
                "      }" +
                "    }" +
                "  });" +
                "  if(toCompress.length===0||typeof DataTransfer==='undefined')return;" +
                "  e.preventDefault();" +
                "  Promise.all(toCompress.map(function(item){" +
                "    return compressImageUntilFits(item.file,ONLINE_TARGET_BYTES).then(function(blob){" +
                "      item.newFile=new File([blob],item.file.name,{type:(blob.type||item.file.type)});" +
                "    });" +
                "  })).then(function(){" +
                "    var byInput=new Map();" +
                "    toCompress.forEach(function(item){" +
                "      if(!byInput.has(item.input))byInput.set(item.input,[]);" +
                "      byInput.get(item.input).push(item);" +
                "    });" +
                "    byInput.forEach(function(items,input){" +
                "      var repl={};" +
                "      items.forEach(function(it){repl[it.index]=it.newFile;});" +
                "      var dt=new DataTransfer();" +
                "      for(var i=0;i<input.files.length;i++){" +
                "        dt.items.add(repl[i]||input.files[i]);" +
                "      }" +
                "      input.files=dt.files;" +
                "    });" +
                "    form.submit();" +
                "  }).catch(function(){form.submit();});" +
                "},true);" +
                "function w2aQueueBody(url,method,body,fallbackEnctype){" +
                "  var p;" +
                "  if(typeof FormData!=='undefined'&&body instanceof FormData){" +
                "    p=serializeFormData(body).then(function(fields){" +
                "      return queueSubmission(url,method,fields,'multipart/form-data');" +
                "    });" +
                "  }else if(typeof URLSearchParams!=='undefined'&&body instanceof URLSearchParams){" +
                "    var fields=[];" +
                "    body.forEach(function(value,key){fields.push({key:key,type:'text',value:String(value)});});" +
                "    p=Promise.resolve(queueSubmission(url,method,fields,'application/x-www-form-urlencoded'));" +
                "  }else if(typeof body==='string'&&body.length>0){" +
                "    p=Promise.resolve(queueSubmission(url,method,[{key:'body',type:'text',value:body}],'raw'));" +
                "  }else{" +
                "    p=Promise.resolve(true);" +
                "  }" +
                "  return p.then(function(queued){" +
                "    if(queued&&window.AndroidOfflineQueue&&window.AndroidOfflineQueue.onQueued){" +
                "      window.AndroidOfflineQueue.onQueued();" +
                "    }else if(!queued&&window.AndroidOfflineQueue&&window.AndroidOfflineQueue.onQueueFailed){" +
                "      window.AndroidOfflineQueue.onQueueFailed();" +
                "    }" +
                "    return queued;" +
                "  });" +
                "}" +
                "var originalFetch=window.fetch;" +
                "if(originalFetch){" +
                "  window.fetch=function(input,init){" +
                "    init=init||{};" +
                "    var method=(init.method||'GET').toUpperCase();" +
                "    if(method==='GET'||!w2aIsOffline()){return originalFetch(input,init);}" +
                "    var url;" +
                // This only ever runs on the offline path (see the branch
                // above) - fetch(input) accepts a plain string, a Request
                // object (which has .url), or a URL object (which has
                // .href, NOT .url - that gap is exactly what caused a
                // legitimate fetch(someUrlObject, ...) call to get silently
                // rejected as "invalid" only while offline, even though
                // the exact same call works fine online since the browser's
                // real fetch() handles all three natively). Falling back to
                // String(input) as a last resort covers anything else
                // (e.g. a custom object with a toString()) rather than
                // giving up outright.
                "    if(typeof input==='string'){url=input;}" +
                "    else if(input&&typeof input.url==='string'){url=input.url;}" +
                "    else if(input&&typeof input.href==='string'){url=input.href;}" +
                "    else if(input){url=String(input);}" +
                "    else{url='';}" +
                "    return w2aQueueBody(url,method,init.body,'raw').then(function(){" +
                // Resolve as if the request succeeded, rather than rejecting the
                // promise. The change is safely queued on-device and will really be
                // sent the moment the connection comes back - from the page's point
                // of view it should look and behave exactly like being online, with
                // the offline banner (not a broken save/submit flow) as the only cue
                // that anything is different.
                "      return new Response(JSON.stringify({queued:true,offline:true})," +
                "        {status:200,statusText:'OK (queued offline)'," +
                "         headers:{'Content-Type':'application/json'}});" +
                "    });" +
                "  };" +
                "}" +
                "var OrigXHR=window.XMLHttpRequest;" +
                "if(OrigXHR){" +
                "  var origOpen=OrigXHR.prototype.open;" +
                "  var origSend=OrigXHR.prototype.send;" +
                "  var origSetHeader=OrigXHR.prototype.setRequestHeader;" +
                "  OrigXHR.prototype.open=function(method,url){" +
                "    this.__w2aMethod=(method||'GET').toUpperCase();" +
                "    if(typeof url==='string'){this.__w2aUrl=url;}" +
                "    else if(url&&typeof url.href==='string'){this.__w2aUrl=url.href;}" +
                "    else if(url){this.__w2aUrl=String(url);}" +
                "    else{this.__w2aUrl='';}" +
                "    return origOpen.apply(this,arguments);" +
                "  };" +
                "  OrigXHR.prototype.setRequestHeader=function(name,value){" +
                "    return origSetHeader.apply(this,arguments);" +
                "  };" +
                "  OrigXHR.prototype.send=function(body){" +
                "    var self=this;" +
                "    var method=this.__w2aMethod||'GET';" +
                "    if(method==='GET'||!w2aIsOffline()){return origSend.apply(this,arguments);}" +
                "    var url=this.__w2aUrl||'';" +
                "    w2aQueueBody(url,method,body,'raw').then(function(){" +
                "      setTimeout(function(){" +
                // Report the queued request as a normal 200 OK, not a failure - the
                // page's existing success handling (redirects, toasts, UI updates)
                // then runs exactly as it would online, while the real network call
                // happens silently later once connectivity is back.
                "        var fakeBody='{\"queued\":true,\"offline\":true}';" +
                "        try{Object.defineProperty(self,'readyState',{value:4,configurable:true});}catch(e){}" +
                "        try{Object.defineProperty(self,'status',{value:200,configurable:true});}catch(e){}" +
                "        try{Object.defineProperty(self,'statusText',{value:'OK (queued offline)',configurable:true});}catch(e){}" +
                "        try{Object.defineProperty(self,'response',{value:fakeBody,configurable:true});}catch(e){}" +
                "        try{Object.defineProperty(self,'responseText',{value:fakeBody,configurable:true});}catch(e){}" +
                "        if(typeof self.onreadystatechange==='function')self.onreadystatechange();" +
                "        if(typeof self.onload==='function')self.onload(new ProgressEvent('load'));" +
                "        try{self.dispatchEvent(new ProgressEvent('load'));}catch(e){}" +
                "      },0);" +
                "    });" +
                "  };" +
                "}" +
                "(function(){" +
                "  var pwField=document.querySelector('input[type=password]');" +
                "  if(!pwField)return;" +
                "  var form=pwField.form;" +
                "  if(!form)return;" +
                "  function pickUserField(){" +
                "    return form.querySelector('input[type=email]')||" +
                "      form.querySelector('input[type=text]')||" +
                "      form.querySelector('input:not([type=password]):not([type=hidden]):not([type=submit]):not([type=checkbox])');" +
                "  }" +
                "  if(!window.__w2aAutoLoginTried&&window.AndroidCredentials){" +
                "    window.__w2aAutoLoginTried=true;" +
                "    try{" +
                "      var savedRaw=window.AndroidCredentials.getSaved();" +
                "      if(savedRaw){" +
                "        var saved=JSON.parse(savedRaw);" +
                "        var userField=pickUserField();" +
                "        if(userField&&!userField.value&&!pwField.value){" +
                "          userField.value=saved.u;" +
                "          pwField.value=saved.p;" +
                "          var remember=form.querySelector('input[type=checkbox]');" +
                "          if(remember&&!remember.checked)remember.checked=true;" +
                "          setTimeout(function(){" +
                "            if(form.requestSubmit)form.requestSubmit();else form.submit();" +
                "          },50);" +
                "        }" +
                "      }" +
                "    }catch(e){}" +
                "  }" +
                "  if(!form.__w2aCaptureBound){" +
                "    form.__w2aCaptureBound=true;" +
                "    form.addEventListener('submit',function(){" +
                "      try{" +
                "        var userField=pickUserField();" +
                "        if(userField&&pwField.value&&window.AndroidCredentials){" +
                "          window.AndroidCredentials.capture(userField.value,pwField.value);" +
                "        }" +
                "      }catch(e){}" +
                "    },true);" +
                "  }" +
                "})();" +
                "})();";

        view.evaluateJavascript(script, null);
    }

    /**
     * Redirects window.print() to AndroidPrint.printPage() so a site's
     * existing "Print" button (using the standard, ubiquitous window.print()
     * API) works with zero changes on the site's side. Installed once per
     * page load, guarded the same way injectOfflineQueueScript is.
     */
    private void injectPrintOverrideScript(WebView view) {
        String script =
                "(function(){" +
                "if(window.__w2aPrintInstalled)return;" +
                "window.__w2aPrintInstalled=true;" +
                "if(window.AndroidPrint){" +
                "  window.print=function(){ AndroidPrint.printPage(); };" +
                "}" +
                "})();";
        view.evaluateJavascript(script, null);
    }

    // ---------------------------------------------------------------
    // QR / barcode scanner (window.AndroidScanQR - see QrScanBridge.java)
    // ---------------------------------------------------------------

    void launchQrScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            // Same CAMERA permission the WebView's own file/camera capture
            // inputs already use (declared once in the manifest) - this is
            // just a separate runtime prompt for it, triggered the first
            // time the page actually calls AndroidScanQR.scan().
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 9001);
            return;
        }
        ScanOptions options = new ScanOptions();
        options.setBeepEnabled(true);
        options.setOrientationLocked(false);
        options.setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity.class);
        qrScanLauncher.launch(options);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 9001) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                launchQrScanner();
            } else {
                deliverQrResult(null, "permission_denied");
            }
        }
    }

    private void deliverQrResult(String text, String error) {
        if (webView == null) return;
        String script;
        if (text != null) {
            script = "window.onQRScanResult && window.onQRScanResult(" + jsQuote(text) + ");";
        } else {
            script = "window.onQRScanError && window.onQRScanError(" + jsQuote(error) + ");";
        }
        webView.evaluateJavascript(script, null);
    }

    private static String jsQuote(String value) {
        if (value == null) value = "";
        try {
            return org.json.JSONObject.quote(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    // ---------------------------------------------------------------
    // NFC tap bridge (window.AndroidNfc - see NfcBridge.java)
    // ---------------------------------------------------------------

    boolean isNfcAvailable() {
        return nfcAdapter != null && nfcAdapter.isEnabled();
    }

    void armPendingNfcWrite(String text) {
        pendingNfcWriteText = text;
        showSnackbar("Tap a tag now to write to it");
    }

    private void enableNfcForegroundDispatch() {
        if (nfcAdapter == null) return;
        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_MUTABLE);
        try {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);
        } catch (Exception ignored) {
            // Some OEM NFC stacks throw if NFC was just toggled off in
            // Settings - never worth crashing over.
        }
    }

    private void disableNfcForegroundDispatch() {
        if (nfcAdapter == null) return;
        try {
            nfcAdapter.disableForegroundDispatch(this);
        } catch (Exception ignored) {
        }
    }

    private void handleNfcIntentIfAny(Intent intent) {
        if (intent == null || nfcAdapter == null) return;
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return;

        if (pendingNfcWriteText != null) {
            String toWrite = pendingNfcWriteText;
            pendingNfcWriteText = null;
            boolean wrote = writeTextToTag(tag, toWrite);
            showSnackbar(wrote ? "Tag written" : "Could not write to this tag");
            deliverNfcTag(toWrite, tag);
            return;
        }

        NdefMessage[] messages = null;
        Parcelable[] raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (raw != null) {
            messages = new NdefMessage[raw.length];
            for (int i = 0; i < raw.length; i++) messages[i] = (NdefMessage) raw[i];
        }
        String text = (messages != null && messages.length > 0) ? NfcBridge.readTextFrom(messages[0]) : "";
        deliverNfcTag(text, tag);
    }

    private boolean writeTextToTag(Tag tag, String text) {
        NdefMessage message = NfcBridge.buildTextMessage(text);
        try {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                ndef.writeNdefMessage(message);
                ndef.close();
                return true;
            }
            NdefFormatable formatable = NdefFormatable.get(tag);
            if (formatable != null) {
                formatable.connect();
                formatable.format(message);
                formatable.close();
                return true;
            }
        } catch (Exception ignored) {
            // Tag pulled away mid-write, read-only tag, unsupported tech -
            // all treated the same: the write just didn't happen.
        }
        return false;
    }

    private void deliverNfcTag(String text, Tag tag) {
        if (webView == null) return;
        String tagId = NfcBridge.tagIdToHex(tag);
        String script = "window.onNfcTag && window.onNfcTag(" + jsQuote(text) + "," + jsQuote(tagId) + ");";
        webView.evaluateJavascript(script, null);
    }

    // ---------------------------------------------------------------
    // Kiosk mode (screen pinning) - build-time toggle, kiosk_enabled
    // ---------------------------------------------------------------

    /**
     * Screen pinning (Activity.startLockTask()) needs no special permission
     * or device-owner setup - any app can call it. The person can always
     * exit it via Android's own back+recents-button hold gesture, which is
     * a system-level escape hatch this code has no control over and isn't
     * trying to remove.
     */
    private void maybeEnterKioskMode() {
        if (!kioskEnabled) return;
        try {
            startLockTask();
        } catch (Exception ignored) {
            // Already pinned, or this OEM's launcher blocks it - either way,
            // not worth crashing over.
        }
    }

    // ---------------------------------------------------------------
    // Location & geofencing (window.AndroidLocation - see LocationBridge.java)
    // ---------------------------------------------------------------

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void runPendingLocationAction() {
        if (pendingLocationAction != null) {
            Runnable action = pendingLocationAction;
            pendingLocationAction = null;
            action.run();
        }
    }

    void requestCurrentLocation() {
        if (!hasLocationPermission()) {
            pendingLocationAction = this::requestCurrentLocation;
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        try {
            CancellationTokenSource cancelSource = new CancellationTokenSource();
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .build();
            fusedLocationClient.getCurrentLocation(request, cancelSource.getToken())
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            deliverLocationResult(location.getLatitude(), location.getLongitude(), location.getAccuracy());
                        } else {
                            deliverLocationError("unavailable");
                        }
                    })
                    .addOnFailureListener(e -> deliverLocationError("failed"));
        } catch (SecurityException e) {
            deliverLocationError("permission_denied");
        }
    }

    private void deliverLocationResult(double lat, double lng, float accuracy) {
        if (webView == null) return;
        String script = "window.onLocationResult && window.onLocationResult(" + lat + "," + lng + "," + accuracy + ");";
        webView.evaluateJavascript(script, null);
    }

    private void deliverLocationError(String reason) {
        if (webView == null) return;
        webView.evaluateJavascript("window.onLocationError && window.onLocationError(" + jsQuote(reason) + ");", null);
    }

    private PendingIntent geofencePendingIntent() {
        Intent intent = new Intent(this, GeofenceBroadcastReceiver.class);
        // Play Services writes the triggering geofence data into this
        // Intent's extras at fire time, so it must be mutable (required
        // explicitly since Android 12).
        return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    void registerGeofence(String id, double lat, double lng, float radiusMeters) {
        if (id == null || id.isEmpty()) return;
        if (!hasLocationPermission()) {
            pendingLocationAction = () -> registerGeofence(id, lat, lng, radiusMeters);
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        Geofence geofence = new Geofence.Builder()
                .setRequestId(id)
                .setCircularRegion(lat, lng, Math.max(radiusMeters, 20f))
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
                .build();
        GeofencingRequest request = new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build();
        try {
            geofencingClient.addGeofences(request, geofencePendingIntent());
        } catch (SecurityException ignored) {
            deliverLocationError("permission_denied");
        }
    }

    void unregisterGeofence(String id) {
        if (id == null || id.isEmpty() || geofencingClient == null) return;
        geofencingClient.removeGeofences(Collections.singletonList(id));
    }

    /** Called by GeofenceBroadcastReceiver when this Activity is the live foreground instance. */
    void deliverGeofenceEvent(String id, boolean entered) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String script = "window.onGeofenceEvent && window.onGeofenceEvent(" + jsQuote(id) + "," + entered + ");";
            webView.evaluateJavascript(script, null);
        });
    }

    private class RetryBridge {
        @android.webkit.JavascriptInterface
        public void retry() {
            runOnUiThread(MainActivity.this::attemptRealRefresh);
        }
    }

    private void attemptRealRefresh() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        boolean nowOnline = cm != null && isOnline(cm);
        isCurrentlyOnline = nowOnline;
        updateOfflineBanner(nowOnline);

        String currentUrl = webView.getUrl();
        boolean showingOfflinePlaceholder = currentUrl != null
                && currentUrl.startsWith("file:///android_asset/offline.html");

        if (showingOfflinePlaceholder && lastConfirmedCacheMissUrl != null) {
            webView.loadUrl(lastConfirmedCacheMissUrl);
        } else {
            webView.reload();
        }

        if (nowOnline && getQueueSize() > 0) {
            flushOfflineQueue();
        }
    }

    private class CredentialBridge {
        @android.webkit.JavascriptInterface
        public void capture(String username, String password) {
            pendingCredentials = new String[]{username, password};
        }

        @android.webkit.JavascriptInterface
        public String getSaved() {
            if (skipAutoLoginOnce) {
                skipAutoLoginOnce = false;
                return null;
            }
            String[] creds = CredentialVault.get(getApplicationContext());
            if (creds == null) return null;
            try {
                JSONObject o = new JSONObject();
                o.put("u", creds[0]);
                o.put("p", creds[1]);
                return o.toString();
            } catch (Exception e) {
                return null;
            }
        }
    }

    private class OfflineQueueBridge {
        @android.webkit.JavascriptInterface
        public void enqueue(String json) {
            queueSubmission(json);
        }

        @android.webkit.JavascriptInterface
        public boolean isOnline() {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            return cm != null && MainActivity.this.isOnline(cm);
        }

        @android.webkit.JavascriptInterface
        public void onQueued() {
            runOnUiThread(() -> {
                updateSyncBanner(getQueueSize());
                showSnackbar("Saved - will send once you're back online");
            });
            OfflineQueueWorker.scheduleIfNeeded(getApplicationContext());
        }

        @android.webkit.JavascriptInterface
        public void onQueueFailed() {
            // The page tried to save something offline, but queueSubmission()
            // rejected it (an invalid/garbage URL, almost always a bug in the
            // page's own AJAX call rather than a real network problem) -
            // this is the one case where offline queuing genuinely can't
            // help, so it's better to say so plainly than to look like the
            // save silently succeeded.
            runOnUiThread(() -> showSnackbar("Could not save this - please try again"));
        }
    }

    private void queueSubmission(String json) {
        OfflineQueueSync.queueSubmission(this, json);
    }

    private int getQueueSize() {
        return OfflineQueueSync.getQueueSize(this);
    }

    private void updateSyncBanner(int pendingCount) {
        if (syncPendingBanner == null) return;
        if (pendingCount <= 0) {
            syncPendingBanner.setVisibility(View.GONE);
            return;
        }
        String label = pendingCount == 1
                ? "1 item waiting to sync"
                : pendingCount + " items waiting to sync";
        if (syncPendingText != null) syncPendingText.setText(label);
        syncPendingBanner.setVisibility(View.VISIBLE);
    }

    private void forceSendQueueNow() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        boolean nowOnline = cm != null && isOnline(cm);
        isCurrentlyOnline = nowOnline;
        updateOfflineBanner(nowOnline);

        if (!nowOnline) {
            showSnackbar("Still no connection - will send automatically once you're back online");
            return;
        }
        showSnackbar("Sending now\u2026");
        flushOfflineQueue();
    }

    private void flushOfflineQueue() {
        new Thread(() -> {
            OfflineQueueSync.FlushResult result = OfflineQueueSync.flush(this);
            final int finalSucceeded = result.succeeded;
            final int finalRemaining = result.remaining;
            final int finalDropped = result.dropped;
            final String lastError = result.lastErrorMessage;
            runOnUiThread(() -> {
                updateSyncBanner(finalRemaining);
                if (finalSucceeded > 0) {
                    showSnackbar(finalSucceeded == 1 ? "1 saved item sent" : finalSucceeded + " saved items sent");
                } else if (finalDropped > 0) {
                    // Distinct from a normal send failure: this item could
                    // never have succeeded no matter how many times it was
                    // retried (a broken URL, corrupt queued data), so it's
                    // been discarded rather than left to fail forever on
                    // every future Retry tap. Logged with the original
                    // error so it's still traceable if this keeps
                    // happening for the same page action.
                    showSnackbar(finalDropped == 1
                            ? "1 item couldn't be sent and was discarded"
                            : finalDropped + " items couldn't be sent and were discarded");
                    android.util.Log.w("OfflineQueue", "Dropped unrecoverable item(s): " + lastError);
                } else if (finalRemaining > 0 && lastError != null) {
                    // Previously silent on failure - now shows exactly why so
                    // it doesn't look like nothing happened when it actually
                    // tried and failed (e.g. expired session, wrong URL,
                    // server error).
                    showSnackbar("Send failed: " + lastError);
                    android.util.Log.w("OfflineQueue", "Flush failed: " + lastError);
                }
                if (finalRemaining > 0) {
                    OfflineQueueWorker.scheduleIfNeeded(getApplicationContext());
                    if (isCurrentlyOnline) scheduleQueueRetry();
                } else {
                    cancelQueueRetry();
                }
            });
        }).start();
    }

    private void scheduleQueueRetry() {
        cancelQueueRetryCallbackOnly();
        int idx = Math.min(flushRetryAttempt, FLUSH_RETRY_DELAYS_MS.length - 1);
        long delay = FLUSH_RETRY_DELAYS_MS[idx];
        flushRetryAttempt++;
        pendingQueueRetry = () -> {
            if (isCurrentlyOnline) flushOfflineQueue();
        };
        queueRetryHandler.postDelayed(pendingQueueRetry, delay);
    }

    private void cancelQueueRetry() {
        cancelQueueRetryCallbackOnly();
        flushRetryAttempt = 0;
    }

    private void cancelQueueRetryCallbackOnly() {
        if (pendingQueueRetry != null) {
            queueRetryHandler.removeCallbacks(pendingQueueRetry);
            pendingQueueRetry = null;
        }
    }

    private void maybeFlushCookies() {
        long now = System.currentTimeMillis();
        if (now - lastCookieFlushTime < COOKIE_FLUSH_MIN_INTERVAL_MS) return;
        lastCookieFlushTime = now;
        CookieManager.getInstance().flush();
    }

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
                }
            });
        }
    }

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

    private void setupDownloadListener() {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            long now = System.currentTimeMillis();
            if (url.equals(lastDownloadUrl) && (now - lastDownloadTime) < DOWNLOAD_DEBOUNCE_MS) {
                return;
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

    private void setupShareButton() {
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

        makeDraggable(shareButton, "share_btn");
    }

    private void setupRefreshButton() {
        refreshButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            attemptRealRefresh();
        });

        makeDraggable(refreshButton, "refresh_btn");
    }

    private void setupSettingsButton() {
        settingsButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            startActivity(new Intent(this, SettingsActivity.class));
        });

        makeDraggable(settingsButton, "settings_btn");
    }

    private void makeDraggable(View view, String prefsKeyPrefix) {
        if (prefs.contains(prefsKeyPrefix + "_x")) {
            view.post(() -> {
                float x = prefs.getFloat(prefsKeyPrefix + "_x", view.getX());
                float y = prefs.getFloat(prefsKeyPrefix + "_y", view.getY());
                view.setX(x);
                view.setY(y);
            });
        }

        final float[] touchOffsetX = new float[1];
        final float[] touchOffsetY = new float[1];
        final float[] downRawX = new float[1];
        final float[] downRawY = new float[1];
        final boolean[] isDragging = {false};

        view.setOnTouchListener((v, event) -> {
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
                        prefs.edit()
                                .putFloat(prefsKeyPrefix + "_x", v.getX())
                                .putFloat(prefsKeyPrefix + "_y", v.getY())
                                .apply();
                    } else {
                        v.performClick();
                    }
                    return true;

                default:
                    return false;
            }
        });
    }

    private boolean openExternally(Uri uri) {
        String scheme = uri.getScheme();
        boolean isWebScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        // Non-http(s) links (tel:, mailto:, intent:, upi:, whatsapp:// deep
        // links, mobile money app schemes, etc.) have to go through a plain
        // Intent - a Custom Tab can only ever open a web URL.
        if (isWebScheme && CustomTabsHelper.open(this, uri, CustomTabsHelper.resolveToolbarColor(this))) {
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void applySystemThemeBackground() {
        int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDarkMode = uiMode == Configuration.UI_MODE_NIGHT_YES;
        webView.setBackgroundColor(isDarkMode ? Color.parseColor("#171A21") : Color.WHITE);
    }

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

                String letter = label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase();
                IconCompat icon = createShortcutIcon(letter, getShortcutColor(i));

                ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(this, "tab_" + i)
                        .setShortLabel(label)
                        .setIcon(icon)
                        .setIntent(shortcutIntent)
                        .build();
                shortcuts.add(shortcut);
            }

            if (!shortcuts.isEmpty()) {
                ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts);
            }
        } catch (Exception ignored) {
        }
    }

    private int getShortcutColor(int index) {
        int baseColor = ContextCompat.getColor(this, R.color.accent_color);
        float[] hsv = new float[3];
        Color.colorToHSV(baseColor, hsv);
        hsv[0] = (hsv[0] + index * 55f) % 360f;
        return Color.HSVToColor(hsv);
    }

    private IconCompat createShortcutIcon(String letter, int color) {
        int size = 108;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(color);

        android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(size * 0.5f);
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        android.graphics.Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(letter, size / 2f, textY, textPaint);

        return IconCompat.createWithAdaptiveBitmap(bitmap);
    }

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
            }
        }).start();
    }

    private void showUpdateBanner(String apkUrl) {
        updateBanner.setVisibility(View.VISIBLE);
        updateBanner.setOnClickListener(v -> downloadAndInstallUpdate(apkUrl));
    }

    /**
     * Downloads the new APK in the background via DownloadManager and hands
     * it straight to the system installer when done - previously this just
     * opened the APK's bare URL externally, which on most devices only
     * opens a browser tab rather than actually installing anything.
     */
    private void downloadAndInstallUpdate(String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            // The person has to explicitly grant "install unknown apps" for
            // this app once - Android requires this to be a deliberate,
            // visible permission grant, it can't be requested silently.
            Intent settingsIntent = new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(settingsIntent);
                showSnackbar("Allow installs from this app, then tap Update again");
            } catch (Exception e) {
                showSnackbar("Could not open install-permission settings");
            }
            return;
        }

        showSnackbar("Downloading update...");

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "update.apk");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setTitle(getString(R.string.app_name) + " update");

        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) {
            showSnackbar("Could not start download");
            return;
        }
        long expectedId = dm.enqueue(request);

        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (completedId != expectedId) return;
                try {
                    unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                installDownloadedUpdate();
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void installDownloadedUpdate() {
        File apkFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
        if (!apkFile.exists()) {
            showSnackbar("Update download failed");
            return;
        }

        Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", apkFile);

        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(installIntent);
        } catch (Exception e) {
            showSnackbar("Could not open the installer");
        }
    }

    private void setupConnectivityBanner() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;

        isCurrentlyOnline = isOnline(cm);
        updateOfflineBanner(isCurrentlyOnline);
        if (isCurrentlyOnline && getQueueSize() > 0) {
            flushOfflineQueue();
        } else if (getQueueSize() > 0) {
            OfflineQueueWorker.scheduleIfNeeded(getApplicationContext());
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            private boolean isActiveNetwork(Network network) {
                Network active = cm.getActiveNetwork();
                return active != null && active.equals(network);
            }

            @Override
            public void onAvailable(Network network) {
                // Only trust this if it's actually the network the device
                // is using right now - a phone can have a second network
                // (e.g. a weak/idle Wi-Fi alongside cellular) whose events
                // have nothing to do with what's actually being used, and
                // reacting to those was flipping the banner backwards.
                if (!isActiveNetwork(network)) return;

                // Don't just assume "available" means truly online - a
                // network can appear (radio connected) before Android has
                // finished validating it actually reaches the internet.
                boolean nowOnline = isOnline(cm);
                boolean wasOffline = !isCurrentlyOnline;
                isCurrentlyOnline = nowOnline;
                runOnUiThread(() -> {
                    updateOfflineBanner(nowOnline);
                    if (nowOnline && wasOffline) showSnackbar("Back online");
                });
                if (nowOnline) flushOfflineQueue();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                // Same reasoning as onAvailable: ignore capability changes
                // on any network other than the one actually in use.
                if (!isActiveNetwork(network)) return;

                // The radio connection can stay up the entire time while
                // NET_CAPABILITY_VALIDATED silently flips - e.g. a data
                // bundle running out mid-session, or a carrier walled
                // garden appearing. Neither onAvailable nor onLost fires in
                // that case since the network itself never actually
                // connects or disconnects - this is the only callback that
                // catches it, so the offline banner and queue reflect
                // reality within moments instead of staying wrong until the
                // next full network change.
                boolean nowOnline = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                boolean changed = nowOnline != isCurrentlyOnline;
                boolean wasOffline = !isCurrentlyOnline;
                isCurrentlyOnline = nowOnline;
                if (!changed) return;
                runOnUiThread(() -> {
                    updateOfflineBanner(nowOnline);
                    if (nowOnline) {
                        if (wasOffline) showSnackbar("Back online");
                        flushOfflineQueue();
                    } else {
                        cancelQueueRetry();
                    }
                });
            }

            @Override
            public void onLosing(Network network, int maxMsToLive) {
                // Same reasoning: a secondary network fading out shouldn't
                // flip the banner if the device's actual default network is
                // fine.
                if (!isActiveNetwork(network)) return;

                // Android's own early warning that this network is about to
                // go away, fired before the actual disconnect - switch to
                // offline mode proactively instead of waiting for onLost.
                isCurrentlyOnline = false;
                runOnUiThread(() -> {
                    updateOfflineBanner(false);
                    cancelQueueRetry();
                });
            }

            @Override
            public void onLost(Network network) {
                isCurrentlyOnline = isOnline(cm);
                runOnUiThread(() -> {
                    updateOfflineBanner(isCurrentlyOnline);
                    if (!isCurrentlyOnline) cancelQueueRetry();
                });
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
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false;
        }
        // NET_CAPABILITY_INTERNET only means the network SHOULD provide
        // internet based on its transport type - it stays true even when a
        // carrier's walled-garden/captive-portal page (e.g. a "no data
        // bundle" landing page) is intercepting every request instead of
        // real internet actually being reachable. NET_CAPABILITY_VALIDATED
        // is Android's own background check that a real connection to the
        // internet succeeded, and correctly goes false in exactly that
        // situation.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void updateOfflineBanner(boolean online) {
        if (pendingBannerUpdate != null) bannerDebounceHandler.removeCallbacks(pendingBannerUpdate);
        pendingBannerUpdate = () -> offlineBanner.setVisibility(online ? View.GONE : View.VISIBLE);
        bannerDebounceHandler.postDelayed(pendingBannerUpdate, online ? 600 : 0);

        // Keep the widget's status line in sync with whatever the in-app
        // banner is showing, without the widget needing the app process
        // alive to know this.
        WebAppWidgetProvider.setLastOnline(getApplicationContext(), online);

        if (!online) {
            scheduleReachabilityRecheck();
        }
    }

    private volatile boolean reachabilityProbeInFlight = false;

    /**
     * NET_CAPABILITY_VALIDATED (isOnline()'s primary signal) is right the
     * overwhelming majority of the time, but two real gaps are worth
     * covering rather than just trusting it blindly: some OEM network
     * stacks are slow to flip VALIDATED right after a network change, and a
     * carrier walled-garden/expired-data-bundle page can occasionally still
     * leave VALIDATED true briefly (or leave it false longer than the radio
     * is actually usable). A few seconds after the banner goes to
     * "offline", this fires one real HTTP probe against the app's own
     * domain - the only actual ground truth for "will this app work right
     * now" - and if it succeeds where the OS signal said it wouldn't, it
     * corrects the banner and flushes the queue immediately instead of
     * waiting for the next network callback (which, in a walled-garden
     * case, might never come, since the radio never actually changes
     * state).
     */
    private void scheduleReachabilityRecheck() {
        if (reachabilityProbeInFlight) return;
        reachabilityProbeInFlight = true;
        bannerDebounceHandler.postDelayed(() -> {
            new Thread(() -> {
                boolean reachable = NetworkReachability.probe(getApplicationContext());
                reachabilityProbeInFlight = false;
                if (!reachable || isCurrentlyOnline) return;
                runOnUiThread(() -> {
                    boolean wasOffline = !isCurrentlyOnline;
                    isCurrentlyOnline = true;
                    updateOfflineBanner(true);
                    if (wasOffline) showSnackbar("Back online");
                    flushOfflineQueue();
                });
            }).start();
        }, 3000);
    }

    private static final long WATCHDOG_INTERVAL_MS = 12_000;
    private static final long WATCHDOG_ANSWER_TIMEOUT_MS = 7_000;
    private static final int WATCHDOG_MISSED_BEATS_BEFORE_WARNING = 2;
    private static final long WATCHDOG_SNACKBAR_COOLDOWN_MS = 60_000;

    /**
     * A hung WebView renderer (a runaway page script, a rare WebView-process
     * hiccup that doesn't always surface as onRenderProcessGone on every
     * Android version) otherwise just looks like a frozen white/blank
     * screen with no feedback and no way out except force-closing the whole
     * app. Every WATCHDOG_INTERVAL_MS this asks the page a trivial question
     * (its own title) - a healthy renderer answers within milliseconds. If
     * two heartbeats in a row never get an answer within the timeout, the
     * renderer is genuinely stuck, not just doing something slow once, and
     * a "Reload" Snackbar gives the person a way out that doesn't require
     * killing the app.
     */
    private void startWebViewWatchdog() {
        watchdogTick = () -> {
            if (webView == null) return;
            final boolean[] answered = {false};
            try {
                webView.evaluateJavascript("document.title||''", value -> answered[0] = true);
            } catch (Exception e) {
                answered[0] = true; // evaluateJavascript itself failing isn't a hang.
            }
            watchdogHandler.postDelayed(() -> {
                if (answered[0]) {
                    watchdogMissedBeats = 0;
                } else {
                    watchdogMissedBeats++;
                    if (watchdogMissedBeats >= WATCHDOG_MISSED_BEATS_BEFORE_WARNING) {
                        maybeShowHungPageWarning();
                    }
                }
                watchdogHandler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS);
            }, WATCHDOG_ANSWER_TIMEOUT_MS);
        };
        watchdogHandler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS);
    }

    private void stopWebViewWatchdog() {
        if (watchdogTick != null) {
            watchdogHandler.removeCallbacks(watchdogTick);
        }
    }

    private void maybeShowHungPageWarning() {
        long now = System.currentTimeMillis();
        if (now - watchdogSnackbarShownAt < WATCHDOG_SNACKBAR_COOLDOWN_MS) return;
        watchdogSnackbarShownAt = now;
        watchdogMissedBeats = 0;

        View root = findViewById(android.R.id.content);
        Snackbar snackbar = Snackbar.make(root, "This page isn't responding", Snackbar.LENGTH_INDEFINITE);
        snackbar.setBackgroundTint(ContextCompat.getColor(this, R.color.primary_dark_color));
        snackbar.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        snackbar.setAction("Reload", v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            attemptRealRefresh();
        });
        snackbar.setActionTextColor(ContextCompat.getColor(this, R.color.accent_color));
        snackbar.show();
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
            tabContainer.setContentDescription(label);
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
