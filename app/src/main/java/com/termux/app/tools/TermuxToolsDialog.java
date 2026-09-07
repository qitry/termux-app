package com.termux.app.tools;

import android.app.AlertDialog;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Wrench dialog in the navigation drawer: quick access to termux-api / termux-tools
 * utilities. Every tool runs as a command in a fresh terminal session, so output and
 * interactive prompts are visible in the terminal itself.
 */
public final class TermuxToolsDialog {

    private static final String BIN = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin";

    public static void show(final TermuxActivity activity) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        boolean hasInfo = new File(BIN, "termux-info").exists();
        boolean hasRepo = new File(BIN, "termux-change-repo").exists();
        boolean hasBattery = new File(BIN, "termux-battery-status").exists();
        boolean hasStorage = new File(BIN, "termux-setup-storage").exists();
        boolean hasWake = new File(BIN, "termux-wake-lock").exists();

        if (!hasInfo || !hasRepo || !hasBattery || !hasStorage || !hasWake) {
            labels.add(activity.getString(R.string.tools_install));
            actions.add(() -> runInTerminal(activity, "pkg install -y termux-api termux-tools",
                activity.getString(R.string.tools_install_session_name)));
        }

        if (hasInfo) {
            labels.add(activity.getString(R.string.tools_system_info));
            actions.add(() -> runInTerminal(activity, "termux-info --no-set-clipboard",
                activity.getString(R.string.tools_system_info)));
        }
        if (hasBattery) {
            labels.add(activity.getString(R.string.tools_battery));
            actions.add(() -> runInTerminal(activity, "termux-battery-status",
                activity.getString(R.string.tools_battery)));
        }
        if (hasWake) {
            labels.add(activity.getString(R.string.tools_wake_lock));
            actions.add(() -> runInTerminal(activity, "termux-wake-lock",
                activity.getString(R.string.tools_wake_lock)));
            labels.add(activity.getString(R.string.tools_wake_unlock));
            actions.add(() -> runInTerminal(activity, "termux-wake-unlock",
                activity.getString(R.string.tools_wake_unlock)));
        }
        if (hasStorage) {
            labels.add(activity.getString(R.string.tools_storage_setup));
            actions.add(() -> runInTerminal(activity, "termux-setup-storage",
                activity.getString(R.string.tools_storage_setup)));
        }
        if (hasRepo) {
            labels.add(activity.getString(R.string.tools_change_repo));
            actions.add(() -> runInTerminal(activity, "termux-change-repo",
                activity.getString(R.string.tools_change_repo)));
        }

        new AlertDialog.Builder(activity)
            .setTitle(R.string.tools_title)
            .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run())
            .show();
    }

    /** Runs the given shell command in a fresh visible terminal session. */
    private static void runInTerminal(TermuxActivity activity, String command, String sessionName) {
        TermuxService service = activity.getTermuxService();
        if (service == null) {
            Toast.makeText(activity, R.string.tools_exec_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        TermuxSession session = service.createTermuxSession(BIN + "/bash",
            new String[]{"-lc", command}, null,
            TermuxConstants.TERMUX_HOME_DIR_PATH, false, sessionName);
        if (session == null) {
            Toast.makeText(activity, R.string.tools_exec_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        activity.getTermuxTerminalSessionClient().setCurrentSession(session.getTerminalSession());
        activity.getDrawer().closeDrawers();
    }

    private TermuxToolsDialog() { }
}
