package com.asfu222.bajpdl.shizuku;

import java.io.IOException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

public class ShizukuUtil {
    private static Method newProcessMethod;

    static {
        try {
            newProcessMethod = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            newProcessMethod.setAccessible(true);
        } catch (NoSuchMethodException ignored) {}
    }

    public static Process executeProcess(String cmd) throws IOException {
        return exec(new String[] { "sh", "-c", cmd }, null, null);
    }
    public static Process exec(String[] cmd, String[] env, String dir) throws IOException {
        if (newProcessMethod != null) {
            try {
                return (Process) newProcessMethod.invoke(null, cmd, env, dir);
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            throw new IOException("Shizuku.newProcess method not found");
        }
    }
}