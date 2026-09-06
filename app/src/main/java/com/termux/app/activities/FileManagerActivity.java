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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileManagerActivity extends AppCompatActivity {

    private static final int REQUEST_STORAGE_PERMISSIONS = 101;

    private static final int MENU_ITEM_NEW_FOLDER = 1;
    private static final int MENU_ITEM_PASTE = 2;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final FilePane mTermuxPane = new FilePane();
    private final FilePane mAndroidPane = new FilePane();
    private FilePane mActivePane;

    private TextView mPathText;

    private final List<FileEntry> mClipboard = new ArrayList<>();
    private boolean mClipboardIsCut;

    private ActionMode mActionMode;

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
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        mPathText = findViewById(R.id.file_manager_path);
        findViewById(R.id.file_manager_up).setOnClickListener(v -> navigateUp());
        findViewById(R.id.file_manager_refresh).setOnClickListener(v -> refreshPane(mActivePane));

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
                if (mActionMode != null) mActionMode.invalidate();
            }
        });
        pane.listView.setAdapter(pane.adapter);

        pane.listView.setOnLongClickListener(v -> {
            if (mActionMode != null) return false;
            setActivePane(pane);
            showBackgroundMenu(v);
            return true;
        });
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
        if (mActivePane.archiveContext != null) {
            String path = mActivePane.archiveContext.getAbsolutePath();
            if (!mActivePane.archivePrefix.isEmpty()) path += "/" + mActivePane.archivePrefix;
            mPathText.setText(path);
        } else {
            mPathText.setText(mActivePane.currentDirectory.getAbsolutePath());
        }
    }

    protected void loadPane(FilePane pane, File directory) {
        mExecutor.execute(() -> {
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
                pane.adapter.setItems(entries);
                if (pane == mActivePane) updatePathText();
            });
        });
    }

    private void loadArchivePane(FilePane pane, File archive, String prefix) {
        mExecutor.execute(() -> {
            List<FileEntry> entries;
            try {
                entries = ArchiveSource.list(archive, prefix);
            } catch (IOException e) {
                mHandler.post(() -> Toast.makeText(FileManagerActivity.this,
                    R.string.fm_archive_failed, Toast.LENGTH_SHORT).show());
                return;
            }
            mHandler.post(() -> {
                pane.archiveContext = archive;
                pane.archivePrefix = prefix;
                pane.adapter.setItems(entries);
                if (pane == mActivePane) updatePathText();
            });
        });
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
        FilePane pane = mActivePane;
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
        if (entry.isArchiveEntry()) {
            if (entry.isDirectory) {
                loadArchivePane(pane, entry.archiveHostFile, entry.archiveEntryPath);
            } else {
                openArchiveEntry(entry);
            }
            return;
        }
        if (entry.isDirectory) {
            loadPane(pane, entry.hostFile);
        } else if (FileIcons.isArchive(entry.hostFile)) {
            loadArchivePane(pane, entry.hostFile, "");
        } else {
            openFile(entry.hostFile);
        }
    }

    private void openArchiveEntry(FileEntry entry) {
        mExecutor.execute(() -> {
            File cacheDir = new File(getCacheDir(), "fm_open");
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) return;
            if (!ArchiveSource.extractEntry(entry.archiveHostFile, entry.archiveEntryPath, cacheDir)) {
                mHandler.post(() -> Toast.makeText(FileManagerActivity.this,
                    R.string.fm_extract_failed, Toast.LENGTH_SHORT).show());
                return;
            }
            File extracted = new File(cacheDir, entry.archiveEntryPath);
            mHandler.post(() -> openFile(extracted));
        });
    }

    private void handleFileLongClicked(FilePane pane, FileEntry entry) {
        setActivePane(pane);
        if (mActionMode == null)
            mActionMode = startSupportActionMode(new SelectionActionModeCallback());
        mTermuxPane.adapter.clearSelection();
        mAndroidPane.adapter.clearSelection();
        pane.adapter.startSelection(entry);
    }

    protected int getSelectedCount() {
        return mTermuxPane.adapter.getSelectedCount() + mAndroidPane.adapter.getSelectedCount();
    }

    protected List<FileEntry> getSelectedEntries() {
        List<FileEntry> selected = new ArrayList<>(mTermuxPane.adapter.getSelected());
        selected.addAll(mAndroidPane.adapter.getSelected());
        return selected;
    }

    private static boolean isArchiveTarget(FileEntry entry) {
        return entry.isArchiveEntry() || FileIcons.isArchiveName(entry.name);
    }

    private class SelectionActionModeCallback implements ActionMode.Callback {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.fm_selection, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            List<FileEntry> selected = getSelectedEntries();
            int count = selected.size();
            menu.findItem(R.id.fm_menu_rename).setVisible(count == 1 && !selected.get(0).isArchiveEntry());
            menu.findItem(R.id.fm_menu_extract).setVisible(count == 1 && isArchiveTarget(selected.get(0)));
            boolean allReal = !selected.isEmpty();
            for (FileEntry entry : selected) {
                if (entry.isArchiveEntry()) { allReal = false; break; }
            }
            menu.findItem(R.id.fm_menu_compress).setVisible(allReal);
            mode.setTitle(getString(R.string.fm_selected_count, count));
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.fm_menu_copy) {
                setClipboard(false);
                mode.finish();
            } else if (id == R.id.fm_menu_cut) {
                setClipboard(true);
                mode.finish();
            } else if (id == R.id.fm_menu_rename) {
                renameSelected();
                mode.finish();
            } else if (id == R.id.fm_menu_select_all) {
                mActivePane.adapter.selectAll();
            } else if (id == R.id.fm_menu_delete) {
                confirmDeleteSelected();
                mode.finish();
            } else if (id == R.id.fm_menu_extract) {
                extractSelected();
                mode.finish();
            } else if (id == R.id.fm_menu_compress) {
                promptCompressSelected();
                mode.finish();
            } else {
                return false;
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mActionMode = null;
            mTermuxPane.adapter.clearSelection();
            mAndroidPane.adapter.clearSelection();
        }
    }

    private void setClipboard(boolean cut) {
        mClipboard.clear();
        for (FileEntry entry : getSelectedEntries()) {
            if (!entry.isArchiveEntry()) mClipboard.add(entry);
        }
        mClipboardIsCut = cut;
        Toast.makeText(this, cut ? R.string.fm_cut_count : R.string.fm_copied_count,
            Toast.LENGTH_SHORT).show();
    }

    private void extractSelected() {
        List<FileEntry> selected = getSelectedEntries();
        if (selected.size() != 1) return;
        FileEntry entry = selected.get(0);
        File targetDir = paneDiskDirectory(mActivePane);

        mExecutor.execute(() -> {
            boolean ok;
            if (entry.isArchiveEntry()) {
                ok = ArchiveSource.extractEntry(entry.archiveHostFile, entry.archiveEntryPath, targetDir);
            } else {
                File destDir = new File(targetDir, baseNameOf(entry.hostFile));
                ok = ArchiveSource.extractAll(entry.hostFile, destDir);
            }
            boolean finalOk = ok;
            mHandler.post(() -> {
                refreshBothPanes();
                if (!finalOk)
                    Toast.makeText(FileManagerActivity.this, R.string.fm_extract_failed, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void promptCompressSelected() {
        List<FileEntry> selected = getSelectedEntries();
        List<File> sources = new ArrayList<>();
        for (FileEntry entry : selected) {
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
                mExecutor.execute(() -> {
                    boolean ok = ArchiveSource.compressZip(files, destFile);
                    mHandler.post(() -> {
                        refreshBothPanes();
                        if (!ok)
                            Toast.makeText(FileManagerActivity.this, R.string.fm_compress_failed, Toast.LENGTH_SHORT).show();
                    });
                });
            }, -1, null, -1, null, null);
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

    private void renameSelected() {
        List<FileEntry> selected = getSelectedEntries();
        if (selected.size() != 1) return;
        File file = selected.get(0).hostFile;
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

    private void confirmDeleteSelected() {
        List<FileEntry> selected = getSelectedEntries();
        if (selected.isEmpty()) return;

        new AlertDialog.Builder(this)
            .setTitle(R.string.fm_delete_confirm_title)
            .setMessage(getString(R.string.fm_delete_confirm_message, selected.size()))
            .setPositiveButton(R.string.fm_delete, (dialog, which) -> {
                List<File> targets = new ArrayList<>();
                for (FileEntry entry : selected) {
                    if (entry.hostFile != null) targets.add(entry.hostFile);
                }
                mExecutor.execute(() -> {
                    boolean failed = false;
                    for (File file : targets) {
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
        popup.getMenu().add(0, MENU_ITEM_PASTE, 1, R.string.fm_paste);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_ITEM_NEW_FOLDER) promptNewFolder();
            else if (item.getItemId() == MENU_ITEM_PASTE) pasteToActivePane();
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

    private void pasteToActivePane() {
        if (mClipboard.isEmpty()) {
            Toast.makeText(this, R.string.fm_nothing_to_paste, Toast.LENGTH_SHORT).show();
            return;
        }
        doPaste(mActivePane.currentDirectory, true);
    }

    private void doPaste(File targetDir, boolean overwrite) {
        List<FileEntry> sources = new ArrayList<>(mClipboard);
        boolean cut = mClipboardIsCut;
        mClipboard.clear();
        mClipboardIsCut = false;

        mExecutor.execute(() -> {
            boolean failed = false;
            for (FileEntry source : sources) {
                File src = source.hostFile;
                if (src == null) continue;
                File destination = new File(targetDir, src.getName());
                if (destination.getAbsolutePath().equals(src.getAbsolutePath())) continue;
                if (destination.exists()) {
                    if (!overwrite) continue;
                    if (!FileManagerUtils.deleteRecursive(destination)) {
                        failed = true;
                        continue;
                    }
                }
                boolean ok = cut ? FileManagerUtils.move(src, destination)
                    : FileManagerUtils.copyRecursive(src, destination);
                if (!ok) failed = true;
            }
            boolean finalFailed = failed;
            mHandler.post(() -> {
                refreshBothPanes();
                if (finalFailed)
                    Toast.makeText(FileManagerActivity.this, R.string.fm_paste_failed, Toast.LENGTH_SHORT).show();
            });
        });
    }

    protected void refreshPane(FilePane pane) {
        if (pane.archiveContext != null) loadArchivePane(pane, pane.archiveContext, pane.archivePrefix);
        else loadPane(pane, pane.currentDirectory);
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
        if (pane.archiveContext != null || !pane.currentDirectory.getAbsolutePath().equals(pane.rootDirectory.getAbsolutePath())) {
            navigateUp();
            return;
        }
        super.onBackPressed();
    }
}
