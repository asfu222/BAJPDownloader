package com.asfu222.bajpdl.shizuku;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class ShizukuService extends IUserService.Stub {
    public ShizukuService() {
    }
    public ShizukuService(Context context) {
    }

    @Override
    public ParcelFileDescriptor openRead(String path, String[] status) {
        try {
            File file = new File(path);
            status[0] = "success";
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            status[0] = printStackTrace(e);
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openWrite(String path, String[] status) {
        try {
            File file = new File(path);
            status[0] = "success";
            return ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE);
        } catch (FileNotFoundException e) {
            status[0] = printStackTrace(e);
            return null;
        }
    }

    @Override
    public boolean mkdirs(String path) {
        return new File(path).mkdirs();
    }

    @Override
    public boolean deleteIfExists(String path, String[] status) {
        try {
            status[0] = "success";
            return Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            status[0] = printStackTrace(e);
            return false;
        }
    }

    @Override
    public boolean exists(String path) {
        return new File(path).exists();
    }

    @Override
    public long size(String path) {
        return new File(path).length();
    }

    @Override
    public void copy(String source, String target, boolean replaceExisting, boolean copyAttributes, boolean atomicMove, String[] status) {
        try {
            Path src = Paths.get(source);
            Path tgt = Paths.get(target);

            if (atomicMove) {
                if (replaceExisting) {
                    Files.move(src, tgt, StandardCopyOption.REPLACE_EXISTING);
                } else Files.move(src, tgt);
            } else {
                CopyOption[] options = new CopyOption[]{};
                if (replaceExisting) options = new CopyOption[]{StandardCopyOption.REPLACE_EXISTING};
                if (copyAttributes) options = new CopyOption[]{StandardCopyOption.COPY_ATTRIBUTES};
                Files.copy(src, tgt, options);
            }
            status[0] = "success";
        } catch (Exception e) {
            status[0] = printStackTrace(e);
        }
    }
    private static String printStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}