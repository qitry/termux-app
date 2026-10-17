package com.termux.app.keepalive;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Preferences for the background keep-alive behaviour. Kept separate from the overlay preferences
 * so that toggling the floating window does not affect how the back key or the battery
 * optimization prompt behave.
 */
public final class KeepAlivePreferences {

    private static final String PREFS_NAME = "keep_alive";

    private static final String KEY_BACK_KEY_MINIMIZE = "back_key_minimize";
    private static final String KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown";
    private static final String KEY_IGNORE_SESSION_COUNT_WARNING = "ignore_session_count_warning";

    /** Back key minimizes the app instead of finishing the activity. */
    public static boolean isBackKeyMinimizeEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_BACK_KEY_MINIMIZE, true);
    }

    public static void setBackKeyMinimizeEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_BACK_KEY_MINIMIZE, enabled).apply();
    }

    /** Whether the one-time battery optimization prompt has already been shown. */
    public static boolean hasShownBatteryPrompt(Context context) {
        return getPrefs(context).getBoolean(KEY_BATTERY_PROMPT_SHOWN, false);
    }

    public static void markBatteryPromptShown(Context context) {
        getPrefs(context).edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, true).apply();
    }

    /** Whether the user chose to stop being warned about the number of open sessions. */
    public static boolean isSessionCountWarningIgnored(Context context) {
        return getPrefs(context).getBoolean(KEY_IGNORE_SESSION_COUNT_WARNING, false);
    }

    public static void setSessionCountWarningIgnored(Context context, boolean ignored) {
        getPrefs(context).edit().putBoolean(KEY_IGNORE_SESSION_COUNT_WARNING, ignored).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private KeepAlivePreferences() { }
}
