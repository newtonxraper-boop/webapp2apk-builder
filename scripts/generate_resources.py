"""
Fills in the {{PLACEHOLDER}} tokens scattered across android-template/
(strings.xml, app_config.json, colors.xml, themes.xml, build.gradle, etc.)
using environment variables set by the GitHub Actions workflow - which in
turn come directly from trigger.php's workflow_dispatch payload.

Also handles the parts that aren't simple text substitution:
  - downloading the user's uploaded icon and generating all launcher
    mipmap densities (square + round)
  - decoding nav_items_b64 into assets/nav_items.json
"""

import base64
import io
import json
import os
import re
import urllib.request

TEMPLATE_ROOT = "android-template"

# Only touch text-based files; skip binary assets (png, keystore, etc.)
TEXT_EXTENSIONS = {".xml", ".gradle", ".json", ".java", ".properties", ".pro", ".txt", ".md"}

MIPMAP_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def sanitize_segment(text: str) -> str:
    """Make a single package-name segment a valid Java identifier."""
    seg = re.sub(r"[^a-zA-Z0-9_]", "", text).lower()
    if not seg or not (seg[0].isalpha() or seg[0] == "_"):
        seg = "a" + seg
    return seg


def derive_package_name(app_name: str) -> str:
    return f"com.webapp2apk.{sanitize_segment(app_name)}"


def validate_package_name(package_name: str) -> str:
    segments = [sanitize_segment(seg) for seg in package_name.split(".") if seg]
    if len(segments) < 2:
        segments = ["com", "webapp2apk"] + segments
    return ".".join(segments)


def sanitize_hex_color(value: str, default: str = "#3DDC84") -> str:
    value = (value or "").strip()
    if re.match(r"^#[0-9A-Fa-f]{6}$", value):
        return value.upper()
    return default


def darken_hex_color(hex_color: str, factor: float = 0.7) -> str:
    hex_color = hex_color.lstrip("#")
    try:
        r = int(hex_color[0:2], 16)
        g = int(hex_color[2:4], 16)
        b = int(hex_color[4:6], 16)
    except (ValueError, IndexError):
        return "#2BB673"
    r = max(0, min(255, int(r * factor)))
    g = max(0, min(255, int(g * factor)))
    b = max(0, min(255, int(b * factor)))
    return f"#{r:02X}{g:02X}{b:02X}"


def bool_literal(env_name: str, default: bool) -> str:
    """Read an env var meant to become a raw (unquoted) JSON boolean literal."""
    val = os.environ.get(env_name, "").strip().lower()
    if val in ("true", "1"):
        return "true"
    if val in ("false", "0"):
        return "false"
    return "true" if default else "false"


def write_nav_items(nav_items_b64: str) -> int:
    """Decode trigger.php's base64-encoded nav_items JSON and write it into
    assets/nav_items.json. Returns the number of tabs written."""
    path = os.path.join(TEMPLATE_ROOT, "app", "src", "main", "assets", "nav_items.json")
    items = []
    if nav_items_b64:
        try:
            decoded = base64.b64decode(nav_items_b64).decode("utf-8")
            parsed = json.loads(decoded)
            if isinstance(parsed, list):
                items = parsed[:5]
        except Exception as e:
            print(f"WARNING: could not decode NAV_ITEMS_B64, defaulting to no tabs: {e}")

    with open(path, "w", encoding="utf-8") as f:
        json.dump(items, f)

    print(f"nav_items.json written with {len(items)} tab(s).")
    return len(items)


def process_icon(icon_url: str) -> bool:
    """Download the uploaded icon and generate square + round launcher icons
    at every mipmap density. Returns True if a custom icon was applied."""
    if not icon_url:
        print("No icon_url provided - keeping default launcher icon.")
        return False

    try:
        req = urllib.request.Request(icon_url, headers={"User-Agent": "webapp2apk-builder"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read()
    except Exception as e:
        print(f"WARNING: could not download icon from {icon_url}: {e}")
        return False

    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("WARNING: Pillow is not installed - skipping icon processing. "
              "Add a 'pip install Pillow' step before this one in the workflow.")
        return False

    try:
        src = Image.open(io.BytesIO(raw)).convert("RGBA")
    except Exception as e:
        print(f"WARNING: downloaded icon is not a valid image: {e}")
        return False

    # Crop to square (centered) before resizing, in case the upload wasn't square.
    w, h = src.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    src = src.crop((left, top, left + side, top + side))

    for folder, size in MIPMAP_SIZES.items():
        dest_dir = os.path.join(TEMPLATE_ROOT, "app", "src", "main", "res", folder)
        os.makedirs(dest_dir, exist_ok=True)

        resized = src.resize((size, size), Image.LANCZOS)
        resized.save(os.path.join(dest_dir, "ic_launcher.png"))

        # Circular-masked variant for ic_launcher_round
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size, size), fill=255)
        round_icon = Image.new("RGBA", (size, size))
        round_icon.paste(resized, (0, 0), mask=mask)
        round_icon.save(os.path.join(dest_dir, "ic_launcher_round.png"))

    print("Custom app icon applied to all mipmap densities.")
    return True


def main():
    app_name = os.environ.get("APP_NAME", "WebApp").strip() or "WebApp"
    app_url = os.environ.get("APP_URL", "https://example.com").strip() or "https://example.com"
    request_id = os.environ.get("REQUEST_ID", "").strip()
    icon_url = os.environ.get("ICON_URL", "").strip()

    raw_package = os.environ.get("PACKAGE_NAME", "").strip()
    package_name = validate_package_name(raw_package) if raw_package else derive_package_name(app_name)

    primary_color = sanitize_hex_color(os.environ.get("PRIMARY_COLOR", ""), default="#3DDC84")
    accent_color = sanitize_hex_color(os.environ.get("ACCENT_COLOR", ""), default="#3DDC84")
    primary_dark_color = darken_hex_color(primary_color)

    splash_enabled = bool_literal("SPLASH_ENABLED", True)
    push_enabled = bool_literal("PUSH_ENABLED", False)
    filecamera_enabled = bool_literal("FILECAMERA_ENABLED", True)

    # Firebase config is optional. If not supplied, we deliberately LEAVE the
    # {{FIREBASE_*}} placeholders in strings.xml untouched - App.java checks
    # `apiKey.startsWith("{{")` and silently skips Firebase init in that case.
    firebase_env = {
        "{{FIREBASE_API_KEY}}": os.environ.get("FIREBASE_API_KEY", "").strip(),
        "{{FIREBASE_APP_ID}}": os.environ.get("FIREBASE_APP_ID", "").strip(),
        "{{FIREBASE_PROJECT_ID}}": os.environ.get("FIREBASE_PROJECT_ID", "").strip(),
        "{{FIREBASE_SENDER_ID}}": os.environ.get("FIREBASE_SENDER_ID", "").strip(),
    }

    replacements = {
        "{{APP_NAME}}": app_name,
        "{{APP_URL}}": app_url,
        "{{PACKAGE_NAME}}": package_name,
        "{{REQUEST_ID}}": request_id,
        "{{PRIMARY_COLOR}}": primary_color,
        "{{PRIMARY_DARK_COLOR}}": primary_dark_color,
        "{{ACCENT_COLOR}}": accent_color,
        "{{SPLASH_ENABLED}}": splash_enabled,
        "{{PUSH_ENABLED}}": push_enabled,
        "{{FILECAMERA_ENABLED}}": filecamera_enabled,
    }
    for placeholder, value in firebase_env.items():
        if value:
            replacements[placeholder] = value
        # else: intentionally leave placeholder in place

    updated_files = []
    for dirpath, _dirnames, filenames in os.walk(TEMPLATE_ROOT):
        for filename in filenames:
            if os.path.splitext(filename)[1] not in TEXT_EXTENSIONS:
                continue
            path = os.path.join(dirpath, filename)
            try:
                with open(path, "r", encoding="utf-8") as f:
                    content = f.read()
            except UnicodeDecodeError:
                continue

            if "{{" not in content:
                continue

            original = content
            for placeholder, value in replacements.items():
                content = content.replace(placeholder, value)

            if content != original:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(content)
                updated_files.append(path)

    nav_tab_count = write_nav_items(os.environ.get("NAV_ITEMS_B64", "").strip())
    icon_applied = process_icon(icon_url)

    print("Resources generated successfully under android-template/")
    print(f"App name: {app_name}")
    print(f"App URL: {app_url}")
    print(f"Package name (applicationId): {package_name}")
    print(f"Primary color: {primary_color}  Primary dark: {primary_dark_color}  Accent: {accent_color}")
    print(f"splash_enabled={splash_enabled} push_enabled={push_enabled} filecamera_enabled={filecamera_enabled}")
    print(f"Nav tabs: {nav_tab_count}")
    print(f"Custom icon applied: {icon_applied}")
    print(f"Text files updated: {len(updated_files)}")
    for f in updated_files:
        print(f"  - {f}")


if __name__ == "__main__":
    main()

