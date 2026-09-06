package com.termux.app.filemanager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.termux.R;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ThumbnailLoader {

    private static final int TARGET_SIZE_PX = 128;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    public static void loadInto(ImageView view, File file) {
        final String path = file.getAbsolutePath();
        view.setTag(path);

        Bitmap cached = CACHE.get(path);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        view.setImageResource(R.drawable.ic_fm_image);
        EXECUTOR.execute(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= TARGET_SIZE_PX
                && bounds.outHeight / (sample * 2) >= TARGET_SIZE_PX) {
                sample *= 2;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap == null) return;

            CACHE.put(path, bitmap);
            MAIN_HANDLER.post(() -> {
                if (path.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    private ThumbnailLoader() { }
}
