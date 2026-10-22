package com.webapp2apk.generated;

import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline copy of a page's stylesheets, scripts, images and fonts.
 *
 * The page cache in MainActivity only keeps a page's HTML, so a cached page
 * used to open offline with no styling or pictures. While the phone is
 * online, MainActivity notes every static file a page actually loads; shortly
 * after the page finishes, those files are downloaded here once (and refreshed
 * at most daily with a conditional request). While offline, shouldInterceptRequest
 * serves them from this folder, so the saved page looks the way it did online.
 *
 * Deliberately NOT cached: anything that isn't a plain static file (JSON/API
 * calls, HTML), anything fetched from a login/payment page, and anything that
 * comes back as HTML (a carrier "buy a bundle" page pretending to be an image).
 */
final class AssetCache {

    interface CookieSource {
        String cookieFor(String url);
    }

    /** Limits for one round of downloading, so a page can't burn a data bundle. */
    static final class Budget {
        int filesLeft;
        long bytesLeft;
        Budget(int files, long bytes) { filesLeft = files; bytesLeft = bytes; }
    }

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final long MAX_CSS_PARSE_BYTES = 1024 * 1024;
    private static final long REVALIDATE_AFTER_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_CSS_DEPTH = 2;

    private static final String[] STATIC_EXTENSIONS = {
            "css", "js", "mjs", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp",
            "woff", "woff2", "ttf", "otf", "eot"
    };

    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*(['\"]?)([^'\")]+?)\\1\\s*\\)");
    private static final Pattern CSS_IMPORT = Pattern.compile("@import\\s+(?:url\\(\\s*)?['\"]([^'\"]+)['\"]");

    private final File dir;
    private final long maxBytes;
    private final String homeHost;
    private final CookieSource cookies;

    AssetCache(File dir, long maxBytes, String homeHost, CookieSource cookies) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.homeHost = homeHost == null ? "" : homeHost.toLowerCase(Locale.ROOT);
        this.cookies = cookies;
        if (!dir.exists()) dir.mkdirs();
    }

    // ------------------------------------------------------------------
    // what is worth keeping
    // ------------------------------------------------------------------

    /** True for plain static files (by extension, or an image/CSS Accept header). */
    static boolean looksStatic(String url, Map<String, String> requestHeaders) {
        try {
            String path = Uri.parse(url).getPath();
            if (path != null) {
                int dot = path.lastIndexOf('.');
                if (dot >= 0 && dot < path.length() - 1) {
                    String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                    for (String e : STATIC_EXTENSIONS) if (e.equals(ext)) return true;
                }
            }
        } catch (Exception ignored) {}
        if (requestHeaders != null) {
            String accept = null;
            for (Map.Entry<String, String> h : requestHeaders.entrySet()) {
                if ("accept".equalsIgnoreCase(h.getKey())) { accept = h.getValue(); break; }
            }
            if (accept != null) {
                String a = accept.toLowerCase(Locale.ROOT);
                return a.startsWith("image/") || a.startsWith("text/css");
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // serving (offline)
    // ------------------------------------------------------------------

    private static String key(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }

    private File body(String url) { return new File(dir, key(url) + ".a"); }
    private File meta(String url) { return new File(dir, key(url) + ".m"); }

    /** The saved copy of this file, or null if there isn't one. */
    WebResourceResponse serve(String url) {
        try {
            File b = body(url);
            File m = meta(url);
            if (!b.isFile() || !m.isFile()) return null;
            String[] parts = readMeta(m);
            String mime = parts[0].isEmpty() ? guessMime(url) : parts[0];
            String enc = parts[1].isEmpty() ? defaultEncoding(mime) : parts[1];
            Map<String, String> headers = new HashMap<>();
            // Cross-origin fonts/CSS/modules are refused without this when the
            // copy is replayed instead of coming from the original host.
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, enc, 200, "OK", headers, new FileInputStream(b));
        } catch (Exception e) {
            return null;
        }
    }

    private static String defaultEncoding(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return (m.startsWith("text/") || m.contains("javascript") || m.contains("json") || m.contains("svg"))
                ? "UTF-8" : null;
    }

    private static String guessMime(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (path != null) {
                int dot = path.lastIndexOf('.');
                if (dot >= 0) {
                    String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                    if (ext.equals("js") || ext.equals("mjs")) return "application/javascript";
                    if (ext.equals("woff2")) return "font/woff2";
                    String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    if (m != null) return m;
                }
            }
        } catch (Exception ignored) {}
        return "application/octet-stream";
    }

    private static String[] readMeta(File m) {
        String[] out = {"", "", "", ""};
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(m)) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            String[] p = new String(bo.toByteArray(), "UTF-8").split("\\|", -1);
            for (int i = 0; i < 4 && i < p.length; i++) out[i] = p[i];
        } catch (Exception ignored) {}
        return out;
    }

    // ------------------------------------------------------------------
    // downloading (online)
    // ------------------------------------------------------------------

    /** Downloads the given files (url -> page that asked for it). Blocking; call from a background thread. */
    void fetchAll(Map<String, String> urlToReferer, Budget budget) {
        for (Map.Entry<String, String> e : urlToReferer.entrySet()) {
            if (budget.filesLeft <= 0 || budget.bytesLeft <= 0) break;
            try {
                fetch(e.getKey(), e.getValue(), budget, 0);
            } catch (Exception ignored) {
                // one bad file never stops the rest
            }
        }
        enforceLimit();
    }

    private boolean sameSite(String host) {
        if (host == null || homeHost.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals(homeHost) || h.endsWith("." + homeHost) || homeHost.endsWith("." + h);
    }

    private void fetch(String url, String referer, Budget budget, int depth) throws Exception {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
        File b = body(url);
        File m = meta(url);

        boolean have = b.isFile() && m.isFile();
        if (have && System.currentTimeMillis() - b.lastModified() < REVALIDATE_AFTER_MS) {
            if (depth < MAX_CSS_DEPTH && isCss(m)) followCss(b, url, budget, depth);
            return;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; WebApp2Apk offline cache)");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (referer != null && !referer.isEmpty()) conn.setRequestProperty("Referer", referer);
            String host = Uri.parse(url).getHost();
            if (sameSite(host) && cookies != null) {
                String c = cookies.cookieFor(url);
                if (c != null && !c.isEmpty()) conn.setRequestProperty("Cookie", c);
            }
            if (have) {
                String[] old = readMeta(m);
                if (!old[2].isEmpty()) conn.setRequestProperty("If-None-Match", old[2]);
                if (!old[3].isEmpty()) conn.setRequestProperty("If-Modified-Since", old[3]);
            }

            int status = conn.getResponseCode();
            if (status == 304 && have) {
                b.setLastModified(System.currentTimeMillis());
                if (depth < MAX_CSS_DEPTH && isCss(m)) followCss(b, url, budget, depth);
                return;
            }
            if (status < 200 || status >= 300) return;

            String contentType = conn.getContentType();
            String mime = "";
            String charset = "";
            if (contentType != null) {
                String[] parts = contentType.split(";");
                mime = parts[0].trim().toLowerCase(Locale.ROOT);
                for (String p : parts) {
                    p = p.trim();
                    if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) charset = p.substring(8).trim();
                }
            }
            // a carrier's "no bundle" page or an error page is HTML - never keep it as a stylesheet/image
            if (mime.startsWith("text/html") || mime.contains("xhtml")) return;

            long declared = conn.getContentLengthLong();
            if (declared > MAX_FILE_BYTES || declared > budget.bytesLeft) return;

            boolean gzip = "gzip".equalsIgnoreCase(conn.getContentEncoding());
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream raw = conn.getInputStream();
                 InputStream in = gzip ? new java.util.zip.GZIPInputStream(raw) : raw) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > MAX_FILE_BYTES) return;
                }
            }
            byte[] data = bo.toByteArray();
            if (data.length == 0 || data.length > budget.bytesLeft) return;
            budget.bytesLeft -= data.length;
            budget.filesLeft--;

            File tmp = new File(dir, key(url) + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            if (b.exists()) b.delete();
            if (!tmp.renameTo(b)) { tmp.delete(); return; }
            String etag = conn.getHeaderField("ETag");
            String lm = conn.getHeaderField("Last-Modified");
            String metaStr = mime + "|" + charset + "|" + (etag == null ? "" : etag.replace("|", ""))
                    + "|" + (lm == null ? "" : lm.replace("|", ""));
            try (FileOutputStream out = new FileOutputStream(m)) {
                out.write(metaStr.getBytes("UTF-8"));
            }

            if (depth < MAX_CSS_DEPTH && (mime.contains("css") || url.toLowerCase(Locale.ROOT).contains(".css"))
                    && data.length <= MAX_CSS_PARSE_BYTES) {
                followCss(b, url, budget, depth);
            }
        } finally {
            conn.disconnect();
        }
    }

    private boolean isCss(File metaFile) {
        return readMeta(metaFile)[0].contains("css");
    }

    /** Fonts and images referenced from a stylesheet (and stylesheets it imports) are saved too. */
    private void followCss(File cssFile, String cssUrl, Budget budget, int depth) {
        try {
            if (cssFile.length() > MAX_CSS_PARSE_BYTES) return;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(cssFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            String css = new String(bo.toByteArray(), "UTF-8");
            java.util.LinkedHashSet<String> refs = new java.util.LinkedHashSet<>();
            Matcher mi = CSS_IMPORT.matcher(css);
            while (mi.find()) refs.add(mi.group(1));
            Matcher mu = CSS_URL.matcher(css);
            while (mu.find()) refs.add(mu.group(2));

            URI base = new URI(cssUrl);
            for (String ref : refs) {
                if (budget.filesLeft <= 0 || budget.bytesLeft <= 0) return;
                String r = ref.trim();
                if (r.isEmpty() || r.startsWith("data:") || r.startsWith("#") || r.startsWith("blob:")) continue;
                String abs;
                try {
                    abs = base.resolve(r).toString();
                } catch (Exception bad) {
                    continue;
                }
                // drop #fragment (font-face svg ids) - the file is the same
                int hash = abs.indexOf('#');
                if (hash >= 0) abs = abs.substring(0, hash);
                try {
                    fetch(abs, cssUrl, budget, depth + 1);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // housekeeping
    // ------------------------------------------------------------------

    /** Keeps the folder under its size limit, dropping the least recently refreshed files first. */
    void enforceLimit() {
        try {
            File[] bodies = dir.listFiles((d, name) -> name.endsWith(".a"));
            if (bodies == null) return;
            long total = 0;
            for (File f : bodies) total += f.length();
            if (total <= maxBytes) return;
            Arrays.sort(bodies, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            for (File f : bodies) {
                if (total <= maxBytes * 8 / 10) break; // leave some headroom
                total -= f.length();
                String stem = f.getName().substring(0, f.getName().length() - 2);
                f.delete();
                new File(dir, stem + ".m").delete();
            }
        } catch (Exception ignored) {}
    }

    void clear() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) { try { f.delete(); } catch (Exception ignored) {} }
    }
}
