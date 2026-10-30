package com.dsh.balance;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

/**
 * 桌面小组件：显示剩余余额 / 今日消耗 / 续航天数。
 *
 * 数据来源：和 App 一样读局域网那台电脑的 /api/balance。
 * 电脑地址由 App 通过 AndroidHost.saveConfig() 写进同一个 SharedPreferences，
 * 所以只要 App 连上过一次，小组件就知道该找谁。
 *
 * 系统限制 updatePeriodMillis 最短 30 分钟，所以：
 *   - 正常靠系统定时刷新
 *   - 点一下小组件会立刻重取，并顺带打开 App
 *   - 取不到时显示上次成功的数值 + 「离线」，不留空白
 */
public class WidgetProvider extends AppWidgetProvider {

    /** 点小组件时先刷新再打开 App（用同一个 receiver 收，避免多写一个类） */
    public static final String ACTION_REFRESH = "com.dsh.balance.WIDGET_REFRESH";
    private static final String PATH = "/api/balance";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        // goAsync 把广播的生命周期延长到后台线程干完活
        final PendingResult pr = goAsync();
        Context app = ctx.getApplicationContext();
        Thread t = new Thread(new RefreshTask(app, mgr, ids, pr), "dsh-widget");
        t.start();
        // 每次系统刷新后，把 1 分钟的定时器挂上
        WidgetAlarm.schedule(app);
    }

    @Override
    public void onEnabled(Context ctx) {
        super.onEnabled(ctx);
        WidgetAlarm.schedule(ctx.getApplicationContext());
    }

    @Override
    public void onDisabled(Context ctx) {
        super.onDisabled(ctx);
        WidgetAlarm.cancel(ctx.getApplicationContext());
    }

    /** 从定时器/App 内刷新请求更新，走标准 AppWidget 广播 */
    static void requestUpdate(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
            if (ids != null && ids.length > 0) {
                Intent it = new Intent(ctx, WidgetProvider.class);
                it.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
                ctx.sendBroadcast(it);
            }
        } catch (Exception ignored) { }
    }

    /**
     * 只重绘，不联网。用在「App 里刚拿到新数据」的场景：
     * 页面把三个数值写进 SharedPreferences 后调这个，小组件秒变新值。
     * @param skipNetwork 目前总为 true（不联网）；留参数是为了以后扩展
     */
    static void pushUpdate(Context ctx, boolean skipNetwork) {
        try {
            SharedPreferences sp = MainActivity.prefs(ctx);
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
            if (ids == null || ids.length == 0) return;

            String bal = sp.getString("last_balance", "");
            String today = sp.getString("last_today", "");
            String perDay = sp.getString("last_perday", "");
            long at = sp.getLong("last_at", 0);

            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
            if (bal == null || bal.length() == 0) {
                v.setTextViewText(R.id.widget_balance, "--");
                v.setTextViewText(R.id.widget_line, ctx.getString(R.string.widget_never));
                v.setTextViewText(R.id.widget_state, "");
            } else {
                v.setTextViewText(R.id.widget_balance, "¥" + bal);
                String line = "今日 ¥" + (today == null || today.length() == 0 ? "--" : today);
                if (perDay != null && perDay.length() > 0) {
                    try {
                        double p = Double.parseDouble(perDay);
                        double b = Double.parseDouble(bal);
                        if (p > 0) line += " · 还够 " + Math.round(b / p * 10) / 10.0 + " 天";
                    } catch (Exception ignored) { }
                }
                v.setTextViewText(R.id.widget_line, line);
                v.setTextViewText(R.id.widget_state, "● 在线 " + hm(at));
                v.setTextColor(R.id.widget_state, ctx.getResources().getColor(R.color.widget_ac));
                v.setTextColor(R.id.widget_balance, ctx.getResources().getColor(R.color.widget_tx));
            }

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;

            Intent tick = new Intent(ctx, WidgetProvider.class);
            tick.setAction(ACTION_REFRESH);
            v.setOnClickPendingIntent(R.id.widget_balance,
                    PendingIntent.getBroadcast(ctx, 1, tick, flags));

            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            v.setOnClickPendingIntent(R.id.widget_root,
                    PendingIntent.getActivity(ctx, 2, open, flags));

            for (int i = 0; i < ids.length; i++) mgr.updateAppWidget(ids[i], v);
        } catch (Exception ignored) { }
    }

    private static String hm(long at) {
        if (at <= 0) return "";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(at);
        int h = c.get(java.util.Calendar.HOUR_OF_DAY), m = c.get(java.util.Calendar.MINUTE);
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
            if (ids != null && ids.length > 0) {
                onUpdate(ctx, mgr, ids);
                return;
            }
        }
        super.onReceive(ctx, intent);
    }

    /* ── 后台刷新 ─────────────────────────────────────── */

    private static final class RefreshTask implements Runnable {
        private final Context ctx;
        private final AppWidgetManager mgr;
        private final int[] ids;
        private final BroadcastReceiver.PendingResult pr;

        RefreshTask(Context ctx, AppWidgetManager mgr, int[] ids,
                    BroadcastReceiver.PendingResult pr) {
            this.ctx = ctx;
            this.mgr = mgr;
            this.ids = ids;
            this.pr = pr;
        }

        @Override
        public void run() {
            try {
                SharedPreferences sp = MainActivity.prefs(ctx);
                String lan = sp.getString("lan", "");
                String mode = sp.getString("mode", "lan");

                boolean online = false;
                String balance = null, today = null, perDay = null;

                /* 官方 API 模式：直接问 DeepSeek，不需要电脑开机。
                   key 由用户在 App 里填，存在本地 SharedPreferences。 */
                if ("api".equals(mode)) {
                    String apiKey = sp.getString("apikey", "");
                    if (apiKey != null && apiKey.length() > 0) {
                        String raw = Net.fetchRaw("https://api.deepseek.com/user/balance", apiKey);
                        int cut = raw == null ? -1 : raw.indexOf('|');
                        if (cut > 0 && raw.startsWith("200|")) {
                            try {
                                JSONObject j = new JSONObject(raw.substring(cut + 1));
                                org.json.JSONArray arr = j.optJSONArray("balance_infos");
                                if (arr != null && arr.length() > 0) {
                                    JSONObject b = arr.getJSONObject(0);
                                    double bal = Double.parseDouble(b.optString("total_balance", "0"));
                                    balance = fmt(bal);
                                    online = true;
                                    SharedPreferences.Editor e = sp.edit();
                                    e.putString("last_balance", balance);
                                    e.putString("last_today", "");
                                    e.putString("last_perday", "");
                                    e.putLong("last_at", System.currentTimeMillis());
                                    e.putBoolean("last_official", true);
                                    e.apply();
                                }
                            } catch (Exception ignored) { }
                        }
                    }
                } else if (lan != null && lan.length() > 0 && !"demo".equals(mode)) {
                    String body = Net.probeFirst(lan, PATH, 5);
                    if (body != null) {
                        try {
                            JSONObject j = new JSONObject(body);
                            double bal = j.optDouble("balance", Double.NaN);
                            if (!Double.isNaN(bal)) {
                                JSONObject hist = j.optJSONObject("history");
                                double sum = 0; int cnt = 0;
                                if (hist != null) {
                                    ArrayList<String> keys = new ArrayList<String>();
                                    Iterator<String> it = hist.keys();
                                    while (it.hasNext()) keys.add(it.next());
                                    Collections.sort(keys);
                                    int from = Math.max(0, keys.size() - 7);
                                    for (int i = from; i < keys.size(); i++) {
                                        sum += hist.optDouble(keys.get(i), 0);
                                        cnt++;
                                    }
                                }
                                balance = fmt(bal);
                                today = fmt(j.optDouble("todayUsage", 0));
                                perDay = cnt > 0 ? fmt(sum / cnt) : null;
                                online = true;

                                SharedPreferences.Editor e = sp.edit();
                                e.putString("last_balance", balance);
                                e.putString("last_today", today);
                                e.putString("last_perday", perDay == null ? "" : perDay);
                                e.putLong("last_at", System.currentTimeMillis());
                                e.apply();
                            }
                        } catch (Exception ignored) { }
                    }
                }

                if (!online) {
                    balance = sp.getString("last_balance", "");
                    today = sp.getString("last_today", "");
                    perDay = sp.getString("last_perday", "");
                }
                long at = sp.getLong("last_at", 0);
                render(balance, today, perDay, online, at);

            } catch (Exception ignored) {
                // 兜底：哪怕出错也要 finish，否则广播一直不结束
            } finally {
                try { pr.finish(); } catch (Exception ignored) { }
            }
        }

        private void render(String balance, String today, String perDay,
                            boolean online, long at) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
            Context c = ctx;

            if (balance == null || balance.length() == 0) {
                v.setTextViewText(R.id.widget_balance, "--");
                v.setTextViewText(R.id.widget_line, c.getString(R.string.widget_never));
                v.setTextViewText(R.id.widget_state, "");
            } else {
                v.setTextViewText(R.id.widget_balance, "¥" + balance);
                String line = "今日 ¥" + (today == null || today.length() == 0 ? "--" : today);
                if (perDay != null && perDay.length() > 0) {
                    try {
                        double p = Double.parseDouble(perDay);
                        double b = Double.parseDouble(balance);
                        if (p > 0) line += " · 还够 " + trim1(b / p) + " 天";
                    } catch (Exception ignored) { }
                }
                v.setTextViewText(R.id.widget_line, line);
                v.setTextViewText(R.id.widget_state,
                        online ? "● 在线" : "● 离线 " + hm(at));
                v.setTextColor(R.id.widget_state,
                        c.getResources().getColor(online ? R.color.widget_ac : R.color.widget_sub));
            }
            v.setTextColor(R.id.widget_balance,
                    c.getResources().getColor(online || balance == null || balance.length() == 0
                            ? R.color.widget_tx : R.color.widget_sub));

            /* 点一下：刷新 + 打开 App（用广播转一手，避免在 RemoteViews 里塞两个 intent） */
            Intent tick = new Intent(c, WidgetProvider.class);
            tick.setAction(ACTION_REFRESH);
            PendingIntent refresh = PendingIntent.getBroadcast(c, 1, tick, piFlags());
            v.setOnClickPendingIntent(R.id.widget_balance, refresh);

            Intent open = new Intent(c, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent go = PendingIntent.getActivity(c, 2, open, piFlags());
            v.setOnClickPendingIntent(R.id.widget_root, go);

            if (ids != null) {
                for (int i = 0; i < ids.length; i++) mgr.updateAppWidget(ids[i], v);
            }
        }

        private static int piFlags() {
            int f = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
            return f;
        }

        private static String fmt(double d) {
            return String.valueOf(Math.round(d * 100.0) / 100.0);
        }

        private static String trim1(double d) {
            return String.valueOf(Math.round(d * 10.0) / 10.0);
        }

        private static String hm(long at) {
            if (at <= 0) return "";
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(at);
            int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int m = cal.get(java.util.Calendar.MINUTE);
            return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
        }
    }
}