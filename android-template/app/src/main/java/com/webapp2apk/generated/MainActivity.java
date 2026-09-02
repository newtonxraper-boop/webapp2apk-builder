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

        setupActivityResultLaunchers();
        setupWebView();
        setupDownloadListener();
        setupShareButton();
        setupConnectivityBanner();
        setupSwipeRefresh();
        setupBottomTabs();
        setupHomeScreenShortcuts();
        checkForAppUpdate();

        webView.loadUrl(startUrl);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(networkCallback);
                } catch (Exception ignored) {
                }
            }
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
                progressBar.setVisibility(View.GONE);
                stopProgressPulse();
                view.animate().alpha(1f).setDuration(250).start();
                swipeRefresh.setRefreshing(false);
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

        String cacheKey = sha256(uri.toString());
        File bodyFile = new File(offlineCacheDir, cacheKey + ".body");
        File metaFile = new File(offlineCacheDir, cacheKey + ".meta");

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; WebApp2Apk offline cache)");

            CookieManager cookieManager = CookieManager.getInstance();
            String cookie = cookieManager.getCookie(uri.toString());
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                Map<String, List<String>> headers = conn.getHeaderFields();
                List<String> setCookies = headers.get("Set-Cookie");
                if (setCookies != null) {
                    for (String sc : setCookies) cookieManager.setCookie(uri.toString(), sc);
                    cookieManager.flush();
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

                byte[] data = readAllBytes(conn.getInputStream());
                conn.disconnect();

                writeFileQuietly(bodyFile, data);
                writeFileQuietly(metaFile, (mimeType + "|" + encoding).getBytes("UTF-8"));
                enforceCacheSizeLimit();

                return new WebResourceResponse(mimeType, encoding, new ByteArrayInputStream(data));
            } else {
                conn.disconnect();
            }
        } catch (Exception networkFailed) {
            // fall through to the cached copy below
        }

        if (bodyFile.exists() && metaFile.exists()) {
            try {
                String meta = new String(readAllBytes(new FileInputStream(metaFile)), "UTF-8");
                String[] parts = meta.split("\\|", 2);
                String mimeType = parts.length > 0 ? parts[0] : "text/html";
                String encoding = parts.length > 1 ? parts[1] : "UTF-8";
                return new WebResourceResponse(mimeType, encoding, new FileInputStream(bodyFile));
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

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private void enforceCacheSizeLimit() {
        File[] files = offlineCacheDir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= maxCacheBytes) return;

        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= maxCacheBytes) break;
            total -= f.length();
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
        swipeRefresh.setOnRefreshListener(() -> webView.loadUrl(homeUrl));
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
                    View parent = (View) v.getParent();
                    float newX = event.getRawX() + touchOffsetX[0];
                    float newY = event.getRawY() + touchOffsetY[0];
                    newX = Math.max(0, Math.min(newX, parent.getWidth() - v.getWidth()));
                    newY = Math.max(0, Math.min(newY, parent.getHeight() - v.getHeight()));
                    v.setX(newX);
                    v.setY(newY);

                    float moved = Math.abs(event.getRawX() - downRawX[0]) + Math.abs(event.getRawY() - downRawY[0]);
                    if (moved > dpToPx(8)) isDragging[0] = true;
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
        android.content.SharedPreferences prefs = getSharedPreferences("webapp2apk_prefs", MODE_PRIVATE);
        if (!prefs.contains("share_btn_x")) return; // keep the default XML corner position

        shareButton.post(() -> {
            float x = prefs.getFloat("share_btn_x", shareButton.getX());
            float y = prefs.getFloat("share_btn_y", shareButton.getY());
            shareButton.setX(x);
            shareButton.setY(y);
        });
    }

    private void saveShareButtonPosition(float x, float y) {
        getSharedPreferences("webapp2apk_prefs", MODE_PRIVATE)
                .edit()
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

        updateOfflineBanner(isOnline(cm));

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> updateOfflineBanner(true));
            }

            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> updateOfflineBanner(isOnline(cm)));
            }
        };

        try {
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception ignored) {
            networkCallback = null;
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
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void applyRippleForeground(View view) {
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        if (outValue.resourceId != 0) {
            view.setForeground(ContextCompat.getDrawable(this, outValue.resourceId));
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

                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setShape(GradientDrawable.OVAL);
                badgeBg.setColor(ContextCompat.getColor(this, R.color.accent_color));
                badgeBg.setAlpha(50);
                iconView.setBackground(badgeBg);
            }

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
     * swapping icon/label colors with no motion.
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
        }
    }

    private JSONArray loadNavItems() {
        try (InputStream is = getAssets().open("nav_items.json")) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
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
