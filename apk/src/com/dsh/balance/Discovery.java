package com.dsh.balance;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 局域网自动发现。
 *
 * 为什么需要：电脑 IP 会变（实测 192.168.1.10 → 192.168.1.25），
 * 把地址写死在 App 里，换个网络或电脑重启就失效。
 *
 * 做法：监听 UDP 8788（电脑每 5 秒广播一次），同时主动往
 * 255.255.255.255:8789 发查询包让电脑立刻应答。谁先到用谁。
 *
 * 全程后台线程，绝不阻塞 UI。
 */
final class Discovery {

    static final int BEACON_PORT = 8788;
    private static final int ASK_PORT = 8789;
    private static final int TIMEOUT_MS = 5000;

    interface Callback {
        /** 找到时回调，ips 形如 ["http://192.168.1.10:8787"] */
        void onFound(List<String> urls);
        void onFail(String reason);
    }

    private Discovery() { }

    /**
     * 在后台线程里找电脑。回调在主线程。
     * @param port 电脑 HTTP 服务端口（默认 8787）
     */
    static void find(final int port, final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket();
                    socket.setBroadcast(true);
                    socket.setSoTimeout(600);

                    // 先主动问一句，电脑会立刻回，比等 5 秒一次的广播快
                    try {
                        byte[] ask = "{\"service\":\"dsh-balance\",\"ask\":true}".getBytes("UTF-8");
                        socket.send(new DatagramPacket(ask, ask.length,
                                InetAddress.getByName("255.255.255.255"), ASK_PORT));
                    } catch (Exception ignored) { }

                    long deadline = System.currentTimeMillis() + TIMEOUT_MS;
                    byte[] buf = new byte[2048];

                    while (System.currentTimeMillis() < deadline) {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        try {
                            socket.receive(pkt);
                        } catch (Exception timeout) {
                            continue;   // 超时就再等一轮
                        }
                        String text = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
                        List<String> urls = parse(text, port);
                        if (urls != null && urls.size() > 0) {
                            final List<String> hit = urls;
                            ui.post(new Runnable() {
                                @Override
                                public void run() { cb.onFound(hit); }
                            });
                            return;
                        }
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() { cb.onFail("局域网里没找到电脑（电脑端服务没开？）"); }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() { cb.onFail(String.valueOf(e.getMessage())); }
                    });
                } finally {
                    if (socket != null) socket.close();
                }
            }
        }, "dsh-discovery");
        t.setDaemon(true);
        t.start();
    }

    /** 解析广播/应答包，返回可直接请求的 URL 列表 */
    private static List<String> parse(String text, int fallbackPort) {
        try {
            JSONObject j = new JSONObject(text);
            if (!"dsh-balance".equals(j.optString("service"))) return null;
            int port = j.optInt("port", fallbackPort);
            org.json.JSONArray ips = j.optJSONArray("ips");
            List<String> out = new ArrayList<String>();
            if (ips != null) {
                for (int i = 0; i < ips.length(); i++) {
                    String ip = ips.optString(i, "");
                    if (ip.length() > 0) out.add("http://" + ip + ":" + port);
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}