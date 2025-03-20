package com.asfu222.bajpdl.util;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.Environment;

import com.asfu222.bajpdl.shizuku.IUserService;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;


/**
 * A wrapper to convert java.nio.file.Files operations to escalated shell operations.
 */
public abstract class EscalatedFS {
    private static boolean rootAvailable;
    private static IUserService shizukuService;

    public static void setRootAvailable(boolean value) {
        rootAvailable = value;
    }

    public static void setShizukuService(IUserService service) {
        shizukuService = service;
    }

    private static boolean needsEscalation() {
        return !canReadWriteAndroidData();
    }

    public static boolean canReadWriteAndroidData() {
        File dataDir = new File(Environment.getExternalStorageDirectory() + "/Android/data/com.YostarJP.BlueArchive/files/");
        return dataDir.canRead() && dataDir.canWrite();
    }

    public static Path createDirectories(Path path) throws IOException {
        if (!needsEscalation()) {
            return Files.createDirectories(path);
        }

        if (shizukuService != null) {
            try {
                shizukuService.mkdirs(path.toString());
            } catch (RemoteException e) {
                throw new IOException("Shizuku 创建文件夹时报错：", e);
            }
        } else if (rootAvailable) {
            try {
                execEscalated("mkdir -p " + path.toString()).waitFor();
            } catch (InterruptedException e) {
                throw new IOException("创建文件夹时报错：" + e.getMessage(), e);
            }
        }
        return path;
    }

    public static OutputStream newOutputStream(Path path) throws IOException {
        if (!needsEscalation()) {
            return Files.newOutputStream(path);
        }
        if (shizukuService != null) {
            try {
                String[] status = new String[1];
                ParcelFileDescriptor pfd = shizukuService.openWrite(path.toString(), status);
                if (status[0].equals("success")) {
                    return new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
                } else {
                    throw new IOException("Shizuku 文件写入错误: " + status[0]);
                }
            } catch (RemoteException e) {
                throw new IOException("Shizuku 文件写入错误", e);
            }
        } else if (rootAvailable) {
            return new ProcessOutputStream(execEscalated("cat > " + path.toString()));
        }
        throw new IOException("无可用的 root 或 Shizuku 权限");
    }

    public static InputStream newInputStream(Path path) throws IOException {
        if (!needsEscalation()) {
            return Files.newInputStream(path);
        }
        if (shizukuService != null) {
            try {
                String[] status = new String[1];
                ParcelFileDescriptor pfd = shizukuService.openRead(path.toString(), status);
                if (status[0].equals("success")) {
                    return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                } else {
                    throw new IOException("Shizuku 文件读取错误: " + status[0]);
                }
            } catch (RemoteException e) {
                throw new IOException("Shizuku 文件读取错误", e);
            }
        } else if (rootAvailable) {
            return new ProcessInputStream(execEscalated("cat " + path.toString()));
        }
        throw new IOException("无可用的 root 或 Shizuku 权限");
    }

    public static byte[] readAllBytes(Path path) throws IOException {
        if (!needsEscalation()) {
            return Files.readAllBytes(path);
        }

        try (InputStream is = newInputStream(path);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }

    public static void deleteIfExists(Path path) throws IOException {
        if (!needsEscalation()) {
            Files.deleteIfExists(path);
            return;
        }
        if (shizukuService != null) {
            try {
                String[] status = new String[1];
                shizukuService.deleteIfExists(path.toString(), status);
                if (!status[0].equals("success")) {
                    throw new IOException("Shizuku 文件删除错误: " + status[0]);
                }
            } catch (RemoteException e) {
                throw new IOException("Shizuku 文件删除错误", e);
            }
        } else if (rootAvailable) {
            try {
                execEscalated("rm -f " + path.toString()).waitFor();
            } catch (InterruptedException e) {
                throw new IOException("文件删除错误: " + e.getMessage(), e);
            }
        }
    }

    public static boolean exists(Path path) throws IOException {
        if (!needsEscalation()) {
            return Files.exists(path);
        }

        if (shizukuService != null) {
            try {
                return shizukuService.exists(path.toString());
            } catch (RemoteException e) {
                throw new IOException("Shizuku 文件检测错误", e);
            }
        } else if (rootAvailable) {
            try {
                return execEscalated("test -e " + path.toString()).waitFor() == 0;
            } catch (InterruptedException e) {
                throw new IOException("文件检测错误: " + e.getMessage(), e);
            }
        }
        throw new IOException("无可用的 root 或 Shizuku 权限");
    }

    public static void copy(Path source, Path target, java.nio.file.CopyOption... options) throws IOException {
        if (!needsEscalation()) {
            Files.copy(source, target, options);
            return;
        }

        boolean replaceExisting = false;
        boolean copyAttributes = false;
        boolean atomicMove = false;

        for (java.nio.file.CopyOption option : options) {
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                replaceExisting = true;
            } else if (option == StandardCopyOption.COPY_ATTRIBUTES) {
                copyAttributes = true;
            } else if (option == StandardCopyOption.ATOMIC_MOVE) {
                atomicMove = true;
            }
        }

        if (shizukuService != null) {
            try {
                String[] status = new String[1];
                shizukuService.copy(source.toString(), target.toString(), replaceExisting, copyAttributes, atomicMove, status);
                if (!status[0].equals("success")) {
                    throw new IOException("Shizuku 复制错误: " + status[0]);
                }
            } catch (RemoteException e) {
                throw new IOException("Shizuku 复制错误", e);
            }
        } else if (rootAvailable) {
            StringBuilder command = new StringBuilder();

            if (atomicMove) {
                command.append("mv ");
                if (replaceExisting) {
                    command.append("-f ");
                }
            } else {
                command.append("cp ");
                if (replaceExisting) {
                    command.append("-f ");
                }
                if (copyAttributes) {
                    command.append("-p ");
                }
            }

            command.append(source.toString()).append(" ").append(target.toString());

            try {
                // Make sure target directory exists
                EscalatedFS.createDirectories(target.getParent());

                Process process = execEscalated(command.toString());
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    try (java.io.InputStream errorStream = process.getErrorStream();
                         java.util.Scanner scanner = new java.util.Scanner(errorStream).useDelimiter("\\A")) {
                        String errorMessage = scanner.hasNext() ? scanner.next() : "Unknown error";
                        throw new IOException("复制错误: " + errorMessage.trim());
                    }
                }
            } catch (InterruptedException e) {
                throw new IOException("文件操作被打断: " + e.getMessage(), e);
            }
        } else {
            throw new IOException("无可用的 root 或 Shizuku 权限");
        }
    }

    public static long size(Path path) throws IOException {
        if (!needsEscalation()) {
            return Files.size(path);
        }
        if (shizukuService != null) {
            try {
                return shizukuService.size(path.toString());
            } catch (RemoteException e) {
                throw new IOException("Shizuku 获取文件大小时报错", e);
            }
        } else if (rootAvailable) {
            Process process = null;
            try {
                String absolutePath = path.toAbsolutePath().toString();
                process = execEscalated("stat -c %s " + absolutePath);

                String result;
                String error;

                try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                     BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

                    result = outReader.readLine();
                    error = errReader.readLine();

                    int exitCode = process.waitFor();
                    if (exitCode != 0) {
                        throw new IOException("获取文件大小时报错。错误代码" + exitCode + ": " + error);
                    }

                    return Long.parseLong(result.trim());
                }
            } catch (InterruptedException | NumberFormatException e) {
                throw new IOException("获取文件大小失败: " + e.getMessage(), e);
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        throw new IOException("无可用的 root 或 Shizuku 权限");
    }

    public static Stream<Path> walk(Path start) throws IOException {
        if (!needsEscalation()) {
            return Files.walk(start);
        }

        if (rootAvailable) {
            Process process = execEscalated("find " + start.toString() + " -type f -o -type d");

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));

            return reader.lines()
                    .filter(line -> !line.isEmpty())
                    .map(java.nio.file.Paths::get)
                    .onClose(() -> {
                        try {
                            reader.close();
                            if (process.waitFor() != 0) {
                                throw new java.io.UncheckedIOException(
                                        new IOException("Failed to walk directory: " + start));
                            }
                        } catch (IOException | InterruptedException e) {
                            throw new java.io.UncheckedIOException(
                                    new IOException("Error closing resources: " + e.getMessage(), e));
                        }
                    });
        }
        return Files.walk(start);
    }

    private static Process execEscalated(String command) throws IOException {
        if (rootAvailable) return Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        throw new IOException("Shizuku shell execution has been removed.");
    }

    static class ProcessOutputStream extends OutputStream {
        private final Process process;
        private final OutputStream out;
        private final Thread errorDrainer;

        ProcessOutputStream(Process process) {
            this.process = process;
            this.out = process.getOutputStream();

            // Drain error stream to prevent blocking
            this.errorDrainer = new Thread(() -> {
                try (InputStream errorStream = process.getErrorStream()) {
                    byte[] buffer = new byte[1024];
                    while (errorStream.read(buffer) != -1) {
                        // Drain error stream
                    }
                } catch (IOException ignored) {
                    // Ignore exceptions during cleanup
                }
            });
            errorDrainer.setDaemon(true);
            errorDrainer.start();
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            IOException exception = null;

            try {
                out.close();
            } catch (IOException e) {
                exception = e;
            }

            try {
                int exitCode = process.waitFor();
                if (exitCode != 0 && exitCode != 141) {  // 141 is SIGPIPE
                    String errorMessage;
                    try (InputStream es = process.getErrorStream();
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = es.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        errorMessage = new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
                    } catch (IOException e) {
                        errorMessage = "Failed to get error message";
                    }
                    throw new IOException("Process exited with code " + exitCode
                            + (errorMessage.isEmpty() ? "" : ": " + errorMessage));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exception = new IOException("Process interrupted: " + e.getMessage(), e);
            } finally {
                process.destroy();
            }

            try {
                errorDrainer.join(1000);
            } catch (InterruptedException ignored) {
            }

            if (exception != null) {
                throw exception;
            }
        }
    }

    static class ProcessInputStream extends InputStream {
        private final Process process;
        private final InputStream in;
        private final BufferedInputStream bufferedIn;
        private final Thread errorDrainer;

        ProcessInputStream(Process process) {
            this.process = process;
            this.in = process.getInputStream();
            this.bufferedIn = new BufferedInputStream(in);

            // Drain error stream in background to prevent blocking
            errorDrainer = new Thread(() -> {
                try (InputStream errorStream = process.getErrorStream()) {
                    byte[] buffer = new byte[1024];
                    while (errorStream.read(buffer) != -1) {
                        // Drain error stream
                    }
                } catch (IOException ignored) {
                    // Ignore exceptions during cleanup
                }
            });
            errorDrainer.setDaemon(true);
            errorDrainer.start();
        }

        @Override
        public int read() throws IOException {
            return bufferedIn.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return bufferedIn.read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return bufferedIn.read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            return bufferedIn.skip(n);
        }

        @Override
        public int available() throws IOException {
            return bufferedIn.available();
        }

        @Override
        public void close() throws IOException {
            IOException exception = null;

            try {
                bufferedIn.close();
            } catch (IOException e) {
                exception = e;
            }

            try {
                in.close();
            } catch (IOException e) {
                if (exception == null) exception = e;
            }

            try {
                int exitCode = process.waitFor();
                // SIGPIPE (141) is normal when reading ends before process finishes
                if (exitCode != 0 && exitCode != 141) {
                    throw new IOException("Process exited with error code: " + exitCode);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (exception == null) {
                    exception = new IOException("Process interrupted: " + e.getMessage(), e);
                }
            } finally {
                process.destroy();
            }

            if (exception != null) {
                throw exception;
            }
        }
    }
}