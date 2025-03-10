package com.asfu222.bajpdl.shizuku;

import android.os.RemoteException;

public interface IFileOperations {
    int executeCommand(String command) throws RemoteException;
}