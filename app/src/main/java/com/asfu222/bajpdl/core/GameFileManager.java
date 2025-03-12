package com.asfu222.bajpdl.core;

import android.content.Context;

import com.asfu222.bajpdl.MainActivity;
import com.asfu222.bajpdl.config.AppConfig;
import com.asfu222.bajpdl.service.CommonCatalogItem;
import com.asfu222.bajpdl.service.FileDownloader;
import com.asfu222.bajpdl.service.MXCatalog;
import com.asfu222.bajpdl.util.FileUtils;
import com.asfu222.bajpdl.util.MediaFS;

import org.json.JSONException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private final MediaFS dataPath;
    private final Context appContext;
    private final AtomicInteger totalFiles = new AtomicInteger();
    private final AtomicLong totalSize = new AtomicLong();
    private final AtomicInteger downloadedFiles = new AtomicInteger();
    private final AtomicLong downloadedSize = new AtomicLong();

    public GameFileManager(Context context) {
        this.appConfig = new AppConfig(context);
        this.fileDownloader = new FileDownloader(appConfig);
        this.dataPath = new MediaFS(context);
        dataPath.setRootDir(context.getExternalMediaDirs()[0]);
        this.appContext = context;
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public MediaFS getDataPath() {
        return dataPath;
    }

    public CompletableFuture<Boolean> processFile(Map.Entry<String, CommonCatalogItem> catalogEntry) {
        return fileDownloader.downloadFile(dataPath, catalogEntry.getKey(), catalogEntry.getValue()::verifyIntegrity, appConfig.shouldAlwaysRedownload(), this::logError)
                .thenApply(downloadedFile -> {
                    downloadedFiles.incrementAndGet();
                    downloadedSize.addAndGet(catalogEntry.getValue().size);
                    updateProgress();
                    if (downloadedFile == null) {
                        log("Failed to download file: " + catalogEntry.getKey());
                        // Still increment counter so we don't get stuck
                        return false;
                    }

                    try {
                        log("Copying file to game: " + catalogEntry.getKey());
                        FileUtils.copyToGame(dataPath, downloadedFile, catalogEntry.getKey());
                        return true;
                    } catch (IOException e) {
                        logError("Error copying file to game", e);
                        // Continue processing other files even if one fails
                        return false;
                    }
                });
    }

    public CompletableFuture<Boolean> processFiles(Map<String, CommonCatalogItem> catalog) {
        log("Processing " + catalog.size() + " files");

        // Create a single completable future for the entire process
        CompletableFuture<Boolean> completionFuture = new CompletableFuture<>();

        // Get all catalog entries and sort by size (process smaller files first)
        List<Map.Entry<String, CommonCatalogItem>> sortedEntries = catalog.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().size, e1.getValue().size)) // Largest first
                .collect(Collectors.toList());

        // Batch size and failure tracking
        final int BATCH_SIZE = appConfig.getBatchSize();
        AtomicInteger currentIndex = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Process next batch recursively
        processNextBatch(sortedEntries, currentIndex, failureCount, BATCH_SIZE, completionFuture);

        return completionFuture;
    }

    private void processNextBatch(List<Map.Entry<String, CommonCatalogItem>> entries,
                                  AtomicInteger currentIndex,
                                  AtomicInteger failureCount,
                                  int batchSize,
                                  CompletableFuture<Boolean> completionFuture) {

        // Check if we're done
        if (currentIndex.get() >= entries.size()) {
            log("All files processed. Failed: " + failureCount.get());
            completionFuture.complete(failureCount.get() == 0);
            return;
        }

        // Get next batch of files to process
        int endIndex = Math.min(currentIndex.get() + batchSize, entries.size());
        List<Map.Entry<String, CommonCatalogItem>> currentBatch =
                entries.subList(currentIndex.get(), endIndex);

        log(String.format("Processing batch %d (files %d-%d of %d)",
                currentIndex.get() / batchSize + 1,
                currentIndex.get() + 1, endIndex, entries.size()));

        // Process each file in the batch
        List<CompletableFuture<Boolean>> batchFutures = currentBatch.stream()
                .map(entry -> {
                    // log("Starting download: " + entry.getKey());
                    return processFile(entry);
                })
                .collect(Collectors.toList());

        // When all files in this batch complete, process the next batch
        CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                .thenAccept(v -> {
                    // Count failures in this batch
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

                    // Move to next batch
                    currentIndex.set(endIndex);

                    // Schedule next batch processing
                    processNextBatch(entries, currentIndex, failureCount, batchSize, completionFuture);
                })
                .exceptionally(ex -> {
                    logError("Critical error in batch", new Exception(ex));
                    failureCount.incrementAndGet();

                    // Move to next batch despite errors
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

            List<CompletableFuture<Boolean>> catalogFutures = new ArrayList<>();
            catalogFutures.add(fileDownloader.downloadFile(dataPath, "TableBundles/TableCatalog.bytes", (basePath,path) -> true, true, this::logError).thenCompose(catalogPath -> {
                try {
                    log("Downloaded TableCatalog.bytes, processing...");
                    var catalog = MXCatalog.parseMemoryPackerBytes(dataPath.readAllBytes(catalogPath), false).getData();
                    if (appConfig.shouldDownloadCustomOnly()) {
                        catalog.keySet().removeIf(key -> !availableCustomDownloads.contains(key));
                    }
                    totalFiles.addAndGet(catalog.size());
                    totalSize.addAndGet(catalog.values().stream().mapToLong(item -> item.size).sum());
                    updateProgress();
                    log("TableCatalog contains " + catalog.size() + " files");

                    FileUtils.copyToGame(dataPath, catalogPath, "TableBundles/TableCatalog.bytes");
                    return processFiles(catalog);
                } catch (IOException ex) {
                    logError("Error processing TableCatalog.bytes", ex);
                    return CompletableFuture.completedFuture(false);
                }
            }));
            catalogFutures.add(fileDownloader.downloadFile(dataPath, "MediaResources/Catalog/MediaCatalog.bytes", (basePath,path) -> true, true, this::logError).thenCompose(catalogPath -> {
                try {
                    log("Downloaded MediaCatalog.bytes, processing...");
                    var catalog = MXCatalog.parseMemoryPackerBytes(dataPath.readAllBytes(catalogPath), true).getData();
                    if (appConfig.shouldDownloadCustomOnly()) {
                        catalog.keySet().removeIf(key -> !availableCustomDownloads.contains(key));
                    }
                    totalFiles.addAndGet(catalog.size());
                    totalSize.addAndGet(catalog.values().stream().mapToLong(item -> item.size).sum());
                    updateProgress();
                    log("MediaCatalog contains " + catalog.size() + " files");

                    FileUtils.copyToGame(dataPath, catalogPath, "MediaResources/Catalog/MediaCatalog.bytes");
                    return processFiles(catalog);
                } catch (IOException ex) {
                    logError("Error processing MediaCatalog", ex);
                    return CompletableFuture.completedFuture(false);
                }
            }));
            catalogFutures.add(fileDownloader.downloadFile(dataPath, "Android/bundleDownloadInfo.json", (basePath,path) -> true, true, this::logError).thenCompose(catalogPath -> {
                try {
                    log("Downloaded bundleDownloadInfo.json, processing...");
                    var catalog = MXCatalog.parseBundleDLInfoJson(dataPath.readAllBytes(catalogPath)).getData();
                    if (appConfig.shouldDownloadCustomOnly()) {
                        catalog.keySet().removeIf(key -> !availableCustomDownloads.contains(key));
                    }
                    totalFiles.addAndGet(catalog.size());
                    totalSize.addAndGet(catalog.values().stream().mapToLong(item -> item.size).sum());
                    updateProgress();
                    log("bundleDownloadInfo contains " + catalog.size() + " files");

                    FileUtils.copyToGame(dataPath, catalogPath, "Android/bundleDownloadInfo.json");
                    return processFiles(catalog);
                } catch (IOException | JSONException ex) {
                    logError("Error processing bundleDownloadInfo.json", ex);
                    return CompletableFuture.completedFuture(false);
                }
            }));
            catalogFutures.add(fileDownloader.downloadFile(dataPath, "TableBundles/TableCatalog.hash", (basePath,path) -> true, true, this::logError).thenCompose(path -> {
                try {
                    FileUtils.copyToGame(dataPath, path, "TableBundles/TableCatalog.hash");
                    return CompletableFuture.completedFuture(true);
                } catch (IOException e) {
                    logError("Error copying TableCatalog.hash to game", e);
                    return CompletableFuture.completedFuture(false);
                }
            }));
            catalogFutures.add(fileDownloader.downloadFile(dataPath, "MediaResources/Catalog/MediaCatalog.hash", (basePath,path) -> true, true, this::logError).thenCompose(path -> {
                try {
                    FileUtils.copyToGame(dataPath, path, "MediaResources/Catalog/MediaCatalog.hash");
                    return CompletableFuture.completedFuture(true);
                } catch (IOException e) {
                    logError("Error copying MediaCatalog.hash to game", e);
                    return CompletableFuture.completedFuture(false);
                }
            }));
            catalogFutures.add(fileDownloader.downloadFile(dataPath, "Android/bundleDownloadInfo.hash", (basePath,path) -> true, true, this::logError).thenCompose(path -> {
                try {
                    FileUtils.copyToGame(dataPath, path, "Android/bundleDownloadInfo.hash");
                    return CompletableFuture.completedFuture(true);
                } catch (IOException e) {
                    logError("Error copying bundleDownloadInfo.hash to game", e);
                    return CompletableFuture.completedFuture(false);
                }
            }));
            CompletableFuture.allOf(catalogFutures.toArray(new CompletableFuture[0]))
                    .thenAccept(v -> log("All downloads completed"));
        });
    }

    public void startReplacements() {
        try (var paths = dataPath.walk()) {
            CompletableFuture.allOf(paths
                            .filter(Files::isRegularFile)
                            .map(file -> CompletableFuture.runAsync(() -> {
                                try {
                                    Path relPath = dataPath.toPath().relativize(file);
                                    FileUtils.copyToGame(dataPath, relPath, relPath.toString());
                                    log("Successfully copied file to game: " + relPath);
                                } catch (IOException e) {
                                    logError("Error copying file to game", e);
                                }
                            })).toArray(CompletableFuture[]::new))
                    .thenRun(() -> log("Done copying all files"));
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
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        mainActivity.updateConsole(message + ": " + sw);
    }
}
