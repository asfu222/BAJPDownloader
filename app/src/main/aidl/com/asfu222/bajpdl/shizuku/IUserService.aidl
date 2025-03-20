package com.asfu222.bajpdl.shizuku;

import android.os.ParcelFileDescriptor;
import com.asfu222.bajpdl.shizuku.IRemoteProcess;

interface IUserService {
    ParcelFileDescriptor openRead(String path, out String[] status);
    ParcelFileDescriptor openWrite(String path, out String[] status);
    boolean mkdirs(String path);
    boolean deleteIfExists(String path, out String[] status);
    boolean exists(String path);
    long size(String path);
    void copy(String source, String target, boolean replaceExisting, boolean copyAttributes, boolean atomicMove, out String[] status);
    IRemoteProcess newProcess(in String[] cmd, in String[] env, in String dir);
}