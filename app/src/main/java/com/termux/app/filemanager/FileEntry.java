package com.termux.app.filemanager;

import java.io.File;

/**
 * A single row in a file pane: either a real file/directory on disk, or an entry inside an
 * archive file (zip/tar/7z). Archive entries carry {@link #archiveHostFile} + {@link #archiveEntryPath}
 * and are read-only in v1.
 */
public class FileEntry {

    public static final int SPECIAL_NONE = 0;
    /** Non-clickable status row, e.g. "searching…" / "no results". */
    public static final int SPECIAL_PLACEHOLDER = 1;
    /** Row that navigates to the previous page from history. */
    public static final int SPECIAL_BACK = 2;
    /** Row that navigates to the parent directory. */
    public static final int SPECIAL_PARENT = 3;

    public final String name;
    public final boolean isDirectory;
    public final long size;
    public final long lastModified;

    /** Non-null for real files/directories on disk. */
    public final File hostFile;

    /** Non-null for entries inside an archive: the archive file itself. */
    public final File archiveHostFile;

    /** Non-null for entries inside an archive: the entry path within the archive. */
    public final String archiveEntryPath;

    /** One of the {@code SPECIAL_*} flags; {@link #SPECIAL_NONE} for a normal file row. */
    public final int special;

    public static FileEntry fromFile(File file) {
        return new FileEntry(file.getName(), file.isDirectory(), file.length(), file.lastModified(),
            file, null, null, SPECIAL_NONE);
    }

    public static FileEntry special(String label, int specialFlag) {
        return new FileEntry(label, false, 0, 0, null, null, null, specialFlag);
    }

    public FileEntry(String name, boolean isDirectory, long size, long lastModified,
                     File hostFile, File archiveHostFile, String archiveEntryPath) {
        this(name, isDirectory, size, lastModified, hostFile, archiveHostFile, archiveEntryPath, SPECIAL_NONE);
    }

    public FileEntry(String name, boolean isDirectory, long size, long lastModified,
                     File hostFile, File archiveHostFile, String archiveEntryPath, int special) {
        this.name = name;
        this.isDirectory = isDirectory;
        this.size = size;
        this.lastModified = lastModified;
        this.hostFile = hostFile;
        this.archiveHostFile = archiveHostFile;
        this.archiveEntryPath = archiveEntryPath;
        this.special = special;
    }

    public boolean isArchiveEntry() {
        return archiveHostFile != null;
    }

    public boolean isSpecial() {
        return special != SPECIAL_NONE;
    }

    public File asFile() {
        return hostFile;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FileEntry)) return false;
        FileEntry entry = (FileEntry) other;
        if (hostFile != null) return hostFile.equals(entry.hostFile);
        if (archiveHostFile != null)
            return entry.archiveHostFile != null && archiveHostFile.equals(entry.archiveHostFile)
                && archiveEntryPath.equals(entry.archiveEntryPath);
        return special == entry.special && name.equals(entry.name);
    }

    @Override
    public int hashCode() {
        if (hostFile != null) return hostFile.hashCode();
        if (archiveHostFile != null) return archiveHostFile.hashCode() * 31 + archiveEntryPath.hashCode();
        return name.hashCode() * 31 + special;
    }
}
