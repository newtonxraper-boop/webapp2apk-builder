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

    private static final int MAX_SAME_HOST_REDIRECTS = 3;

    static boolean probe(String urlString) {
        try {
            return probeOnce(urlString, "HEAD", true, MAX_SAME_HOST_REDIRECTS);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean probeOnce(String urlString, String method, boolean allowMethodFallback, int redirectsLeft) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod(method);
            // Handle redirects ourselves instead of letting the platform
            // follow them silently. A captive portal (MTN's expired-data
            // "top up" page, an airport Wi-Fi login page, etc.) works by
            // transparently redirecting every request to ITS OWN domain -
            // if we just followed that redirect and saw a 200 come back, as
            // the default auto-follow behavior would, we'd wrongly report
            // "reachable" precisely in the one situation this probe exists
            // to catch. A redirect is only trusted as a real, benign
            // same-site redirect (http->https, /->/login, etc.) when its
            // target host matches the host we actually asked for.
            conn.setInstanceFollowRedirects(false);

            int status = conn.getResponseCode();

            if (status == 405 || status == 501) {
                if (allowMethodFallback) {
                    conn.disconnect();
                    return probeOnce(urlString, "GET", false, redirectsLeft);
                }
                return false;
            }

            if (status >= 300 && status < 400) {
                if (redirectsLeft <= 0) return false;
                String location = conn.getHeaderField("Location");
                if (location == null) return false;
                String redirectHost;
                try {
                    redirectHost = new URL(url, location).getHost();
                } catch (Exception e) {
                    return false;
                }
                String originalHost = url.getHost();
                if (redirectHost == null || originalHost == null || !redirectHost.equalsIgnoreCase(originalHost)) {
                    // Redirected off to a different domain entirely - the
                    // captive-portal / DNS-hijack signature. The real site
                    // is not actually reachable right now.
                    return false;
                }
                // Same-host redirect (e.g. http -> https) - follow it once
                // ourselves and evaluate that response instead.
                return probeOnce(new URL(url, location).toString(), method, allowMethodFallback, redirectsLeft - 1);
            }

            // Anything else that isn't a hard connection failure counts as
            // "reachable" here, including 4xx/5xx from the app itself - a
            // 500 error page still proves the network path and DNS/TLS to
            // the domain work, which is what this probe exists to confirm;
            // it's the offline queue's job to decide whether a specific
            // request actually succeeded, not this reachability check.
            return status > 0;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
