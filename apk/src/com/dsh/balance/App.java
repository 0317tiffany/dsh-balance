package com.dsh.balance;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 崩溃截获器：闪退时不再无声退出，而是弹出一个独立 Activity，
 * 把完整堆栈画在屏上，让用户截图回来 —— 一击定位，而不是盲猜。
 *
 * 平时不占资源：只在未捕获异常发生时才起作用。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    startCrashActivity(t, e);
                } catch (Throwable ignored) { }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    private void startCrashActivity(Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        String trace = sw.toString();

        Intent it = new Intent(this, CrashActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        it.putExtra("thread", String.valueOf(t));
        it.putExtra("trace", trace);
        startActivity(it);
    }
}