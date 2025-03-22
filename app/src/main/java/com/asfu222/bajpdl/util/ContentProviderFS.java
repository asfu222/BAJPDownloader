package com.asfu222.bajpdl.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.file.*;
import java.util.stream.Stream;

public class ContentProviderFS {
    private final ContentResolver contentResolver;
    public static final String AUTHORITY = "com.asfu222.bajpdl.mitm.fs";

    public ContentProviderFS(ContentResolver contentResolver) {
        this.contentResolver = contentResolver;
    }

    private Uri buildUri(Path path) {
        return Uri.parse("content://" + AUTHORITY + path.toString());
    }

    public Stream<Path> walk(Path start) {
        Uri uri = buildUri(start);
        try (Cursor cursor = contentResolver.query(uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return Stream.empty();
            return Stream.of(start);
        }
    }

    public long size(Path path) throws IOException {
        Uri uri = buildUri(path);
        try (Cursor cursor = contentResolver.query(uri, new String[]{"size"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                System.out.println("Getting size of " + path + " " + cursor.getLong(1));
                return cursor.getLong(1);
            }
        }
        throw new IOException("Failed to get file size");
    }

    public boolean exists(Path path) {
        Uri uri = buildUri(path);
        try (Cursor cursor = contentResolver.query(uri, new String[]{"exists"}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    public void deleteIfExists(Path path) {
        Uri uri = buildUri(path);
        contentResolver.delete(uri, null, null);
    }

    public InputStream newInputStream(Path path) throws IOException {
        Uri uri = buildUri(path);
        ParcelFileDescriptor pfd = contentResolver.openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Failed to open file");
        return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
    }

    public OutputStream newOutputStream(Path path) throws IOException {
        Uri uri = buildUri(path);
        return contentResolver.openOutputStream(uri);
    }

    public Path createDirectories(Path path) throws IOException {
        Uri uri = buildUri(path);
        Uri result = contentResolver.insert(uri, new ContentValues());
        if (result == null) {
            throw new IOException("Failed to create directory: " + path);
        }
        return path;
    }
}
