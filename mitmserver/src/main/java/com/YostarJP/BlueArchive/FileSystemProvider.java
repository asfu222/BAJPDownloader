package com.YostarJP.BlueArchive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Paths;

public class FileSystemProvider extends ContentProvider {
    private static final String TAG = "FileSystemProvider";
    private static final String AUTHORITY = "com.asfu222.bajpdl.mitm.fs";
    private boolean initialized = false;

    @Override
    public synchronized boolean onCreate() {
        initialized = true;
        getContext().getExternalFilesDir(null).mkdirs();
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

    private File getFileForUri(@NonNull Uri uri) {
        waitForInitialization();
        return getContext().getExternalFilesDir(null).toPath().resolveSibling(Paths.get(uri.getPath())).toFile();
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        waitForInitialization();

        if (AUTHORITY.equals(uri.getAuthority())) {
            File file = getFileForUri(uri);
            int fileMode;
            if (mode.equals("r")) {
                fileMode = ParcelFileDescriptor.MODE_READ_ONLY;
            } else {
                fileMode = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_CREATE;
            }

            try {
                return ParcelFileDescriptor.open(file, fileMode);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Error opening file", e);
                throw e;
            }
        }
        throw new FileNotFoundException("File not found: " + uri);
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        waitForInitialization();

        if (AUTHORITY.equals(uri.getAuthority())) {
            File file = getFileForUri(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{"exists", "size"});
            Log.d(TAG, "Querying file: " + file + ", length = " + file.length());
            cursor.addRow(new Object[]{file.exists() ? 1 : 0, file.exists() ? file.length() : 0});
            return cursor;
        }
        return null;
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        waitForInitialization();

        if (AUTHORITY.equals(uri.getAuthority())) {
            File file = getFileForUri(uri);
            if (file.isDirectory() && file.exists()) {
                return uri;
            }
            try {
                if (file.mkdirs()) {
                    return uri;
                } else {
                    Log.e(TAG, "Failed to create directories");
                    Log.d(TAG, "Parent directory: " + file.getParentFile());
                    return null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to create directories", e);
                return null;
            }
        }
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        waitForInitialization();

        if (AUTHORITY.equals(uri.getAuthority())) {
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
