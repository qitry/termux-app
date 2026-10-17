package com.termux.app.keepalive;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;

/**
 * Process wide tracker of how many Termux activities are currently resumed.
 * <p/>
 * The keep-alive overlay is only useful while the app is in the background, so it must stay hidden
 * whenever any of the app's own screens is in front of the user (terminal, file manager, editor,
 * settings...). Counting resumed activities here avoids having to add the same resume/stop hooks to
 * every activity and keeps the overlay from flashing over another screen of the app.
 */
public final class AppForegroundTracker implements Application.ActivityLifecycleCallbacks {

    private static AppForegroundTracker sInstance;

    private int mResumedActivities;
    private Listener mListener;

    /** Receives a callback whenever the app enters or leaves the foreground. */
    public interface Listener {
        void onAppForegroundChanged(boolean foreground);
    }

    private AppForegroundTracker() { }

    /** Registers the tracker on the application, once. Safe to call repeatedly. */
    public static synchronized AppForegroundTracker init(@NonNull Application application) {
        if (sInstance == null) {
            sInstance = new AppForegroundTracker();
            application.registerActivityLifecycleCallbacks(sInstance);
        }
        return sInstance;
    }

    public static synchronized AppForegroundTracker get() {
        return sInstance;
    }

    /**
     * Sets the listener and immediately reports the current state, so a listener registered while
     * the app is already in the background is not left thinking the app is in the foreground.
     */
    public synchronized void setListener(Listener listener) {
        mListener = listener;
        if (listener != null) {
            listener.onAppForegroundChanged(mResumedActivities > 0);
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) { }

    @Override
    public void onActivityStarted(@NonNull Activity activity) { }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        synchronized (this) {
            mResumedActivities++;
            if (mResumedActivities != 1) return;
        }
        if (mListener != null) mListener.onAppForegroundChanged(true);
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        synchronized (this) {
            if (mResumedActivities > 0) mResumedActivities--;
            if (mResumedActivities != 0) return;
        }
        if (mListener != null) mListener.onAppForegroundChanged(false);
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) { }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) { }
}
