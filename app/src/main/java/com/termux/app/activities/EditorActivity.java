package com.termux.app.activities;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.rosemoe.sora.widget.CodeEditor;

public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "file_path";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private CodeEditor mEditor;
    private File mFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        mFile = path == null ? null : new File(path);

        ((TextView) findViewById(R.id.editor_title)).setText(mFile == null ? "" : mFile.getName());
        ((ImageButton) findViewById(R.id.editor_back)).setOnClickListener(v -> finish());
        findViewById(R.id.editor_save).setOnClickListener(v -> save());

        mEditor = new CodeEditor(this);
        mEditor.setTypefaceText(Typeface.MONOSPACE);
        mEditor.setWordwrap(true);
        mEditor.setEditorLanguage(new com.termux.app.filemanager.RegexHighlightLanguage());
        ((FrameLayout) findViewById(R.id.editor_container)).addView(mEditor,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (mFile == null || !mFile.isFile()) {
            Toast.makeText(this, R.string.fm_open_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File file = mFile;
        mExecutor.execute(() -> {
            String content;
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) file.length()];
                int offset = 0;
                while (offset < bytes.length) {
                    int read = in.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                content = new String(bytes, 0, offset, StandardCharsets.UTF_8);
            } catch (IOException | OutOfMemoryError e) {
                mHandler.post(() -> {
                    Toast.makeText(EditorActivity.this, R.string.fm_open_failed, Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }
            mHandler.post(() -> mEditor.setText(content));
        });
    }

    private void save() {
        File file = mFile;
        if (file == null) return;
        String content = mEditor.getText().toString();
        mExecutor.execute(() -> {
            boolean ok;
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                ok = true;
            } catch (IOException e) {
                ok = false;
            }
            boolean finalOk = ok;
            mHandler.post(() -> Toast.makeText(EditorActivity.this,
                finalOk ? R.string.fm_saved : R.string.fm_save_failed, Toast.LENGTH_SHORT).show());
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
        if (mEditor != null) mEditor.release();
    }
}
