package com.webapp2apk.generated;

import android.content.Context;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * NET_CAPABILITY_VALIDATED (what isOnline() in MainActivity relies on as its
 * primary signal) is Android's own background check, and it's usually
 * right - but it isn't universal ground truth. A few real situations it
 * gets wrong or gets to slowly:
 *
 *   - Some OEM network stacks (seen on a number of budget Android builds
 *     common in the region this app targets) mark a mobile-data network
 *     VALIDATED off a generic connectivity-check host, even while an
 *     MTN/Airtel data bundle has actually run out and every other request
 *     is being redirected to an operator "top up now" page.
 *   - Right after a network change, VALIDATED can lag a few seconds behind
 *     the radio actually being connected - the banner would flicker
 *     "offline" briefly even though the connection is fine.
 *
 * This is a direct HTTP HEAD (falling back to GET, since some servers 405
 * on HEAD) against the web app's OWN domain rather than a generic
 * connectivity-check endpoint - it's the one host that actually matters for
 * "will this app work right now", and it doubles as a real warm-up request
 * for the domain's DNS + TLS handshake. Always call this off the main
 * thread; it does a blocking network call with a short timeout.
 */
final class NetworkReachability {

    private static final int TIMEOUT_MS = 4000;

    private NetworkReachability() {
    }

    static boolean probe(Context context) {
        String appUrl = context.getString(R.string.app_url);
        if (appUrl == null || appUrl.isEmpty() || appUrl.startsWith("{{")) {
            return false;
        }
        return probe(appUrl);
    }

    static boolean probe(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(true);
            int status = conn.getResponseCode();
            if (status == 405 || status == 501) {
                // Server doesn't support HEAD - retry with a real GET
                // before giving up, rather than reporting a false offline.
                conn.disconnect();
                conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestMethod("GET");
                status = conn.getResponseCode();
            }
            // Anything that isn't a hard connection failure counts as
            // "reachable" here, including 4xx/5xx from the app itself - a
            // 500 error page still proves the network path and DNS/TLS to
            // the domain work, which is what this probe exists to confirm;
            // it's the offline queue's job to decide whether a specific
            // request actually succeeded, not this reachability check.
            return status > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
