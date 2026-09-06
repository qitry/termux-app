package com.termux.app.filemanager;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class ArchiveSource {

    /** Lists the direct children of {@code prefix} ("" for archive root). */
    public static List<FileEntry> list(File archive, String prefix) throws IOException {
        String name = archive.getName().toLowerCase(Locale.US);
        Map<String, FileEntry> children = new LinkedHashMap<>();

        if (name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".apk")) {
            try (ZipFile zipFile = new ZipFile(archive)) {
                Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    collect(children, archive, entry.getName(), entry.isDirectory(),
                        entry.getSize(), entry.getLastModifiedTime().toMillis(), prefix);
                }
            }
        } else if (name.endsWith(".7z")) {
            try (SevenZFile sevenZFile = new SevenZFile(archive)) {
                for (SevenZArchiveEntry entry : sevenZFile.getEntries()) {
                    collect(children, archive, entry.getName(), entry.isDirectory(),
                        entry.getSize(), entry.getLastModifiedDate().getTime(), prefix);
                }
            }
        } else if (isTar(name)) {
            try (InputStream in = openMaybeCompressed(archive);
                 ArchiveInputStream<?> archiveIn =
                     new ArchiveStreamFactory().createArchiveInputStream("tar", in)) {
                ArchiveEntry entry;
                while ((entry = archiveIn.getNextEntry()) != null) {
                    collect(children, archive, entry.getName(), entry.isDirectory(),
                        entry.getSize(), entry.getLastModifiedDate().getTime(), prefix);
                }
            } catch (org.apache.commons.compress.archivers.ArchiveException e) {
                throw new IOException(e);
            }
        } else {
            return Collections.emptyList();
        }

        List<FileEntry> result = new ArrayList<>(children.values());
        Collections.sort(result, (a, b) -> {
            if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
            return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
        });
        return result;
    }

    /** Extracts one entry (file or directory subtree) into {@code destDir}. */
    public static boolean extractEntry(File archive, String entryPath, File destDir) {
        String name = archive.getName().toLowerCase(Locale.US);
        try {
            if (name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".apk")) {
                try (ZipFile zipFile = new ZipFile(archive)) {
                    Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
                    while (entries.hasMoreElements()) {
                        ZipArchiveEntry entry = entries.nextElement();
                        String entryName = normalize(entry.getName());
                        if (!entryName.equals(entryPath) && !entryName.startsWith(entryPath + "/")) continue;
                        writeExtracted(archive, zipFile.getInputStream(entry), entryName, entry.isDirectory(), destDir);
                    }
                }
            } else if (name.endsWith(".7z")) {
                try (SevenZFile sevenZFile = new SevenZFile(archive)) {
                    for (SevenZArchiveEntry entry : sevenZFile.getEntries()) {
                        String entryName = normalize(entry.getName());
                        if (!entryName.equals(entryPath) && !entryName.startsWith(entryPath + "/")) continue;
                        writeExtracted(archive, sevenZFile.getInputStream(entry), entryName, entry.isDirectory(), destDir);
                    }
                }
            } else if (isTar(name)) {
                try (InputStream in = openMaybeCompressed(archive);
                     ArchiveInputStream<?> archiveIn =
                         new ArchiveStreamFactory().createArchiveInputStream("tar", in)) {
                    ArchiveEntry entry;
                    while ((entry = archiveIn.getNextEntry()) != null) {
                        String entryName = normalize(entry.getName());
                        if (!entryName.equals(entryPath) && !entryName.startsWith(entryPath + "/")) continue;
                        writeExtracted(archive, archiveIn, entryName, entry.isDirectory(), destDir);
                    }
                } catch (org.apache.commons.compress.archivers.ArchiveException e) {
                    return false;
                }
            } else {
                return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Extracts the whole archive into {@code destDir}. */
    public static boolean extractAll(File archive, File destDir) {
        String name = archive.getName().toLowerCase(Locale.US);
        if (name.endsWith(".gz") && !name.endsWith(".tar.gz") && !name.endsWith(".tgz")) {
            return extractBareCompressed(archive, destDir);
        }
        return extractEntry(archive, "", destDir);
    }

    /** Compresses {@code sources} into a zip file at {@code destFile}. */
    public static boolean compressZip(List<File> sources, File destFile) {
        try (ZipOutputStreamBridge bridge = new ZipOutputStreamBridge(destFile)) {
            for (File source : sources) {
                addRecursive(bridge, source, source.getName());
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // --- internals ---

    private static void collect(Map<String, FileEntry> children, File archive, String rawEntryName,
                                boolean isDirectory, long size, long date, String prefix) {
        String entryName = normalize(rawEntryName);
        if (prefix.isEmpty()) {
            if (entryName.isEmpty()) return;
        } else {
            if (!entryName.startsWith(prefix + "/")) return;
            entryName = entryName.substring(prefix.length() + 1);
            if (entryName.isEmpty()) return;
        }

        int slash = entryName.indexOf('/');
        if (slash >= 0) {
            // Deeper entry: surface its top segment as a directory.
            String dirName = entryName.substring(0, slash);
            if (!children.containsKey(dirName)) {
                children.put(dirName, new FileEntry(dirName, true, 0, 0, null, archive,
                    prefix.isEmpty() ? dirName : prefix + "/" + dirName));
            }
        } else {
            children.put(entryName, new FileEntry(entryName, isDirectory, size, date, null, archive,
                prefix.isEmpty() ? entryName : prefix + "/" + entryName));
        }
    }

    private static String normalize(String entryName) {
        String name = entryName.replace('\\', '/');
        while (name.startsWith("/")) name = name.substring(1);
        while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        return name;
    }

    private static void writeExtracted(File archive, InputStream entryStream, String entryName,
                                       boolean isDirectory, File destDir) throws IOException {
        File dest = new File(destDir, entryName);
        if (!dest.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
            throw new IOException("Archive entry escapes destination: " + entryName);
        }
        if (isDirectory) {
            if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("mkdirs failed: " + dest);
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("mkdirs failed: " + parent);
        if (entryStream == null) return;
        try (InputStream in = entryStream; OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private static boolean extractBareCompressed(File archive, File destDir) {
        String name = archive.getName();
        String outName = name.substring(0, name.length() - 3);
        File dest = new File(destDir, outName);
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive)));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isTar(String name) {
        return name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz")
            || name.endsWith(".tar.bz2") || name.endsWith(".tar.xz");
    }

    private static InputStream openMaybeCompressed(File archive) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(archive));
        String name = archive.getName().toLowerCase(Locale.US);
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) return new GZIPInputStream(in);
        return in;
    }

    private static void addRecursive(ZipOutputStreamBridge bridge, File file, String entryName) throws IOException {
        if (file.isDirectory()) {
            bridge.putDirectory(entryName + "/");
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addRecursive(bridge, child, entryName + "/" + child.getName());
                }
            }
        } else {
            bridge.putFile(file, entryName);
        }
    }

    private ArchiveSource() { }
}
