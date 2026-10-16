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

/**
 * Drawer control dialog for the keep-alive {@link FloatingStatusOverlay}. Replaces the former
 * wrench tool-set dialog: it toggles the overlay, picks between the movable and the fully
 * click-through mode, and adjusts its opacity. If the "display over other apps" permission has
 * not been granted, enabling the overlay sends the user to the system permission page instead.
 */
public final class FloatingOverlayDialog {

    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 1001;

    public static void show(final TermuxActivity activity) {
        final View content = activity.getLayoutInflater().inflate(R.layout.dialog_floating_overlay, null);

        final SwitchMaterial enableSwitch = content.findViewById(R.id.overlay_enable_switch);
        final TextView permissionHint = content.findViewById(R.id.overlay_permission_hint);
        final RadioGroup modeGroup = content.findViewById(R.id.overlay_mode_group);
        final TextView alphaLabel = content.findViewById(R.id.overlay_alpha_label);
        final SeekBar alphaSeek = content.findViewById(R.id.overlay_alpha_seek);

        final boolean canDraw = FloatingStatusOverlay.canDrawOverlays(activity);

        enableSwitch.setChecked(FloatingStatusOverlay.isEnabled(activity));

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

        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.overlay_title)
            .setView(content)
            .setPositiveButton(R.string.overlay_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();

        // Override the positive button so the dialog only closes after a successful apply; when the
        // overlay permission is missing we keep the dialog open and send the user to the settings page.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean enabled = enableSwitch.isChecked();
            boolean wantMovable = modeGroup.getCheckedRadioButtonId() == R.id.overlay_mode_movable;
            int alpha = alphaSeek.getProgress() + alphaMin;

            if (enabled && !FloatingStatusOverlay.canDrawOverlays(activity)) {
                Toast.makeText(activity, R.string.overlay_permission_required, Toast.LENGTH_LONG).show();
                requestOverlayPermission(activity);
                return;
            }

            FloatingStatusOverlay.setMovable(activity, wantMovable);
            FloatingStatusOverlay.setAlphaPercent(activity, alpha);
            FloatingStatusOverlay.setEnabled(activity, enabled);

            FloatingStatusOverlay overlay = FloatingStatusOverlay.getInstance(activity);
            if (enabled) {
                if (!overlay.isShown()) overlay.show();
                else overlay.refresh();
                if (!overlay.isShown()) {
                    // show() can still fail even with permission on some ROMs.
                    Toast.makeText(activity, R.string.overlay_show_failed, Toast.LENGTH_LONG).show();
                    FloatingStatusOverlay.setEnabled(activity, false);
                    return;
                }
            } else {
                overlay.hide();
            }

            dialog.dismiss();
        }));

        dialog.show();
    }

    private static void updateAlphaLabel(TextView label, int percent) {
        label.setText(label.getContext().getString(R.string.overlay_alpha_title_with_value, percent));
    }

    private static void requestOverlayPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
            activity.startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
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
