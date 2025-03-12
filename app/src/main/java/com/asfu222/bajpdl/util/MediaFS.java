package com.asfu222.bajpdl.util;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    public Path createDirectories(Path path) throws IOException {
        DocumentFile currentDir = rootDir;

        for (Path part : path) {
            String dirName = part.toString();

            // First try the efficient findFile method
            DocumentFile nextDir = currentDir.findFile(dirName);

            // If not found or not a directory, do a thorough manual search
            if (nextDir == null || !nextDir.isDirectory()) {
                // Manually search through all children for an exact name match
                DocumentFile[] files = currentDir.listFiles();
                for (DocumentFile file : files) {
                    if (file.isDirectory() && dirName.equals(file.getName())) {
                        nextDir = file;
                        break;
                    }
                }
            }

            // If still not found, create new directory
            if (nextDir == null) {
                nextDir = currentDir.createDirectory(dirName);
                if (nextDir == null) {
                    throw new IOException("Failed to create directory: " + dirName);
                }
            } else if (!nextDir.isDirectory()) {
                throw new IOException("A file with the same name exists: " + dirName);
            }

            currentDir = nextDir;
        }
        return path;
    }

    public byte[] readAllBytes(Path path) throws IOException {
        try (InputStream is = newInputStream(path)) {
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            return buffer;
        }
    }

    public void deleteIfExists(Path path) {
        DocumentFile currentDir = rootDir;

        for (Path part : path.getParent()) {
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
        DocumentFile newFile = currentDir.createFile("application/octet-stream", path.getFileName().toString());

        if (newFile != null && !newFile.getName().equals(path.getFileName().toString())) {
            newFile.renameTo(path.getFileName().toString());
        }
        return appContext.getContentResolver().openOutputStream(newFile.getUri());
    }

    public InputStream newInputStream(Path path) throws IOException {
        DocumentFile currentDir = rootDir;

        for (Path part : path.getParent()) {
            DocumentFile nextDir = currentDir.findFile(part.toString());
            if (nextDir == null || !nextDir.isDirectory()) {
                throw new IOException("Directory does not exist: " + part);
            }
            currentDir = nextDir;
        }

        DocumentFile file = currentDir.findFile(path.getFileName().toString());
        if (file == null || !file.isFile()) {
            throw new IOException("File does not exist: " + path);
        }

        return appContext.getContentResolver().openInputStream(file.getUri());
    }
    public boolean exists(Path path) {
        DocumentFile currentDir = rootDir;

        for (Path part : path.getParent()) {
            DocumentFile nextDir = currentDir.findFile(part.toString());
            if (nextDir == null || !nextDir.isDirectory()) {
                return false;
            }
            currentDir = nextDir;
        }

        DocumentFile file = currentDir.findFile(path.getFileName().toString());
        return file != null && file.isFile();
    }

    public Path toPath() {
        String realPath = getRealPathFromUri(rootDir.getUri());
        return Paths.get(realPath);
    }

    public Path toPath(Path path) {
        return Paths.get(getRealPathFromUri(rootDir.getUri()), path.toString());
    }

    public boolean isReady() {
        return rootDir != null;
    }

    private String getRealPathFromUri(Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        String docId = DocumentsContract.getDocumentId(uri);
        String[] split = docId.split(":");
        String type = split[0];
        String realPath;

        if ("primary".equalsIgnoreCase(type)) {
            realPath = "/storage/emulated/0/" + split[1];
        } else {
            // Handle non-primary volumes
            realPath = "/storage/" + type + "/" + split[1];
        }

        return realPath;
    }

    public Stream<Path> walk() {
        List<Path> paths = new ArrayList<>();
        walkDirectory(rootDir, paths);
        return paths.stream();
    }

    private void walkDirectory(DocumentFile dir, List<Path> paths) {
        for (DocumentFile file : dir.listFiles()) {
            if (file.isDirectory()) {
                walkDirectory(file, paths);
            } else {
                paths.add(Paths.get(getRealPathFromUri(file.getUri())));
            }
        }
    }

    public long size(Path path) {
        DocumentFile currentDir = rootDir;

        for (Path part : path.getParent()) {
            DocumentFile nextDir = currentDir.findFile(part.toString());
            if (nextDir == null || !nextDir.isDirectory()) {
                return 0;
            }
            currentDir = nextDir;
        }

        DocumentFile file = currentDir.findFile(path.getFileName().toString());
        if (file == null || !file.isFile()) {
            return 0;
        }

        return file.length();
    }
}
