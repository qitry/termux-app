package com.termux.app.filemanager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Thin wrapper that keeps {@link ArchiveSource} free of stream bookkeeping. */
class ZipOutputStreamBridge implements Closeable {

    private final ZipOutputStream mOut;

    ZipOutputStreamBridge(File destFile) throws IOException {
        mOut = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destFile)));
    }

    void putDirectory(String entryName) throws IOException {
        mOut.putNextEntry(new ZipEntry(entryName));
        mOut.closeEntry();
    }

    void putFile(File file, String entryName) throws IOException {
        mOut.putNextEntry(new ZipEntry(entryName));
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) mOut.write(buffer, 0, read);
        }
        mOut.closeEntry();
    }

    @Override
    public void close() throws IOException {
        mOut.finish();
        mOut.close();
    }
}
