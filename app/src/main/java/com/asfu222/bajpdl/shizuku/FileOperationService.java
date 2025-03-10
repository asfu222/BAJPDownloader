package com.asfu222.bajpdl.shizuku;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

public class FileOperationService extends Binder {
    // Transaction code for executeCommand
    private static final int TRANSACTION_executeCommand = 1;
    // Transaction code for destroy command
    private static final int TRANSACTION_destroy = 16777115;

    // Constructor with Context (for Shizuku v13+)
    public FileOperationService(Context context) {
    }

    // Default constructor for older Shizuku versions
    public FileOperationService() {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        switch (code) {
            case TRANSACTION_executeCommand:
                data.enforceInterface("com.asfu222.bajpdl.shizuku.IFileOperations");
                String command = data.readString();
                int result = executeCommand(command);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            case TRANSACTION_destroy:
                // Clean up and exit when service is destroyed
                System.exit(0);
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    private int executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            return process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }
}