package com.webapp2apk.generated;

import android.content.Context;
import android.webkit.CookieManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The single source of truth for reading, writing, and replaying the
 * offline submission queue. Used by both MainActivity (an immediate
 * foreground flush attempt, for fast visible feedback while the app is
 * open) and OfflineQueueWorker (a reliable background retry that survives
 * the app being killed or the phone rebooting) - deliberately factored out
 * once so both call the exact same replay logic instead of risking two
 * implementations drifting apart.
 */
final class OfflineQueueSync {

    private static final Object QUEUE_LOCK = new Object();

    // Hard cap on the queue file's total size. Multipart submissions with a
    // photo attached are base64-encoded before queuing, so without this cap
    // a long stretch offline with several photo uploads could genuinely
    // contribute to filling a tight hosting/device disk quota - the exact
    // kind of problem that took down APK delivery on the server side today.
    private static final long MAX_QUEUE_BYTES = 20L * 1024 * 1024; // 20 MB

    // A double-tap on a Save/Delete button while offline would previously
    // queue two near-identical requests, both of which replay later and can
    // create duplicate records. Treating an identical request arriving
    // within this window as the same tap (not a second one) fixes that
    // without needing any change on the web-app side.
    private static final long DUPLICATE_WINDOW_MS = 2000;

    private OfflineQueueSync() {
    }

    static File queueFile(Context context) {
        return new File(context.getFilesDir(), "offline_queue.jsonl");
    }

    static void queueSubmission(Context context, String json) {
        synchronized (QUEUE_LOCK) {
            try {
                if (isDuplicateOfLast(context, json)) return;
                enforceQueueSizeCap(context, json.length());
                try (java.io.FileWriter writer = new java.io.FileWriter(queueFile(context), true)) {
                    writer.write(json.replace("\n", " ") + "\n");
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isDuplicateOfLast(Context context, String json) {
        try {
            List<String> lines = readQueueLines(context);
            if (lines.isEmpty()) return false;
            JSONObject incoming = new JSONObject(json);
            JSONObject last = new JSONObject(lines.get(lines.size() - 1));
            long tsDiff = Math.abs(incoming.optLong("ts", 0) - last.optLong("ts", 0));
            if (tsDiff > DUPLICATE_WINDOW_MS) return false;

            JSONArray incomingFields = incoming.optJSONArray("fields");
            JSONArray lastFields = last.optJSONArray("fields");
            return incoming.optString("url").equals(last.optString("url"))
                    && incoming.optString("method").equals(last.optString("method"))
                    && incoming.optString("enctype").equals(last.optString("enctype"))
                    && String.valueOf(incomingFields).equals(String.valueOf(lastFields));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Evicts the oldest queued entries (rather than refusing the new one)
     * whenever adding this submission would push the queue file over its
     * size cap - a long offline stretch with heavy attachments should never
     * be able to grow without bound, but the newest action a person just
     * took is usually the one most worth keeping.
     */
    private static void enforceQueueSizeCap(Context context, int incomingBytes) {
        File f = queueFile(context);
        long currentSize = f.exists() ? f.length() : 0;
        if (currentSize + incomingBytes <= MAX_QUEUE_BYTES) return;

        try {
            List<String> lines = readQueueLines(context);
            long total = currentSize + incomingBytes;
            while (!lines.isEmpty() && total > MAX_QUEUE_BYTES) {
                String removed = lines.remove(0);
                total -= (removed.length() + 1);
            }
            writeQueueLines(context, lines);
        } catch (Exception ignored) {
        }
    }

    static int getQueueSize(Context context) {
        synchronized (QUEUE_LOCK) {
            if (!queueFile(context).exists()) return 0;
            try {
                return readQueueLines(context).size();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    static List<String> readQueueLines(Context context) throws java.io.IOException {
        List<String> lines = new ArrayList<>();
        File f = queueFile(context);
        if (!f.exists()) return lines;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    static void writeQueueLines(Context context, List<String> lines) {
        try (java.io.FileWriter writer = new java.io.FileWriter(queueFile(context), false)) {
            for (String line : lines) {
                writer.write(line + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    /** Result of a single flush attempt. */
    static final class FlushResult {
        final int succeeded;
        final int remaining;

        FlushResult(int succeeded, int remaining) {
            this.succeeded = succeeded;
            this.remaining = remaining;
        }
    }

    /**
     * Attempts to actually send every queued submission as a real HTTP
     * request, using whatever login session is current right now (not a
     * stale one from when it was queued). Anything that still fails (server
     * still unreachable, etc.) stays queued for the next attempt instead of
     * being lost. Safe to call from a background thread or a WorkManager
     * worker.
     */
    static FlushResult flush(Context context) {
        List<String> lines;
        synchronized (QUEUE_LOCK) {
            try {
                lines = readQueueLines(context);
            } catch (Exception e) {
                return new FlushResult(0, 0);
            }
        }
        if (lines.isEmpty()) return new FlushResult(0, 0);

        List<String> remaining = new ArrayList<>();
        int succeeded = 0;
        for (String line : lines) {
            if (trySubmitQueuedItem(line)) {
                succeeded++;
            } else {
                remaining.add(line);
            }
        }

        synchronized (QUEUE_LOCK) {
            writeQueueLines(context, remaining);
        }

        return new FlushResult(succeeded, remaining.size());
    }

    private static boolean trySubmitQueuedItem(String jsonLine) {
        try {
            JSONObject obj = new JSONObject(jsonLine);
            String urlStr = obj.getString("url");
            String method = obj.optString("method", "POST");
            String enctype = obj.optString("enctype", "application/x-www-form-urlencoded");
            JSONArray fields = obj.getJSONArray("fields");

            boolean hasFile = false;
            for (int i = 0; i < fields.length(); i++) {
                if ("file".equals(fields.getJSONObject(i).optString("type"))) {
                    hasFile = true;
                    break;
                }
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET".equalsIgnoreCase(method) ? "POST" : method); // never replay as GET
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);

            String cookie = CookieManager.getInstance().getCookie(urlStr);
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);

            if (hasFile || (enctype != null && enctype.toLowerCase().contains("multipart"))) {
                submitAsMultipart(conn, fields);
            } else if ("raw".equals(enctype)) {
                submitAsRawBody(conn, fields);
            } else {
                submitAsUrlEncoded(conn, fields);
            }

            int status = conn.getResponseCode();
            conn.disconnect();
            return status >= 200 && status < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private static void submitAsUrlEncoded(HttpURLConnection conn, JSONArray fields) throws Exception {
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.getJSONObject(i);
            if (!"text".equals(f.optString("type"))) continue;
            if (body.length() > 0) body.append('&');
            body.append(java.net.URLEncoder.encode(f.getString("key"), "UTF-8"));
            body.append('=');
            body.append(java.net.URLEncoder.encode(f.optString("value", ""), "UTF-8"));
        }
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }
    }

    private static void submitAsRawBody(HttpURLConnection conn, JSONArray fields) throws Exception {
        String body = "";
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.getJSONObject(i);
            if ("body".equals(f.optString("key"))) {
                body = f.optString("value", "");
                break;
            }
        }
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }
    }

    private static void submitAsMultipart(HttpURLConnection conn, JSONArray fields) throws Exception {
        String boundary = "----w2aBoundary" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (java.io.OutputStream os = conn.getOutputStream();
             java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(os, "UTF-8"), true)) {

            for (int i = 0; i < fields.length(); i++) {
                JSONObject f = fields.getJSONObject(i);
                String type = f.optString("type");
                String key = f.optString("key");

                writer.append("--").append(boundary).append("\r\n");

                if ("file".equals(type)) {
                    String name = f.optString("name", "upload");
                    String mime = f.optString("mime", "application/octet-stream");
                    writer.append("Content-Disposition: form-data; name=\"").append(key)
                            .append("\"; filename=\"").append(name).append("\"\r\n");
                    writer.append("Content-Type: ").append(mime).append("\r\n\r\n");
                    writer.flush();
                    byte[] fileBytes = android.util.Base64.decode(f.optString("data", ""), android.util.Base64.DEFAULT);
                    os.write(fileBytes);
                    os.flush();
                    writer.append("\r\n");
                } else if ("text".equals(type)) {
                    writer.append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n");
                    writer.append(f.optString("value", "")).append("\r\n");
                }
                // "file_too_large" entries are intentionally skipped on replay.
            }

            writer.append("--").append(boundary).append("--\r\n");
            writer.flush();
        }
    }
}
