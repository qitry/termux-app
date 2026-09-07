package com.termux.app.filemanager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class FileManagerUtils {

    private static final int BUFFER_SIZE = 8192;

    public static boolean copyRecursive(File source, File destination) {
        if (source.isDirectory()) {
            if (!destination.isDirectory() && !destination.mkdirs()) return false;
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!copyRecursive(child, new File(destination, child.getName()))) return false;
                }
            }
            return true;
        }

        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) return false;
                }
            }
        }
        return file.delete();
    }

    public static boolean move(File source, File destination) {
        if (source.renameTo(destination)) return true;
        // Cross-device move (Termux home <-> shared storage) cannot use rename(): fall back to copy + delete.
        if (!copyRecursive(source, destination)) return false;
        return deleteRecursive(source);
    }

    public static String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private FileManagerUtils() { }
}
