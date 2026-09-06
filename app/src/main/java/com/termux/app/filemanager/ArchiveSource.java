package com.termux.app.filemanager;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Archive operations. zip/jar/apk go through zip4j (password support, Java 8 safe);
 * tar/tgz/7z go through commons-compress 1.24 (the 1.26+ line uses {@code List.addLast},
 * unavailable under this app's pinned desugar_jdk_libs 1.1.5).
 */
public final class ArchiveSource {

    /** Thrown when an archive needs a password that was not supplied. */
    public static class PasswordRequiredException extends IOException {
        public PasswordRequiredException() { super("password required"); }
    }

    /** Thrown when a supplied password is wrong. */
    public static class WrongPasswordException extends IOException {
        public WrongPasswordException(Throwable cause) { super("wrong password", cause); }
    }

    public static boolean isZip(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".apk");
    }

    /** Lists the direct children of {@code prefix} ("" for archive root). */
    public static List<FileEntry> list(File archive, String prefix, char[] password) throws IOException {
        String name = archive.getName();
        Map<String, FileEntry> children = new LinkedHashMap<>();

        if (isZip(name)) {
            ZipFile zipFile = new ZipFile(archive);
            try {
                if (zipFile.isEncrypted() && password == null) throw new PasswordRequiredException();
                if (password != null) zipFile.setPassword(password);
                for (FileHeader header : zipFile.getFileHeaders()) {
                    collect(children, archive, header.getFileName(), header.isDirectory(),
                        header.getUncompressedSize(), header.getLastModifiedTime().getTime(), prefix);
                }
            } catch (net.lingala.zip4j.exception.ZipException e) {
                throw wrapZip4j(e);
            } finally {
                closeQuietly(zipFile);
            }
        } else if (name.toLowerCase(Locale.US).endsWith(".7z")) {
            try (SevenZFile sevenZFile = password != null
                ? new SevenZFile(archive, password) : new SevenZFile(archive)) {
                for (SevenZArchiveEntry entry : sevenZFile.getEntries()) {
                    collect(children, archive, entry.getName(), entry.isDirectory(),
                        entry.getSize(), entry.getLastModifiedDate().getTime(), prefix);
                }
            } catch (IOException e) {
                if (password != null && isPasswordError(e)) throw new WrongPasswordException(e);
                throw e;
            }
        } else if (isTar(name)) {
            try (InputStream in = openMaybeCompressed(archive);
                 ArchiveInputStream archiveIn =
                     new ArchiveStreamFactory().createArchiveInputStream("tar", in)) {
                ArchiveEntry entry;
                while ((entry = archiveIn.getNextEntry()) != null) {
                    collect(children, archive, entry.getName(), entry.isDirectory(),
                        entry.getSize(), entry.getLastModifiedDate().getTime(), prefix);
                }
            } catch (ArchiveException e) {
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
    public static boolean extractEntry(File archive, String entryPath, File destDir, char[] password)
        throws IOException {
        String name = archive.getName();
        if (isZip(name)) {
            ZipFile zipFile = new ZipFile(archive);
            try {
                if (zipFile.isEncrypted() && password == null) throw new PasswordRequiredException();
                if (password != null) zipFile.setPassword(password);
                for (FileHeader header : zipFile.getFileHeaders()) {
                    String entryName = normalize(header.getFileName());
                    if (!entryName.equals(entryPath) && !entryName.startsWith(entryPath + "/")) continue;
                    if (header.isDirectory()) {
                        File dir = new File(destDir, entryName);
                        if (!dir.isDirectory() && !dir.mkdirs()) return false;
                    } else {
                        zipFile.extractFile(header, destDir.getAbsolutePath());
                    }
                }
            } catch (net.lingala.zip4j.exception.ZipException e) {
                if (isPasswordError(e)) throw new WrongPasswordException(e);
                return false;
            } finally {
                closeQuietly(zipFile);
            }
            return true;
        }

        try {
            if (name.toLowerCase(Locale.US).endsWith(".7z")) {
                try (SevenZFile sevenZFile = password != null
                    ? new SevenZFile(archive, password) : new SevenZFile(archive)) {
                    for (SevenZArchiveEntry entry : sevenZFile.getEntries()) {
                        String entryName = normalize(entry.getName());
                        if (!entryName.equals(entryPath) && !entryName.startsWith(entryPath + "/")) continue;
                        writeExtracted(archive, sevenZFile.getInputStream(entry), entryName, entry.isDirectory(), destDir);
                    }
                }
            } else if (isTar(name)) {
                try (InputStream in = openMaybeCompressed(archive);
                     ArchiveInputStream archiveIn =
                         new ArchiveStreamFactory().createArchiveInputStream("tar", in)) {
                    ArchiveEntry entry;
                    while ((entry = archiveIn.getNextEntry()) != null) {
                        String entryName = normalize(entry.getName());
                        if (!entryName.equals(entryPath) && !entryName.startsWith(entryPath + "/")) continue;
                        writeExtracted(archive, archiveIn, entryName, entry.isDirectory(), destDir);
                    }
                } catch (ArchiveException e) {
                    return false;
                }
            } else {
                return false;
            }
            return true;
        } catch (IOException e) {
            if (isPasswordError(e)) {
                if (password == null) throw new PasswordRequiredException();
                throw new WrongPasswordException(e);
            }
            throw e;
        }
    }

    /** Extracts the whole archive into {@code destDir}. */
    public static boolean extractAll(File archive, File destDir, char[] password) throws IOException {
        String name = archive.getName().toLowerCase(Locale.US);
        if (name.endsWith(".gz") && !name.endsWith(".tar.gz") && !name.endsWith(".tgz")) {
            return extractBareCompressed(archive, destDir);
        }
        if (isZip(name)) {
            ZipFile zipFile = new ZipFile(archive);
            try {
                if (zipFile.isEncrypted() && password == null) throw new PasswordRequiredException();
                if (password != null) zipFile.setPassword(password);
                zipFile.extractAll(destDir.getAbsolutePath());
                return true;
            } catch (net.lingala.zip4j.exception.ZipException e) {
                if (isPasswordError(e)) throw new WrongPasswordException(e);
                return false;
            } finally {
                closeQuietly(zipFile);
            }
        }
        return extractEntry(archive, "", destDir, password);
    }

    /** Compresses {@code sources} into a zip at {@code destFile}; optional AES-256 password. */
    public static boolean compressZip(List<File> sources, File destFile, char[] password) {
        boolean usePassword = password != null && password.length > 0;
        ZipFile zipFile = usePassword ? new ZipFile(destFile, password) : new ZipFile(destFile);
        try {
            ZipParameters parameters = new ZipParameters();
            parameters.setIncludeRootFolder(false);
            if (usePassword) {
                parameters.setEncryptFiles(true);
                parameters.setEncryptionMethod(EncryptionMethod.AES);
                parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
            }
            for (File source : sources) {
                if (source.isDirectory()) zipFile.addFolder(source, parameters);
                else zipFile.addFile(source, parameters);
            }
            return true;
        } catch (ZipException e) {
            return false;
        } finally {
            closeQuietly(zipFile);
        }
    }

    /** Searches all entry paths (case-insensitive contains), name = full entry path. */
    public static List<FileEntry> search(File archive, String query, char[] password) throws IOException {
        String lower = query.toLowerCase(Locale.US);
        List<FileEntry> results = new ArrayList<>();

        if (isZip(archive.getName())) {
            ZipFile zipFile = new ZipFile(archive);
            try {
                if (zipFile.isEncrypted() && password == null) throw new PasswordRequiredException();
                if (password != null) zipFile.setPassword(password);
                for (FileHeader header : zipFile.getFileHeaders()) {
                    addSearchResult(results, archive, normalize(header.getFileName()),
                        header.isDirectory(), header.getUncompressedSize(),
                        header.getLastModifiedTime().getTime(), lower);
                }
            } catch (net.lingala.zip4j.exception.ZipException e) {
                throw wrapZip4j(e);
            } finally {
                closeQuietly(zipFile);
            }
        } else if (archive.getName().toLowerCase(Locale.US).endsWith(".7z")) {
            try (SevenZFile sevenZFile = password != null
                ? new SevenZFile(archive, password) : new SevenZFile(archive)) {
                for (SevenZArchiveEntry entry : sevenZFile.getEntries()) {
                    addSearchResult(results, archive, normalize(entry.getName()),
                        entry.isDirectory(), entry.getSize(), entry.getLastModifiedDate().getTime(), lower);
                }
            }
        } else if (isTar(archive.getName())) {
            try (InputStream in = openMaybeCompressed(archive);
                 ArchiveInputStream archiveIn =
                     new ArchiveStreamFactory().createArchiveInputStream("tar", in)) {
                ArchiveEntry entry;
                while ((entry = archiveIn.getNextEntry()) != null) {
                    addSearchResult(results, archive, normalize(entry.getName()),
                        entry.isDirectory(), entry.getSize(), entry.getLastModifiedDate().getTime(), lower);
                }
            } catch (ArchiveException e) {
                throw new IOException(e);
            }
        }

        Collections.sort(results, (a, b) -> {
            if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
            return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
        });
        return results;
    }

    private static void addSearchResult(List<FileEntry> out, File archive, String name,
                                        boolean isDirectory, long size, long date, String lowerQuery) {
        if (out.size() >= 500 || name.isEmpty() || !name.toLowerCase(Locale.US).contains(lowerQuery)) return;
        out.add(new FileEntry(name, isDirectory, size, date, null, archive, name));
    }

    /** Whether the archive requires a password to open. */
    public static boolean requiresPassword(File archive) {
        if (isZip(archive.getName())) {
            ZipFile zipFile = new ZipFile(archive);
            try {
                return zipFile.isEncrypted();
            } finally {
                closeQuietly(zipFile);
            }
        }
        return false;
    }

    // --- internals ---

    private static IOException wrapZip4j(net.lingala.zip4j.exception.ZipException e) {
        if (isPasswordError(e)) return new WrongPasswordException(e);
        return e;
    }

    private static boolean isPasswordError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof net.lingala.zip4j.exception.WrongPasswordException) return true;
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.US);
                if (lower.contains("password") || lower.contains("crypt")) return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static void closeQuietly(ZipFile zipFile) {
        try {
            zipFile.close();
        } catch (IOException ignored) { }
    }

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
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
            || lower.endsWith(".tar.bz2") || lower.endsWith(".tar.xz");
    }

    private static InputStream openMaybeCompressed(File archive) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(archive));
        String name = archive.getName().toLowerCase(Locale.US);
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) return new GZIPInputStream(in);
        return in;
    }

    private ArchiveSource() { }
}
