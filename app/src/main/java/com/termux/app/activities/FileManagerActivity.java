package com.termux.app.activities;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.filemanager.ArchiveSource;
import com.termux.app.filemanager.FileEntry;
import com.termux.app.filemanager.FileIcons;
import com.termux.app.filemanager.FileManagerAdapter;
import com.termux.app.filemanager.FileManagerUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.interact.TextInputDialogUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileManagerActivity extends AppCompatActivity {

    private static final int REQUEST_STORAGE_PERMISSIONS = 101;

    private static final int MENU_ITEM_NEW_FOLDER = 1;

    private static final long MAX_EDITABLE_SIZE = 5 * 1024 * 1024;


    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final FilePane mTermuxPane = new FilePane();
    private final FilePane mAndroidPane = new FilePane();
    private FilePane mActivePane;

    private TextView mPathText;

    private ActionMode mActionMode;

    /** Cached archive passwords keyed by archive absolute path. */
    private final Map<String, char[]> mArchivePasswords = new HashMap<>();

    protected class FilePane {
        View container;
        View indicator;
        RecyclerView listView;
        FileManagerAdapter adapter;
        File rootDirectory;
        File currentDirectory;
        /** Non-null while browsing inside an archive file. */
        File archiveContext;
        /** Entry path prefix inside {@link #archiveContext}; "" for archive root. */
        String archivePrefix = "";
        /** Non-null while showing search results instead of a directory listing. */
        String searchQuery;
        final Deque<PaneLocation> history = new ArrayDeque<>();
    }

    private static class PaneLocation {
        final File directory;
        final File archive;
        final String prefix;

        PaneLocation(File directory) {
            this.directory = directory; this.archive = null; this.prefix = null;
        }

        PaneLocation(File archive, String prefix) {
            this.directory = null; this.archive = archive; this.prefix = prefix;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        mPathText = findViewById(R.id.file_manager_path);
        findViewById(R.id.file_manager_terminal).setOnClickListener(v -> finish());
        findViewById(R.id.file_manager_search).setOnClickListener(v -> promptSearch());
        findViewById(R.id.file_manager_refresh).setOnClickListener(v -> refreshPane(mActivePane));
        mPathText.setOnClickListener(v -> promptPathJump());

        setupPane(mTermuxPane, R.id.termux_pane_container, R.id.termux_pane_indicator, R.id.termux_pane_list,
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        setupPane(mAndroidPane, R.id.android_pane_container, R.id.android_pane_indicator, R.id.android_pane_list,
            Environment.getExternalStorageDirectory());

        setActivePane(mTermuxPane);

        loadPane(mTermuxPane, mTermuxPane.rootDirectory);
        loadPane(mAndroidPane, mAndroidPane.rootDirectory);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && !PermissionUtils.checkPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            PermissionUtils.requestPermissions(this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_STORAGE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSIONS)
            loadPane(mAndroidPane, mAndroidPane.currentDirectory);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    /** Runs a task off the main thread, never letting a stray exception crash the app. */
    private void runBackground(Runnable task) {
        mExecutor.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                android.util.Log.e("FileManager", "Background task failed", t);
                mHandler.post(() -> Toast.makeText(FileManagerActivity.this,
                    R.string.fm_operation_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void setupPane(FilePane pane, int containerId, int indicatorId, int listId, File root) {
        pane.rootDirectory = root;
        pane.currentDirectory = root;
        pane.container = findViewById(containerId);
        pane.indicator = pane.container.findViewById(indicatorId);
        pane.listView = pane.container.findViewById(listId);

        pane.listView.setLayoutManager(new LinearLayoutManager(this));
        pane.listView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        pane.adapter = new FileManagerAdapter(new FileManagerAdapter.Callbacks() {
            @Override
            public void onFileClicked(FileEntry entry) {
                handleFileClicked(pane, entry);
            }

            @Override
            public void onFileLongClicked(FileEntry entry) {
                handleFileLongClicked(pane, entry);
            }

            @Override
            public void onSelectionChanged(int count) {
                // Only claim focus on real selections; clearing (action mode teardown) must not
                // steal the active pane.
                if (count > 0) setActivePane(pane);
                if (mActionMode != null) mActionMode.invalidate();
            }
        });
        pane.listView.setAdapter(pane.adapter);

        // Horizontal swipe selects; a second swipe extends the range from the previous anchor.
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
            ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getBindingAdapterPosition();
                FileEntry entry = pane.adapter.getEntryAt(position);
                if (entry == null) return;
                if (entry.isSpecial()) {
                    pane.adapter.notifyItemChanged(position);
                    return;
                }
                handleSwipeSelected(pane, entry);
            }

            @Override
            public void onChildDraw(@NonNull android.graphics.Canvas c, @NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                // Keep the row visually in place: selection must not drag the file name off screen.
                super.onChildDraw(c, recyclerView, viewHolder, 0f, 0f, actionState, isCurrentlyActive);
            }
        });
        touchHelper.attachToRecyclerView(pane.listView);

        pane.listView.setOnLongClickListener(v -> {
            if (mActionMode != null) return false;
            setActivePane(pane);
            showBackgroundMenu(v);
            return true;
        });
        // Focus on any touch inside the pane (CSS-hover-like): ACTION_DOWN claims focus without
        // consuming the event, so scrolling and item gestures keep working.
        pane.listView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) setActivePane(pane);
                return false;
            }
        });
        pane.listView.setOnClickListener(v -> setActivePane(pane));
        pane.container.setOnClickListener(v -> setActivePane(pane));
    }

    private void setActivePane(FilePane pane) {
        mActivePane = pane;
        mTermuxPane.indicator.setVisibility(pane == mTermuxPane ? View.VISIBLE : View.INVISIBLE);
        mAndroidPane.indicator.setVisibility(pane == mAndroidPane ? View.VISIBLE : View.INVISIBLE);
        updatePathText();
    }

    private void updatePathText() {
        if (mActivePane == null) return;
        if (mActivePane.searchQuery != null) {
            mPathText.setText(getString(R.string.fm_searching, mActivePane.searchQuery));
        } else if (mActivePane.archiveContext != null) {
            String path = mActivePane.archiveContext.getAbsolutePath();
            if (!mActivePane.archivePrefix.isEmpty()) path += "/" + mActivePane.archivePrefix;
            mPathText.setText(path);
        } else {
            mPathText.setText(mActivePane.currentDirectory.getAbsolutePath());
        }
    }

    protected void loadPane(FilePane pane, File directory) {
        runBackground(() -> {
            File[] files = directory.listFiles();
            List<FileEntry> entries = new ArrayList<>();
            if (files != null) {
                for (File file : files) entries.add(FileEntry.fromFile(file));
            }
            sortEntries(entries);
            mHandler.post(() -> {
                pane.archiveContext = null;
                pane.archivePrefix = "";
                pane.currentDirectory = directory;
                pane.searchQuery = null;
                applyEntries(pane, entries);
                if (pane == mActivePane) updatePathText();
            });
        });
    }

    private void loadArchivePane(FilePane pane, File archive, String prefix) {
        runBackground(() -> doLoadArchivePane(pane, archive, prefix, true));
    }

    private void doLoadArchivePane(FilePane pane, File archive, String prefix, boolean allowPrompt) {
        char[] password = mArchivePasswords.get(archive.getAbsolutePath());
        List<FileEntry> entries;
        try {
            entries = ArchiveSource.list(archive, prefix, password);
        } catch (ArchiveSource.PasswordRequiredException | ArchiveSource.WrongPasswordException e) {
            mArchivePasswords.remove(archive.getAbsolutePath());
            if (allowPrompt) promptArchivePassword(archive, () -> doLoadArchivePane(pane, archive, prefix, false));
            else mHandler.post(() -> Toast.makeText(FileManagerActivity.this,
                R.string.fm_wrong_password, Toast.LENGTH_SHORT).show());
            return;
        } catch (IOException e) {
            mHandler.post(() -> Toast.makeText(FileManagerActivity.this,
                R.string.fm_archive_failed, Toast.LENGTH_SHORT).show());
            return;
        }
        mHandler.post(() -> {
            pane.archiveContext = archive;
            pane.archivePrefix = prefix;
            pane.searchQuery = null;
            applyEntries(pane, entries);
            if (pane == mActivePane) updatePathText();
        });
    }

    private void promptArchivePassword(File archive, Runnable onEntered) {
        mHandler.post(() -> TextInputDialogUtils.textInput(this, R.string.fm_password_title, "",
            R.string.fm_confirm, text -> {
                mArchivePasswords.put(archive.getAbsolutePath(), text.toCharArray());
                runBackground(onEntered);
            }, -1, null, -1, null, null));
    }

    /** Runs an archive operation, prompting for a password when required. */
    private interface ArchiveOp {
        boolean run(char[] password) throws IOException;
    }

    private void withArchivePassword(File archive, ArchiveOp op) {
        runBackground(() -> {
            char[] password = mArchivePasswords.get(archive.getAbsolutePath());
            try {
                if (!op.run(password)) {
                    mHandler.post(() -> Toast.makeText(FileManagerActivity.this,
                        R.string.fm_extract_failed, Toast.LENGTH_SHORT).show());
                }
            } catch (ArchiveSource.PasswordRequiredException | ArchiveSource.WrongPasswordException e) {
                mArchivePasswords.remove(archive.getAbsolutePath());
                promptArchivePassword(archive, () -> {
                    try {
                        boolean ok = op.run(mArchivePasswords.get(archive.getAbsolutePath()));
                        mHandler.post(() -> finishArchiveOp(ok));
                    } catch (IOException ex) {
                        finishArchiveOp(false);
                    }
                });
            } catch (IOException e) {
                mHandler.post(() -> finishArchiveOp(false));
            }
        });
    }

    private void finishArchiveOp(boolean ok) {
        refreshBothPanes();
        if (!ok) Toast.makeText(this, R.string.fm_extract_failed, Toast.LENGTH_SHORT).show();
    }

    private void applyEntries(FilePane pane, List<FileEntry> entries) {
        List<FileEntry> display = new ArrayList<>();
        if (!pane.history.isEmpty())
            display.add(FileEntry.special(getString(R.string.fm_back_to_previous), FileEntry.SPECIAL_BACK));
        if (canGoUp(pane))
            display.add(FileEntry.special(getString(R.string.fm_parent_directory), FileEntry.SPECIAL_PARENT));
        display.addAll(entries);
        pane.adapter.setItems(display);
    }

    private boolean canGoUp(FilePane pane) {
        if (pane.archiveContext != null) return true;
        return !pane.currentDirectory.getAbsolutePath().equals(pane.rootDirectory.getAbsolutePath());
    }

    private void pushHistory(FilePane pane) {
        pane.history.addLast(pane.archiveContext != null
            ? new PaneLocation(pane.archiveContext, pane.archivePrefix)
            : new PaneLocation(pane.currentDirectory));
        while (pane.history.size() > 50) pane.history.removeFirst();
    }

    private void popHistory(FilePane pane) {
        PaneLocation location = pane.history.pollLast();
        if (location == null) return;
        if (location.archive != null) loadArchivePane(pane, location.archive, location.prefix);
        else loadPane(pane, location.directory);
    }

    private static void sortEntries(List<FileEntry> entries) {
        Collections.sort(entries, (a, b) -> {
            if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
            return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
        });
    }

    /** The on-disk directory a pane's operations should target (archive's parent when inside one). */
    private File paneDiskDirectory(FilePane pane) {
        return pane.archiveContext != null ? pane.archiveContext.getParentFile() : pane.currentDirectory;
    }

    private void navigateUp() {
        navigateUp(mActivePane);
    }

    private void navigateUp(FilePane pane) {
        if (pane.archiveContext != null) {
            String prefix = pane.archivePrefix;
            if (!prefix.isEmpty()) {
                int slash = prefix.lastIndexOf('/');
                loadArchivePane(pane, pane.archiveContext, slash < 0 ? "" : prefix.substring(0, slash));
            } else {
                loadPane(pane, pane.archiveContext.getParentFile());
            }
            return;
        }
        File current = pane.currentDirectory;
        if (current.getAbsolutePath().equals(pane.rootDirectory.getAbsolutePath())) return;
        File parent = current.getParentFile();
        if (parent != null) loadPane(pane, parent);
    }

    private void handleFileClicked(FilePane pane, FileEntry entry) {
        setActivePane(pane);
        if (entry.special == FileEntry.SPECIAL_PLACEHOLDER) return;
        if (entry.special == FileEntry.SPECIAL_BACK) {
            popHistory(pane);
            return;
        }
        if (entry.special == FileEntry.SPECIAL_PARENT) {
            pushHistory(pane);
            navigateUp(pane);
            return;
        }
        if (entry.isArchiveEntry()) {
            if (entry.isDirectory) {
                pushHistory(pane);
                loadArchivePane(pane, entry.archiveHostFile, entry.archiveEntryPath);
            } else {
                openArchiveEntry(entry);
            }
            return;
        }
        if (entry.isDirectory) {
            pushHistory(pane);
            loadPane(pane, entry.hostFile);
        } else if (FileIcons.isArchive(entry.hostFile)) {
            pushHistory(pane);
            loadArchivePane(pane, entry.hostFile, "");
        } else {
            String mode = getDefaultOpenMode(entry);
            if (mode != null) {
                openWithMode(entry, mode);
            } else if (FileIcons.isImage(entry)) {
                launchViewer(entry.hostFile);
            } else if (FileIcons.isEditable(entry) && entry.size <= MAX_EDITABLE_SIZE) {
                launchEditor(entry.hostFile);
            } else {
                openFile(entry.hostFile);
            }
        }
    }

    private void launchEditor(File file) {
        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_PATH, file.getAbsolutePath());
        startActivity(intent);
    }

    private void launchViewer(File file) {
        Intent intent = new Intent(this, ImageViewerActivity.class);
        intent.putExtra(ImageViewerActivity.EXTRA_PATH, file.getAbsolutePath());
        startActivity(intent);
    }

    // --- open with / per-extension defaults ---

    private static final String MODE_SYSTEM = "system";
    private static final String MODE_EDITOR = "editor";
    private static final String MODE_VIEWER = "viewer";
    private static final String MODE_ARCHIVE = "archive";

    private String getDefaultOpenMode(FileEntry entry) {
        String mode = getSharedPreferences("file_manager", MODE_PRIVATE)
            .getString("open_default_" + FileIcons.extensionOf(entry.name), null);
        return mode == null || mode.isEmpty() ? null : mode;
    }

    private void setDefaultOpenMode(FileEntry entry, String mode) {
        String ext = FileIcons.extensionOf(entry.name);
        getSharedPreferences("file_manager", MODE_PRIVATE).edit()
            .putString("open_default_" + ext, mode == null ? "" : mode).apply();
        Toast.makeText(this, getString(R.string.fm_default_set,
            ext.isEmpty() ? entry.name : "." + ext,
            getString(modeLabelRes(mode))), Toast.LENGTH_SHORT).show();
    }

    private static int modeLabelRes(String mode) {
        if (MODE_EDITOR.equals(mode)) return R.string.fm_mode_editor;
        if (MODE_VIEWER.equals(mode)) return R.string.fm_mode_viewer;
        if (MODE_ARCHIVE.equals(mode)) return R.string.fm_mode_archive;
        return R.string.fm_mode_system;
    }

    private void openWithMode(FileEntry entry, String mode) {
        if (MODE_EDITOR.equals(mode)) launchEditor(entry.hostFile);
        else if (MODE_VIEWER.equals(mode)) launchViewer(entry.hostFile);
        else if (MODE_ARCHIVE.equals(mode)) loadArchivePane(mActivePane, entry.hostFile, "");
        else openFile(entry.hostFile);
    }

    private void showOpenWithDialog(FileEntry entry) {
        final boolean archiveEntry = entry.isArchiveEntry();
        final String[] modes = {MODE_SYSTEM, MODE_EDITOR, MODE_VIEWER, MODE_ARCHIVE};
        final int[] labels = {R.string.fm_mode_system, R.string.fm_mode_editor,
            R.string.fm_mode_viewer, R.string.fm_mode_archive};

        List<String> items = new ArrayList<>();
        for (int label : labels) items.add(getString(label));

        final AlertDialog[] holder = new AlertDialog[1];
        holder[0] = new AlertDialog.Builder(this)
            .setTitle(R.string.fm_open_with_title)
            .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
                String mode = modes[which];
                if (archiveEntry) {
                    if (MODE_SYSTEM.equals(mode)) openArchiveEntry(entry);
                    else Toast.makeText(this, R.string.fm_mode_unsupported_in_archive, Toast.LENGTH_SHORT).show();
                } else {
                    openWithMode(entry, mode);
                }
            })
            .create();
        holder[0].show();
        holder[0].getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            if (archiveEntry) return true;
            String mode = modes[position];
            setDefaultOpenMode(entry, MODE_SYSTEM.equals(mode) ? null : mode);
            holder[0].dismiss();
            return true;
        });
    }

    private void openArchiveEntry(FileEntry entry) {
        withArchivePassword(entry.archiveHostFile, password -> {
            File cacheDir = new File(getCacheDir(), "fm_open");
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) return false;
            if (!ArchiveSource.extractEntry(entry.archiveHostFile, entry.archiveEntryPath, cacheDir, password))
                return false;
            File extracted = new File(cacheDir, entry.archiveEntryPath);
            mHandler.post(() -> openFile(extracted));
            return true;
        });
    }

    private void handleSwipeSelected(FilePane pane, FileEntry entry) {
        setActivePane(pane);
        if (mActionMode == null) {
            mActionMode = startSupportActionMode(new SelectionActionModeCallback());
            mTermuxPane.adapter.clearSelection();
            mAndroidPane.adapter.clearSelection();
            pane.adapter.startSelection(entry);
        } else {
            pane.adapter.selectRange(entry);
        }
    }

    private void handleFileLongClicked(FilePane pane, FileEntry entry) {
        setActivePane(pane);
        List<FileEntry> targets;
        if (mActionMode != null && pane.adapter.getSelected().contains(entry)) {
            targets = getSelectedEntries();
        } else {
            targets = new ArrayList<>();
            targets.add(entry);
        }
        showOperationsDialog(targets);
    }

    protected List<FileEntry> getSelectedEntries() {
        List<FileEntry> selected = new ArrayList<>(mTermuxPane.adapter.getSelected());
        selected.addAll(mAndroidPane.adapter.getSelected());
        return selected;
    }

    private class SelectionActionModeCallback implements ActionMode.Callback {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.fm_selection, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            mode.setTitle(getString(R.string.fm_selected_count, getSelectedEntries().size()));
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.fm_menu_select_all) {
                mActivePane.adapter.selectAll();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mActionMode = null;
            mTermuxPane.adapter.clearSelection();
            mAndroidPane.adapter.clearSelection();
        }
    }

    // --- operations dialog ---

    private void showOperationsDialog(List<FileEntry> targets) {
        if (targets.isEmpty()) return;

        final List<String> labels = new ArrayList<>();
        final List<Integer> icons = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        boolean single = targets.size() == 1;
        FileEntry entry = single ? targets.get(0) : null;

        if (single && entry.isArchiveEntry()) {
            addOp(labels, icons, actions, R.string.fm_extract, R.drawable.ic_fm_extract, () -> extractEntries(targets));
            addOp(labels, icons, actions, R.string.fm_open_with, R.drawable.ic_fm_open, () -> openArchiveEntry(entry));
        } else if (single && !entry.isDirectory && FileIcons.isArchiveName(entry.name)) {
            addOp(labels, icons, actions, R.string.fm_extract, R.drawable.ic_fm_extract, () -> extractEntries(targets));
            addOp(labels, icons, actions, R.string.fm_open_with, R.drawable.ic_fm_open, () -> showOpenWithDialog(entry));
        } else if (single && FileIcons.isImage(entry)) {
            addOp(labels, icons, actions, R.string.fm_view_image, R.drawable.ic_fm_image, () -> launchViewer(entry.hostFile));
            addOp(labels, icons, actions, R.string.fm_open_with, R.drawable.ic_fm_open, () -> showOpenWithDialog(entry));
        } else if (single && FileIcons.isEditable(entry) && entry.hostFile != null && entry.size <= MAX_EDITABLE_SIZE) {
            addOp(labels, icons, actions, R.string.fm_edit, R.drawable.ic_fm_code, () -> launchEditor(entry.hostFile));
            addOp(labels, icons, actions, R.string.fm_open_with, R.drawable.ic_fm_open, () -> showOpenWithDialog(entry));
        } else if (single && !entry.isDirectory) {
            addOp(labels, icons, actions, R.string.fm_open_with, R.drawable.ic_fm_open, () -> showOpenWithDialog(entry));
        }

        boolean allReal = true;
        for (FileEntry target : targets) {
            if (target.isArchiveEntry()) { allReal = false; break; }
        }

        if (single && !entry.isArchiveEntry()) {
            addOp(labels, icons, actions, R.string.fm_rename, R.drawable.ic_fm_edit, () -> renameEntries(targets));
        }
        if (allReal) {
            addOp(labels, icons, actions, R.string.fm_compress, R.drawable.ic_fm_archive, () -> promptCompress(targets));
            addOp(labels, icons, actions, R.string.fm_move, R.drawable.ic_fm_move, () -> pickTargetAndTransfer(targets, true));
            addOp(labels, icons, actions, R.string.fm_copy, R.drawable.ic_fm_copy, () -> pickTargetAndTransfer(targets, false));
            addOp(labels, icons, actions, R.string.fm_delete, R.drawable.ic_fm_delete, () -> confirmDelete(targets));
        }

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(8 * density);
        grid.setPadding(padding, padding, padding, padding);

        for (int i = 0; i < labels.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(buildOpCell(labels.get(i), icons.get(i), dialogHolder, actions.get(i)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (i + 1 < labels.size())
                row.addView(buildOpCell(labels.get(i + 1), icons.get(i + 1), dialogHolder, actions.get(i + 1)),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            else
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
            grid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        dialogHolder[0] = new AlertDialog.Builder(this)
            .setTitle(single ? entry.name : getString(R.string.fm_selected_count, targets.size()))
            .setView(grid)
            .show();
    }

    private void addOp(List<String> labels, List<Integer> icons, List<Runnable> actions,
                       int labelRes, int iconRes, Runnable action) {
        labels.add(getString(labelRes));
        icons.add(iconRes);
        actions.add(action);
    }

    private View buildOpCell(String label, int iconRes, AlertDialog[] dialogHolder, Runnable action) {
        float density = getResources().getDisplayMetrics().density;

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setPadding(Math.round(12 * density), Math.round(12 * density),
            Math.round(12 * density), Math.round(12 * density));
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        cell.setBackgroundResource(outValue.resourceId);
        cell.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            action.run();
        });

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        TypedValue tintValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, tintValue, true);
        if (tintValue.resourceId != 0)
            icon.setImageTintList(getColorStateList(tintValue.resourceId));
        else
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(tintValue.data));
        cell.addView(icon, new LinearLayout.LayoutParams(Math.round(24 * density), Math.round(24 * density)));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(13);
        text.setGravity(android.view.Gravity.CENTER);
        TypedValue textColorValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, textColorValue, true);
        if (textColorValue.resourceId != 0)
            text.setTextColor(getColorStateList(textColorValue.resourceId));
        cell.addView(text);

        return cell;
    }

    private void pickTargetAndTransfer(List<FileEntry> targets, boolean move) {
        FilePane opposite = mActivePane == mTermuxPane ? mAndroidPane : mTermuxPane;
        File activeDir = paneDiskDirectory(mActivePane);
        File oppositeDir = paneDiskDirectory(opposite);

        String[] options = {
            getString(R.string.fm_target_opposite, oppositeDir.getAbsolutePath()),
            getString(R.string.fm_target_current, activeDir.getAbsolutePath())
        };
        new AlertDialog.Builder(this)
            .setTitle(move ? R.string.fm_move : R.string.fm_copy)
            .setItems(options, (dialog, which) ->
                transferEntries(targets, which == 0 ? oppositeDir : activeDir, move))
            .show();
    }

    private void transferEntries(List<FileEntry> targets, File targetDir, boolean move) {
        List<File> sources = new ArrayList<>();
        for (FileEntry entry : targets) {
            if (entry.hostFile != null) sources.add(entry.hostFile);
        }
        if (sources.isEmpty()) return;

        boolean hasConflict = false;
        for (File src : sources) {
            if (new File(targetDir, src.getName()).exists()) {
                hasConflict = true;
                break;
            }
        }

        if (hasConflict) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.fm_conflict_title)
                .setMessage(R.string.fm_conflict_message)
                .setPositiveButton(R.string.fm_overwrite, (dialog, which) -> doTransfer(sources, targetDir, move))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        } else {
            doTransfer(sources, targetDir, move);
        }
    }

    private void doTransfer(List<File> sources, File targetDir, boolean move) {
        runBackground(() -> {
            boolean failed = false;
            for (File src : sources) {
                File destination = new File(targetDir, src.getName());
                if (destination.getAbsolutePath().equals(src.getAbsolutePath())) continue;
                if (destination.exists() && !FileManagerUtils.deleteRecursive(destination)) {
                    failed = true;
                    continue;
                }
                boolean ok = move ? FileManagerUtils.move(src, destination)
                    : FileManagerUtils.copyRecursive(src, destination);
                if (!ok) failed = true;
            }
            boolean finalFailed = failed;
            mHandler.post(() -> {
                refreshBothPanes();
                if (finalFailed)
                    Toast.makeText(FileManagerActivity.this,
                        move ? R.string.fm_move_failed : R.string.fm_copy_failed, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void extractEntries(List<FileEntry> targets) {
        if (targets.size() != 1) return;
        FileEntry entry = targets.get(0);
        File targetDir = paneDiskDirectory(mActivePane);

        File archive = entry.isArchiveEntry() ? entry.archiveHostFile : entry.hostFile;
        withArchivePassword(archive, password -> {
            if (entry.isArchiveEntry())
                return ArchiveSource.extractEntry(entry.archiveHostFile, entry.archiveEntryPath, targetDir, password);
            File destDir = new File(targetDir, baseNameOf(entry.hostFile));
            return ArchiveSource.extractAll(entry.hostFile, destDir, password);
        });
    }

    private void promptCompress(List<FileEntry> targets) {
        List<File> sources = new ArrayList<>();
        for (FileEntry entry : targets) {
            if (entry.hostFile != null) sources.add(entry.hostFile);
        }
        if (sources.isEmpty()) return;

        String defaultName = sources.size() == 1 ? baseNameOf(sources.get(0)) : "Archive";
        File targetDir = paneDiskDirectory(mActivePane);

        TextInputDialogUtils.textInput(this, R.string.fm_compress, defaultName,
            R.string.fm_confirm, text -> {
                String name = text.trim();
                if (name.isEmpty() || name.contains("/")) {
                    Toast.makeText(FileManagerActivity.this, R.string.fm_invalid_name, Toast.LENGTH_SHORT).show();
                    return;
                }
                File destFile = new File(targetDir, name.endsWith(".zip") ? name : name + ".zip");
                List<File> files = new ArrayList<>(sources);
                promptCompressPassword(files, destFile);
            }, -1, null, -1, null, null);
    }

    private void promptCompressPassword(List<File> files, File destFile) {
        TextInputDialogUtils.textInput(this, R.string.fm_compress_password, "",
            R.string.fm_confirm, text -> startCompress(files, destFile, text.toCharArray()),
            R.string.fm_no_password, text -> startCompress(files, destFile, null),
            -1, null, null);
    }

    private void startCompress(List<File> files, File destFile, char[] password) {
        runBackground(() -> {
            boolean ok = ArchiveSource.compressZip(files, destFile, password);
            mHandler.post(() -> {
                refreshBothPanes();
                if (!ok)
                    Toast.makeText(FileManagerActivity.this, R.string.fm_compress_failed, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static String baseNameOf(File archive) {
        String name = archive.getName();
        String lower = name.toLowerCase(Locale.US);
        String[] suffixes = {".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tar", ".zip", ".jar", ".war", ".apk", ".7z", ".gz", ".bz2", ".xz"};
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) return name.substring(0, name.length() - suffix.length());
        }
        return name;
    }

    private void renameEntries(List<FileEntry> targets) {
        if (targets.size() != 1) return;
        File file = targets.get(0).hostFile;
        if (file == null) return;

        TextInputDialogUtils.textInput(this, R.string.fm_rename, file.getName(),
            R.string.fm_confirm, text -> {
                String newName = text.trim();
                if (newName.isEmpty() || newName.contains("/")) {
                    Toast.makeText(FileManagerActivity.this, R.string.fm_invalid_name, Toast.LENGTH_SHORT).show();
                    return;
                }
                File destination = new File(file.getParentFile(), newName);
                if (destination.exists()) {
                    Toast.makeText(FileManagerActivity.this, R.string.fm_conflict_title, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!file.renameTo(destination))
                    Toast.makeText(FileManagerActivity.this, R.string.fm_rename_failed, Toast.LENGTH_SHORT).show();
                refreshBothPanes();
            }, -1, null, -1, null, null);
    }

    private void confirmDelete(List<FileEntry> targets) {
        if (targets.isEmpty()) return;

        new AlertDialog.Builder(this)
            .setTitle(R.string.fm_delete_confirm_title)
            .setMessage(getString(R.string.fm_delete_confirm_message, targets.size()))
            .setPositiveButton(R.string.fm_delete, (dialog, which) -> {
                List<File> files = new ArrayList<>();
                for (FileEntry entry : targets) {
                    if (entry.hostFile != null) files.add(entry.hostFile);
                }
                runBackground(() -> {
                    boolean failed = false;
                    for (File file : files) {
                        if (!FileManagerUtils.deleteRecursive(file)) failed = true;
                    }
                    boolean finalFailed = failed;
                    mHandler.post(() -> {
                        refreshBothPanes();
                        if (finalFailed)
                            Toast.makeText(FileManagerActivity.this, R.string.fm_delete_failed, Toast.LENGTH_SHORT).show();
                    });
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showBackgroundMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, MENU_ITEM_NEW_FOLDER, 0, R.string.fm_new_folder);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_ITEM_NEW_FOLDER) promptNewFolder();
            return true;
        });
        popup.show();
    }

    private void promptNewFolder() {
        TextInputDialogUtils.textInput(this, R.string.fm_new_folder, "",
            R.string.fm_confirm, text -> {
                String name = text.trim();
                if (name.isEmpty() || name.contains("/")) {
                    Toast.makeText(FileManagerActivity.this, R.string.fm_invalid_name, Toast.LENGTH_SHORT).show();
                    return;
                }
                File directory = new File(mActivePane.currentDirectory, name);
                if (!directory.mkdirs())
                    Toast.makeText(FileManagerActivity.this, R.string.fm_new_folder_failed, Toast.LENGTH_SHORT).show();
                refreshPane(mActivePane);
            }, -1, null, -1, null, null);
    }

    protected void refreshPane(FilePane pane) {
        if (pane.searchQuery != null) startSearch(pane, pane.searchQuery);
        else if (pane.archiveContext != null) loadArchivePane(pane, pane.archiveContext, pane.archivePrefix);
        else loadPane(pane, pane.currentDirectory);
    }

    // --- search ---

    private void promptSearch() {
        TextInputDialogUtils.textInput(this, R.string.fm_search, "",
            R.string.fm_search, text -> {
                String query = text.trim();
                if (query.isEmpty()) return;
                startSearch(mActivePane, query);
            }, -1, null, -1, null, null);
    }

    private void startSearch(FilePane pane, String query) {
        pane.searchQuery = query;
        pane.adapter.setItems(Collections.singletonList(
            FileEntry.special(getString(R.string.fm_searching_now), FileEntry.SPECIAL_PLACEHOLDER)));
        if (pane.archiveContext != null) {
            File archive = pane.archiveContext;
            runBackground(() -> {
                char[] password = mArchivePasswords.get(archive.getAbsolutePath());
                List<FileEntry> results;
                try {
                    results = ArchiveSource.search(archive, query, password);
                } catch (IOException e) {
                    mHandler.post(() -> {
                        pane.searchQuery = null;
                        refreshPane(pane);
                        Toast.makeText(FileManagerActivity.this, R.string.fm_archive_failed, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                mHandler.post(() -> {
                    pane.adapter.setItems(withNoResultsPlaceholder(results));
                    if (pane == mActivePane) updatePathText();
                });
            });
        } else {
            File root = pane.currentDirectory;
            runBackground(() -> {
                List<FileEntry> results = new ArrayList<>();
                searchWalk(root, root, query.toLowerCase(Locale.US), results, 0);
                mHandler.post(() -> {
                    pane.adapter.setItems(withNoResultsPlaceholder(results));
                    if (pane == mActivePane) updatePathText();
                });
            });
        }
    }

    private List<FileEntry> withNoResultsPlaceholder(List<FileEntry> results) {
        if (!results.isEmpty()) return results;
        List<FileEntry> withPlaceholder = new ArrayList<>();
        withPlaceholder.add(FileEntry.special(getString(R.string.fm_no_results), FileEntry.SPECIAL_PLACEHOLDER));
        return withPlaceholder;
    }

    private void searchWalk(File dir, File root, String lowerQuery, List<FileEntry> out, int depth) {
        if (depth > 12 || out.size() >= 500) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (out.size() >= 500) return;
            if (file.getName().toLowerCase(Locale.US).contains(lowerQuery)) {
                String relative = file.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
                out.add(new FileEntry(relative, file.isDirectory(), file.length(), file.lastModified(),
                    file, null, null));
            }
            if (file.isDirectory() && !isSymlink(file))
                searchWalk(file, root, lowerQuery, out, depth + 1);
        }
    }

    private static boolean isSymlink(File file) {
        try {
            return !file.getCanonicalFile().equals(file.getAbsoluteFile());
        } catch (IOException e) {
            return true;
        }
    }

    private void exitSearch(FilePane pane) {
        pane.searchQuery = null;
        refreshPane(pane);
    }

    // --- path jump ---

    private void promptPathJump() {
        FilePane pane = mActivePane;
        String current = pane.archiveContext != null
            ? pane.archiveContext.getAbsolutePath() + (pane.archivePrefix.isEmpty() ? "" : "/" + pane.archivePrefix)
            : pane.currentDirectory.getAbsolutePath();

        TextInputDialogUtils.textInput(this, R.string.fm_path_jump, current,
            R.string.fm_confirm, text -> {
                String target = text.trim();
                if (target.isEmpty()) return;
                File file = new File(target);
                pane.searchQuery = null;
                if (file.isDirectory()) {
                    pushHistory(pane);
                    loadPane(pane, file);
                } else if (file.isFile() && FileIcons.isArchiveName(file.getName())) {
                    pushHistory(pane);
                    loadArchivePane(pane, file, "");
                } else {
                    File archive = resolveArchivePath(target);
                    if (archive != null) {
                        String prefix = target.substring(archive.getAbsolutePath().length() + 1);
                        pushHistory(pane);
                        loadArchivePane(pane, archive, prefix);
                    } else {
                        Toast.makeText(FileManagerActivity.this, R.string.fm_invalid_path, Toast.LENGTH_SHORT).show();
                    }
                }
            }, -1, null, -1, null, null);
    }

    /** For "/a/b/c.zip/inner/x", returns the existing archive file "/a/b/c.zip" or null. */
    private File resolveArchivePath(String target) {
        File file = new File(target);
        while (file != null && file.getParentFile() != null) {
            File parent = file.getParentFile();
            if (parent.isFile() && FileIcons.isArchiveName(parent.getName())) return parent;
            file = parent;
        }
        return null;
    }

    protected void refreshBothPanes() {
        refreshPane(mTermuxPane);
        refreshPane(mAndroidPane);
    }

    private void openFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".filemanager", file);
            intent.setDataAndType(uri, getMimeType(file));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.fm_no_app_to_open, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, R.string.fm_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(File file) {
        String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(file).toString());
        if (extension != null && !extension.isEmpty()) {
            String mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension.toLowerCase(Locale.US));
            if (mimeType != null) return mimeType;
        }
        return "*/*";
    }

    @Override
    public void onBackPressed() {
        if (mActionMode != null) {
            mActionMode.finish();
            return;
        }
        FilePane pane = mActivePane;
        if (pane.searchQuery != null) {
            exitSearch(pane);
            return;
        }
        if (pane.archiveContext != null || !pane.currentDirectory.getAbsolutePath().equals(pane.rootDirectory.getAbsolutePath())) {
            pushHistory(pane);
            navigateUp();
            return;
        }
        super.onBackPressed();
    }
}
