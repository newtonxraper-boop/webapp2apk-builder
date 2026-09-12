# Keep our own generated app classes - Application/Activity/Service are
# referenced by name from AndroidManifest.xml, and JSON-config-driven code
# paths aren't always visible to R8's static analysis.
-keep class com.webapp2apk.generated.** { *; }

# WebView JavaScript interfaces must keep their annotated methods to remain
# callable from page JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Referenced only from AndroidManifest.xml, not from direct Java code.
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService

# Defensive keep for JSON parsing.
-keep class org.json.** { *; }

-dontwarn org.jetbrains.annotations.**
