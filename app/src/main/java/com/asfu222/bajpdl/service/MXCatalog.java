package com.asfu222.bajpdl.service;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MXCatalog {
    private final Map<String, CommonCatalogItem> data;

    public MXCatalog(Map<String, CommonCatalogItem> data) {
        this.data = data;
    }

    public static MXCatalog parseMemoryPackerBytes(byte[] bytesData, boolean media) throws IOException {
        try (ByteArrayInputStream cursor = new ByteArrayInputStream(bytesData);
             DataInputStream dataInputStream = new DataInputStream(cursor)) {

            Map<String, CommonCatalogItem> data = new HashMap<>();

            dataInputStream.readByte(); // Skip 1 byte
            int dataSize = readI32(dataInputStream);

            for (int i = 0; i < dataSize; i++) {
                Map.Entry<String, CommonCatalogItem> entry = media ? readMedia(dataInputStream) : readTable(dataInputStream);
                data.put(entry.getKey(), entry.getValue());
            }

            return new MXCatalog(data);
        }
    }

    public static MXCatalog parseBundleDLInfoJson(byte[] jsonData) throws JSONException {
        JSONObject jsonObj = new JSONObject(new String(jsonData));
        JSONArray bundleFiles = jsonObj.getJSONArray("BundleFiles");
        Map<String, CommonCatalogItem> data = new HashMap<>();
        for (int i = 0; i < bundleFiles.length(); i++) {
            JSONObject bundleFile = bundleFiles.getJSONObject(i);
            String name = bundleFile.getString("Name");
            long size = bundleFile.getLong("Size");
            long crc = bundleFile.getLong("Crc");
            boolean split = bundleFile.getBoolean("IsSplitDownload");
            data.put("Android/" + name, new CommonCatalogItem(name, size, crc, split));
        }
        return new MXCatalog(data);
    }

    private static Map.Entry<String, CommonCatalogItem> readTable(DataInputStream dataInputStream) throws IOException {
        readI32(dataInputStream); // Skip 4 bytes
        String key = readString(dataInputStream);
        readI8(dataInputStream); // Skip 1 byte
        readI32(dataInputStream); // Skip 4 bytes
        String name = readString(dataInputStream);
        long size = readI64(dataInputStream);
        long crc = readI64(dataInputStream);
        boolean isInBuild = readBool(dataInputStream);
        boolean isChanged = readBool(dataInputStream);
        boolean isPrologue = readBool(dataInputStream);
        boolean isSplitDownload = readBool(dataInputStream);
        List<String> includes = readIncludes(dataInputStream);

        /*
        Map<String, Object> value = new HashMap<>();
        value.put("name", name);
        value.put("size", size);
        value.put("crc", crc);
        value.put("is_in_build", isInBuild);
        value.put("is_changed", isChanged);
        value.put("is_prologue", isPrologue);
        value.put("is_split_download", isSplitDownload);
        value.put("includes", includes);
         */
        return new AbstractMap.SimpleEntry<>("TableBundles/" + key, new CommonCatalogItem(name, size, crc, isSplitDownload));
    }

    private static Map.Entry<String, CommonCatalogItem> readMedia(DataInputStream dataInputStream) throws IOException {
        readI32(dataInputStream); // Skip 4 bytes
        String key = readString(dataInputStream);
        readI8(dataInputStream); // Skip 1 byte
        readI32(dataInputStream); // Skip 4 bytes
        String path = readString(dataInputStream);
        readI32(dataInputStream); // Skip 4 bytes
        String fileName = readString(dataInputStream);
        long size = readI64(dataInputStream);
        long crc = readI64(dataInputStream);
        boolean isPrologue = readBool(dataInputStream);
        boolean isSplitDownload = readBool(dataInputStream);
        int mediaType = readI32(dataInputStream);
        /*
        Map<String, Object> value = new HashMap<>();
        value.put("path", path);
        value.put("name", fileName);
        value.put("size", size);
        value.put("crc", crc);
        value.put("is_prologue", isPrologue);
        value.put("is_split_download", isSplitDownload);
        value.put("media_type", mediaType);
         */
        return new AbstractMap.SimpleEntry<>("MediaResources/" + path.replace("\\", "/"), new CommonCatalogItem(fileName, size, crc, isSplitDownload));
    }

    private static String readString(DataInputStream dataInputStream) throws IOException {
        int length = readI32(dataInputStream);
        if (length < 0) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = new byte[length];
        int bytesRead = dataInputStream.read(bytes, 0, length);
        if (bytesRead < length) {
            throw new EOFException("Unexpected end of file while reading string of length " + length);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> readIncludes(DataInputStream dataInputStream) throws IOException {
        int size = readI32(dataInputStream);
        if (size == -1) {
            return Collections.emptyList();
        }
        readI32(dataInputStream); // Skip 4 bytes
        List<String> includes = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            includes.add(readString(dataInputStream));
            if (i != size - 1) {
                readI32(dataInputStream); // Skip 4 bytes
            }
        }
        return includes;
    }

    private static byte readI8(DataInputStream dataInputStream) throws IOException {
        return dataInputStream.readByte();
    }

    private static int readI32(DataInputStream dataInputStream) throws IOException {
        byte[] bytes = new byte[4];
        dataInputStream.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long readI64(DataInputStream dataInputStream) throws IOException {
        byte[] bytes = new byte[8];
        dataInputStream.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static boolean readBool(DataInputStream dataInputStream) throws IOException {
        return dataInputStream.readBoolean();
    }

    public Map<String, CommonCatalogItem> getData() {
        return data;
    }
}