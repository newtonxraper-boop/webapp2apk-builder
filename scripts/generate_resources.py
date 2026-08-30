"""
Fills in the {{PLACEHOLDER}} tokens scattered across android-template/
(strings.xml, app_config.json, build.gradle, etc.) using values passed in
as environment variables by the GitHub Actions workflow.

This walks the whole template tree instead of hand-editing individual files,
so any new placeholder added to any template file in the future is picked up
automatically without touching this script.
"""

import os
import re

TEMPLATE_ROOT = "android-template"

# Only touch text-based files; skip binary assets (png, keystore, etc.)
TEXT_EXTENSIONS = {".xml", ".gradle", ".json", ".java", ".properties", ".pro", ".txt", ".md"}


def sanitize_segment(text: str) -> str:
    """Make a single package-name segment a valid Java identifier."""
    seg = re.sub(r"[^a-zA-Z0-9_]", "", text).lower()
    if not seg or not (seg[0].isalpha() or seg[0] == "_"):
        seg = "a" + seg
    return seg


def derive_package_name(app_name: str) -> str:
    return f"com.webapp2apk.{sanitize_segment(app_name)}"


def validate_package_name(package_name: str) -> str:
    """Ensure every dot-separated segment of a (possibly user-supplied) package
    name is a valid Java identifier, fixing it up rather than failing the build."""
    segments = [sanitize_segment(seg) for seg in package_name.split(".") if seg]
    if len(segments) < 2:
        segments = ["com", "webapp2apk"] + segments
    return ".".join(segments)


def main():
    app_name = os.environ.get("APP_NAME", "WebApp").strip() or "WebApp"
    app_url = os.environ.get("APP_URL", "https://example.com").strip() or "https://example.com"
    request_id = os.environ.get("REQUEST_ID", "").strip()

    raw_package = os.environ.get("PACKAGE_NAME", "").strip()
    package_name = validate_package_name(raw_package) if raw_package else derive_package_name(app_name)

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

            # Fix legacy App.java Firebase initialization if present in the template source
            if "App.java" in filename:
                content = content.replace("FirebaseMessaging.getInstance(app)", "FirebaseMessaging.getInstance()")

            if "{{" not in content and "FirebaseMessaging.getInstance(" not in original_content_check_marker(content): # safe check
                pass

            original = content
            for placeholder, value in replacements.items():
                content = content.replace(placeholder, value)

            if content != original:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(content)
                updated_files.append(path)

    print("Resources generated successfully under android-template/")
    print(f"App name: {app_name}")
    print(f"App URL: {app_url}")
    print(f"Package name (applicationId): {package_name}")
    print(f"Files updated: {len(updated_files)}")
    for f in updated_files:
        print(f"  - {f}")


if __name__ == "__main__":
    main()
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

    print("Resources generated successfully under android-template/")
    print(f"App name: {app_name}")
    print(f"App URL: {app_url}")
    print(f"Package name (applicationId): {package_name}")
    print(f"Files updated: {len(updated_files)}")
    for f in updated_files:
        print(f"  - {f}")


if __name__ == "__main__":
    main()
