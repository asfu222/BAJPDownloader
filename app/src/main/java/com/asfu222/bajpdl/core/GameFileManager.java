package com.asfu222.bajpdl.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.asfu222.bajpdl.MainActivity;
import com.asfu222.bajpdl.config.AppCache;
import com.asfu222.bajpdl.config.AppConfig;
import com.asfu222.bajpdl.service.CommonCatalogItem;
import com.asfu222.bajpdl.service.FileDownloader;
import com.asfu222.bajpdl.service.MXCatalog;
import com.asfu222.bajpdl.util.EscalatedFS;
import com.asfu222.bajpdl.util.FileUtils;

import org.json.JSONException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class GameFileManager {
    private static final double BYTES_TO_MB = 1024.0 * 1024.0;

    private final FileDownloader fileDownloader;
    private final AppConfig appConfig;
    private final AppCache appCache;
    private final Path dataPath;
    private final Context appContext;
    private final AtomicInteger totalFiles = new AtomicInteger();
    private final AtomicLong totalSize = new AtomicLong();
    private final AtomicInteger downloadedFiles = new AtomicInteger();
    private final AtomicLong downloadedSize = new AtomicLong();

    public GameFileManager(Context context) {
        this.appConfig = new AppConfig(context, this::logError);
        this.appCache = new AppCache(context);
        this.fileDownloader = new FileDownloader(appConfig);
        this.dataPath = context.getExternalMediaDirs()[0].toPath();
        this.appContext = context;
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public AppCache getAppCache() {
        return appCache;
    }

    public CompletableFuture<Path> downloadSingleAPK(String apkUrl) {
        downloadedFiles.set(0);
        totalFiles.set(1);
        downloadedSize.set(0);
        totalSize.set(0);
        isDownloading = true;
        handler.post(mainProgressRunnable);
        Path cachePath = appContext.getExternalFilesDir("bajpdl_cache").toPath();
        String apkFileName = apkUrl.substring(apkUrl.lastIndexOf('/') + 1);
        log("开始下载APK文件: " + apkUrl);
        log(apkFileName);
        return fileDownloader.downloadAsync(apkUrl + ".hash", cachePath.resolve(apkFileName + ".hash"), path -> true, true, this::logError, new AtomicLong()).thenCompose(hashPath -> {
            try {
                String[] hash_info = new String(EscalatedFS.readAllBytes(hashPath), StandardCharsets.UTF_8).trim().split(" ");
                long crc = Long.parseLong(hash_info[0]);
                long size = Long.parseLong(hash_info[1]);
                CommonCatalogItem item = new CommonCatalogItem(apkFileName, size, crc, false);
                totalSize.set(size);
                return fileDownloader.downloadAsync(apkUrl, cachePath.resolve(apkFileName), item::verifyIntegrity, false, this::logError, downloadedSize)
                        .thenApply(p -> {
                            isDownloading = false;
                            handler.removeCallbacks(mainProgressRunnable);
                            downloadedFiles.set(1);
                            updateProgress();
                            return p;
                        });
            } catch (IOException e) {
                logError("读取APK哈希值时报错", e);
                return CompletableFuture.completedFuture(null);
            }
        });
    }
    public CompletableFuture<Boolean> processFile(Map.Entry<String, CommonCatalogItem> catalogEntry) {
        return fileDownloader.downloadFile(dataPath, catalogEntry.getKey(),
                catalogEntry.getValue()::verifyIntegrity, appConfig.shouldAlwaysRedownload(), this::logError, catalogEntry.getValue(), downloadedSize).thenCompose(downloadedFile -> {
            if (downloadedFile == null) {
                log("下载此文件失败: " + catalogEntry.getKey());
                return CompletableFuture.completedFuture(false);
            }
            try {
                if (!appConfig.shouldDownloadStraightToGame()) {
                    downloadedFile = FileUtils.copyToGame(downloadedFile, catalogEntry.getKey());
                }
                FileUtils.deleteOldGameFiles(downloadedFile, this::logError);
            } catch (Exception e) {
                logError("处理文件时报错: " + catalogEntry.getKey(), e);
                return CompletableFuture.completedFuture(false);
            }

            downloadedFiles.incrementAndGet();
            //downloadedSize.addAndGet(catalogEntry.getValue().size);
            //updateProgress();
            return CompletableFuture.completedFuture(true);
        });
    }

    public CompletableFuture<Boolean> processFiles(Map<String, CommonCatalogItem> catalog) {
        log("正在处理 " + catalog.size() + " 个文件");

        CompletableFuture<Boolean> completionFuture = new CompletableFuture<>();

        List<Map.Entry<String, CommonCatalogItem>> sortedEntries = catalog.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().size, e1.getValue().size))
                .collect(Collectors.toList());

        // Start all files downloads concurrently without batching
        List<CompletableFuture<Boolean>> downloadFutures = sortedEntries.stream()
                .map(this::processFile) // processFile handles each file download and post-processing
                .collect(Collectors.toList());

        // After all downloads finish, handle completion
        CompletableFuture.allOf(downloadFutures.toArray(new CompletableFuture[0]))
                .thenAccept(v -> {
                    long failures = downloadFutures.stream().filter(future -> !future.join()).count();
                    log("所有文件处理完毕。 失败文件数: " + failures);
                    completionFuture.complete(failures == 0);
                })
                .exceptionally(ex -> {
                    logError("处理文件错误", new Exception(ex));
                    completionFuture.complete(false); // If an error occurs, complete the future with failure
                    return null;
                });

        return completionFuture;
    }


    private static boolean isDownloading = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable mainProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (isDownloading) {
                updateProgress();
                handler.postDelayed(this, 500);
            }
        }
    };

    private final Deque<Runnable> onDownloadComplete = new ArrayDeque<>();

    public Deque<Runnable> getOnDownloadComplete() {
        return onDownloadComplete;
    }


    public void startDownloads() {
        if (isDownloading) {
            log("已经在下载中");
            return;
        }
        log("开始下载更新...");
        downloadedFiles.set(0);
        downloadedSize.set(0);
        totalFiles.set(0);
        totalSize.set(0);
        fileDownloader.updateThreadPool();
        isDownloading = true;
        handler.post(mainProgressRunnable);

        fileDownloader.fetchServerAvailable().thenRun(() -> {
            Set<String> availableCustomDownloads = fileDownloader.getAvailableCustomDownloads();

            List<CompletableFuture<Boolean>> catalogFutures = List.of(
                    downloadAndProcessCatalog("TableBundles/TableCatalog.bytes", availableCustomDownloads),
                    downloadAndProcessCatalog("MediaResources/Catalog/MediaCatalog.bytes", availableCustomDownloads),
                    downloadAndProcessCatalog("Android/bundleDownloadInfo.json", availableCustomDownloads),
                    downloadAndCopyFile("TableBundles/TableCatalog.hash"),
                    downloadAndCopyFile("MediaResources/Catalog/MediaCatalog.hash"),
                    downloadAndCopyFile("Android/bundleDownloadInfo.hash")
            );

            CompletableFuture.allOf(catalogFutures.toArray(new CompletableFuture[0]))
                    .thenAccept(v -> {
                        log("已完成更新");
                        isDownloading = false;
                        updateProgress();
                        handler.removeCallbacks(mainProgressRunnable);
                        for (Runnable runnable : onDownloadComplete) {
                            runnable.run();
                        }
                        onDownloadComplete.clear();
                    });
        });
    }

    private CompletableFuture<Boolean> downloadAndProcessCatalog(String catalogPath, Set<String> availableCustomDownloads) {
        return fileDownloader.downloadFile(dataPath, catalogPath, path -> true, true, this::logError, CommonCatalogItem.EMPTY, new AtomicLong()).thenCompose(path -> {
            try {
                log("已下载 " + catalogPath + ", 处理中...");
                //long catalogCrc = FileUtils.calculateCRC32(path);
                MXCatalog catalog;
                switch (catalogPath) {
                    case "TableBundles/TableCatalog.bytes":
                        //if (catalogCrc == appCache.getTbCrc()) return CompletableFuture.completedFuture(true);
                        catalog = MXCatalog.parseMemoryPackerBytes(EscalatedFS.readAllBytes(path), false);
                        break;
                    case "MediaResources/Catalog/MediaCatalog.bytes":
                        //if (catalogCrc == appCache.getMpCrc()) return CompletableFuture.completedFuture(true);
                        catalog = MXCatalog.parseMemoryPackerBytes(EscalatedFS.readAllBytes(path), true);
                        break;
                    case "Android/bundleDownloadInfo.json":
                        //if (catalogCrc == appCache.getAbCrc()) return CompletableFuture.completedFuture(true);
                        catalog = MXCatalog.parseBundleDLInfoJson(EscalatedFS.readAllBytes(path));
                        break;
                    default:
                        return CompletableFuture.completedFuture(false);
                }
                if (appConfig.shouldDownloadCustomOnly()) {
                    catalog.getData().keySet().removeIf(key -> !availableCustomDownloads.contains(key));
                }
                    // catalog.getData().entrySet().removeIf(entry -> !availableCustomDownloads.contains(entry.getKey()) && entry.getValue().size < BYTES_TO_MB);
                totalFiles.addAndGet(catalog.getData().size());
                totalSize.addAndGet(catalog.getData().values().stream().mapToLong(item -> item.size).sum());
                updateProgress();
                log(catalogPath + " 含有 " + catalog.getData().size() + " 个文件");

                FileUtils.copyToGame(path, catalogPath);
                return processFiles(catalog.getData());
            } catch (IOException | JSONException ex) {
                logError("处理时报错： " + catalogPath, ex);
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    private CompletableFuture<Boolean> downloadAndCopyFile(String filePath) {
        return fileDownloader.downloadFile(dataPath, filePath, path -> true, true, this::logError, CommonCatalogItem.EMPTY, new AtomicLong()).thenCompose(path -> {
            try {
                FileUtils.copyToGame(path, filePath);
                return CompletableFuture.completedFuture(true);
            } catch (IOException e) {
                logError("复制此文件到游戏时报错： " + filePath, e);
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    /*
    public void startReplacements() {
        try (var paths = EscalatedFS.walk(dataPath)) {
                CompletableFuture.allOf(paths
                            .filter(Files::isRegularFile)
                            .map(file -> CompletableFuture.runAsync(() -> {
                                try {
                                    Path relPath = dataPath.relativize(file);
                                    FileUtils.copyToGame(file, relPath.toString());
                                    log("已复制至游戏: " + relPath);
                                } catch (IOException e) {
                                    logError("复制到游戏时报错", e);
                                }
                            })).toArray(CompletableFuture[]::new))
                    .thenRun(() -> log("完成替换（未下载任何文件）"));
        } catch (IOException e) {
            logError("Error walking directory", e);
        }
    }
     */

    public void shutdown() {
        fileDownloader.shutdown();
    }

    private void updateProgress() {
        MainActivity mainActivity = (MainActivity) appContext;
        mainActivity.updateProgress(
                downloadedFiles.get(),
                totalFiles.get(),
                (long)(downloadedSize.get() / BYTES_TO_MB),
                (long)(totalSize.get() / BYTES_TO_MB)
        );
    }

    private void log(String message) {
        MainActivity mainActivity = (MainActivity) appContext;
        mainActivity.updateConsole(message);
    }

    private void logError(String message, Exception ex) {
        MainActivity mainActivity = (MainActivity) appContext;
        mainActivity.logErrorToConsole(message, ex);
    }
}