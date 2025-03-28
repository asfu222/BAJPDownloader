package com.asfu222.bajpdl.service;

import com.asfu222.bajpdl.util.EscalatedFS;
import com.asfu222.bajpdl.util.FileUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class CommonCatalogItem {
    public final String name;
    public final long size;
    public final long crc;
    public final boolean isSplit;

    public static final CommonCatalogItem EMPTY = new CommonCatalogItem("Empty (dummy)", -1, -1, false);
    public CommonCatalogItem(String name, long size, long crc, boolean isSplit) {
        this.name = name;
        this.size = size;
        this.crc = crc;
        this.isSplit = isSplit;
    }

    public static CommonCatalogItem fromMap(Map<String, Object> map) {
        return new CommonCatalogItem(
            (String) map.get("name"),
            (long) map.get("size"),
            (long) map.get("crc"),
            (boolean) map.get("isSplit")
        );
    }

    public boolean verifyIntegrity(Path file) {
        try {
            return FileUtils.calculateCRC32(file) == crc && EscalatedFS.size(file) == size;
        } catch (IOException e) {
            return false;
        }
    }
}
