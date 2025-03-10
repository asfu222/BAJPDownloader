package com.asfu222.bajpdl.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import rikka.shizuku.Shizuku;

public class ShizukuService {
    private static final String TAG = "ShizukuService";
    private static IBinder serviceBinder;
    private static boolean serviceConnected = false;
    private static final int TRANSACTION_executeCommand = 1;

    private static final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            serviceBinder = binder;
            serviceConnected = true;
            Log.d(TAG, "Shizuku service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBinder = null;
            serviceConnected = false;
            Log.d(TAG, "Shizuku service disconnected");
        }
    };

    public static void bindService(Context context) {
        try {
            ComponentName componentName = new ComponentName(context, FileOperationService.class);
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(componentName)
                    .daemon(false)
                    .processNameSuffix("file_service")
                    .version(1);

            Shizuku.bindUserService(args, serviceConnection);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind service", e);
        }
    }

    public static void unbindService(Context context) {
        try {
            ComponentName componentName = new ComponentName(context, FileOperationService.class);
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(componentName);
            Shizuku.unbindUserService(args, serviceConnection, true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to unbind service", e);
        }
    }

    public static int executeCommand(String command) throws RemoteException {
        if (!serviceConnected || serviceBinder == null) {
            throw new RemoteException("Service not connected");
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.asfu222.bajpdl.shizuku.IFileOperations");
            data.writeString(command);
            serviceBinder.transact(TRANSACTION_executeCommand, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static boolean isServiceConnected() {
        return serviceConnected;
    }
}