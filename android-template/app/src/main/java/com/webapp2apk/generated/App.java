package com.webapp2apk.generated;

import android.app.Application;
import android.os.Bundle;
import com.google.android.material.color.DynamicColors;
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

    // Whether App Lock has already been satisfied for the app's current time
    // in the foreground. Starts false so a lock-enabled app always challenges
    // on its very first launch; startedActivityCount below is what flips it
    // back to false again the moment the whole app (every one of its
    // activities, not just MainActivity) leaves the foreground - that's the
    // actual "the person could have handed the unlocked phone to someone
    // else" moment App Lock exists to guard, not any single activity
    // transition or rotation.
    public static volatile boolean sessionUnlocked = false;

    private int startedActivityCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        loadAppConfig();
        CrashReporter.install(this, appConfig.optString("crash_report_url", ""));
        registerActivityLifecycleCallbacks(new ActivityLifecycleTracker());
        // On Android 12+ (Material You devices) this tints each activity's
        // Material components - buttons, the settings screen's toolbar,
        // switches - with colors extracted from the user's own wallpaper,
        // on top of the app's own primary/accent branding underneath. It's
        // a genuine no-op everywhere else (older Android, or a device whose
        // OEM skin doesn't expose dynamic colors), so it's always safe to
        // register unconditionally rather than gating it on a build flag.
        DynamicColors.applyToActivitiesIfAvailable(this);
        // Deferred to the next main-thread loop iteration so Firebase SDK
        // initialization never blocks the very first frame the user sees -
        // appConfig itself is still loaded synchronously above since
        // Splash/MainActivity depend on it being ready immediately.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(this::maybeInitFirebase);
    }

    private final class ActivityLifecycleTracker implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityStarted(android.app.Activity activity) {
            startedActivityCount++;
        }

        @Override
        public void onActivityStopped(android.app.Activity activity) {
            startedActivityCount = Math.max(0, startedActivityCount - 1);
            if (startedActivityCount == 0) {
                sessionUnlocked = false;
            }
        }

        @Override public void onActivityCreated(android.app.Activity activity, Bundle savedInstanceState) {}
        @Override public void onActivityResumed(android.app.Activity activity) {}
        @Override public void onActivityPaused(android.app.Activity activity) {}
        @Override public void onActivitySaveInstanceState(android.app.Activity activity, Bundle outState) {}
        @Override public void onActivityDestroyed(android.app.Activity activity) {}
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
