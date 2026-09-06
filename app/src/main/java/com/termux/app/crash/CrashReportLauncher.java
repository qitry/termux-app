package com.termux.app.crash;

import android.app.Activity;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

import com.termux.app.activities.CrashReportActivity;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * Replaces the upstream crash notification: if a crash log from the previous run exists,
 * read it (plus a tail of the system logcat) and open {@link CrashReportActivity}.
 */
public final class CrashReportLauncher {

    private static final String LOG_TAG = "CrashReportLauncher";

    public static void checkAndShow(final Activity activity) {
        new Thread(() -> {
            File crashFile = new File(TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH);
            if (!crashFile.isFile()) return;

            StringBuilder reportBuilder = new StringBuilder();
            Error error = FileUtils.readTextFromFile("crash log", crashFile.getAbsolutePath(),
                Charset.defaultCharset(), reportBuilder, false);
            if (error != null) {
                Log.e(LOG_TAG, "Failed to read crash log: " + error);
                return;
            }

            FileUtils.moveRegularFile("crash log", crashFile.getAbsolutePath(),
                TermuxConstants.TERMUX_CRASH_LOG_BACKUP_FILE_PATH, true);

            String report = reportBuilder.toString();
            if (report.isEmpty()) return;

            String logcat = captureLogcatTail();
            if (!logcat.isEmpty())
                report = report + "\n\n## Recent Logcat\n```\n" + logcat + "\n```";

            final String finalReport = report;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                Intent intent = new Intent(activity, CrashReportActivity.class);
                intent.putExtra(CrashReportActivity.EXTRA_REPORT, finalReport);
                activity.startActivity(intent);
            });
        }).start();
    }

    private static String captureLogcatTail() {
        StringBuilder tail = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(
                new String[]{"logcat", "-d", "-t", "2000", "-v", "brief"});
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("com.termux") || line.contains("AndroidRuntime")
                        || line.contains("FATAL")) {
                        tail.append(line).append('\n');
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            Log.w(LOG_TAG, "logcat capture unavailable: " + e);
        }
        return tail.toString();
    }

    private CrashReportLauncher() { }
}
