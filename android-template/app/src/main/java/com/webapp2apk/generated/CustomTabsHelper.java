package com.webapp2apk.generated;

import android.content.Context;
import android.net.Uri;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

/**
 * Links that leave the web app's own domain (a payment gateway, a partner
 * site linked from content, a "read more" article link) used to hand off to
 * whatever the phone's default browser was via a bare ACTION_VIEW Intent -
 * that's a jarring full app-switch, and the person has to manually navigate
 * back afterwards. A Custom Tab instead slides in as a themed sheet on top
 * of the app, keeps the system back gesture/button returning straight into
 * the WebView, and still gets all of Chrome's real rendering, autofill, and
 * saved-password support - it's just presented as part of this app's flow
 * instead of a context switch away from it.
 */
final class CustomTabsHelper {

    private CustomTabsHelper() {
    }

    static boolean open(Context context, Uri uri, int toolbarColor) {
        try {
            CustomTabColorSchemeParams colorParams = new CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(toolbarColor)
                    .build();

            CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(colorParams)
                    .setShowTitle(true)
                    .setUrlBarHidingEnabled(true)
                    .build();

            customTabsIntent.launchUrl(context, uri);
            return true;
        } catch (Exception e) {
            // No Custom Tabs-capable browser installed (rare, but not
            // impossible on a stripped-down device) - the caller falls back
            // to a plain ACTION_VIEW Intent in that case.
            return false;
        }
    }

    static int resolveToolbarColor(Context context) {
        try {
            return ContextCompat.getColor(context, R.color.primary_dark_color);
        } catch (Exception e) {
            return 0xFF171A21;
        }
    }
}
