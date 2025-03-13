package com.asfu222.bajpdl.config;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class AppConfig {
    private boolean alwaysRedownload = false;
    private boolean downloadCustomOnly = true;
    private List<String> serverUrls;
    private final Context context;
    private String fallbackUrl;
    private int batchSize = 30;

    public AppConfig(Context context, BiConsumer<String, Exception> handler) {
        this.context = context;
        loadConfig();
        fetchFallbackUrl(handler);
    }

    public boolean shouldAlwaysRedownload() {
        return alwaysRedownload;
    }

    public void setAlwaysRedownload(boolean alwaysRedownload) {
        this.alwaysRedownload = alwaysRedownload;
    }

    public boolean shouldDownloadCustomOnly() {
        return downloadCustomOnly;
    }

    public void setDownloadCustomOnly(boolean downloadCustomOnly) {
        this.downloadCustomOnly = downloadCustomOnly;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public List<String> getServerUrls() {
        return serverUrls;
    }

    public void setServerUrls(List<String> serverUrls) {
        this.serverUrls = serverUrls;
    }

    public String getFallbackUrl() {
        return fallbackUrl;
    }

    private void loadConfig() {
        File configFile = new File(context.getExternalFilesDir("config"), "config.json");
        if (configFile.exists()) {
            try {
                String content = new String(Files.readAllBytes(Paths.get(configFile.getPath())));
                JSONObject json = new JSONObject(content);
                alwaysRedownload = json.getBoolean("replaceDownloadedFiles");
                downloadCustomOnly = json.getBoolean("downloadCustomOnly");
                JSONArray urlsArray = json.getJSONArray("serverUrls");
                batchSize = json.optInt("batchSize", 30);
                serverUrls = new ArrayList<>();
                for (int i = 0; i < urlsArray.length(); i++) {
                    serverUrls.add(urlsArray.getString(i));
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        } else {
            serverUrls = new ArrayList<>();
            serverUrls.add("https://cdn.bluearchive.me/beicheng/latest");
            serverUrls.add("https://cdn.bluearchive.me/new/latest");
        }
    }

    private void fetchFallbackUrl(BiConsumer<String, Exception> handler) {
    Executors.newSingleThreadExecutor().execute(() -> {
        final String FALLBACK_URL = "https://raw.githubusercontent.com/asfu222/BACNLocalizationResources/refs/heads/main/ba.env";
        final int MAX_RETRIES = 3;
        final int TIMEOUT = 5000; // 5 seconds

        List<Exception> errorList = new ArrayList<>();
        int attempt = 0;

        while (attempt < MAX_RETRIES) {
            try {
                attempt++;
                System.out.println("Attempt " + attempt + " to fetch fallback URL...");

                URL url = new URL(FALLBACK_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(TIMEOUT);
                connection.setReadTimeout(TIMEOUT);

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    throw new IOException("HTTP response code: " + responseCode);
                }

                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("ADDRESSABLE_CATALOG_URL=")) {
                            synchronized (this) {
                                fallbackUrl = line.split("=", 2)[1];
                            }
                            System.out.println("Successfully fetched fallback URL: " + fallbackUrl);
                            return;
                        }
                    }
                }

            } catch (Exception e) {
                errorList.add(e);
                System.err.println("Error fetching fallback URL (Attempt " + attempt + "): " + e.getMessage());

                if (attempt == MAX_RETRIES) {
                    StringBuilder errorMessage = new StringBuilder("Failed to fetch fallback URL after " + MAX_RETRIES + " attempts.\n");
                    for (int i = 0; i < errorList.size(); i++) {
                        errorMessage.append("Attempt ").append(i + 1).append(": ").append(errorList.get(i).getMessage()).append("\n");
                    }
                    handler.accept(null, new IOException(errorMessage.toString(), errorList.get(0))); // Pass error to handler
                }
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handler.accept(null, e);
                break;
            }
        }
    });
}

    public void saveConfig() {
        File configFile = new File(context.getExternalFilesDir("config"), "config.json");
        try (FileWriter writer = new FileWriter(configFile)) {
            JSONObject json = new JSONObject();
            json.put("replaceDownloadedFiles", alwaysRedownload);
            json.put("downloadCustomOnly", downloadCustomOnly);
            JSONArray urlsArray = new JSONArray(serverUrls);
            json.put("serverUrls", urlsArray);
            json.put("batchSize", batchSize);
            writer.write(json.toString());
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }
}
