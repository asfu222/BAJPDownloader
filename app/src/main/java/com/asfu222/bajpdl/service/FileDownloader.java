package com.asfu222.bajpdl.service;

import com.asfu222.bajpdl.config.AppConfig;
import com.asfu222.bajpdl.util.EscalatedFS;
import com.asfu222.bajpdl.util.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class FileDownloader {
    private ExecutorService executorService;
    private final AppConfig appConfig;
    private static final int CONNECTION_TIMEOUT = 15000; // 15 seconds
    private static final int READ_TIMEOUT = 15000; // 15 seconds

    private static final Map<String, Set<String>> serverAvailable = new HashMap<>();

    public FileDownloader(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.executorService = Executors.newFixedThreadPool(appConfig.getConcurrentDownloads());
    }

    public void updateThreadPool() {
        executorService.shutdown();
        executorService = Executors.newFixedThreadPool(appConfig.getConcurrentDownloads());
    }

    public CompletableFuture<Path> downloadFile(Path basePath, String relPath,
                                                Function<Path, Boolean> verifier, boolean replace, BiConsumer<String, Exception> handler, CommonCatalogItem item, AtomicLong downloadedSize) {
        return CompletableFuture.supplyAsync(() -> {
            int attempts = 5;
            int delay = 5000; // 5 seconds
            for (int i = 0; i < attempts; i++) {
                Path result = null;
                try {
                    result = tryDownloadFromAllSources(basePath, relPath, verifier, replace, item, downloadedSize);
                } catch (IOException e) {
                    handler.accept("Error downloading " + relPath, e);
                }
                if (result != null) {
                    return result;
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    handler.accept("Error downloading " + relPath, e);
                }
            }

            return null;
        }, executorService);
    }

    private Path tryDownloadFromAllSources(Path basePath, String relPath,
                                           Function<Path, Boolean> verifier, boolean replace, CommonCatalogItem item, AtomicLong downloadedSize) throws IOException {
        List<String> serverUrls = appConfig.getServerUrls();
        StringBuilder crcLog = new StringBuilder();
        // Try primary servers first
        for (String baseUrl : serverUrls) {
            String fileUrl = baseUrl + "/" + relPath;
            if (serverAvailable.get(baseUrl).contains(relPath)) {
                Path downloadPath = basePath.resolve(relPath);
                if (appConfig.shouldDownloadStraightToGame()) {
                    downloadPath = FileUtils.getInGamePath(relPath).getParent().resolve(FileUtils.renameToInGameFormat(downloadPath.getFileName().toString(), item.crc));
                }
                Path downloadedFile = downloadSingleFile(fileUrl,
                        downloadPath, verifier, replace, downloadedSize);

                if (verifier.apply(downloadedFile)) {
                    return downloadedFile;
                }
                downloadedSize.addAndGet(-EscalatedFS.size(downloadedFile));
                crcLog.append("网址 ").append(baseUrl).append("\n");
                crcLog.append("预期CRC： ").append(item.crc).append("\n");
                crcLog.append("收到CRC： ").append(FileUtils.calculateCRC32(downloadedFile)).append("\n");
                crcLog.append("预期大小： ").append(item.size).append("\n");
                crcLog.append("收到大小： ").append(EscalatedFS.size(downloadedFile)).append("\n");
                // Delete invalid file
                EscalatedFS.deleteIfExists(downloadedFile);
            }
        }

        // Try fallback server as last resort
        String fallbackUrl = appConfig.getFallbackUrl() + "/" + relPath;
        Path downloadPath = basePath.resolve(relPath);
        if (appConfig.shouldDownloadStraightToGame()) {
            downloadPath = FileUtils.getInGamePath(relPath).getParent().resolve(FileUtils.renameToInGameFormat(downloadPath.getFileName().toString(), item.crc));
        }
        Path downloadedFile = downloadSingleFile(fallbackUrl,
                downloadPath, verifier, replace, downloadedSize);

        if (verifier.apply(downloadedFile)) {
            return downloadedFile;
        }
        downloadedSize.addAndGet(-EscalatedFS.size(downloadedFile));
        crcLog.append("网址 ").append(appConfig.getFallbackUrl()).append("\n");
        crcLog.append("预期CRC： ").append(item.crc).append("\n");
        crcLog.append("收到CRC： ").append(FileUtils.calculateCRC32(downloadedFile)).append("\n");
        crcLog.append("预期大小： ").append(item.size).append("\n");
        crcLog.append("收到大小： ").append(EscalatedFS.size(downloadedFile)).append("\n");
        // Delete invalid file
        EscalatedFS.deleteIfExists(downloadedFile);

        throw new IOException("下载失败： " + relPath + "：未通过CRC验证。详情：\n" + crcLog);
    }
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10000, java.util.concurrent.TimeUnit.MILLISECONDS) // 10 seconds
            .readTimeout(20000, java.util.concurrent.TimeUnit.MILLISECONDS)    // 20 seconds
            .build();

    public CompletableFuture<Path> downloadAsync(String fileUrl, Path dest, Function<Path, Boolean> verifier, boolean replace, BiConsumer<String, Exception> handler, AtomicLong downloadedSize) {
        return CompletableFuture.supplyAsync(() -> {
            int attempts = 5;
            int delay = 5000; // 5 seconds
            for (int i = 0; i < attempts; i++) {
                Path result = null;
                try {
                    result = downloadSingleFile(fileUrl, dest, verifier, replace, downloadedSize);
                } catch (IOException e) {
                    handler.accept("从" + fileUrl + "下载时报错：" , e);
                }
                if (result != null) {
                    return result;
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    handler.accept("从" + fileUrl + "下载时报错：" , e);
                }
            }
            return null;
        }, executorService);
    }

    private Path downloadSingleFile(String fileUrl, Path dest, Function<Path, Boolean> verifier, boolean replace, AtomicLong downloadedSize) throws IOException {
        // Check if file exists and is valid
        if (EscalatedFS.exists(dest)) {
            if (verifier.apply(dest) && !replace) {
                downloadedSize.addAndGet(EscalatedFS.size(dest));
                return dest; // File exists and is valid; return it.
            } else {
                EscalatedFS.deleteIfExists(dest); // Delete file if it's invalid or replace is true.
            }
        }

        Request request = new Request.Builder()
                .url(fileUrl)
                .addHeader("User-Agent", "BAAssetDownloaderAPP")
                .build();

        try (Response response = client.newCall(request).execute()) {
            // Ensure the request was successful
            if (!response.isSuccessful()) {
                throw new IOException("Failed to download file: " + response.code());
            }

            // Ensure the destination directories are created
            EscalatedFS.createDirectories(dest.getParent());

            // Get the input stream from the response body
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("No response body received");
            }

            // Write the response body to the destination file
            try (InputStream in = body.byteStream();
                 OutputStream out = EscalatedFS.newOutputStream(dest)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloadedSize.addAndGet(bytesRead);
                }
                out.flush();
            }

            return dest;
        } catch (IOException e) {
            throw new IOException("Download failed: " + e.getMessage(), e);
        }
    }

    public CompletableFuture<Void> fetchServerAvailable() {
        return CompletableFuture.runAsync(() -> {
            for (String serverUrl : appConfig.getServerUrls()) {
                serverAvailable.put(serverUrl, new HashSet<>());
                try {
                    Request request = new Request.Builder()
                            .url(serverUrl + "/catalog.json")
                            .addHeader("User-Agent", "BAAssetDownloaderAPP")
                            .build();
                    try (Response response = client.newCall(request).execute()) {
                        // Ensure the request was successful
                        if (!response.isSuccessful()) {
                            throw new IOException("Failed to download file: " + response.code());
                        }
                        ResponseBody body = response.body();
                        if (body == null) {
                            throw new IOException("No response body received");
                        }
                        try (InputStream inputStream = new BufferedInputStream(body.byteStream());
                             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                            StringBuilder jsonText = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                jsonText.append(line);
                            }

                            JSONArray jsonArray = new JSONArray(jsonText.toString());
                            Set<String> availablePaths = new HashSet<>();
                            for (int i = 0; i < jsonArray.length(); i++) {
                                availablePaths.add(jsonArray.getString(i));
                            }
                            serverAvailable.put(serverUrl, availablePaths);
                        }
                    }
                } catch (IOException | JSONException e) {
                    System.err.println("Error fetching catalog from " + serverUrl + ": " + e.getMessage());
                }
            }
        }, executorService);
    }

    public Set<String> getAvailableCustomDownloads() {
        Set<String> available = new HashSet<>();
        for (Set<String> paths : serverAvailable.values()) {
            available.addAll(paths);
        }
        return available;
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
