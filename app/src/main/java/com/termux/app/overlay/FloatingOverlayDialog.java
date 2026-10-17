package com.termux.app.overlay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.switchmaterial.SwitchMaterial;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.keepalive.KeepAlivePreferences;
import com.termux.app.keepalive.KeepAliveUtils;

/**
 * Drawer control dialog for the keep-alive feature. It replaces the former wrench tool-set dialog
 * and combines two things:
 * <ul>
 *     <li>the {@link FloatingStatusOverlay} settings (enabled, interaction mode, opacity), and</li>
 *     <li>background keep-alive helpers: the back key behaviour, the battery optimization exemption
 *     and a ROM specific hint about autostart / background running.</li>
 * </ul>
 * Android cannot whitelist an app programmatically, so the keep-alive section only guides the user
 * to the system pages that matter.
 */
public final class FloatingOverlayDialog {

    public static void show(final TermuxActivity activity) {
        final View content = activity.getLayoutInflater().inflate(R.layout.dialog_floating_overlay, null);

        final SwitchMaterial enableSwitch = content.findViewById(R.id.overlay_enable_switch);
        final TextView permissionHint = content.findViewById(R.id.overlay_permission_hint);
        final RadioGroup modeGroup = content.findViewById(R.id.overlay_mode_group);
        final TextView alphaLabel = content.findViewById(R.id.overlay_alpha_label);
        final SeekBar alphaSeek = content.findViewById(R.id.overlay_alpha_seek);

        final SwitchMaterial backMinimizeSwitch = content.findViewById(R.id.keep_alive_back_minimize_switch);
        final TextView batteryStatus = content.findViewById(R.id.keep_alive_battery_status);
        final TextView romHint = content.findViewById(R.id.keep_alive_rom_hint);
        final TextView stats = content.findViewById(R.id.keep_alive_stats);

        final boolean canDraw = FloatingStatusOverlay.canDrawOverlays(activity);
        final boolean wasEnabled = FloatingStatusOverlay.isEnabled(activity);

        enableSwitch.setChecked(wasEnabled);

        boolean movable = FloatingStatusOverlay.isMovable(activity);
        modeGroup.check(movable ? R.id.overlay_mode_movable : R.id.overlay_mode_passthrough);

        final int alphaMin = FloatingStatusOverlay.getAlphaMin();
        final int alphaMax = FloatingStatusOverlay.getAlphaMax();
        alphaSeek.setMax(alphaMax - alphaMin);
        alphaSeek.setProgress(FloatingStatusOverlay.getAlphaPercent(activity) - alphaMin);
        updateAlphaLabel(alphaLabel, FloatingStatusOverlay.getAlphaPercent(activity));

        alphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateAlphaLabel(alphaLabel, progress + alphaMin);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        if (!canDraw) permissionHint.setVisibility(View.VISIBLE);

        // --- keep-alive section ---

        backMinimizeSwitch.setChecked(KeepAlivePreferences.isBackKeyMinimizeEnabled(activity));

        updateBatteryStatus(batteryStatus, activity);
        romHint.setText(KeepAliveUtils.getRomHint(activity));
        updateStats(stats, activity);

        content.findViewById(R.id.keep_alive_battery_button).setOnClickListener(v -> {
            if (KeepAliveUtils.isBatteryOptimizationIgnored(activity)) {
                Toast.makeText(activity, R.string.keep_alive_battery_already, Toast.LENGTH_SHORT).show();
                return;
            }
            KeepAliveUtils.requestIgnoreBatteryOptimizations(activity);
        });

        content.findViewById(R.id.keep_alive_app_settings_button)
            .setOnClickListener(v -> KeepAliveUtils.openAppDetailsSettings(activity));

        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.overlay_title)
            .setView(content)
            .setPositiveButton(R.string.overlay_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();

        // The positive button is overridden so the dialog only closes after a successful apply; when
        // the overlay permission is missing the dialog stays open and the user is sent to settings.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean enabled = enableSwitch.isChecked();
            boolean wantMovable = modeGroup.getCheckedRadioButtonId() == R.id.overlay_mode_movable;
            int alpha = alphaSeek.getProgress() + alphaMin;

            KeepAlivePreferences.setBackKeyMinimizeEnabled(activity, backMinimizeSwitch.isChecked());

            if (enabled && !FloatingStatusOverlay.canDrawOverlays(activity)) {
                Toast.makeText(activity, R.string.overlay_permission_required, Toast.LENGTH_LONG).show();
                requestOverlayPermission(activity);
                return;
            }

            FloatingStatusOverlay.setMovable(activity, wantMovable);
            FloatingStatusOverlay.setAlphaPercent(activity, alpha);
            FloatingStatusOverlay.setEnabled(activity, enabled);

            FloatingStatusOverlay overlay = FloatingStatusOverlay.getInstance(activity);
            if (overlay.isShown()) overlay.refresh();
            else if (!enabled) overlay.hide();

            if (enabled && !overlay.isShown() && !wasEnabled) {
                // The activity is in the foreground, so the overlay is intentionally kept hidden
                // until the app goes to the background. Only mention it when it was just turned on.
                Toast.makeText(activity, R.string.overlay_enabled_background_only, Toast.LENGTH_SHORT).show();
            }

            // The first time keep-alive is turned on, ask for the battery optimization exemption:
            // on aggressive ROMs it is the single most effective measure to avoid being killed.
            if (enabled && !KeepAlivePreferences.hasShownBatteryPrompt(activity)) {
                KeepAlivePreferences.markBatteryPromptShown(activity);
                if (!KeepAliveUtils.isBatteryOptimizationIgnored(activity)) {
                    KeepAliveUtils.requestIgnoreBatteryOptimizations(activity);
                }
            }

            dialog.dismiss();
        }));

        dialog.show();
    }

    private static void updateAlphaLabel(TextView label, int percent) {
        label.setText(label.getContext().getString(R.string.overlay_alpha_title_with_value, percent));
    }

    private static void updateBatteryStatus(TextView view, Activity activity) {
        boolean ignored = KeepAliveUtils.isBatteryOptimizationIgnored(activity);
        view.setText(ignored
            ? R.string.keep_alive_battery_status_ok
            : R.string.keep_alive_battery_status_pending);
    }

    private static void updateStats(TextView view, TermuxActivity activity) {
        TermuxService service = activity.getTermuxService();
        int sessionCount = service == null ? 0 : service.getTermuxSessionsSize();
        int transcriptRows = activity.getProperties().getTerminalTranscriptRows();
        view.setText(activity.getString(R.string.keep_alive_stats, sessionCount, transcriptRows));
    }

    private static void requestOverlayPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            // Fall back to the app details page if the dedicated overlay settings page is missing.
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception ignored) { }
        }
    }

    private FloatingOverlayDialog() { }
}
