package com.termux.app.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.termux.R;
import com.termux.app.keepalive.AppForegroundTracker;

/**
 * A small always-on-top window showing a green dot and a "运行中" label, used as a visual
 * keep-alive indicator while {@link com.termux.app.TermuxService} is running.
 * <p/>
 * Two modes are supported:
 * <ul>
 *     <li>{@link #MODE_MOVABLE} (default): the window can be dragged around but never handles
 *     clicks, so taps fall through to whatever is underneath.</li>
 *     <li>{@link #MODE_PASSTHROUGH}: the window is fixed and marked not touchable, so it does not
 *     intercept input at all.</li>
 * </ul>
 * The window opacity is user configurable. Settings are persisted in the {@code floating_overlay}
 * shared preferences file and shared with the drawer control dialog.
 */
public final class FloatingStatusOverlay {

    public static final String PREFS_NAME = "floating_overlay";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_MOVABLE = "movable";
    public static final String KEY_ALPHA = "alpha";
    public static final String KEY_X = "x";
    public static final String KEY_Y = "y";

    /** Draggable, but the window itself never consumes clicks. */
    public static final boolean MODE_MOVABLE = true;
    /** Fixed and fully click-through. */
    public static final boolean MODE_PASSTHROUGH = false;

    private static final int DEFAULT_ALPHA = 85;
    private static final int ALPHA_MIN = 20;
    private static final int ALPHA_MAX = 100;

    private static FloatingStatusOverlay sInstance;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final SharedPreferences mPreferences;

    private View mView;
    private WindowManager.LayoutParams mLayoutParams;
    private boolean mShown;

    /**
     * The overlay is a background indicator, so it is suppressed while a Termux activity is in the
     * foreground (the terminal itself, the file manager, the editor...) and only shown when the app
     * moves to the background.
     */
    private boolean mForeground;

    private int mTouchStartX;
    private int mTouchStartY;
    private float mTouchStartRawX;
    private float mTouchStartRawY;
    private boolean mDragging;

    private FloatingStatusOverlay(Context context) {
        mContext = context.getApplicationContext();
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mPreferences = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // The overlay only makes sense in the background, so follow the process wide foreground
        // state instead of hooking every single activity.
        AppForegroundTracker tracker = AppForegroundTracker.get();
        if (tracker != null) tracker.setListener(this::onAppForegroundChanged);
    }

    public static synchronized FloatingStatusOverlay getInstance(Context context) {
        if (sInstance == null) sInstance = new FloatingStatusOverlay(context);
        return sInstance;
    }

    /** Whether the overlay is enabled in preferences. Does not imply it is currently shown. */
    public static boolean isEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static boolean isMovable(Context context) {
        return getPrefs(context).getBoolean(KEY_MOVABLE, MODE_MOVABLE);
    }

    public static int getAlphaPercent(Context context) {
        return getPrefs(context).getInt(KEY_ALPHA, DEFAULT_ALPHA);
    }

    public static int getAlphaMin() {
        return ALPHA_MIN;
    }

    public static int getAlphaMax() {
        return ALPHA_MAX;
    }

    public static void setEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static void setMovable(Context context, boolean movable) {
        getPrefs(context).edit().putBoolean(KEY_MOVABLE, movable).apply();
    }

    public static void setAlphaPercent(Context context, int alpha) {
        getPrefs(context).edit().putInt(KEY_ALPHA, clampAlpha(alpha)).apply();
    }

    /** Whether the user has granted the "display over other apps" permission. */
    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(context);
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static int clampAlpha(int alpha) {
        if (alpha < ALPHA_MIN) return ALPHA_MIN;
        if (alpha > ALPHA_MAX) return ALPHA_MAX;
        return alpha;
    }

    /**
     * Shows the overlay if it is enabled in preferences and permission has been granted.
     * Safe to call repeatedly.
     */
    public synchronized void show() {
        if (mShown) return;
        if (mForeground) return;
        if (!isEnabled(mContext)) return;
        if (!canDrawOverlays(mContext)) return;

        mView = LayoutInflater.from(new ContextThemeWrapper(mContext, R.style.Theme_TermuxApp_DayNight_NoActionBar))
            .inflate(R.layout.view_floating_status, null);
        mView.setAlpha(getAlphaPercent(mContext) / 100f);

        mLayoutParams = buildLayoutParams();
        mLayoutParams.x = mPreferences.getInt(KEY_X, 0);
        mLayoutParams.y = mPreferences.getInt(KEY_Y, 200);

        applyTouchable(mLayoutParams);

        try {
            mWindowManager.addView(mView, mLayoutParams);
            mShown = true;
        } catch (Exception e) {
            // Permission may have been revoked between the check and addView, or the window type
            // is not allowed on this device. Fail silently, the user can retry from the drawer.
            mView = null;
            mLayoutParams = null;
        }
    }

    /** Removes the overlay if it is currently shown. Safe to call repeatedly. */
    public synchronized void hide() {
        if (!mShown || mView == null) return;
        try {
            mWindowManager.removeView(mView);
        } catch (Exception e) {
            // Already detached, ignore.
        }
        mView = null;
        mLayoutParams = null;
        mShown = false;
    }

    /** Applies the current mode and opacity preferences to a shown overlay. */
    public synchronized void refresh() {
        if (!mShown || mView == null || mLayoutParams == null) return;

        mView.setAlpha(getAlphaPercent(mContext) / 100f);

        applyTouchable(mLayoutParams);

        try {
            mWindowManager.updateViewLayout(mView, mLayoutParams);
        } catch (Exception e) {
            // View was detached concurrently, ignore.
        }
    }

    public synchronized boolean isShown() {
        return mShown;
    }

    /**
     * Hides the overlay while a Termux activity is in the foreground and shows it again once the
     * whole app moves to the background. Driven by {@link AppForegroundTracker}.
     */
    private synchronized void onAppForegroundChanged(boolean foreground) {
        mForeground = foreground;
        if (foreground) {
            hide();
        } else if (isEnabled(mContext)) {
            show();
        }
    }

    private WindowManager.LayoutParams buildLayoutParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Never take focus, so typing and the back key keep going to the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        // Anchor at the top-start corner so the stored x/y offsets are stable.
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    private void applyTouchable(WindowManager.LayoutParams params) {
        if (isMovable(mContext)) {
            // Accept touches only so the view can be dragged. Clicks on the view itself are
            // swallowed by the (empty) touch listener, so nothing behind it is triggered.
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            attachDragListener();
        } else {
            // Let every touch pass straight through to whatever is underneath.
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            detachDragListener();
        }
    }

    private void attachDragListener() {
        if (mView == null) return;
        mView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mTouchStartX = mLayoutParams.x;
                    mTouchStartY = mLayoutParams.y;
                    mTouchStartRawX = event.getRawX();
                    mTouchStartRawY = event.getRawY();
                    mDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - mTouchStartRawX);
                    int dy = Math.round(event.getRawY() - mTouchStartRawY);
                    if (!mDragging && Math.abs(dx) + Math.abs(dy) < 8) return true;
                    mDragging = true;
                    mLayoutParams.x = mTouchStartX + dx;
                    mLayoutParams.y = mTouchStartY + dy;
                    try {
                        mWindowManager.updateViewLayout(mView, mLayoutParams);
                    } catch (Exception e) {
                        // View was detached concurrently, ignore.
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mDragging) {
                        mPreferences.edit()
                            .putInt(KEY_X, mLayoutParams.x)
                            .putInt(KEY_Y, mLayoutParams.y)
                            .apply();
                    }
                    mDragging = false;
                    return true;
                default:
                    return false;
            }
        });
    }

    private void detachDragListener() {
        if (mView != null) mView.setOnTouchListener(null);
    }
}
