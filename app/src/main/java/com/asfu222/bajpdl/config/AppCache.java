package com.asfu222.bajpdl.config;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AppCache {
    private long tbCrc;
    private long mpCrc;
    private long abCrc;
  //  private String yostarServerUrl;
    private final Context context;
    public AppCache(Context context) {
        this.context = context;
        loadCache();
    }
    public long getTbCrc() {
        return tbCrc;
    }

    public void setTbCrc(long tbCrc) {
        this.tbCrc = tbCrc;
    }

    public long getMpCrc() {
        return mpCrc;
    }

    public void setMpCrc(long mpCrc) {
        this.mpCrc = mpCrc;
    }

    public long getAbCrc() {
        return abCrc;
    }

    public void setAbCrc(long abCrc) {
        this.abCrc = abCrc;
    }

    /*
    public String getYostarServerUrl() {
        return yostarServerUrl;
    }
     */

    private void loadCache() {
        File versionFile = new File(context.getExternalFilesDir("bajpdl_cache"), "version.json");
        if (versionFile.exists()) {
            try {
                String content = new String(Files.readAllBytes(Paths.get(versionFile.getPath())));
                JSONObject json = new JSONObject(content);
                // yostarServerUrl = json.optString("yostarServerUrl");
                tbCrc = json.optLong("tbCrc");
                mpCrc = json.optLong("mpCrc");
                abCrc = json.optLong("abCrc");
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        }
    }
    
    public void saveCache(AppConfig config) {
        File versionFile = new File(context.getExternalFilesDir("bajpdl_cache"), "version.json");
        try (FileWriter writer = new FileWriter(versionFile)) {
            JSONObject json = new JSONObject();
            // yostarServerUrl = config.getYostarServerInfoUrl();
            // json.put("yostarServerUrl", yostarServerUrl);
            json.put("tbCrc", tbCrc);
            json.put("mpCrc", mpCrc);
            json.put("abCrc", abCrc);
            writer.write(json.toString());
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }
}
