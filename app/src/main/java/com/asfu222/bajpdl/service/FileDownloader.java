package com.asfu222.bajpdl.service;

import com.asfu222.bajpdl.config.AppConfig;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class FileDownloader {
    private final ExecutorService executorService;
    private final AppConfig appConfig;
    private static final int CONNECTION_TIMEOUT = 15000; // 15 seconds
    private static final int READ_TIMEOUT = 15000; // 15 seconds

    private static final Map<String, Set<String>> serverAvailable = new HashMap<>();

    public FileDownloader(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.executorService = Executors.newFixedThreadPool(20);
    }

    public CompletableFuture<Path> downloadFile(Path basePath, String relPath,
                                                Function<Path, Boolean> verifier, boolean replace, BiConsumer<String, Exception> handler) {
        return CompletableFuture.supplyAsync(() -> {
            int attempts = 5;
            int delay = 5000; // 5 seconds
            for (int i = 0; i < attempts; i++) {
                Path result = null;
                try {
                    result = tryDownloadFromAllSources(basePath, relPath, verifier, replace);
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
                                           Function<Path, Boolean> verifier, boolean replace) throws IOException {
        List<String> serverUrls = appConfig.getServerUrls();

        // Try primary servers first
        for (String baseUrl : serverUrls) {
            String fileUrl = baseUrl + "/" + relPath;
            if (serverAvailable.get(baseUrl).contains(relPath)) {
                Path downloadedFile = downloadSingleFile(fileUrl,
                        basePath.resolve(relPath), verifier, replace);

                if (verifier.apply(downloadedFile)) {
                    return downloadedFile;
                }

                // Delete invalid file
                Files.deleteIfExists(downloadedFile);
            }
        }

        // Try fallback server as last resort
        String fallbackUrl = appConfig.getFallbackUrl() + "/" + relPath;
        Path downloadedFile = downloadSingleFile(fallbackUrl,
                basePath.resolve(relPath), verifier, replace);

        if (verifier.apply(downloadedFile)) {
            return downloadedFile;
        }

        // Delete invalid file
        Files.deleteIfExists(downloadedFile);

        return null;
    }
    private Path downloadSingleFile(String fileUrl, Path dest, Function<Path, Boolean> verifier, boolean replace) throws IOException {
        if (Files.exists(dest) && verifier.apply(dest) && !replace) {
            return dest;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(fileUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);

            Files.createDirectories(dest.getParent());

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest,
                         replace ? StandardOpenOption.CREATE : StandardOpenOption.CREATE_NEW))) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                return dest;
            } catch (FileAlreadyExistsException e) {
                return dest; // Return existing file
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    public CompletableFuture<Void> fetchServerAvailable() {
        return CompletableFuture.runAsync(() -> {
            for (String serverUrl : appConfig.getServerUrls()) {
                serverAvailable.put(serverUrl, new HashSet<>());
                try {
                    URL url = new URL(serverUrl + "/catalog.json");
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(CONNECTION_TIMEOUT);
                    connection.setReadTimeout(READ_TIMEOUT);

                    try (InputStream inputStream = new BufferedInputStream(connection.getInputStream());
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
                    } finally {
                        connection.disconnect();
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