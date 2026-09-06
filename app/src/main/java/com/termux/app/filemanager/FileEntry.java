package com.termux.app.filemanager;

import java.io.File;

/**
 * A single row in a file pane: either a real file/directory on disk, or an entry inside an
 * archive file (zip/tar/7z). Archive entries carry {@link #archiveHostFile} + {@link #archiveEntryPath}
 * and are read-only in v1.
 */
public class FileEntry {

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

    public static FileEntry fromFile(File file) {
        return new FileEntry(file.getName(), file.isDirectory(), file.length(), file.lastModified(),
            file, null, null);
    }

    public FileEntry(String name, boolean isDirectory, long size, long lastModified,
                     File hostFile, File archiveHostFile, String archiveEntryPath) {
        this.name = name;
        this.isDirectory = isDirectory;
        this.size = size;
        this.lastModified = lastModified;
        this.hostFile = hostFile;
        this.archiveHostFile = archiveHostFile;
        this.archiveEntryPath = archiveEntryPath;
    }

    public boolean isArchiveEntry() {
        return archiveHostFile != null;
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
        return entry.archiveHostFile != null && archiveHostFile.equals(entry.archiveHostFile)
            && archiveEntryPath.equals(entry.archiveEntryPath);
    }

    @Override
    public int hashCode() {
        return hostFile != null ? hostFile.hashCode() : (archiveHostFile.hashCode() * 31 + archiveEntryPath.hashCode());
    }
}
