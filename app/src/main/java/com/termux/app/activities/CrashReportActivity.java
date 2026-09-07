package com.termux.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.termux.BuildConfig;
import com.termux.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CrashReportActivity extends AppCompatActivity {

    public static final String EXTRA_REPORT = "report";

    private static final String LOGSHARE_API = "https://api.logshare.cn/v1/log";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private String mReport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash_report);

        mReport = getIntent().getStringExtra(EXTRA_REPORT);
        if (mReport == null) mReport = "";

        ((ImageButton) findViewById(R.id.crash_back)).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.crash_content)).setText(mReport);
        findViewById(R.id.crash_share).setOnClickListener(v -> share());
        findViewById(R.id.crash_export).setOnClickListener(v -> export());
        findViewById(R.id.crash_upload).setOnClickListener(v -> upload());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    private void share() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, mReport);
        startActivity(Intent.createChooser(intent, getString(R.string.crash_share)));
    }

    private void export() {
        mExecutor.execute(() -> {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "termux_crash_" + timestamp + ".md";
            File written = null;
            try {
                File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.isDirectory() && !downloads.mkdirs()) throw new java.io.IOException("no downloads dir");
                File target = new File(downloads, fileName);
                writeReport(target);
                written = target;
            } catch (Exception e) {
                try {
                    File fallback = new File(getExternalFilesDir(null), fileName);
                    writeReport(fallback);
                    written = fallback;
                } catch (Exception ignored) { }
            }
            final File finalWritten = written;
            mHandler.post(() -> {
                if (finalWritten != null)
                    Toast.makeText(this, getString(R.string.crash_exported, finalWritten.getAbsolutePath()),
                        Toast.LENGTH_LONG).show();
                else
                    Toast.makeText(this, R.string.crash_export_failed, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void writeReport(File target) throws Exception {
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(mReport.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void upload() {
        findViewById(R.id.crash_upload).setEnabled(false);
        mExecutor.execute(() -> {
            String url = null;
            try {
                JSONObject body = new JSONObject();
                body.put("content", mReport);
                body.put("source", "termux-fork/" + BuildConfig.VERSION_NAME);

                HttpURLConnection connection = (HttpURLConnection) new URL(LOGSHARE_API).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }

                int status = connection.getResponseCode();
                InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                StringBuilder response = new StringBuilder();
                if (in != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) response.append(line);
                    }
                }
                connection.disconnect();

                if (status == 200) {
                    JSONObject json = new JSONObject(response.toString());
                    if (json.optBoolean("success", false)) url = json.optString("url", null);
                }
            } catch (Exception e) {
                android.util.Log.e("CrashReport", "LogShare upload failed", e);
            }

            final String finalUrl = url;
            mHandler.post(() -> {
                findViewById(R.id.crash_upload).setEnabled(true);
                if (finalUrl != null) showUploadResult(finalUrl);
                else Toast.makeText(this, R.string.crash_upload_failed, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void showUploadResult(String url) {
        String[] options = {getString(R.string.crash_copy_link), getString(R.string.crash_open_link)};
        new AlertDialog.Builder(this)
            .setTitle(R.string.crash_uploaded)
            .setMessage(url)
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("logshare", url));
                    Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) { }
                }
            })
            .show();
    }
}
