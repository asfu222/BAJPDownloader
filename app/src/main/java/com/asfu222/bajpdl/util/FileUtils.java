package com.asfu222.bajpdl.util;

import android.os.Build;

import com.asfu222.bajpdl.shizuku.ShizukuService;

import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.zip.CRC32;

public abstract class FileUtils {
    private static final XXHash64 xxHash64 = XXHashFactory.fastestInstance().hash64();
    private static boolean rootAvailable;

    public static void setRootAvailable(boolean value) {
        rootAvailable = value;
    }

    // Reusable buffer pool
    private static final ThreadLocal<byte[]> bufferPool = ThreadLocal.withInitial(() -> new byte[8192]);

    public static long calculateCRC32(MediaFS basePath, Path file) throws IOException {
        byte[] buffer = bufferPool.get();
        CRC32 crc32 = new CRC32();

        try (InputStream is = new BufferedInputStream(basePath.newInputStream(file))) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                crc32.update(buffer, 0, bytesRead);
            }
        }

        return crc32.getValue();
    }

    public static String calculateHash64(String name) {
        byte[] data = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return Long.toUnsignedString(xxHash64.hash(data, 0, data.length, 0));
    }

    public static String mapToInGamePath(String urlPath) {
        String fileName = urlPath.substring(urlPath.lastIndexOf("/") + 1);

        if (urlPath.startsWith("Android/")) {
            return "AssetBundls/" + fileName;
        } else if (urlPath.startsWith("MediaResources/")) {
            if (fileName.startsWith("MediaCatalog")) {
                return "MediaPatch/Catalog/" + fileName;
            }
            return "MediaPatch/" + fileName;
        }

        return urlPath;
    }

    private static final Set<String> STATIC_FILES = Set.of("TableCatalog.bytes", "MediaCatalog.bytes", "bundleDownloadInfo.json", "TableCatalog.hash", "MediaCatalog.hash", "bundleDownloadInfo.hash");

    public static void copyToGame(MediaFS basePath, Path file, String urlPath) throws IOException {
        Path gamePath = Paths.get("/storage/emulated/0/Android/data/com.YostarJP.BlueArchive/files/")
                .resolve(mapToInGamePath(urlPath));
        String newName = file.getFileName().toString();
        if (!newName.endsWith(".bundle") && !STATIC_FILES.contains(newName)) {
            newName = calculateHash64(newName) + "_" + calculateCRC32(basePath, file);
        }
        Path newPath = gamePath.getParent().resolve(newName);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Create parent directory if needed
            if (!Files.exists(newPath.getParent())) {
                Files.createDirectories(newPath.getParent());
            }
            Files.copy(file, newPath, StandardCopyOption.REPLACE_EXISTING);
        } else if (rootAvailable) {
            try {
                // Create directory using root command
                String mkdirCmd = "su -c 'mkdir -p \"" + newPath.getParent().toString() + "\"'";
                int mkdirResult = executeRootCommand(mkdirCmd);

                if (mkdirResult != 0) {
                    throw new IOException("Failed to create directory: " + mkdirResult);
                }

                // Copy file using root command
                String cpCmd = "su -c 'cp -f \"" + basePath.toPath(file) + "\" \"" + newPath + "\"'";
                int cpResult = executeRootCommand(cpCmd);

                if (cpResult != 0) {
                    throw new IOException("Failed to copy file: " + cpResult);
                }
            } catch (Exception e) {
                System.err.println("Root operation failed: " + e.getMessage());
                throw new IOException("Failed to copy file using root: " + e.getMessage(), e);
            }
        } else {
            try {
                // Make sure service is connected
                if (!ShizukuService.isServiceConnected()) {
                    throw new IOException("Shizuku service not connected");
                }

                // Create directory using shell command
                String mkdirCmd = "mkdir -p '" + newPath.getParent().toString() + "'";
                int mkdirResult = ShizukuService.executeCommand(mkdirCmd);

                if (mkdirResult != 0) {
                    throw new IOException("Failed to create directory: " + mkdirResult);
                }

                // Copy file using shell command
                String cpCmd = "cp -f '" + basePath.toPath(file) + "' '" + newPath + "'";
                int cpResult = ShizukuService.executeCommand(cpCmd);

                if (cpResult != 0) {
                    throw new IOException("Failed to copy file: " + cpResult);
                }

                // System.out.println("File copied successfully: " + newPath);
            } catch (Exception e) {
                System.err.println("Shizuku operation failed: " + e.getMessage());
                throw new IOException("Failed to copy file " + file + " using Shizuku: " + e.getMessage(), e);
            }
        }
    }

    private static int executeRootCommand(String command) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(command);
        return process.waitFor();
    }
}
