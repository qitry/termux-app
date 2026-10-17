package com.termux.app.keepalive;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.termux.R;
import com.termux.shared.android.PermissionUtils;

/**
 * Helpers for the background keep-alive panel. Android gives no API to whitelist an app against
 * aggressive OEM task killers, so this class can only query the standard battery optimization
 * state and send the user to the relevant system pages.
 */
public final class KeepAliveUtils {

    private KeepAliveUtils() { }

    /** Whether the app is already exempt from battery optimizations. */
    public static boolean isBatteryOptimizationIgnored(Context context) {
        return PermissionUtils.checkIfBatteryOptimizationsDisabled(context);
    }

    /** Asks the system to let the app ignore battery optimizations. */
    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        if (isBatteryOptimizationIgnored(activity)) return;
        PermissionUtils.requestDisableBatteryOptimizations(activity);
    }

    /** Opens the system app details page, where OEM autostart / background switches usually live. */
    public static void openAppDetailsSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception ignored) {
            // Nothing else we can do if the settings page is unavailable.
        }
    }

    /**
     * A short, ROM specific hint about where to allow background running. There is no way to deep
     * link into these OEM pages, so the text is the best we can offer.
     */
    public static String getRomHint(Context context) {
        String manufacturer = safeLowerCase(Build.MANUFACTURER);

        if (manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme")) {
            return context.getString(R.string.keep_alive_hint_coloros);
        } else if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) {
            return context.getString(R.string.keep_alive_hint_miui);
        } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            return context.getString(R.string.keep_alive_hint_emui);
        } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            return context.getString(R.string.keep_alive_hint_originos);
        } else if (manufacturer.contains("samsung")) {
            return context.getString(R.string.keep_alive_hint_samsung);
        }
        return context.getString(R.string.keep_alive_hint_generic);
    }

    private static String safeLowerCase(String value) {
        return value == null ? "" : value.toLowerCase();
    }
}
