package com.YostarJP.BlueArchive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

public class FileSystemProvider extends ContentProvider {
    private static final String TAG = "FileSystemProvider";
    private static final String AUTHORITY = "com.asfu222.bajpdl.mitm.fs";
    private static final int FILE_CODE = 1;
    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    private boolean initialized = false;

    static {
        uriMatcher.addURI(AUTHORITY, "file/*", FILE_CODE);
    }

    @Override
    public synchronized boolean onCreate() {
        initialized = true;
        notifyAll();
        return true;
    }

    private synchronized void waitForInitialization() {
        while (!initialized) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private File getFileForUri(Uri uri) {
        waitForInitialization();

        String filePath = uri.getPath();
        if (filePath == null) {
            throw new IllegalArgumentException("Invalid file path");
        }

        File rootDir = Objects.requireNonNull(getContext()).getExternalFilesDir("");
        assert rootDir != null;
        File targetFile = new File(rootDir, filePath.replaceFirst("/file/", ""));

        try {
            if (!targetFile.getCanonicalPath().startsWith(rootDir.getCanonicalPath())) {
                throw new SecurityException("Access denied: " + targetFile.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to resolve file path", e);
        }

        return targetFile;
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        waitForInitialization();

        if (uriMatcher.match(uri) == FILE_CODE) {
            File file = getFileForUri(uri);
            int fileMode = mode.equals("r") ? ParcelFileDescriptor.MODE_READ_ONLY : ParcelFileDescriptor.MODE_READ_WRITE;
            return ParcelFileDescriptor.open(file, fileMode);
        }
        throw new FileNotFoundException("File not found: " + uri);
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        waitForInitialization();

        if (uriMatcher.match(uri) == FILE_CODE) {
            File file = getFileForUri(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{"exists", "size"});
            cursor.addRow(new Object[]{file.exists() ? 1 : 0, file.exists() ? file.length() : 0});
            return cursor;
        }
        return null;
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        waitForInitialization();

        if (uriMatcher.match(uri) == FILE_CODE) {
            File file = getFileForUri(uri);
            try {
                if (file.mkdirs()) {
                    return Uri.withAppendedPath(uri, file.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to create directories", e);
            }
        }
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        waitForInitialization();

        if (uriMatcher.match(uri) == FILE_CODE) {
            File file = getFileForUri(uri);
            if (file.exists() && file.delete()) {
                return 1;
            }
        }
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0; // Not implemented
    }

    @Override
    public String getType(@NonNull Uri uri) {
        return "vnd.android.cursor.item/file";
    }
}
