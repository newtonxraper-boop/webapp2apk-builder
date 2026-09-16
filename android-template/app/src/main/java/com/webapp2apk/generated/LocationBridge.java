package com.webapp2apk.generated;

import android.webkit.JavascriptInterface;

/**
 * Exposed to the WebView as window.AndroidLocation. Call from the page as:
 *
 *   AndroidLocation.getCurrentPosition();
 *   window.onLocationResult = function(lat, lng, accuracyMeters) { ... };
 *   window.onLocationError  = function(reason) { ... };
 *
 *   AndroidLocation.startGeofence("store_1", 6.5244, 3.3792, 150);
 *   AndroidLocation.stopGeofence("store_1");
 *   window.onGeofenceEvent = function(id, entered) { ... }; // entered: true/false
 *
 * Geofence events are delivered to onGeofenceEvent only while this page is
 * open and the app is in the foreground/recently backgrounded - see
 * GeofenceBroadcastReceiver.java for what happens the rest of the time
 * (a system notification, plus the same callback fires next time the app
 * is opened). This bridge deliberately never requests background location
 * access - see the ACCESS_BACKGROUND_LOCATION comment in AndroidManifest.xml.
 */
final class LocationBridge {

    private final MainActivity activity;

    LocationBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void getCurrentPosition() {
        activity.runOnUiThread(activity::requestCurrentLocation);
    }

    @JavascriptInterface
    public void startGeofence(String id, double lat, double lng, float radiusMeters) {
        activity.runOnUiThread(() -> activity.registerGeofence(id, lat, lng, radiusMeters));
    }

    @JavascriptInterface
    public void stopGeofence(String id) {
        activity.runOnUiThread(() -> activity.unregisterGeofence(id));
    }
}
