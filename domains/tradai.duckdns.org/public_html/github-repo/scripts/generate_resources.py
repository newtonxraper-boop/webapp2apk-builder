"""
generate_resources.py - run from inside android-template/ by the GitHub Actions
workflow. Reads settings from environment variables and writes the Android
resource files (strings.xml, colors.xml, manifest package name, app_config.json,
nav_items.json) for this specific build.
"""
import os
import re
import json
import base64
import html

app_name = os.environ.get("APP_NAME", "App")
app_url = os.environ.get("APP_URL", "https://example.com")
request_id = os.environ.get("REQUEST_ID", "unknown")
primary_color = os.environ.get("PRIMARY_COLOR") or "#3DDC84"
accent_color = os.environ.get("ACCENT_COLOR") or "#3DDC84"
splash_enabled = os.environ.get("SPLASH_ENABLED", "true").lower() == "true"
push_enabled = os.environ.get("PUSH_ENABLED", "false").lower() == "true"
filecamera_enabled = os.environ.get("FILECAMERA_ENABLED", "true").lower() == "true"

hex_re = re.compile(r'^#[0-9A-Fa-f]{6}$')
if not hex_re.match(primary_color):
    primary_color = "#3DDC84"
if not hex_re.match(accent_color):
    accent_color = "#3DDC84"

# sanitize a package name from the app name
safe = re.sub(r'[^a-z0-9]', '', app_name.lower()) or "app"
if safe[0].isdigit():
    # A Java package segment can't start with a digit - prefix it.
    safe = "a" + safe
package_name = f"com.webapp2apk.generated.{safe}"


def esc(s):
    return html.escape(s, quote=True)


# --- strings.xml ---------------------------------------------------------
with open("app/src/main/res/values/strings.xml", "w") as f:
    f.write(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        f'    <string name="app_name">{esc(app_name)}</string>\n'
        f'    <string name="app_url">{esc(app_url)}</string>\n'
        f'    <string name="firebase_api_key" translatable="false">{esc(os.environ.get("FIREBASE_API_KEY", ""))}</string>\n'
        f'    <string name="firebase_app_id" translatable="false">{esc(os.environ.get("FIREBASE_APP_ID", ""))}</string>\n'
        f'    <string name="firebase_project_id" translatable="false">{esc(os.environ.get("FIREBASE_PROJECT_ID", ""))}</string>\n'
        f'    <string name="firebase_sender_id" translatable="false">{esc(os.environ.get("FIREBASE_SENDER_ID", ""))}</string>\n'
        '</resources>\n'
    )

# --- colors.xml ------------------------------------------------------------
with open("app/src/main/res/values/colors.xml", "w") as f:
    f.write(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        f'    <color name="primary_color">{primary_color}</color>\n'
        f'    <color name="primary_dark_color">{primary_color}</color>\n'
        f'    <color name="accent_color">{accent_color}</color>\n'
        '    <color name="tab_inactive">#9AA0A6</color>\n'
        '    <color name="tab_bg">#171A21</color>\n'
        '</resources>\n'
    )

# --- package name substitution in manifest + build.gradle -------------------
for path in ["app/src/main/AndroidManifest.xml", "app/build.gradle"]:
    with open(path) as f:
        content = f.read()
    content = content.replace("{{PACKAGE_NAME}}", package_name)
    with open(path, "w") as f:
        f.write(content)

# --- if splash disabled, make MainActivity the launcher instead -------------
if not splash_enabled:
    manifest_path = "app/src/main/AndroidManifest.xml"
    with open(manifest_path) as f:
        m = f.read()

    old_block = (
        '<activity\n'
        '            android:name=".SplashActivity"\n'
        '            android:exported="true"\n'
        '            android:label="@string/app_name"\n'
        '            android:theme="@style/Theme.WebApp2Apk">\n'
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN" />\n'
        '                <category android:name="android.intent.category.LAUNCHER" />\n'
        '            </intent-filter>\n'
        '        </activity>\n\n'
        '        <activity\n'
        '            android:name=".MainActivity"\n'
        '            android:exported="false"\n'
        '            android:label="@string/app_name"\n'
        '            android:configChanges="orientation|screenSize|keyboardHidden" />'
    )
    new_block = (
        '<activity\n'
        '            android:name=".SplashActivity"\n'
        '            android:exported="false"\n'
        '            android:label="@string/app_name"\n'
        '            android:theme="@style/Theme.WebApp2Apk" />\n\n'
        '        <activity\n'
        '            android:name=".MainActivity"\n'
        '            android:exported="true"\n'
        '            android:label="@string/app_name"\n'
        '            android:configChanges="orientation|screenSize|keyboardHidden">\n'
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN" />\n'
        '                <category android:name="android.intent.category.LAUNCHER" />\n'
        '            </intent-filter>\n'
        '        </activity>'
    )
    if old_block in m:
        m = m.replace(old_block, new_block)
    with open(manifest_path, "w") as f:
        f.write(m)

# --- app_config.json asset ---------------------------------------------------
config = {
    "app_name": app_name,
    "app_url": app_url,
    "request_id": request_id,
    "push_enabled": push_enabled,
    "filecamera_enabled": filecamera_enabled,
    "splash_enabled": splash_enabled,
}
with open("app/src/main/assets/app_config.json", "w") as f:
    json.dump(config, f)

# --- nav_items.json asset ----------------------------------------------------
nav_b64 = os.environ.get("NAV_ITEMS_B64", "") or ""
nav_items = []
if nav_b64:
    try:
        decoded = base64.b64decode(nav_b64).decode("utf-8")
        parsed = json.loads(decoded)
        if isinstance(parsed, list):
            nav_items = [
                {
                    "label": str(i.get("label", "Tab"))[:20],
                    "icon": str(i.get("icon", "\u25CF"))[:4],
                    "url": str(i.get("url", app_url)),
                }
                for i in parsed if isinstance(i, dict)
            ][:5]
    except Exception:
        nav_items = []

with open("app/src/main/assets/nav_items.json", "w") as f:
    json.dump(nav_items, f)

print(f"Generated resources for package: {package_name}")
