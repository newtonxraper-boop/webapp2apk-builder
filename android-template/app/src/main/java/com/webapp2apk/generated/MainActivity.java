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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
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
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressBar = findViewById(R.id.progressBar);
        progressBarIcon = findViewById(R.id.progressBarIcon);
        bottomTabBar = findViewById(R.id.bottomTabBar);
        bottomTabBarContainer = findViewById(R.id.bottomTabBarContainer);
        tabIndicator = findViewById(R.id.tabIndicator);
        tabNotch = findViewById(R.id.tabNotch);
        offlineBanner = findViewById(R.id.offlineBanner);
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

        String shortcutUrl = getIntent() != null ? getIntent().getStringExtra("shortcut_url") : null;
        String startUrl = (shortcutUrl != null && !shortcutUrl.isEmpty()) ? shortcutUrl : homeUrl;
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
        setupSwipeRefresh();
        setupBottomTabs();
        checkForAppUpdate();
        maybeShowLockScreen();

        webView.loadUrl(startUrl);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        webView.pauseTimers();
        CookieManager.getInstance().flush();
        OfflineQueueWorker.scheduleIfNeeded(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeShowLockScreen();
        webView.onResume();
        webView.resumeTimers();
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

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent_color));

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

                String failedUrl = request.getUrl().toString();
                boolean alreadyConfirmedMissing = failedUrl.equals(lastConfirmedCacheMissUrl);

                if (isCurrentlyOnline) {
                    isCurrentlyOnline = false;
                    updateOfflineBanner(false);
                }

                if (!cacheRetryInProgress && !alreadyConfirmedMissing) {
                    cacheRetryInProgress = true;
                    view.loadUrl(failedUrl);
                } else {
                    cacheRetryInProgress = false;
                    view.loadUrl("file:///android_asset/offline.html");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);

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

    private void setupSwipeRefresh() {
        swipeRefresh.setEnabled(false);
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
                "  var payload=JSON.stringify({url:url,method:method,enctype:enctype,fields:fields,ts:Date.now()});" +
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
                "      queueSubmission(url,method,fields,'multipart/form-data');" +
                "    });" +
                "  }else if(typeof URLSearchParams!=='undefined'&&body instanceof URLSearchParams){" +
                "    var fields=[];" +
                "    body.forEach(function(value,key){fields.push({key:key,type:'text',value:String(value)});});" +
                "    queueSubmission(url,method,fields,'application/x-www-form-urlencoded');" +
                "    p=Promise.resolve();" +
                "  }else if(typeof body==='string'&&body.length>0){" +
                "    queueSubmission(url,method,[{key:'body',type:'text',value:body}],'raw');" +
                "    p=Promise.resolve();" +
                "  }else{" +
                "    p=Promise.resolve();" +
                "  }" +
                "  return p.then(function(){" +
                "    if(window.AndroidOfflineQueue&&window.AndroidOfflineQueue.onQueued){" +
                "      window.AndroidOfflineQueue.onQueued();" +
                "    }" +
                "  });" +
                "}" +
                "var originalFetch=window.fetch;" +
                "if(originalFetch){" +
                "  window.fetch=function(input,init){" +
                "    init=init||{};" +
                "    var method=(init.method||'GET').toUpperCase();" +
                "    if(method==='GET'||!w2aIsOffline()){return originalFetch(input,init);}" +
                "    var url=typeof input==='string'?input:input.url;" +
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
                "    this.__w2aUrl=url;" +
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
            runOnUiThread(() -> {
                updateSyncBanner(finalRemaining);
                if (finalSucceeded > 0) {
                    showSnackbar(finalSucceeded == 1 ? "1 saved item sent" : finalSucceeded + " saved items sent");
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
        updateBanner.setOnClickListener(v -> openExternally(Uri.parse(apkUrl)));
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
            @Override
            public void onAvailable(Network network) {
                boolean wasOffline = !isCurrentlyOnline;
                isCurrentlyOnline = true;
                runOnUiThread(() -> {
                    updateOfflineBanner(true);
                    if (wasOffline) showSnackbar("Back online");
                });
                flushOfflineQueue();
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
