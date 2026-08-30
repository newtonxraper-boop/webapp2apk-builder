package com.webapp2apk.generated;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

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
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    view.loadUrl("file:///android_asset/offline.html");
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
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

    private void setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener(() -> webView.loadUrl(homeUrl));
    }

    private void setupBottomTabs() {
        JSONArray navItems = loadNavItems();
        if (navItems == null || navItems.length() == 0) {
            bottomTabBar.setVisibility(View.GONE);
            return;
        }

        bottomTabBar.setVisibility(View.VISIBLE);
        bottomTabBar.removeAllViews();

        for (int i = 0; i < navItems.length(); i++) {
            JSONObject item = navItems.optJSONObject(i);
            if (item == null) continue;
            String label = item.optString("label", "Tab");
            String icon = item.optString("icon", "\u25CF");
            String url = item.optString("url", homeUrl);

            TextView tabView = new TextView(this);
            tabView.setText(icon + "\n" + label);
            tabView.setGravity(Gravity.CENTER);
            tabView.setTextColor(ContextCompat.getColor(this, R.color.tab_inactive));
            tabView.setTextSize(11);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            tabView.setLayoutParams(params);
            tabView.setOnClickListener(v -> webView.loadUrl(url));
            bottomTabBar.addView(tabView);
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
