package com.termux.app.tools;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.method.ScrollingMovementMethod;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Wrench dialog in the navigation drawer: quick access to termux-api / termux-tools
 * utilities. Long-running interactive tools (change-repo, pkg install) open in a
 * terminal session; one-shot commands run in the background and show their output.
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
            actions.add(() -> runInTerminal(activity, BIN + "/bash",
                new String[]{"-lc", "pkg install -y termux-api termux-tools"},
                activity.getString(R.string.tools_install_session_name)));
        }

        if (hasInfo) {
            labels.add(activity.getString(R.string.tools_system_info));
            actions.add(() -> runOutput(activity, "termux-info", "--no-set-clipboard"));
        }
        if (hasBattery) {
            labels.add(activity.getString(R.string.tools_battery));
            actions.add(() -> runOutput(activity, "termux-battery-status"));
        }
        if (hasWake) {
            labels.add(activity.getString(R.string.tools_wake_lock));
            actions.add(() -> runSimple(activity, "termux-wake-lock", R.string.tools_wake_lock_ok));
            labels.add(activity.getString(R.string.tools_wake_unlock));
            actions.add(() -> runSimple(activity, "termux-wake-unlock", R.string.tools_wake_unlock_ok));
        }
        if (hasStorage) {
            labels.add(activity.getString(R.string.tools_storage_setup));
            actions.add(() -> runSimple(activity, "termux-setup-storage", R.string.tools_storage_setup_hint));
        }
        if (hasRepo) {
            labels.add(activity.getString(R.string.tools_change_repo));
            actions.add(() -> runInTerminal(activity, BIN + "/termux-change-repo", null,
                activity.getString(R.string.tools_change_repo)));
        }

        new AlertDialog.Builder(activity)
            .setTitle(R.string.tools_title)
            .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run())
            .show();
    }

    /** Runs a one-shot command in the background and shows its output in a scrollable dialog. */
    private static void runOutput(final TermuxActivity activity, String... command) {
        Toast.makeText(activity, R.string.tools_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String output;
            try {
                output = exec(command);
            } catch (Exception e) {
                output = null;
            }
            final String result = output;
            activity.runOnUiThread(() -> {
                if (result == null || result.trim().isEmpty()) {
                    Toast.makeText(activity, R.string.tools_exec_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                showOutputDialog(activity, result);
            });
        }).start();
    }

    private static void showOutputDialog(Context context, String output) {
        TextView textView = new TextView(context);
        textView.setTextIsSelectable(true);
        textView.setPadding(32, 16, 32, 16);
        textView.setText(output);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(textView);

        new AlertDialog.Builder(context)
            .setTitle(R.string.tools_result)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.tools_copy, (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("termux-tools", output));
            })
            .show();
    }

    /** Runs a command and only reports success/failure via toast. */
    private static void runSimple(final TermuxActivity activity, String command, final int successMessageRes) {
        new Thread(() -> {
            boolean ok;
            try {
                exec(command);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean result = ok;
            activity.runOnUiThread(() -> Toast.makeText(activity,
                result ? successMessageRes : R.string.tools_exec_failed, Toast.LENGTH_LONG).show());
        }).start();
    }

    private static String exec(String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        Map<String, String> env = builder.environment();
        env.put("PATH", BIN + ":" + System.getenv("PATH"));
        env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        env.put("TERMUX_APP_PACKAGE", TermuxConstants.TERMUX_PACKAGE_NAME);
        env.put("TERMUX_API_APP_PACKAGE", TermuxConstants.TERMUX_API_PACKAGE_NAME);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroy();
            throw new Exception("timeout");
        }
        if (process.exitValue() != 0)
            throw new Exception("exit " + process.exitValue() + ": " + output);
        return output.toString();
    }

    /** Starts a visible terminal session running the given command. */
    private static void runInTerminal(TermuxActivity activity, String executable, String[] arguments, String name) {
        TermuxService service = activity.getTermuxService();
        if (service == null) return;
        TermuxSession session = service.createTermuxSession(executable, arguments, null,
            TermuxConstants.TERMUX_HOME_DIR_PATH, false, name);
        if (session == null) return;
        activity.getTermuxTerminalSessionClient().setCurrentSession(session.getTerminalSession());
        activity.getDrawer().closeDrawers();
    }

    private TermuxToolsDialog() { }
}
