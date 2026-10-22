package com.webapp2apk.generated;

import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.core.net.ConnectivityManagerCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Self-update for the generated APK - the Android counterpart of the desktop
 * app's lib/updater.js.
 *
 * Flow: ask the build site whether a newer build of THIS app exists ->
 * download it inside the app -> verify it (size, optional SHA-256, same
 * package name, newer versionCode, same signing certificate) -> install it
 * through PackageInstaller.
 *
 * Android does not let a normal app replace itself with no UI at all the way
 * Windows' silent "/S" installer does. What it does allow (Android 12+) is an
 * unattended update when this app is the "installer of record", which it
 * becomes after the first update it installs itself. Until then, and on older
 * Android versions, the system shows its usual one-tap confirmation.
 */
public final class UpdateManager {

    public static final String PREFS = "webapp2apk_prefs";

    // user settings
    public static final String K_AUTO_UPDATE = "auto_update_enabled";
    public static final String K_ALLOW_METERED = "update_allow_metered";

    // state
    static final String K_LAST_CHECK = "last_update_check";
    static final String K_AVAIL_URL = "update_avail_url";
    static final String K_AVAIL_SIZE = "update_avail_size";
    static final String K_AVAIL_SHA = "update_avail_sha";
    static final String K_AVAIL_VERSION = "update_avail_version";
    static final String K_READY_PATH = "update_ready_path";
    static final String K_READY_VERSION = "update_ready_version";
    static final String K_ATTEMPTS = "update_attempts_";
    static final String K_REJECTED_URL = "update_rejected_url";
    static final String K_AVAIL_NAME = "update_avail_name";
    static final String K_READY_NAME = "update_ready_name";

    static final String CHANNEL_ID = "app_updates";
    static final int NOTIF_ID = 7412;
    static final String PROGRESS_CHANNEL_ID = "app_update_progress";
    static final int PROGRESS_NOTIF_ID = 7413;
    private static final long PART_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_AUTO_INSTALL_ATTEMPTS = 3;
    private static final int MAX_REDIRECTS = 5;

    public interface Progress {
        void onProgress(int percent);
    }

    public static final class Info {
        public String url;
        public String sha256;   // optional
        public long size;       // optional, 0 = unknown
        public long versionCode; // optional, 0 = unknown
        public String versionName = ""; // optional, e.g. "1.0.4" (shown in the banner)
    }

    /** A problem retrying can't fix (wrong app, not newer, wrong key, bad checksum). That file is never offered again. */
    static final class Rejected extends IOException {
        Rejected(String message) { super(message); }
    }

    /** The server would not continue a partial download - start over. */
    private static final class RangeRejected extends IOException {
        RangeRejected() { super("Server did not resume the download"); }
    }

    private UpdateManager() {}

    // ------------------------------------------------------------------
    // settings / state helpers
    // ------------------------------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAutoUpdateEnabled(Context c) {
        return prefs(c).getBoolean(K_AUTO_UPDATE, true);
    }

    public static boolean isMeteredAllowed(Context c) {
        return prefs(c).getBoolean(K_ALLOW_METERED, false);
    }

    /** True when it is OK to download an update on the current connection without asking. */
    public static boolean canAutoDownloadNow(Context c) {
        if (isMeteredAllowed(c)) return true;
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        return !ConnectivityManagerCompat.isActiveNetworkMetered(cm);
    }

    public static long currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return PackageInfoCompat.getLongVersionCode(pi);
        } catch (Exception e) {
            return 0;
        }
    }

    /** The downloaded + verified update, or null. Drops stale files (already installed / older). */
    public static File getReadyFile(Context c) {
        SharedPreferences p = prefs(c);
        String path = p.getString(K_READY_PATH, "");
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        long ver = p.getLong(K_READY_VERSION, 0);
        if (!f.isFile() || (ver > 0 && ver <= currentVersionCode(c))) {
            clearReady(c);
            return null;
        }
        return f;
    }

    /** Version text of the downloaded update ("1.0.4"), or "" when the server didn't say. */
    public static String getReadyVersionName(Context c) {
        String v = prefs(c).getString(K_READY_NAME, "");
        return v == null ? "" : v;
    }

    /** An update that exists but has not been downloaded yet (e.g. waiting for Wi-Fi), or null. */
    public static Info getAvailable(Context c) {
        SharedPreferences p = prefs(c);
        String url = p.getString(K_AVAIL_URL, "");
        if (url == null || url.isEmpty()) return null;
        long ver = p.getLong(K_AVAIL_VERSION, 0);
        if (ver > 0 && ver <= currentVersionCode(c)) {
            clearAvailable(c);
            return null;
        }
        Info i = new Info();
        i.url = url;
        i.sha256 = p.getString(K_AVAIL_SHA, "");
        i.size = p.getLong(K_AVAIL_SIZE, 0);
        i.versionCode = ver;
        i.versionName = p.getString(K_AVAIL_NAME, "");
        return i;
    }

    private static void clearAvailable(Context c) {
        prefs(c).edit().remove(K_AVAIL_URL).remove(K_AVAIL_SIZE).remove(K_AVAIL_SHA)
                .remove(K_AVAIL_VERSION).remove(K_AVAIL_NAME).apply();
    }

    private static void clearReady(Context c) {
        SharedPreferences p = prefs(c);
        String path = p.getString(K_READY_PATH, "");
        if (path != null && !path.isEmpty()) {
            try { new File(path).delete(); } catch (Exception ignored) {}
        }
        p.edit().remove(K_READY_PATH).remove(K_READY_VERSION).remove(K_READY_NAME).apply();
    }

    private static File updatesDir(Context c) {
        File d = new File(c.getFilesDir(), "updates");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------
    // check
    // ------------------------------------------------------------------

    private static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private static String stripWww(String h) {
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static boolean sameSite(String a, String b) {
        a = stripWww(a);
        b = stripWww(b);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }

    /** Only ever download from the build site or the app's own website - never anywhere else. */
    static boolean isTrustedHost(String url) {
        String h = hostOf(url);
        if (h.isEmpty()) return false;
        String checkHost = hostOf(App.appConfig.optString("update_check_url", ""));
        String appHost = hostOf(App.appConfig.optString("app_url", ""));
        return (!checkHost.isEmpty() && sameSite(h, checkHost)) || sameSite(h, appHost);
    }

    private static JSONObject fetchJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 256 * 1024) return null; // not a version file
                }
            }
            return new JSONObject(new String(out.toByteArray(), "UTF-8"));
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Asks the server whether a newer build exists. Blocking - call from a
     * background thread. Returns the update info, or null when up to date or
     * the check failed. Also remembers the result so the banner can show it.
     *
     * Two server formats are understood:
     *   build site (same endpoint the desktop app uses, platform=android):
     *     {"update":true,"download_url":"...","sha256":"...","size":123,"version_code":1700000000}
     *   the app's own site /version.json (what the APK used before):
     *     {"version_code":1700000000,"apk_url":"..."}
     */
    public static Info check(Context ctx) {
        Context c = ctx.getApplicationContext();
        prefs(c).edit().putLong(K_LAST_CHECK, System.currentTimeMillis()).apply();
        try {
            JSONObject cfg = App.appConfig;
            String appUrl = cfg.optString("app_url", "");
            String checkUrl = cfg.optString("update_check_url", "");
            String buildId = cfg.optString("build_id", "");
            if (buildId.isEmpty()) buildId = cfg.optString("request_id", "");
            long current = currentVersionCode(c);

            JSONObject json;
            if (!checkUrl.isEmpty() && !buildId.isEmpty()) {
                Uri u = Uri.parse(checkUrl).buildUpon()
                        .appendQueryParameter("domain", stripWww(hostOf(appUrl)))
                        .appendQueryParameter("name", cfg.optString("app_name", ""))
                        .appendQueryParameter("platform", "android")
                        .appendQueryParameter("build", buildId)
                        .appendQueryParameter("version_code", String.valueOf(current))
                        .appendQueryParameter("package", c.getPackageName())
                        .build();
                json = fetchJson(u.toString());
            } else {
                Uri home = Uri.parse(appUrl);
                if (home.getHost() == null) return null;
                json = fetchJson(home.getScheme() + "://" + home.getHost() + "/version.json");
            }
            if (json == null) return null;

            String url = json.optString("download_url", "");
            if (url.isEmpty()) url = json.optString("apk_url", "");
            long ver = json.optLong("version_code", 0);

            boolean wants = json.has("update") ? json.optBoolean("update", false) : ver > current;
            if (!wants || url.isEmpty()) {
                clearAvailable(c);
                return null;
            }
            if (ver > 0 && ver <= current) {
                clearAvailable(c);
                return null;
            }
            if (!isTrustedHost(url)) return null;
            if (url.equals(prefs(c).getString(K_REJECTED_URL, ""))) {
                clearAvailable(c); // this exact file was already found unusable
                return null;
            }

            Info info = new Info();
            info.url = url;
            info.sha256 = json.optString("sha256", "");
            info.size = json.optLong("size", 0);
            info.versionCode = ver;
            String vn = json.optString("version", "");
            info.versionName = vn.matches("[0-9A-Za-z.+_-]{1,20}") ? vn : "";
            prefs(c).edit()
                    .putString(K_AVAIL_NAME, info.versionName)
                    .putString(K_AVAIL_URL, info.url)
                    .putString(K_AVAIL_SHA, info.sha256)
                    .putLong(K_AVAIL_SIZE, info.size)
                    .putLong(K_AVAIL_VERSION, info.versionCode)
                    .apply();
            return info;
        } catch (Exception e) {
            return null; // silent, like the desktop app
        }
    }

    // ------------------------------------------------------------------
    // download + verify
    // ------------------------------------------------------------------

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static HttpURLConnection openFollowingTrustedRedirects(String url, long resumeFrom, String ifRange)
            throws IOException {
        String current = url;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            // raw bytes, so a byte offset means the same thing on both sides
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (resumeFrom > 0) {
                conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
                // only continue if the file is still the one we started on
                if (ifRange != null && !ifRange.isEmpty()) conn.setRequestProperty("If-Range", ifRange);
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new IOException("Bad redirect");
                String next = new URL(new URL(current), loc).toString();
                if (!isTrustedHost(next)) throw new IOException("Redirect to an untrusted host");
                current = next;
                continue;
            }
            if (code == 416 && resumeFrom > 0) {
                conn.disconnect();
                throw new RangeRejected();
            }
            if (code != 200 && code != 206) {
                conn.disconnect();
                throw new IOException("Server returned " + code);
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    // "update.apk.part.meta" = url|etag|last-modified of the partial file, so a
    // later attempt knows it is continuing the SAME file.
    private static String[] readPartMeta(File f) {
        if (!f.isFile()) return null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            String[] p = new String(bo.toByteArray(), "UTF-8").split("\\|", -1);
            if (p.length < 3) return null;
            return new String[]{p[0], p[1], p[2]};
        } catch (Exception e) {
            return null;
        }
    }

    private static void writePartMeta(File f, String url, String etag, String lastModified) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            String line = url.replace("|", "") + "|" + (etag == null ? "" : etag.replace("|", ""))
                    + "|" + (lastModified == null ? "" : lastModified.replace("|", ""));
            out.write(line.getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    private static long parseContentRangeStart(String cr) {
        try { // "bytes 1000-1999/2000"
            String v = cr.trim().substring(cr.indexOf(' ') + 1);
            return Long.parseLong(v.substring(0, v.indexOf('-')).trim());
        } catch (Exception e) { return -1; }
    }

    private static long parseContentRangeTotal(String cr) {
        try {
            String t = cr.substring(cr.indexOf('/') + 1).trim();
            return "*".equals(t) ? -1 : Long.parseLong(t);
        } catch (Exception e) { return -1; }
    }

    /**
     * Streams the file into `part`, continuing from `have` bytes when the
     * server agrees. Returns the SHA-256 of the whole file. If the connection
     * drops, the partial file is KEPT so the next attempt carries on from
     * there instead of starting over (important on slow mobile data).
     */
    private static byte[] transfer(Info info, File part, File partMeta, long have, String[] oldMeta,
                                   Progress progress) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("No SHA-256 available");
        }
        String ifRange = null;
        if (have > 0 && oldMeta != null) ifRange = !oldMeta[1].isEmpty() ? oldMeta[1] : oldMeta[2];

        HttpURLConnection conn = openFollowingTrustedRedirects(info.url, have, ifRange);
        try {
            int code = conn.getResponseCode();
            boolean append = false;
            long total;
            if (have > 0 && code == 206) {
                String cr = conn.getHeaderField("Content-Range");
                if (cr == null || parseContentRangeStart(cr) != have) throw new RangeRejected();
                total = parseContentRangeTotal(cr);
                append = true;
            } else {
                // 200: a whole file (server ignored the range, or the file changed)
                total = conn.getContentLengthLong();
            }
            if (total <= 0) total = info.size;

            long got = 0;
            if (append) {
                try (InputStream in = new FileInputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) { md.update(buf, 0, n); got += n; }
                }
            } else {
                part.delete();
            }
            writePartMeta(partMeta, info.url, conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"));

            int lastPct = -1;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    if (progress != null && total > 0) {
                        int pct = (int) Math.min(100, (got * 100) / total);
                        if (pct != lastPct) { lastPct = pct; progress.onProgress(pct); }
                    }
                }
            }
            if (got <= 0) throw new IOException("Empty download");
            if (total > 0 && got != total) throw new IOException("Download interrupted");
            return md.digest();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Downloads and verifies the update into app-private storage, then marks
     * it ready. Blocking. A download that is cut off (signal lost, app closed)
     * is resumed next time from where it stopped. Synchronized so the worker
     * and the open app never download the same file twice - the second caller
     * just gets the result.
     */
    public static synchronized File download(Context ctx, Info info, Progress progress) throws Exception {
        Context c = ctx.getApplicationContext();
        File existing = getReadyFile(c);
        if (existing != null) return existing;
        if (!isTrustedHost(info.url)) throw new IOException("Untrusted update host");

        File dir = updatesDir(c);
        File part = new File(dir, "update.apk.part");
        File partMeta = new File(dir, "update.apk.part.meta");
        File done = new File(dir, "update.apk");

        // Keep a partial file only if it belongs to this same update URL and is recent.
        String[] oldMeta = readPartMeta(partMeta);
        boolean resumable = part.isFile() && oldMeta != null && info.url.equals(oldMeta[0])
                && System.currentTimeMillis() - part.lastModified() < PART_MAX_AGE_MS;
        File[] leftovers = dir.listFiles();
        if (leftovers != null) {
            for (File f : leftovers) {
                if (resumable && (f.equals(part) || f.equals(partMeta))) continue;
                try { f.delete(); } catch (Exception ignored) {}
            }
        }

        boolean tryResume = resumable;
        for (int pass = 0; pass < 2; pass++) {
            long have = tryResume ? part.length() : 0;
            if (!tryResume) { part.delete(); partMeta.delete(); }

            byte[] digest;
            try {
                digest = transfer(info, part, partMeta, have, tryResume ? oldMeta : null, progress);
            } catch (RangeRejected r) {
                tryResume = false; // server won't continue it - download the whole file instead
                continue;
            }

            boolean sizeOk = info.size <= 0 || part.length() == info.size;
            boolean hashOk = info.sha256 == null || info.sha256.isEmpty()
                    || hex(digest).equalsIgnoreCase(info.sha256.trim());
            if (!sizeOk || !hashOk) {
                part.delete();
                partMeta.delete();
                if (tryResume) { tryResume = false; continue; } // resumed bytes may have been bad - once from scratch
                throw reject(c, info, "Download failed its integrity check");
            }

            try {
                verifyApk(c, part);
            } catch (Rejected r) {
                part.delete();
                partMeta.delete();
                reject(c, info, r.getMessage());
                throw r;
            } catch (Exception e) {
                part.delete();
                partMeta.delete();
                throw e;
            }
            if (!part.renameTo(done)) throw new IOException("Could not save the update");
            partMeta.delete();

            long newVer = 0;
            try {
                PackageInfo arch = c.getPackageManager().getPackageArchiveInfo(done.getAbsolutePath(), 0);
                if (arch != null) newVer = PackageInfoCompat.getLongVersionCode(arch);
            } catch (Exception ignored) {}
            prefs(c).edit()
                    .putString(K_READY_PATH, done.getAbsolutePath())
                    .putLong(K_READY_VERSION, newVer)
                    .putString(K_READY_NAME, info.versionName == null ? "" : info.versionName)
                    .apply();
            return done;
        }
        throw new IOException("Update download failed");
    }

    /** Remember that this exact file is unusable so it is not downloaded again every few hours. */
    private static Rejected reject(Context c, Info info, String why) {
        prefs(c).edit().putString(K_REJECTED_URL, info.url).apply();
        clearAvailable(c);
        return new Rejected(why);
    }

    @SuppressWarnings("deprecation")
    private static void verifyApk(Context c, File apk) throws Exception {
        // zip magic - also rejects an .exe/.dmg a mismatched server might hand back
        try (InputStream in = new FileInputStream(apk)) {
            if (in.read() != 'P' || in.read() != 'K') throw new Rejected("Not an Android package");
        }
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo arch = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (arch == null) throw new IOException("Unreadable package");
        if (!c.getPackageName().equals(arch.packageName)) throw new Rejected("Update is for a different app");
        if (PackageInfoCompat.getLongVersionCode(arch) <= currentVersionCode(c)) {
            throw new Rejected("Update is not newer than the installed version");
        }
        // Signing certificate must match, otherwise Android refuses the update anyway.
        // Skipped (the system enforces it at install time) when the archive's
        // certificates can't be read on this Android version.
        PackageInfo cur = pm.getPackageInfo(c.getPackageName(), flags);
        Set<String> a = signers(arch, true);
        Set<String> b = signers(cur, false);
        if (!a.isEmpty() && !b.isEmpty()) {
            boolean overlap = false;
            for (String s : a) if (b.contains(s)) { overlap = true; break; }
            if (!overlap) throw new Rejected("Update is signed with a different key");
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo pi, boolean includeHistory) {
        Set<String> out = new HashSet<>();
        try {
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= 28) {
                SigningInfo si = pi.signingInfo;
                if (si != null) {
                    sigs = (includeHistory && !si.hasMultipleSigners())
                            ? si.getSigningCertificateHistory() : si.getApkContentsSigners();
                }
            } else {
                sigs = pi.signatures;
            }
            if (sigs != null) {
                for (Signature s : sigs) {
                    out.add(android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ------------------------------------------------------------------
    // install
    // ------------------------------------------------------------------

    public static boolean attemptsExhausted(Context c, long version) {
        return prefs(c).getInt(K_ATTEMPTS + version, 0) >= MAX_AUTO_INSTALL_ATTEMPTS;
    }

    /**
     * Hands the verified APK to PackageInstaller. On Android 12+ the request
     * is flagged "no user action needed", which the system honours when this
     * app is the installer of record; otherwise it falls back to its normal
     * confirmation, which UpdateInstallReceiver surfaces.
     */
    public static void install(Context ctx, File apk) throws Exception {
        Context c = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !c.getPackageManager().canRequestPackageInstalls()) {
            throw new SecurityException("Installing updates is not allowed for this app yet");
        }
        long ver = prefs(c).getLong(K_READY_VERSION, 0);
        SharedPreferences p = prefs(c);
        p.edit().putInt(K_ATTEMPTS + ver, p.getInt(K_ATTEMPTS + ver, 0) + 1).apply();

        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(c, UpdateInstallReceiver.class);
            result.setAction(UpdateInstallReceiver.ACTION);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(c, sessionId, result, piFlags);
            session.commit(pending.getIntentSender());
        } catch (Exception e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    // ------------------------------------------------------------------
    // notifications
    // ------------------------------------------------------------------

    static void notifyUser(Context ctx, String title, String text, PendingIntent tap) {
        Context c = ctx.getApplicationContext();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(new NotificationChannel(
                            CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
                }
            }
            NotificationManagerCompat mgr = NotificationManagerCompat.from(c);
            if (!mgr.areNotificationsEnabled()) return;
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true);
            if (tap != null) b.setContentIntent(tap);
            mgr.notify(NOTIF_ID, b.build());
        } catch (SecurityException ignored) {
            // notification permission not granted - the in-app banner still shows
        } catch (Exception ignored) {
        }
    }

    static PendingIntent openAppIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancelNotification(Context c) {
        try { NotificationManagerCompat.from(c).cancel(NOTIF_ID); } catch (Exception ignored) {}
    }

    /** Quiet "Downloading update... 42%" notification while the background worker downloads. */
    static void notifyProgress(Context ctx, int percent) {
        Context c = ctx.getApplicationContext();
        if (App.isInForeground()) return; // the in-app banner already shows progress
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(new NotificationChannel(
                            PROGRESS_CHANNEL_ID, "Update downloads", NotificationManager.IMPORTANCE_LOW));
                }
            }
            NotificationManagerCompat mgr = NotificationManagerCompat.from(c);
            if (!mgr.areNotificationsEnabled()) return;
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, PROGRESS_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle("Downloading " + c.getString(R.string.app_name) + " update")
                    .setContentText(percent + "%")
                    .setProgress(100, percent, false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setContentIntent(openAppIntent(c));
            mgr.notify(PROGRESS_NOTIF_ID, b.build());
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
    }

    static void cancelProgress(Context c) {
        try { NotificationManagerCompat.from(c.getApplicationContext()).cancel(PROGRESS_NOTIF_ID); } catch (Exception ignored) {}
    }

    /** Called once an update is downloaded: tell the person, in case the app isn't open. */
    static void announceReady(Context c) {
        if (App.isInForeground()) return; // the in-app banner already says it
        notifyUser(c, c.getString(R.string.app_name) + " update ready",
                "Tap to finish updating", openAppIntent(c));
    }

    static void announceAvailable(Context c) {
        if (App.isInForeground()) return;
        notifyUser(c, c.getString(R.string.app_name) + " update available",
                "Open the app to download it (it was not downloaded on mobile data)", openAppIntent(c));
    }
}
