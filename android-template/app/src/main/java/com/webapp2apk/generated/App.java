package com.webapp2apk.generated;

import android.app.Application;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class App extends Application {

    public static final String FIREBASE_INSTANCE = "webapp2apk-shared";
    public static JSONObject appConfig = new JSONObject();

    @Override
    public void onCreate() {
        super.onCreate();
        loadAppConfig();
        maybeInitFirebase();
    }

    private void loadAppConfig() {
        try (InputStream is = getAssets().open("app_config.json")) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            appConfig = new JSONObject(sb.toString());
        } catch (Exception e) {
            appConfig = new JSONObject();
        }
    }

    private void maybeInitFirebase() {
        boolean pushEnabled = appConfig.optBoolean("push_enabled", false);
        if (!pushEnabled) return;

        String apiKey = getString(R.string.firebase_api_key);
        String appId = getString(R.string.firebase_app_id);
        String projectId = getString(R.string.firebase_project_id);
        String senderId = getString(R.string.firebase_sender_id);

        if (apiKey.startsWith("{{") || appId.startsWith("{{")) {
            // Firebase secrets were never configured on the build server - skip silently.
            return;
        }

        FirebaseOptions options = new FirebaseOptions.Builder()
                .setApiKey(apiKey)
                .setApplicationId(appId)
                .setProjectId(projectId)
                .setGcmSenderId(senderId)
                .build();

        FirebaseApp app;
        try {
            app = FirebaseApp.initializeApp(this, options, FIREBASE_INSTANCE);
        } catch (IllegalStateException alreadyInitialized) {
            app = FirebaseApp.getInstance(FIREBASE_INSTANCE);
        }

        String requestId = appConfig.optString("request_id", "");
        FirebaseMessaging messaging = app.get(FirebaseMessaging.class);
        if (!requestId.isEmpty()) {
            messaging.subscribeToTopic("app_" + requestId);
        }
        messaging.subscribeToTopic("broadcast_all");
    }
}
