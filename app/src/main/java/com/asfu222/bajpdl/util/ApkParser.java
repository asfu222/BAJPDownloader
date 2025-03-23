package com.asfu222.bajpdl.util;

import android.content.pm.Signature;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApkParser {
    public static String extractPackageName(File apkFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(apkFile)) {
            ZipEntry entry = zipFile.getEntry("AndroidManifest.xml");
            if (entry == null) return null;

            try (InputStream is = zipFile.getInputStream(entry)) {
                byte[] header = new byte[8];
                is.read(header);

                byte[] lenBytes = new byte[4];
                is.read(lenBytes);
                int pkgLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

                byte[] pkgBytes = new byte[pkgLen * 2];
                is.read(pkgBytes);
                return new String(pkgBytes, StandardCharsets.UTF_16LE);
            }
        }
    }

    public static Signature[] extractSignatures(File apkFile) {
        List<Signature> signatures = new ArrayList<>();
        try (JarFile jarFile = new JarFile(apkFile)) {
            JarEntry entry;
            for (Enumeration<JarEntry> e = jarFile.entries(); e.hasMoreElements();) {
                entry = e.nextElement();
                if (entry.getName().startsWith("META-INF/") &&
                        entry.getName().toUpperCase().matches(".*\\.(RSA|DSA)$")) {
                    Certificate[] certs = entry.getCertificates();
                    if (certs != null) {
                        for (Certificate cert : certs) {
                            signatures.add(new Signature(cert.getEncoded()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            return new Signature[0];
        }
        return signatures.toArray(new Signature[0]);
    }
}