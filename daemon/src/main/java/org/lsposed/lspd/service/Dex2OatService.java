package org.lsposed.lspd.service;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SELinux;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Dex2OatService {

    private static final String TAG = "Dex2OatService";

    private final List<Thread> mThreads = new ArrayList<>();

    public void start() {
        try {
            var devPath = Files.readAllLines(Paths.get("/data/adb/lspd/dev_path")).get(0);
            Log.d(TAG, "dev path: " + devPath);
            daemon(devPath, "32");
            if (Process.is64Bit()) daemon(devPath, "64");
        } catch (IOException e) {
            Log.e(TAG, "dex2oat daemon failed to start", e);
        }
    }

    public boolean isAlive() {
        for (Thread thread: mThreads) {
            if (!thread.isAlive()) return false;
        }
        return true;
    }

    private void daemon(String devPath, String lp) {
        var thread = new Thread(() -> {
            try {
                Log.i(TAG, "dex2oat" + lp + " daemon start");
                if (setSocketCreateContext("u:r:dex2oat:s0")) {
                    Log.d(TAG, "set socket context to u:r:dex2oat:s0");
                } else {
                    Log.e(TAG, "failed to set socket context");
                }
                var sockPath = devPath + "/dex2oat" + lp + ".sock";
                var serverSocket = new LocalSocket(LocalSocket.SOCKET_STREAM);
                serverSocket.bind(new LocalSocketAddress(sockPath, LocalSocketAddress.Namespace.FILESYSTEM));
                var server = new LocalServerSocket(serverSocket.getFileDescriptor());
                SELinux.setFileContext(sockPath, "u:object_r:magisk_file:s0");
                var stockFd = Os.open("/apex/com.android.art/bin/dex2oat" + lp, OsConstants.O_RDONLY, 0);
                while (true) {
                    var client = server.accept();
                    try (var os = client.getOutputStream()) {
                        client.setFileDescriptorsForSend(new FileDescriptor[]{stockFd});
                        os.write(1);
                    }
                    Log.d(TAG, "sent fd");
                }
            } catch (Exception e) {
                Log.e(TAG, "dex2oat daemon crashed", e);
            }
        });
        mThreads.add(thread);
        thread.start();
    }

    private boolean setSocketCreateContext(String context) {
        FileDescriptor fd = null;
        try {
            fd = Os.open("/proc/thread-self/attr/sockcreate", OsConstants.O_RDWR, 0);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) {
                int tid = Os.gettid();
                try {
                    fd = Os.open(String.format(Locale.ENGLISH, "/proc/self/task/%d/attr/sockcreate", tid), OsConstants.O_RDWR, 0);
                } catch (ErrnoException ignored) {
                }
            }
        }

        if (fd == null) {
            return false;
        }

        byte[] bytes;
        int length;
        int remaining;
        if (!TextUtils.isEmpty(context)) {
            byte[] stringBytes = context.getBytes();
            bytes = new byte[stringBytes.length + 1];
            System.arraycopy(stringBytes, 0, bytes, 0, stringBytes.length);
            bytes[stringBytes.length] = '\0';

            length = bytes.length;
            remaining = bytes.length;
        } else {
            bytes = null;
            length = 0;
            remaining = 0;
        }

        do {
            try {
                remaining -= Os.write(fd, bytes, length - remaining, remaining);
                if (remaining <= 0) {
                    break;
                }
            } catch (ErrnoException e) {
                break;
            } catch (InterruptedIOException e) {
                remaining -= e.bytesTransferred;
            }
        } while (true);

        try {
            Os.close(fd);
        } catch (ErrnoException e) {
            Log.w(TAG, Log.getStackTraceString(e));
        }
        return true;
    }
}
