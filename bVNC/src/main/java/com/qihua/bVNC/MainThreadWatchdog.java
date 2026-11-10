package com.qihua.bVNC;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * 主线程卡死看门狗。
 * <p>
 * 后台线程每 1s 检查一次主线程心跳:主线程在 3s 内没有跑到心跳,
 * 就把主线程(以及所有线程)的栈打印到 logcat。
 * <p>
 * 用法:Activity 里 {@code watchdog.start()} / {@code onDestroy} 里 {@code stop()}。
 * 目的不是修复卡死,而是让"卡死"变成可诊断的问题 —— 光看现象没法区分
 * 是主线程被锁住、还是 native 线程崩了、还是网络线程在等。
 */
public class MainThreadWatchdog {

    private static final String TAG = "MainThreadWatchdog";
    private static final long BEAT_INTERVAL_MS = 500;
    private static final long STALL_THRESHOLD_MS = 3000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile long lastBeatMs = SystemClock.uptimeMillis();
    private volatile boolean running;
    private Thread watcher;

    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            lastBeatMs = SystemClock.uptimeMillis();
            if (running) {
                mainHandler.postDelayed(this, BEAT_INTERVAL_MS);
            }
        }
    };

    public void start() {
        if (running) {
            return;
        }
        running = true;
        lastBeatMs = SystemClock.uptimeMillis();
        mainHandler.post(beat);
        watcher = new Thread(this::watch, "MainThreadWatchdog");
        watcher.setDaemon(true);
        watcher.start();
    }

    public void stop() {
        running = false;
        mainHandler.removeCallbacks(beat);
        if (watcher != null) {
            watcher.interrupt();
            watcher = null;
        }
    }

    private void watch() {
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long stalled = SystemClock.uptimeMillis() - lastBeatMs;
            if (stalled > STALL_THRESHOLD_MS) {
                dumpStalled(stalled);
                // 打印一次就等主线程缓过来,别把 logcat 刷爆
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void dumpStalled(long stalledMs) {
        Log.e(TAG, "主线程疑似卡死 " + stalledMs + "ms,打印主线程栈:");
        try {
            StackTraceElement[] main = Looper.getMainLooper().getThread().getStackTrace();
            for (StackTraceElement e : main) {
                Log.e(TAG, "  at " + e);
            }
        } catch (Throwable t) {
            Log.e(TAG, "取主线程栈失败", t);
        }
        // 顺带把所有线程的栈抓下来:死锁往往是两个线程互相等
        try {
            for (java.util.Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread th = entry.getKey();
                if (th == Thread.currentThread()) {
                    continue;
                }
                Log.e(TAG, "thread [" + th.getName() + "] state=" + th.getState());
                StackTraceElement[] st = entry.getValue();
                for (int i = 0; i < Math.min(st.length, 12); i++) {
                    Log.e(TAG, "  at " + st[i]);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "取全线程栈失败", t);
        }
    }
}
