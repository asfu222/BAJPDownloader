package com.asfu222.bajpdl.core;

import android.content.Context;

import com.asfu222.bajpdl.MainActivity;
import com.asfu222.bajpdl.config.AppConfig;
import com.asfu222.bajpdl.service.CommonCatalogItem;
import com.asfu222.bajpdl.service.FileDownloader;
import com.asfu222.bajpdl.service.MXCatalog;
import com.asfu222.bajpdl.util.EscalatedFS;
import com.asfu222.bajpdl.util.FileUtils;

import org.json.JSONException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final Path dataPath;
    private final Context appContext;
    private final AtomicInteger totalFiles = new AtomicInteger();
    private final AtomicLong totalSize = new AtomicLong();
    private final AtomicInteger downloadedFiles = new AtomicInteger();
    private final AtomicLong downloadedSize = new AtomicLong();

    public GameFileManager(Context context) {
        this.appConfig = new AppConfig(context, this::logError);
        this.fileDownloader = new FileDownloader(appConfig);
        this.dataPath = context.getExternalMediaDirs()[0].toPath();
        this.appContext = context;
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public CompletableFuture<Boolean> processFile(Map.Entry<String, CommonCatalogItem> catalogEntry) {
        return fileDownloader.downloadFile(dataPath, catalogEntry.getKey(), catalogEntry.getValue()::verifyIntegrity, appConfig.shouldAlwaysRedownload(), this::logError)
                .thenApply(downloadedFile -> {
                    downloadedFiles.incrementAndGet();
                    downloadedSize.addAndGet(catalogEntry.getValue().size);
                    updateProgress();
                    if (downloadedFile == null) {
                        log("Failed to download file: " + catalogEntry.getKey());
                        return false;
                    }

                    try {
                        log("Copying file to game: " + catalogEntry.getKey());
                        FileUtils.copyToGame(downloadedFile, catalogEntry.getKey());
                        return true;
                    } catch (IOException e) {
                        logError("Error copying file to game", e);
                        return false;
                    }
                });
    }

    public CompletableFuture<Boolean> processFiles(Map<String, CommonCatalogItem> catalog) {
        log("Processing " + catalog.size() + " files");

        CompletableFuture<Boolean> completionFuture = new CompletableFuture<>();

        List<Map.Entry<String, CommonCatalogItem>> sortedEntries = catalog.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().size, e1.getValue().size))
                .collect(Collectors.toList());

        final int BATCH_SIZE = appConfig.getBatchSize();
        AtomicInteger currentIndex = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        processNextBatch(sortedEntries, currentIndex, failureCount, BATCH_SIZE, completionFuture);

        return completionFuture;
    }

    private void processNextBatch(List<Map.Entry<String, CommonCatalogItem>> entries,
                                  AtomicInteger currentIndex,
                                  AtomicInteger failureCount,
                                  int batchSize,
                                  CompletableFuture<Boolean> completionFuture) {

        if (currentIndex.get() >= entries.size()) {
            log("All files processed. Failed: " + failureCount.get());
            completionFuture.complete(failureCount.get() == 0);
            return;
        }

        int endIndex = Math.min(currentIndex.get() + batchSize, entries.size());
        List<Map.Entry<String, CommonCatalogItem>> currentBatch = entries.subList(currentIndex.get(), endIndex);

        log(String.format("Processing batch %d (files %d-%d of %d)",
                currentIndex.get() / batchSize + 1,
                currentIndex.get() + 1, endIndex, entries.size()));

        List<CompletableFuture<Boolean>> batchFutures = currentBatch.stream()
                .map(this::processFile)
                .collect(Collectors.toList());

        CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                .thenAccept(v -> {
                    for (CompletableFuture<Boolean> future : batchFutures) {
                        try {
                            if (!future.join()) {
                                failureCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                            logError("Error processing batch item", e);
                        }
                    }

                    log(String.format("Batch %d complete (%d-%d of %d). Current failures: %d",
                            currentIndex.get() / batchSize + 1,
                            currentIndex.get() + 1, endIndex, entries.size(), failureCount.get()));

                    currentIndex.set(endIndex);
                    processNextBatch(entries, currentIndex, failureCount, batchSize, completionFuture);
                })
                .exceptionally(ex -> {
                    logError("Critical error in batch", new Exception(ex));
                    failureCount.incrementAndGet();
                    currentIndex.set(endIndex);
                    processNextBatch(entries, currentIndex, failureCount, batchSize, completionFuture);
                    return null;
                });
    }

    public void startDownloads() {
        log("Starting downloads...");
        downloadedFiles.set(0);
        downloadedSize.set(0);
        totalFiles.set(0);
        totalSize.set(0);
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
                    .thenAccept(v -> log("All downloads completed"));
        });
    }

    private CompletableFuture<Boolean> downloadAndProcessCatalog(String catalogPath, Set<String> availableCustomDownloads) {
        return fileDownloader.downloadFile(dataPath, catalogPath, path -> true, true, this::logError).thenCompose(path -> {
            try {
                log("Downloaded " + catalogPath + ", processing...");
                MXCatalog catalog;
                switch (catalogPath) {
                    case "TableBundles/TableCatalog.bytes":
                        catalog = MXCatalog.parseMemoryPackerBytes(EscalatedFS.readAllBytes(path), false);
                        break;
                    case "MediaResources/Catalog/MediaCatalog.bytes":
                        catalog = MXCatalog.parseMemoryPackerBytes(EscalatedFS.readAllBytes(path), true);
                        break;
                    case "Android/bundleDownloadInfo.json":
                        catalog = MXCatalog.parseBundleDLInfoJson(EscalatedFS.readAllBytes(path));
                        break;
                    default:
                        return CompletableFuture.completedFuture(false);
                }
                if (appConfig.shouldDownloadCustomOnly()) {
                    catalog.getData().keySet().removeIf(key -> !availableCustomDownloads.contains(key));
                }
                totalFiles.addAndGet(catalog.getData().size());
                totalSize.addAndGet(catalog.getData().values().stream().mapToLong(item -> item.size).sum());
                updateProgress();
                log(catalogPath + " contains " + catalog.getData().size() + " files");

                FileUtils.copyToGame(path, catalogPath);
                return processFiles(catalog.getData());
            } catch (IOException | JSONException ex) {
                logError("Error processing " + catalogPath, ex);
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    private CompletableFuture<Boolean> downloadAndCopyFile(String filePath) {
        return fileDownloader.downloadFile(dataPath, filePath, path -> true, true, this::logError).thenCompose(path -> {
            try {
                FileUtils.copyToGame(path, filePath);
                return CompletableFuture.completedFuture(true);
            } catch (IOException e) {
                logError("Error copying " + filePath + " to game", e);
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    public void startReplacements() {
        try (var paths = EscalatedFS.walk(dataPath)) {
            CompletableFuture.allOf(paths
                            .filter(Files::isRegularFile)
                            .map(file -> CompletableFuture.runAsync(() -> {
                                try {
                                    Path relPath = dataPath.relativize(file);
                                    FileUtils.copyToGame(file, relPath.toString());
                                    log("Successfully copied file to game: " + relPath);
                                } catch (IOException e) {
                                    logError("Error copying file to game", e);
                                }
                            })).toArray(CompletableFuture[]::new))
                    .thenRun(() -> log("Done copying all files"));
        } catch (IOException e) {
            logError("Error walking directory", e);
        }
    }

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