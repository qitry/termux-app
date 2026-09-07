package com.termux.app.filemanager;

import com.termux.R;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class FileIcons {

    private static final Map<String, Integer> EXTENSION_ICONS = new HashMap<>();

    static {
        for (String ext : new String[]{"zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "jar", "war"}) {
            EXTENSION_ICONS.put(ext, R.drawable.ic_fm_archive);
        }
        for (String ext : new String[]{"png", "jpg", "jpeg", "gif", "bmp", "webp", "heic"}) {
            EXTENSION_ICONS.put(ext, R.drawable.ic_fm_image);
        }
        for (String ext : new String[]{"mp4", "mkv", "avi", "mov", "webm", "flv"}) {
            EXTENSION_ICONS.put(ext, R.drawable.ic_fm_video);
        }
        for (String ext : new String[]{"mp3", "wav", "flac", "ogg", "m4a", "aac"}) {
            EXTENSION_ICONS.put(ext, R.drawable.ic_fm_audio);
        }
        for (String ext : new String[]{"java", "c", "cpp", "h", "hpp", "py", "js", "ts", "sh", "bash",
            "go", "rs", "rb", "php", "kt", "gradle", "json", "xml", "html", "css", "yml", "yaml"}) {
            EXTENSION_ICONS.put(ext, R.drawable.ic_fm_code);
        }
        for (String ext : new String[]{"txt", "md", "log", "conf", "ini", "properties", "csv"}) {
            EXTENSION_ICONS.put(ext, R.drawable.ic_fm_text);
        }
        EXTENSION_ICONS.put("pdf", R.drawable.ic_fm_pdf);
        EXTENSION_ICONS.put("apk", R.drawable.ic_fm_apk);
    }

    public static int getIconRes(FileEntry entry) {
        if (entry.isDirectory) return R.drawable.ic_fm_folder;
        Integer res = EXTENSION_ICONS.get(extensionOf(entry.name));
        return res != null ? res : R.drawable.ic_fm_file;
    }

    public static boolean isImage(FileEntry entry) {
        if (entry.isDirectory) return false;
        String ext = extensionOf(entry.name);
        return "png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext) || "gif".equals(ext)
            || "bmp".equals(ext) || "webp".equals(ext) || "heic".equals(ext);
    }

    private static final java.util.Set<String> EDITABLE_EXTENSIONS = new java.util.HashSet<>(
        java.util.Arrays.asList("txt", "md", "log", "conf", "ini", "properties", "csv", "json", "xml",
            "yml", "yaml", "html", "css", "js", "ts", "java", "c", "cpp", "h", "hpp", "py", "sh",
            "bash", "go", "rs", "rb", "php", "kt", "gradle"));

    public static boolean isEditable(FileEntry entry) {
        if (entry.isDirectory) return false;
        return EDITABLE_EXTENSIONS.contains(extensionOf(entry.name));
    }

    public static boolean isArchive(File file) {
        return isArchiveName(file.getName());
    }

    public static boolean isArchiveName(String name) {
        String ext = extensionOf(name);
        return "zip".equals(ext) || "jar".equals(ext) || "war".equals(ext) || "apk".equals(ext)
            || "tar".equals(ext) || "gz".equals(ext) || "tgz".equals(ext) || "bz2".equals(ext)
            || "xz".equals(ext) || "7z".equals(ext);
    }

    public static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private FileIcons() { }
}
