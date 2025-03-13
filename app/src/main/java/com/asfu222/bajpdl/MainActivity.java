package com.asfu222.bajpdl;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
import com.asfu222.bajpdl.util.EscalatedFS;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE = 1;
    private EditText serverUrlsInput;
    private Button startDownloadButton;
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
                    updateConsole("Shizuku permission result: " + (grantResult == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        startDownloadButton.setEnabled(true);
                        updateConsole("Shizuku permission granted, ready to use newProcess");
                    } else {
                        updateConsole("⚠️ Shizuku permission denied");
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
        updateConsole("Shizuku service disconnected");
        runOnUiThread(() -> startDownloadButton.setEnabled(false));
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        serverUrlsInput = findViewById(R.id.serverUrlsInput);
        Switch redownloadSwitch = findViewById(R.id.redownloadSwitch);
        Switch downloadCustomOnlySwitch = findViewById(R.id.downloadCustomOnlySwitch);
        startDownloadButton = findViewById(R.id.startDownloadButton);
        Button startReplacementsButton = findViewById(R.id.startReplacementsButton);
        startReplacementsButton.setOnClickListener(v -> gameFileManager.startReplacements());
        progressBar = findViewById(R.id.progressBar);
        progressText = findViewById(R.id.progressText);
        consoleScrollView = findViewById(R.id.consoleScrollView);
        consoleOutput = findViewById(R.id.consoleOutput);

        gameFileManager = new GameFileManager(this);

        List<String> defaultServerUrls = gameFileManager.getAppConfig().getServerUrls();
        serverUrlsInput.setText(String.join(",", defaultServerUrls));

        startDownloadButton.setOnClickListener(v -> startDownloads());

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

        serverUrlsInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard();
                return true;
            }
            return false;
        });

        batchSizeInput = findViewById(R.id.batchSizeInput);

        // Set the current batch size from AppConfig
        batchSizeInput.setText(String.valueOf(gameFileManager.getAppConfig().getBatchSize()));

        // Save the batch size when the user finishes editing
        batchSizeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard();
                return true;
            }
            return false;
        });

        setupEscalatedPermissions();
    }

    private void setupEscalatedPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isRootAvailable()) {
                updateConsole("Root access available");
                EscalatedFS.setRootAvailable(true);
                startDownloadButton.setEnabled(true);
                return;
            }
            startDownloadButton.setEnabled(false);
            updateConsole("Root access not available, checking for Shizuku...");

            Shizuku.addBinderReceivedListener(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);

            if (Shizuku.pingBinder()) {
                updateConsole("Shizuku service is running");
                shizukuBinderReceived = true;
                checkShizukuPermission();
            } else {
                updateConsole("Shizuku service is not running");
            }
        } else {
            requestStoragePermission();
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
            updateConsole("Checking Shizuku permission: " +
                    (permissionResult == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "NOT GRANTED"));

            if (permissionResult == PackageManager.PERMISSION_GRANTED) {
                updateConsole("Shizuku permission already granted");
                startDownloadButton.setEnabled(true);
            } else {
                if (!permissionRequested) {
                    permissionRequested = true;
                    updateConsole("Requesting Shizuku permission...");
                    Shizuku.requestPermission(REQUEST_CODE);
                } else {
                    updateConsole("Permission already requested once, request again...");
                    // Try requesting again after a short delay
                    permissionRequested = false;
                    startDownloadButton.postDelayed(() -> {
                        Shizuku.requestPermission(REQUEST_CODE);
                        permissionRequested = true;
                    }, 1000);
                }
            }
        } catch (Exception e) {
            logErrorToConsole("Error checking Shizuku permission", e);
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
    }

    private void startDownloads() {
        progressBar.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        progressText.setText("Progress: 0%");
        consoleOutput.setText("");

        String serverUrlsText = serverUrlsInput.getText().toString();
        List<String> serverUrls = Arrays.asList(serverUrlsText.split(","));
        gameFileManager.getAppConfig().setServerUrls(serverUrls);
        int batchSize = Math.max(Integer.parseInt(batchSizeInput.getText().toString()), 1);
        gameFileManager.getAppConfig().setBatchSize(batchSize);
        gameFileManager.getAppConfig().saveConfig();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !shizukuBinderReceived &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED && !isRootAvailable()) {
            updateConsole("Error: Root not available or Shizuku permission not granted. Please check permissions.");
            return;
        }

        gameFileManager.startDownloads();
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
                updateConsole("Storage permission granted");
            } else {
                updateConsole("Error: Storage permission denied");
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
            progressText.setText("Progress: " + progress + "%" + " (" + downloadedFiles + "/" + totalFiles + " Files, " + downloadedBytes + "/" + totalBytes + " MB)");
            if (downloadedFiles == totalFiles && downloadedBytes == totalBytes) {
                progressBar.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
            }
        });
    }
}