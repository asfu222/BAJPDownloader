package com.asfu222.bajpdl;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.widget.NestedScrollView;

import com.asfu222.bajpdl.core.GameFileManager;
import com.asfu222.bajpdl.shizuku.IUserService;
import com.asfu222.bajpdl.shizuku.ShizukuService;
import com.asfu222.bajpdl.util.ApkParser;
import com.asfu222.bajpdl.util.ContentProviderFS;
import com.asfu222.bajpdl.util.EscalatedFS;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE = 1;
    private EditText serverUrlsInput;
    private Button startDownloadButton;
    private Button createShortcutButton;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView consoleOutput;
    private GameFileManager gameFileManager;
    private NestedScrollView consoleScrollView;
    private boolean shizukuBinderReceived = false;
    private boolean permissionRequested = false;
    private EditText batchSizeInput;

    private Button installAPKButton;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == REQUEST_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        updateConsole("已获取Shizuku权限");
                        bindShizukuUserService();
                    } else {
                        updateConsole("⚠️用户拒绝给与Shizuku权限");
                        startDownloadButton.setEnabled(false);
                    }
                }
            };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        shizukuBinderReceived = true;
        updateConsole("Shizuku binder received");
        runOnUiThread(this::checkShizukuPermission);
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        shizukuBinderReceived = false;
        updateConsole("Shizuku服务断开");
        runOnUiThread(() -> startDownloadButton.setEnabled(false));
    };

    private IUserService userService;
    private final ServiceConnection userServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName component, IBinder binder) {
            userService = IUserService.Stub.asInterface(binder);
            EscalatedFS.setShizukuService(userService);
            if (userService != null) {
                updateConsole("已挂载用户服务");
                updateEscalatedPermissions(true);
            }
            else {
                updateConsole("挂载用户服务失败，正在重试");
                bindShizukuUserService();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName component) {
            updateConsole("已取消挂载用户服务");
            userService = null;
            EscalatedFS.setShizukuService(null);
            startDownloadButton.setEnabled(false);
        }
    };

    private Shizuku.UserServiceArgs createServiceArgs() {
        return new Shizuku.UserServiceArgs(new ComponentName(this, ShizukuService.class))
                .daemon(false)
                .processNameSuffix("user_service")
                .version(1);
    }

    private void bindShizukuUserService() {
        Shizuku.bindUserService(createServiceArgs(), userServiceConnection);
    }


    private final AtomicReference<String> mInstallPackage = new AtomicReference<>("");
    private final Stack<Consumer<Boolean>> installCallbackStack = new Stack<>();
    private final AtomicReference<String> mUninstallPackage = new AtomicReference<>("");
    private final Stack<Consumer<Boolean>> uninstallCallbackStack = new Stack<>();
    private final Stack<Runnable> onGrantEscalatedPermission = new Stack<>();

    private boolean isAuto = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        serverUrlsInput = findViewById(R.id.serverUrlsInput);
        Switch redownloadSwitch = findViewById(R.id.redownloadSwitch);
        Switch downloadCustomOnlySwitch = findViewById(R.id.downloadCustomOnlySwitch);
        Switch openBA = findViewById(R.id.openBASwitch);
        Switch useMITMSwitch = findViewById(R.id.useMITMSwitch);
        startDownloadButton = findViewById(R.id.startDownloadButton);
        installAPKButton = findViewById(R.id.installAPKButton);
        createShortcutButton = findViewById(R.id.createShortcutButton);
        progressBar = findViewById(R.id.progressBar);
        progressText = findViewById(R.id.progressText);
        consoleScrollView = findViewById(R.id.consoleScrollView);
        consoleOutput = findViewById(R.id.consoleOutput);
        gameFileManager = new GameFileManager(this);
        List<String> defaultServerUrls = gameFileManager.getAppConfig().getServerUrls();
        serverUrlsInput.setText(String.join(",", defaultServerUrls));

        startDownloadButton.setOnClickListener(v -> startDownloads());
        createShortcutButton.setOnClickListener(v -> onCreateShortcutButtonPressed());

        redownloadSwitch.setChecked(gameFileManager.getAppConfig().shouldAlwaysRedownload());
        redownloadSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            gameFileManager.getAppConfig().setAlwaysRedownload(isChecked);
            gameFileManager.getAppConfig().saveConfig();
        });

        downloadCustomOnlySwitch.setChecked(gameFileManager.getAppConfig().shouldDownloadCustomOnly());
        downloadCustomOnlySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            gameFileManager.getAppConfig().setDownloadCustomOnly(isChecked);
            gameFileManager.getAppConfig().saveConfig();
        });

        openBA.setChecked(gameFileManager.getAppConfig().shouldOpenBA());
        openBA.setOnCheckedChangeListener((buttonView, isChecked) -> {
            gameFileManager.getAppConfig().setOpenBA(isChecked);
            gameFileManager.getAppConfig().saveConfig();
        });

        useMITMSwitch.setChecked(gameFileManager.getAppConfig().shouldUseMITM());
        useMITMSwitch.setOnCheckedChangeListener(((buttonView, isChecked) -> {
            gameFileManager.getAppConfig().setUseMITM(isChecked);
            gameFileManager.getAppConfig().saveConfig();
            setupEscalatedPermissions();
            updateDownloadAPKButtonText();
        }));
        if (EscalatedFS.canReadWriteAndroidData()) {
            useMITMSwitch.setEnabled(false);
            updateConsole("检测到存储权限，MITM方案已禁用（无需使用）");
        }

        serverUrlsInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard();
                return true;
            }
            return false;
        });

        batchSizeInput = findViewById(R.id.batchSizeInput);

        // Set the current batch size from AppConfig
        batchSizeInput.setText(String.valueOf(gameFileManager.getAppConfig().getConcurrentDownloads()));

        // Save the batch size when the user finishes editing
        batchSizeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard();
                return true;
            }
            return false;
        });

        installAPKButton.setOnClickListener(v -> {
            runOnUiThread(() -> installAPKButton.setEnabled(false));
            updateConsole("正在获取APK，请稍等...");
            if (gameFileManager.getAppConfig().shouldUseMITM() && !isMITMAvailable()) {
                gameFileManager.downloadServerAPKs().thenRun(() -> runOnUiThread(() -> installAPKButton.setEnabled(true))).thenRun(() -> requestInstallPerms(() -> installAPKFromCache("mitmserver.apk", result -> {
                    if (result) {
                        updateConsole("安装MITM服务成功");
                        setupMITM();
                        installAPKButton.setText("安装游戏客户端");
                    } else {
                        updateConsole("安装MITM服务失败");
                    }
                })));
            } else {
                gameFileManager.downloadServerAPKs().thenRun(() -> runOnUiThread(() -> installAPKButton.setEnabled(true))).thenRun(() -> requestInstallPerms(() -> installAPKFromCache("蔚蓝档案.apk", result -> {
                    if (result) {
                        updateConsole("安装游戏客户端成功");
                        updateDownloadAPKButtonText();
                    } else {
                        updateConsole("安装游戏客户端失败");
                    }
                })));
            }
        });
        Button autoTutorialSwitch = findViewById(R.id.autoTutorialButton);
        autoTutorialSwitch.setOnClickListener(v -> runOnUiThread(() ->
                new AlertDialog.Builder(this)
                .setTitle("自动教程")
                .setMessage("自动教程已启用，点击确定后将自动下载游戏资源。\n⚠️注意：下载过程中不要退出本软件！\n⚠️自动教程将会使用MITM劫持方案，请允许所有卸载/安装⚠️")
                .setPositiveButton("确定", (dialog, which) -> {
                    downloadCustomOnlySwitch.setEnabled(false);
                    openBA.setEnabled(false);
                    useMITMSwitch.setEnabled(false);
                    redownloadSwitch.setEnabled(false);
                    if (gameFileManager.getAppConfig().shouldOpenBA()) {
                    gameFileManager.getOnDownloadComplete().addFirst(() -> runOnUiThread(() -> new AlertDialog.Builder(this)
                            .setTitle("自动教程")
                            .setMessage("游戏资源下载完成，点击确定后将自动安装并打开蔚蓝档案。")
                            .setCancelable(false)
                            .setPositiveButton("确定", (dialog1, which1) -> openBlueArchive())
                            .create().show()));
                    }
                    isAuto = true;
                    openBA.setChecked(true);
                    downloadCustomOnlySwitch.setChecked(true);
                    if (!EscalatedFS.canReadWriteAndroidData()) {
                        onGrantEscalatedPermission.push(this::startDownloads);
                        redownloadSwitch.setChecked(false);
                        if (!useMITMSwitch.isChecked()) useMITMSwitch.setChecked(true);
                        else setupEscalatedPermissions();
                    } else {
                        startDownloads();
                    }
                    })
                        .setNegativeButton("取消", null).create().show()));

        updateDownloadAPKButtonText();
        // 如果从快捷方式启动，直接开始下载
        if ("com.asfu222.bajpdl.SHORTCUT".equals(getIntent().getAction())) {
            onGrantEscalatedPermission.push(this::startDownloads);
        }
        if (!EscalatedFS.canReadWriteAndroidData()) {
            startDownloadButton.setEnabled(false);
            setupEscalatedPermissions();
        } else {
            updateConsole("已获取存储权限");
            updateEscalatedPermissions(true);
        }

        // 如果是从快捷方式启动，禁用更新后打开蔚蓝档案开关，并锁定开启该功能
        if ("com.asfu222.bajpdl.SHORTCUT".equals(getIntent().getAction())) {
            openBA.setEnabled(false);
            gameFileManager.getAppConfig().setOpenBA(true);
            gameFileManager.getAppConfig().saveConfig();
        }
    }

    private void updateDownloadAPKButtonText() {
        runOnUiThread(() -> {
            if (gameFileManager.getAppConfig().shouldUseMITM() && !isMITMAvailable()) {
                installAPKButton.setText("安装MITM服务");
            } else {
                installAPKButton.setText("安装游戏客户端");
            }
        });
    }

    private void updateEscalatedPermissions(boolean granted) {
        if (granted) {
            for (Runnable callback : onGrantEscalatedPermission) {
                callback.run();
            }
            onGrantEscalatedPermission.clear();
            startDownloadButton.setEnabled(true);
        } else {
            startDownloadButton.setEnabled(false);
        }
    }

    public void openBlueArchive() {
        if ("com.YostarJP.BlueArchive".equals(getPackageName()) || (gameFileManager.getAppConfig().shouldUseMITM() && isMITMAvailable())) {
            installCallbackStack.push(s -> {
                if (s) {
                    openBlueArchive();
                }
            });
            installAPKButton.callOnClick();
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.YostarJP.BlueArchive", "com.yostarjp.bluearchive.MxUnityPlayerActivity"));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        System.runFinalization();
        finishAffinity();
    }

    private void installAPKFromCache(String apkName, Consumer<Boolean> callback) {
        File file = new File(getExternalFilesDir("bajpdl_cache"), apkName);

        if (!file.exists()) {
            updateConsole("APK file not found: " + file.getAbsolutePath());
            return;
        }
        if (!file.canRead()) {
            updateConsole("APK file not readable: " + file.getAbsolutePath());
            return;
        }

        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);

        // Start of modified signature checking logic
        try {
            final String packageName;
            final Signature[] apkSignatures;

            // First try normal package parsing
            PackageInfo apkPackageInfo = getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (apkPackageInfo != null) {
                packageName = apkPackageInfo.packageName;
                apkSignatures = apkPackageInfo.signatures;
            } else {
                // Fallback to manual parsing for binary manifests
                packageName = ApkParser.extractPackageName(file);
                apkSignatures = ApkParser.extractSignatures(file);
            }

            // Existing signature comparison logic
            boolean appInstalled = false;
            PackageInfo installedPackageInfo = null;
            try {
                installedPackageInfo = getPackageManager().getPackageInfo(packageName,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ?
                                PackageManager.GET_SIGNING_CERTIFICATES :
                                PackageManager.GET_SIGNATURES);
                appInstalled = true;
            } catch (PackageManager.NameNotFoundException ignored) {}

            if (appInstalled && installedPackageInfo != null) {
                Signature[] installedSignatures = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ?
                        installedPackageInfo.signingInfo.getApkContentsSigners() :
                        installedPackageInfo.signatures;

                Function<Signature, String> getSignatureHash = signature -> {
                    try {
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        md.update(signature.toByteArray());
                        return Base64.encodeToString(md.digest(), Base64.NO_WRAP);
                    } catch (NoSuchAlgorithmException e) {
                        logErrorToConsole("检测到不支持的签名算法", e);
                        return null;
                    }
                };

                String installedHash = (installedSignatures != null && installedSignatures.length > 0) ?
                        getSignatureHash.apply(installedSignatures[0]) : null;
                String apkHash = (apkSignatures != null && apkSignatures.length > 0) ?
                        getSignatureHash.apply(apkSignatures[0]) : null;

                if (installedHash != null && apkHash != null && !installedHash.equals(apkHash)) {
                    runOnUiThread(() ->
                    new AlertDialog.Builder(this)
                            .setTitle("签名不匹配")
                            .setMessage(apkName + "与已安装应用包名重叠" + "（" + packageName + "）但是签名不匹配。是否继续安装？（本操作将会导致应用数据丢失，请提前备份！）")
                            .setPositiveButton("是，卸载原应用", (dialog, which) -> {
                                installCallbackStack.push(callback);
                                uninstallCallbackStack.push(result -> {
                                    if (result) {
                                        launchInstallIntent(uri, packageName);
                                    } else {
                                        callback.accept(false);
                                    }
                                });
                                launchUninstallIntent(packageName);
                            })
                            .setNegativeButton("否，取消安装", null)
                            .show());
                    return;
                }
            }
            installCallbackStack.push(callback);
            launchInstallIntent(uri, packageName);
        } catch (Exception e) {
            logErrorToConsole("APK解析失败", e);
        }
    }

    private void launchUninstallIntent(String packageName) {
        updateConsole("正在卸载APK...");
        Uri packageUri = Uri.parse("package:" + packageName);
        Intent intent = new Intent(Intent.ACTION_DELETE, packageUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mUninstallPackage.set(packageName);
        startActivity(intent);
    }

    private void launchInstallIntent(Uri uri, String packageName) {
        updateConsole("正在安装APK...");
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mInstallPackage.set(packageName);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        if (!mInstallPackage.get().isEmpty()) {
            PackageManager pm = getPackageManager();
            try {
                PackageInfo packageInfo = pm.getPackageInfo(mInstallPackage.get(), 0);
                if (packageInfo != null) {
                    updateConsole("APK安装成功");
                    synchronized (installCallbackStack) {
                        for (Consumer<Boolean> installCallback : installCallbackStack) {
                            installCallback.accept(true);
                        }
                    }
                } else {
                    updateConsole("APK安装失败");
                    synchronized (installCallbackStack) {
                        for (Consumer<Boolean> installCallback : installCallbackStack) {
                            installCallback.accept(false);
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                updateConsole("APK安装失败");
                synchronized (installCallbackStack) {
                    for (Consumer<Boolean> installCallback : installCallbackStack) {
                        installCallback.accept(false);
                    }
                }
            }
            mInstallPackage.set("");
            installCallbackStack.clear();
        }
        if (!mUninstallPackage.get().isEmpty()) {
            PackageManager pm = getPackageManager();
            try {
                PackageInfo packageInfo = pm.getPackageInfo(mUninstallPackage.get(), 0);
                if (packageInfo != null) {
                    updateConsole("APK卸载失败");
                    synchronized (uninstallCallbackStack) {
                        for (Consumer<Boolean> uninstallCallback : uninstallCallbackStack) {
                            uninstallCallback.accept(false);
                        }
                    }
                } else {
                    updateConsole("APK卸载成功");
                    synchronized (uninstallCallbackStack) {
                        for (Consumer<Boolean> uninstallCallback : uninstallCallbackStack) {
                            uninstallCallback.accept(true);
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                updateConsole("APK卸载成功");
                synchronized (uninstallCallbackStack) {
                    for (Consumer<Boolean> uninstallCallback : uninstallCallbackStack) {
                        uninstallCallback.accept(true);
                    }
                }
            }
            mUninstallPackage.set("");
            uninstallCallbackStack.clear();
        }
        super.onResume();
    }

    private void setupEscalatedPermissions() {
        if (gameFileManager.getAppConfig().shouldUseMITM()) {
            if (!isMITMAvailable()) {
                updateConsole("请点击安装MITM服务");
                updateConsole(isAuto ? "自动安装中..." : "请点击安装按钮");
                if (isAuto || "com.asfu222.bajpdl.SHORTCUT".equals(getIntent().getAction())) {
                    installAPKButton.callOnClick();
                }
            }
            else setupMITM();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startDownloadButton.setEnabled(false);
            updateConsole("检测Shizuku服务是否运行...");

            Shizuku.addBinderReceivedListener(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);

            if (Shizuku.pingBinder()) {
                updateConsole("Shizuku服务已运行");
                shizukuBinderReceived = true;
                checkShizukuPermission();
            } else {
                updateConsole("Shizuku服务未运行");
                if (isRootAvailable()) {
                    updateConsole("检测到Root权限。下载器将使用此权限，⚠️不隐藏Root游戏客户端将无法使用，非法应用闪退跟本软件无关⚠️");
                    EscalatedFS.setRootAvailable(true);
                    updateEscalatedPermissions(true);
                }
            }
        } else {
            requestStoragePermission();
        }
    }

    private void setupMITM() {
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            while (!isMITMAvailable()) {
                try {
                    latch.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    logErrorToConsole("等待MITM服务安装时报错", e);
                }
            }
            latch.countDown();
        }).start();
        try {
            latch.await();
            try (var res = getContentResolver().query(Uri.parse("content://com.asfu222.bajpdl.mitm.fs"), null, null, null, null)) {
                updateConsole("检测到MITM权限");
                EscalatedFS.setContentProvider(new ContentProviderFS(getContentResolver()));
                updateEscalatedPermissions(true);
            }
        } catch (InterruptedException e) {
            logErrorToConsole("等待MITM服务安装时报错", e);
        }
    }

    private boolean isMITMAvailable() {
        ProviderInfo providerInfo = null;
        try {
            providerInfo = getPackageManager().resolveContentProvider(ContentProviderFS.AUTHORITY, 0);
        } catch (Exception e) {
            logErrorToConsole("检测内容提供者时报错", e);
        }
        return providerInfo != null;
    }

    private boolean isRootAvailable() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            process.getOutputStream().write("exit\n".getBytes());
            process.getOutputStream().flush();
            int exitValue = process.waitFor();
            return exitValue == 0;
        } catch (Exception e) {
            return false;
        }
    }
    private final AtomicReference<Runnable> installPermsCallback = new AtomicReference<>();
    private final ActivityResultLauncher<Intent> installPermsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (getPackageManager().canRequestPackageInstalls()) {
                    updateConsole("已获取安装应用权限");
                    installPermsCallback.get().run();
                } else {
                    updateConsole("未获取安装应用权限");
                }
            }
    );
    private void requestInstallPerms(Runnable callback) {
        updateConsole("正在获取安装应用权限...");
        if (getPackageManager().canRequestPackageInstalls()) {
            updateConsole("已获取安装应用权限");
            callback.run();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        installPermsCallback.set(callback);
        installPermsLauncher.launch(intent);
    }

    private void checkShizukuPermission() {
        try {
            int permissionResult = Shizuku.checkSelfPermission();
            updateConsole("检测Shizuku权限: " +
                    (permissionResult == PackageManager.PERMISSION_GRANTED ? "已允许" : "未允许"));

            if (permissionResult == PackageManager.PERMISSION_GRANTED) {
                bindShizukuUserService();
            } else {
                if (!permissionRequested) {
                    permissionRequested = true;
                    updateConsole("正在请求Shizuku权限...");
                    Shizuku.requestPermission(REQUEST_CODE);
                } else {
                    updateConsole("再次请求Shizuku权限...");
                    // Try requesting again after a short delay
                    permissionRequested = false;
                    startDownloadButton.postDelayed(() -> {
                        Shizuku.requestPermission(REQUEST_CODE);
                        permissionRequested = true;
                    }, 1000);
                }
            }
        } catch (Exception e) {
            logErrorToConsole("检测Shizuku权限时报错", e);
            startDownloadButton.setEnabled(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up Shizuku listeners
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
        }
        if (userService != null) {
            try {
                Shizuku.unbindUserService(createServiceArgs(), userServiceConnection, true);
            } catch (Exception e) {
                System.out.println("挂载Shizuku用户服务时报错: " + e.getMessage());
            }
            userService = null;
        }
        gameFileManager.shutdown();
    }

    private void startDownloads() {
        try {
            if (!EscalatedFS.canReadWriteAndroidData()) {
                if (!EscalatedFS.isReady()) {
                    updateConsole("错误：请先给与本软件Root或Shizuku或MITM权限。");
                    return;
                }
                try {
                    if (!EscalatedFS.exists(Environment.getExternalStorageDirectory().toPath().resolve("Android/data/com.YostarJP.BlueArchive/files/"))) {
                        if (!isMITMAvailable()) {
                            updateConsole("错误：请先打开蔚蓝档案并等待加载完成");
                            return;
                        }
                        EscalatedFS.createDirectories(Environment.getExternalStorageDirectory().toPath().resolve("Android/data/com.YostarJP.BlueArchive/files/"));
                    }
                } catch (IOException e) {
                    logErrorToConsole("检测蔚蓝档案安装状态时报错", e);
                    return;
                }
            }
            progressBar.setVisibility(View.VISIBLE);
            progressText.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
            progressText.setText("进度: 0%");
            consoleOutput.setText("");

            String serverUrlsText = serverUrlsInput.getText().toString();
            List<String> serverUrls = Arrays.asList(serverUrlsText.split(","));
            gameFileManager.getAppConfig().setServerUrls(serverUrls);
            int batchSize = Math.max(Integer.parseInt(batchSizeInput.getText().toString()), 1);
            gameFileManager.getAppConfig().setConcurrentDownloads(batchSize);
            gameFileManager.getAppConfig().saveConfig();

            if (!isAuto && gameFileManager.getAppConfig().shouldOpenBA()) {
                gameFileManager.getOnDownloadComplete().addLast(this::openBlueArchive);
            }

            gameFileManager.startDownloads();
        } catch (Exception e) {
            logErrorToConsole("开始下载时报错", e);
        }
    }

    private void onCreateShortcutButtonPressed() {
        // 请求向桌面添加快捷方式
        ShortcutManager shortcutManager = getApplicationContext().getSystemService(ShortcutManager.class);

        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
            Intent intent = new Intent(getApplicationContext(), MainActivity.class);
            intent.setAction("com.asfu222.bajpdl.SHORTCUT");

            ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(getApplicationContext(), "launch_game")
                    .setLongLabel("蔚蓝档案")
                    .setShortLabel("蔚蓝档案")
                  //  .setIcon(Icon.createWithResource(getApplicationContext(),R.drawable.ba_icon))
                    .setIntent(intent)
                    .build();

            shortcutManager.requestPinShortcut(shortcutInfo, null);
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateConsole("已获取存储权限");
            } else {
                updateConsole("未获取存储权限");
                startDownloadButton.setEnabled(false);
            }
        }
    }

    public void updateConsole(String message) {
        runOnUiThread(() -> {
            consoleOutput.append(message + "\n");
            consoleScrollView.post(() -> consoleScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    public void logErrorToConsole(String message, Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        updateConsole(message + ": " + sw);
    }

    public void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            view.clearFocus();
        }
    }

    public void updateProgress(int downloadedFiles, int totalFiles, long downloadedBytes, long totalBytes) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.VISIBLE);
            progressText.setVisibility(View.VISIBLE);
            int progress = totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0;
            progressBar.setProgress(progress);
            progressText.setText("进度: " + progress + "%" + " (" + downloadedFiles + "/" + totalFiles + " 文件, " + downloadedBytes + "/" + totalBytes + " MB)");
            if (downloadedFiles == totalFiles && downloadedBytes == totalBytes) {
                progressBar.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
            }
        });
    }
    public void updateProgress(int downloadedFiles, int totalFiles, long downloadedBytes) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.VISIBLE);
            progressText.setVisibility(View.VISIBLE);
            int progress = totalFiles > 0 ? ((downloadedFiles * 100) / totalFiles) : 1;
            progressBar.setProgress(progress);
            progressText.setText("进度: " + progress + "%" + " (" + downloadedFiles + "/" + totalFiles + " 文件, 已下载" + downloadedBytes + " MB)");
            if (downloadedFiles == totalFiles) {
                progressBar.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
            }
        });
    }
}