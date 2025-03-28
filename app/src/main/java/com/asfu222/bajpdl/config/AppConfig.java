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
import java.util.function.BiConsumer;

public class AppConfig {
    private boolean alwaysRedownload = false;
    private boolean downloadCustomOnly = true;

    private boolean useMITM = false;

    private boolean openBA = true;
    private List<String> serverUrls;
    private final Context context;
    private String fallbackUrl;
    private int concurrentDownloads = 5;

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

    public int getConcurrentDownloads() {
        return concurrentDownloads;
    }

    public void setConcurrentDownloads(int concurrentDownloads) {
        this.concurrentDownloads = concurrentDownloads;
    }

    public boolean shouldOpenBA() {
        return openBA;
    }

    public void setOpenBA(boolean openBA) {
        this.openBA = openBA;
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

    public boolean shouldDownloadStraightToGame() {
        return true;
    }

    public boolean shouldUseMITM() {
        return useMITM;
    }

    public void setUseMITM(boolean useMITM) {
        this.useMITM = useMITM;
    }

    private void loadConfig() {
        File configFile = new File(context.getExternalFilesDir("bajpdl_cfg"), "config.json");
        if (configFile.exists()) {
            try {
                String content = new String(Files.readAllBytes(Paths.get(configFile.getPath())));
                JSONObject json = new JSONObject(content);
                alwaysRedownload = json.optBoolean("replaceDownloadedFiles", false);
                downloadCustomOnly = json.optBoolean("downloadCustomOnly", true);
                JSONArray urlsArray = json.getJSONArray("serverUrls");
                concurrentDownloads = Math.min(json.optInt("concurrentDownloads", 5), 5);
                openBA = json.optBoolean("openBA", true);
                useMITM = json.optBoolean("useMITM", false);
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
            serverUrls.add("https://cdn.bluearchive.me/commonpng/latest");
        }
    }

private void fetchFallbackUrl(BiConsumer<String, Exception> handler) {
    Executors.newSingleThreadExecutor().execute(() -> {
        final String FALLBACK_URL = "https://cdn.bluearchive.me/ba.env";
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

                String[] fetchedUrl = new String[2];
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("BA_SERVER_URL=")) {
                            fetchedUrl[0] = line.split("=", 2)[1];
                        }
                        if (line.startsWith("ADDRESSABLE_CATALOG_URL=")) {
                            fetchedUrl[1] = line.split("=", 2)[1];
                            break;
                        }
                    }
                }

                if (fetchedUrl[0] != null && fetchedUrl[1] != null) {
                    synchronized (this) {
                        fallbackUrl = fetchedUrl[1];
                    }
                    System.out.println("Successfully fetched fallback URL: " + fallbackUrl);
                    return;
                } else {
                    throw new IOException("ADDRESSABLE_CATALOG_URL or BA_SERVER_URL not found in response.");
                }

            } catch (Exception e) {
                errorList.add(e);
                System.err.println("Error fetching fallback URL (Attempt " + attempt + "): " + e.getMessage());

                if (attempt == MAX_RETRIES) {
                    StringBuilder errorMessage = new StringBuilder("获取备用资源网址时报错。已尝试 " + MAX_RETRIES + " 次。\n");
                    for (int i = 0; i < errorList.size(); i++) {
                        errorMessage.append("尝试 ").append(i + 1).append(": ").append(errorList.get(i).getMessage()).append("\n");
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
        File configFile = new File(context.getExternalFilesDir("bajpdl_cfg"), "config.json");
        try (FileWriter writer = new FileWriter(configFile)) {
            JSONObject json = new JSONObject();
            json.put("replaceDownloadedFiles", alwaysRedownload);
            json.put("downloadCustomOnly", downloadCustomOnly);
            JSONArray urlsArray = new JSONArray(serverUrls);
            json.put("serverUrls", urlsArray);
            json.put("concurrentDownloads", concurrentDownloads);
            json.put("openBA", openBA);
            json.put("useMITM", shouldUseMITM());
            writer.write(json.toString());
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }
}
