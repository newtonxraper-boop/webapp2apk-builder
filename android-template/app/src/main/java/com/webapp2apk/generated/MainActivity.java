package com.webapp2apk.generated;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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

    private String homeUrl;
    private boolean filecameraEnabled;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<String[]> cameraMicPermissionLauncher;
    private PermissionRequest pendingWebPermissionRequest;

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
    private static final long MAX_CACHE_BYTES = 25L * 1024 * 1024; // 25 MB cap
    private File offlineCacheDir;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressBar = findViewById(R.id.progressBar);
        bottomTabBar = findViewById(R.id.bottomTabBar);

        JSONObject config = App.appConfig;
        homeUrl = config.optString("app_url", getString(R.string.app_url));
        filecameraEnabled = config.optBoolean("filecamera_enabled", true);
        currentActiveUrl = homeUrl;

        offlineCacheDir = new File(getFilesDir(), "webcache");
        if (!offlineCacheDir.exists()) offlineCacheDir.mkdirs();

        setupActivityResultLaunchers();
        setupWebView();
        setupSwipeRefresh();
        setupBottomTabs();

        webView.loadUrl(homeUrl);
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);

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
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (!request.isForMainFrame()) return;

                if (!cacheRetryInProgress) {
                    cacheRetryInProgress = true;
                    view.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ONLY);
                    view.loadUrl(request.getUrl().toString());
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
                return null;
            }
        }

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
        if (total <= MAX_CACHE_BYTES) return;

        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_CACHE_BYTES) break;
            total -= f.length();
            f.delete();
        }
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener(() -> webView.loadUrl(homeUrl));
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
            bottomTabBar.setVisibility(View.GONE);
            return;
        }

        bottomTabBar.setVisibility(View.VISIBLE);
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
                currentActiveUrl = url;
                webView.loadUrl(url);
                refreshTabHighlighting();
            });

            bottomTabBar.addView(tabContainer);
            tabContainers.add(tabContainer);
            tabUrls.add(url);
        }

        refreshTabHighlighting();
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
        } else {
            super.onBackPressed();
        }
    }
}
