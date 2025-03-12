package com.asfu222.bajpdl.util;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A wrapper to convert java.nio.file.Files operations to MediaStore operations based on relative paths.
 */
public class MediaFS {
    private final Context appContext;
    private DocumentFile rootDir;
    public MediaFS(Context context) {
        appContext = context;
    }

    public void setRootDir(Uri treeUri) {
        var file = DocumentFile.fromTreeUri(appContext, treeUri);
        if (file != null && file.isDirectory() && file.canWrite()) {
            rootDir = file;
        }
    }

    public void setRootDir(File path) {
        var file = DocumentFile.fromFile(path);
        if (file.isDirectory() && file.canWrite()) {
            rootDir = file;
        }
    }

    public Path createDirectories(Path path) {
        DocumentFile currentDir = rootDir;

        for (Path part : path) {
            DocumentFile nextDir = currentDir.findFile(part.toString());
            if (nextDir == null || !nextDir.isDirectory()) {
                nextDir = currentDir.createDirectory(part.toString());
            }
            currentDir = nextDir;
        }
        return path;
    }

    public void deleteIfExists(Path path) {
        DocumentFile currentDir = rootDir;

        for (Path part : path) {
            DocumentFile nextDir = currentDir.findFile(part.toString());
            if (nextDir == null || !nextDir.isDirectory()) {
                return; // Directory does not exist, so file cannot exist
            }
            currentDir = nextDir;
        }

        DocumentFile file = currentDir.findFile(path.getFileName().toString());
        if (file != null) {
            file.delete();
        }
    }

    public OutputStream newOutputStream(Path path) throws IOException {
        DocumentFile currentDir = rootDir;

        for (Path part : path.getParent()) {
            DocumentFile nextDir = currentDir.findFile(part.toString());
            if (nextDir == null || !nextDir.isDirectory()) {
                nextDir = currentDir.createDirectory(part.toString());
            }
            currentDir = nextDir;
        }

        DocumentFile file = currentDir.findFile(path.getFileName().toString());
        if (file != null) {
            file.delete();
        }
        return appContext.getContentResolver().openOutputStream(currentDir.createFile("application/octet-stream", path.getFileName().toString()).getUri());
    }


    public Path resolve(Path path) {
        Uri rootUri = rootDir.getUri();
        String realPath = getRealPathFromUri(rootUri);
        Path rootPath = Paths.get(realPath);
        return rootPath.resolve(path);
    }

    public Path resolve(String path) {
        return resolve(Paths.get(path));
    }

    public Path toPath() {
        String realPath = getRealPathFromUri(rootDir.getUri());
        return Paths.get(realPath);
    }

    public boolean isReady() {
        return rootDir != null;
    }

    private String getRealPathFromUri(Uri uri) {
        String docId = DocumentsContract.getDocumentId(uri);
        String[] split = docId.split(":");
        String type = split[0];
        String realPath = "";

        if ("primary".equalsIgnoreCase(type)) {
            realPath = "/storage/emulated/0/" + split[1];
        } else {
            // Handle non-primary volumes
            realPath = "/storage/" + type + "/" + split[1];
        }

        return realPath;
    }
}
