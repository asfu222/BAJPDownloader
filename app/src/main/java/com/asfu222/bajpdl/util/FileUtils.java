package com.asfu222.bajpdl.util;

import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;
import android.os.Environment;

public abstract class FileUtils {
    private static final XXHash64 xxHash64 = XXHashFactory.fastestInstance().hash64();

    // Reusable buffer pool
    private static final ThreadLocal<byte[]> bufferPool = ThreadLocal.withInitial(() -> new byte[8192]);

    public static long calculateCRC32(Path file) throws IOException {
        byte[] buffer = bufferPool.get();
        CRC32 crc32 = new CRC32();

        try (InputStream is = new BufferedInputStream(EscalatedFS.newInputStream(file))) {
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

    public static Path getInGamePath(String urlPath) {
        return 	Environment.getExternalStorageDirectory().toPath().resolve("Android/data/com.YostarJP.BlueArchive/files/")
                .resolve(mapToInGamePath(urlPath));
    }

    public static String renameToInGameFormat(Path file) throws IOException {
        return renameToInGameFormat(file.getFileName().toString(), calculateCRC32(file));
    }

    public static String renameToInGameFormat(String name, long crc) {
        if (!name.endsWith(".bundle") && !STATIC_FILES.contains(name)) {
            return calculateHash64(name) + "_" + crc;
        }
        return name;
    }

    public static Path copyToGame(Path file, String urlPath) throws IOException {
        Path newPath = getInGamePath(urlPath).getParent().resolve(renameToInGameFormat(file));
        if (file.toAbsolutePath().equals(newPath.toAbsolutePath())) {
            return newPath;
        }
        if (!EscalatedFS.exists(newPath.getParent())) {
            EscalatedFS.createDirectories(newPath.getParent());
        }
        EscalatedFS.copy(file, newPath, StandardCopyOption.REPLACE_EXISTING);
        return newPath;
    }

    public static void deleteOldGameFiles(Path newestFile, BiConsumer<String, Exception> handler) {
        String newestFileName = newestFile.getFileName().toString();
        String namePart = newestFileName.substring(0, newestFileName.lastIndexOf('_'));

        try (var paths = EscalatedFS.walk(newestFile.getParent())) {
                paths.filter(f -> {
                String fileName = f.getFileName().toString();
                return fileName.startsWith(namePart) && !fileName.equals(newestFileName);
            }).forEach(f -> {
                try {
                    EscalatedFS.deleteIfExists(f);
                } catch (IOException e) {
                    handler.accept("删除旧文件时报错", e);
                }
            });
        } catch (IOException ex) {
            System.out.println("Caught error while walking " + newestFile.getParent());
            handler.accept("删除旧文件时报错", ex);
        }
    }
}
