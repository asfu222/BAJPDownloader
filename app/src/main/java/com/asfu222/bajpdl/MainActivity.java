package com.asfu222.bajpdl;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.asfu222.bajpdl.core.GameFileManager;
import com.asfu222.bajpdl.shizuku.IUserService;
import com.asfu222.bajpdl.shizuku.ShizukuService;
import com.asfu222.bajpdl.util.EscalatedFS;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

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

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == REQUEST_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        startDownloadButton.setEnabled(true);
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
        runOnUiThread(() -> {
            startDownloadButton.setEnabled(false);
        });
    };

    private IUserService userService;
    private final ServiceConnection userServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName component, IBinder binder) {
            userService = IUserService.Stub.asInterface(binder);
            EscalatedFS.setShizukuService(userService);
            if (userService != null) {
                updateConsole("已挂载用户服务");
                startDownloadButton.setEnabled(true);

                // 如果从快捷方式启动，直接开始下载
                if (getIntent().getAction().equals("com.asfu222.bajpdl.SHORTCUT")) {
                    startDownloads();
                }
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        serverUrlsInput = findViewById(R.id.serverUrlsInput);
        Switch redownloadSwitch = findViewById(R.id.redownloadSwitch);
        Switch downloadCustomOnlySwitch = findViewById(R.id.downloadCustomOnlySwitch);
        Switch downloadStraightToGameSwitch = findViewById(R.id.downloadStraightToGameSwitch);
        Switch openBA = findViewById(R.id.openBASwitch);
        startDownloadButton = findViewById(R.id.startDownloadButton);
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

        downloadStraightToGameSwitch.setChecked(gameFileManager.getAppConfig().shouldDownloadStraightToGame());
        downloadStraightToGameSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            gameFileManager.getAppConfig().setDownloadStraightToGame(isChecked);
            gameFileManager.getAppConfig().saveConfig();
        });

        openBA.setChecked(gameFileManager.getAppConfig().shouldOpenBA());
        openBA.setOnCheckedChangeListener((buttonView, isChecked) -> {
            gameFileManager.getAppConfig().setOpenBA(isChecked);
            gameFileManager.getAppConfig().saveConfig();
        });

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

        if (!EscalatedFS.canReadWriteAndroidData()) {
            setupEscalatedPermissions();
        } else {
            updateConsole("已获取存储权限");
            startDownloadButton.setEnabled(true);
        }

        // 如果是从快捷方式启动，禁用更新后打开蔚蓝档案开关，并锁定开启该功能
        if (getIntent().getAction().equals("com.asfu222.bajpdl.SHORTCUT")) {
            openBA.setEnabled(false);

            gameFileManager.getAppConfig().setOpenBA(true);
            gameFileManager.getAppConfig().saveConfig();
        }
    }

    private void setupEscalatedPermissions() {
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
                    updateConsole("检测到Root权限");
                    EscalatedFS.setRootAvailable(true);
                    startDownloadButton.setEnabled(true);
                }
            }
        } else {
            requestStoragePermission();
        }
    }

    // Add this to handle activity resume cases
    @Override
    protected void onResume() {
        super.onResume();

        // Re-check Shizuku status when activity resumes
        if (!EscalatedFS.canReadWriteAndroidData() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isRootAvailable() && !shizukuBinderReceived) {
            updateConsole("活动恢复，正在检查Shizuku服务状态...");
            if (Shizuku.pingBinder()) {
                updateConsole("Shizuku服务已运行");
                shizukuBinderReceived = true;
                checkShizukuPermission();
            } else {
                updateConsole("Shizuku服务未运行");
            }
        }
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

    private void checkShizukuPermission() {
        try {
            int permissionResult = Shizuku.checkSelfPermission();
            updateConsole("检测Shizuku权限: " +
                    (permissionResult == PackageManager.PERMISSION_GRANTED ? "已允许" : "未允许"));

            if (permissionResult == PackageManager.PERMISSION_GRANTED) {
                startDownloadButton.setEnabled(true);
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
        if (!EscalatedFS.canReadWriteAndroidData()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !shizukuBinderReceived &&
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED && !isRootAvailable()) {
                updateConsole("错误：请先给与本软件Root或Shizuku权限。");
                return;
            }
            try {
                if (!EscalatedFS.exists(Environment.getExternalStorageDirectory().toPath().resolve("Android/data/com.YostarJP.BlueArchive/files/"))) {
                    updateConsole("错误：请先打开蔚蓝档案并等待加载完成");
                    return;
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

        gameFileManager.startDownloads();
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
                    .setIcon(Icon.createWithResource(getApplicationContext(),R.drawable.ba_icon))
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

    public void logErrorToConsole(String message, Exception ex) {
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
}