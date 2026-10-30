package com.dsh.balance;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * 小组件的定时唤醒。
 *
 * 为什么需要这个类：安卓的 updatePeriodMillis 最小只能是 30 分钟
 * （写更小会被系统强行改回 30 分钟，而且不保证准时）。
 * 想每分钟刷新，只能自己用 AlarmManager 排闹钟。
 *
 * 做法：每次刷新完，把下一次闹钟排到 1 分钟后。
 * 用 setAndAllowWhileIdle 让它在轻度休眠时也能醒来。
 */
public class WidgetAlarm extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.dsh.balance.WIDGET_TICK";
    private static final long INTERVAL_MS = 60 * 1000L;   // 1 分钟

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // 先刷新
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
        if (ids != null && ids.length > 0) {
            WidgetProvider.requestUpdate(ctx);
        }
        // 再排下一次（不管这次成没成功，节奏不能断）
        schedule(ctx);
    }

    /** 排下一次唤醒 */
    static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent it = new Intent(ctx, WidgetAlarm.class);
            it.setAction(ACTION_TICK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 100, it, flags);

            long at = SystemClock.elapsedRealtime() + INTERVAL_MS;

            if (Build.VERSION.SDK_INT >= 23) {
                // setAndAllowWhileIdle：轻度休眠也能唤醒，但系统仍会做一定节流
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME, at, pi);
            }
        } catch (Exception ignored) { }
    }

    /** 取消定时（用户在系统里删掉小组件时调用） */
    static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent it = new Intent(ctx, WidgetAlarm.class);
            it.setAction(ACTION_TICK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 100, it, flags);
            am.cancel(pi);
        } catch (Exception ignored) { }
    }
}
