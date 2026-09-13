package com.webapp2apk.generated;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Crash reporting with no third-party SDK, so it costs nothing in app size
 * or build complexity. Any uncaught exception is written to a small local
 * file immediately (so the record survives the crash that's about to kill
 * the process), then uploaded the next time the app starts successfully and
 * a connection is available. Fully opt-in: does nothing at all unless
 * "crash_report_url" is set in app_config.json for this build - most target
 * web apps won't have that endpoint, so this stays silent by default rather
 * than failing loudly against a URL that doesn't exist.
 */
final class CrashReporter {

    private static final String CRASH_FILE = "last_crash.txt";

    private CrashReporter() {
    }

    static void install(Context appContext, String crashReportUrl) {
        if (crashReportUrl == null || crashReportUrl.trim().isEmpty()) return;

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashFile(appContext, throwable);
            } catch (Exception ignored) {
            }
            // Always chain to whatever handler was there before (usually the
            // system's own), so normal crash behavior - the app actually
            // closing, any OEM crash dialog - still happens exactly as it
            // would without this reporter installed.
            if (previous != null) previous.uncaughtException(thread, throwable);
        });

        new Thread(() -> tryUploadPendingCrash(appContext, crashReportUrl)).start();
    }

    private static void writeCrashFile(Context context, Throwable throwable) throws Exception {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        String report = "package=" + context.getPackageName()
                + "\nversion=" + versionName(context)
                + "\ntime=" + System.currentTimeMillis()
                + "\n\n" + sw;
        try (FileWriter writer = new FileWriter(new File(context.getFilesDir(), CRASH_FILE))) {
            writer.write(report);
        }
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void tryUploadPendingCrash(Context context, String crashReportUrl) {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        if (!file.exists()) return;

        try {
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int off = 0;
                int n;
                while (off < data.length && (n = fis.read(data, off, data.length - off)) != -1) off += n;
            }
            String report = new String(data, "UTF-8");

            HttpURLConnection conn = (HttpURLConnection) new URL(crashReportUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            String body = "report=" + URLEncoder.encode(report, "UTF-8");

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            int status = conn.getResponseCode();
            conn.disconnect();

            if (status >= 200 && status < 300) {
                // Uploaded - don't send the same report again on the next launch.
                file.delete();
            }
        } catch (Exception ignored) {
            // Left in place - will simply retry on the next successful app start.
        }
    }
}
