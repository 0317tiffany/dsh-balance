package com.dsh.balance;

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
 * 共用的网络层：主程序（WebView 壳）和桌面小组件都走这里，
 * 避免两边各写一份 fetch 之后慢慢走样。
 *
 * 超时故意设得短：这是「PC 在不在线」的探测，
 * 每秒都在让用户干等，宁可靠「快失败 + 显示离线快照」。
 */
final class Net {

    static final int CONNECT_MS = 2000;
    static final int READ_MS = 4000;
    private static final int MAX_BODY = 300000;

    private Net() { }

    /** 返回 "状态码|响应体"；连不上返回 "0|异常信息" */
    static String fetchRaw(String url, String bearer) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "DSHBalance/1.1 (Android)");
            if (bearer != null && bearer.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            int code = c.getResponseCode();
            InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            return code + "|" + readAll(is);
        } catch (Exception e) {
            return "0|" + e;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        char[] buf = new char[4096];
        int n;
        while ((n = br.read(buf)) > 0) {
            sb.append(buf, 0, n);
            if (sb.length() > MAX_BODY) break;
        }
        br.close();
        return sb.toString();
    }

    /** 单个候选地址的探测（具名类，不用匿名类） */
    private static final class Task implements Runnable {
        private final String url;
        private final AtomicReference<String> hit;
        private final CountDownLatch latch;
        Task(String url, AtomicReference<String> hit, CountDownLatch latch) {
            this.url = url;
            this.hit = hit;
            this.latch = latch;
        }
        @Override
        public void run() {
            String res = fetchRaw(url, null);
            if (res.startsWith("200|") && hit.compareAndSet(null, res.substring(4))) {
                latch.countDown();
            }
        }
    }

    /**
     * 并行探测多个候选地址（用 | 分隔），谁先返回 200 就用谁。
     * 串行探测的话 4 个地址各等一次超时，用户要干等大半分钟才有反馈。
     * @return 成功的响应体；全失败返回 null
     */
    static String probeFirst(String urlsCsv, String path, int timeoutSec) {
        String[] urls = (urlsCsv == null) ? new String[0] : urlsCsv.split("\\|");
        final AtomicReference<String> hit = new AtomicReference<String>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(4, urls.length)));
        int submitted = 0;
        for (int i = 0; i < urls.length; i++) {
            String base = urls[i] == null ? "" : urls[i].trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (base.length() == 0) continue;
            pool.submit(new Task(base + path, hit, latch));
            submitted++;
        }
        pool.shutdown();
        if (submitted == 0) return null;
        try {
            latch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) { }
        return hit.get();
    }

    /** 把响应体包成给网页用的 {"status":..,"body":".."} */
    static String wrap(int status, String body) {
        return "{\"status\":" + status + ",\"body\":" + JSONObject.quote(body == null ? "" : body) + "}";
    }
}