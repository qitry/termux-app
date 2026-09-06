package com.termux.app.activities;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import com.github.chrisbanes.photoview.PhotoView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "file_path";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private PhotoView mPhotoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        File file = path == null ? null : new File(path);

        ((TextView) findViewById(R.id.viewer_title)).setText(file == null ? "" : file.getName());
        ((ImageButton) findViewById(R.id.viewer_back)).setOnClickListener(v -> finish());

        mPhotoView = findViewById(R.id.photo_view);

        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.fm_open_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mExecutor.execute(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

            int sample = 1;
            int target = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
                sample *= 2;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap;
            try {
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            } catch (OutOfMemoryError e) {
                bitmap = null;
            }
            Bitmap finalBitmap = bitmap;
            mHandler.post(() -> {
                if (isFinishing()) return;
                if (finalBitmap == null) {
                    Toast.makeText(ImageViewerActivity.this, R.string.fm_open_failed, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                mPhotoView.setImageBitmap(finalBitmap);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }
}
