package com.dsh.balance;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DSH 余额 —— 极简 WebView 壳。
 *
 * 三个刻意的设计：
 *  1) 不用匿名内部类 / lambda —— javac 25 的匿名类属性 d8 8.2 会 NPE（已实测），
 *     全部改用具名静态嵌套类。
 *  2) 提供 httpGet / probeLan 原生桥 —— 网页版直接 fetch 会被浏览器跨域拦，
 *     而且 WebView 的 JS 桥调用是串行的，多个候选地址必须由 Java 并行探测。
 *  3) 状态栏高度按“CSS 像素”注入，且只在页面确实被状态栏压住时才补间距
 *     （WebView 通常已经在状态栏下方，再加一次就是双重留白）。
 */
public class MainActivity extends Activity {

    private static final String ASSET = "file:///android_asset/index.html";
    static final String PREFS = "dsh";

    /** 默认候选地址（可选）：留空则完全依靠 UDP 自动发现。
     * 如果部署环境不支持广播，在 App 设置里手动填一个即可。 */
    private static final String DEFAULT_LAN = "";

    private static final String BG_LIGHT = "#F2F5FA";
    private static final String BG_DARK  = "#0A0D12";
    private static final int MAX_BODY = 300000;
    private static final int CONNECT_MS = 2000;
    private static final int READ_MS = 4000;

    private WebView web;
    private int statusBarHeightPx = 0;

    /* ── 网络 ───────────────────────────────────────────── */

    /** 返回 "状态码|响应体"；实现放在 Net 里，和桌面小组件共用一份 */
    static String fetchRaw(String url, String bearer) {
        return Net.fetchRaw(url, bearer);
    }

    // readAll / ProbeTask 已移到 Net.java —— 主程序和桌面小组件共用一份实现

    /** 只需要本地资源，外链不跳浏览器；顺便在页面就绪后注入状态栏补偿量 */
    private final class HookedClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return url == null || !url.startsWith("file://");
        }
        @Override
        public void onPageFinished(WebView v, String url) {
            super.onPageFinished(v, url);
            injectTopInset(v);
        }
    }

    /** 暴露给页面：AndroidHost.onTheme / httpGet / probeLan / saveConfig */
    private final class Bridge {

        @JavascriptInterface
        public void onTheme(String mode) {
            if (web == null) return;
            web.post(new ThemeTask(MainActivity.this, "dark".equals(mode)));
        }

        @JavascriptInterface
        public String httpGet(String url, String bearer) {
            String raw = Net.fetchRaw(url, bearer);
            int cut = raw.indexOf('|');
            int st = 0;
            String body = raw;
            if (cut > 0) {
                try { st = Integer.parseInt(raw.substring(0, cut)); } catch (Exception ignored) { }
                body = raw.substring(cut + 1);
            }
            return Net.wrap(st, body);
        }

        /**
         * 并行探测多个候选地址，谁先返回 200 就用谁。
         * JS 桥调用是串行的，所以在 JS 里并行没意义 —— 必须在这里并行。
         */
        @JavascriptInterface
        public String probeLan(String urlsCsv, String path) {
            String body = Net.probeFirst(urlsCsv, path, 6);
            if (body != null) return Net.wrap(200, body);
            return Net.wrap(0, "所有候选地址都连不上（电脑没开机 / 不在同一 WiFi / 防火墙没放行）");
        }

        /** 网页版把地址/模式/API Key 交给原生层，桌面小组件要复用。
            注意：JS 桥对重载方法行为不可靠，只能保留单参数签名。 */
        @JavascriptInterface
        public void saveConfig(String lan, String mode, String apiKey) {
            try {
                SharedPreferences.Editor e = prefs(MainActivity.this).edit();
                if (lan != null) e.putString("lan", lan);
                if (mode != null) e.putString("mode", mode);
                if (apiKey != null && apiKey.length() > 0) e.putString("apikey", apiKey);
                e.apply();
            } catch (Exception ignored) { }
        }

        /**
         * 在 App 里点了刷新，桌面小组件也跟着刷。
         * 如果首页拿到了数值就一并传进来（balance / today / perDay），
         * 小组件直接用它渲染，不用再发一次网络请求，也不会闪旧数据。
         */
        @JavascriptInterface
        public void refreshWidget(String balance, String today, String perDay) {
            try {
                Context app = MainActivity.this.getApplicationContext();
                SharedPreferences.Editor e = prefs(app).edit();
                if (balance != null && balance.length() > 0 && !"--".equals(balance)) {
                    e.putString("last_balance", balance);
                    e.putString("last_today", today == null ? "" : today);
                    e.putString("last_perday", perDay == null ? "" : perDay);
                    e.putLong("last_at", System.currentTimeMillis());
                    e.apply();
                    // 有现成数值：只重绘，不联网
                    WidgetProvider.pushUpdate(app, true);
                } else {
                    // 没给数值：让小组件自己去取
                    e.apply();
                    WidgetProvider.requestUpdate(app);
                }
            } catch (Exception ignored) { }
        }

        /**
         * 异步版并行探测：探测完通过 evaluateJavascript 调 window.__probeCb(status, body)。
         * 为什么必须异步：JS 桥同步调用会把 WebView 主线程卡死最长 6 秒，
         * 电脑离线时用户看到的是纯白屏（首帧都画不出来）。
         */
        @JavascriptInterface
        public void probeLanAsync(final String urlsCsv, final String path) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    final String body = Net.probeFirst(urlsCsv, path, 6);
                    final String js;
                    if (body != null) {
                        js = "window.__probeCb&&window.__probeCb(200," +
                             org.json.JSONObject.quote(body) + ");";
                    } else {
                        js = "window.__probeCb&&window.__probeCb(0,\"\");";
                    }
                    if (web != null) {
                        web.post(new Runnable() {
                            @Override
                            public void run() {
                                try { web.evaluateJavascript(js, null); } catch (Exception ignored) { }
                            }
                        });
                    }
                }
            }, "dsh-probe");
            t.start();
        }
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ── 生命周期 ──────────────────────────────────────── */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        statusBarHeightPx = measureStatusBar();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor(BG_LIGHT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        web.setBackgroundColor(Color.parseColor(BG_LIGHT));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new HookedClient());
        web.addJavascriptInterface(new Bridge(), "AndroidHost");

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        paintStatusBar(false);

        String savedLan = prefs(this).getString("lan", "");
        String lan = (savedLan != null && savedLan.length() > 0) ? savedLan : DEFAULT_LAN;
        web.loadUrl(ASSET + "?lan=" + Uri.encode(lan));
    }

    /** 页面就绪后告诉它顶部还需要补多少 CSS 像素 */
    private void injectTopInset(WebView v) {
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            int extraPhys = Math.max(0, statusBarHeightPx - loc[1]);
            float density = getResources().getDisplayMetrics().density;
            if (density <= 0) density = 1f;
            int css = Math.round(extraPhys / density);
            v.evaluateJavascript(
                "window.__SBH=" + css + ";" +
                "document.documentElement.style.setProperty('--sbh','" + css + "px');" +
                "if(" + css + ">0){document.body.classList.add('has-sbh');}" +
                "else{document.body.classList.remove('has-sbh');}", null);
        } catch (Exception ignored) { }
    }

    private int measureStatusBar() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Exception ignored) { }
        return (int) (24 * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void paintStatusBar(boolean dark) {
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor(dark ? BG_DARK : BG_LIGHT));
        }
        if (Build.VERSION.SDK_INT >= 23) {
            View v = getWindow().getDecorView();
            int flags = v.getSystemUiVisibility();
            if (dark) {
                flags = flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags = flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            v.setSystemUiVisibility(flags);
        }
    }

    /** 状态栏换色任务（用具名类代替匿名 Runnable） */
    private static final class ThemeTask implements Runnable {
        private final MainActivity host;
        private final boolean dark;
        ThemeTask(MainActivity host, boolean dark) {
            this.host = host;
            this.dark = dark;
        }
        @Override
        public void run() {
            host.paintStatusBar(dark);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidHost");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}