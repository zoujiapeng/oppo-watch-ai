package com.glslplayground.app;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.SecureRandom;

/**
 * 极简 WebSocket 客户端（RFC 6455），只为了连本机 DSH 的
 * `ws://127.0.0.1:3080/api/remote.mux` 收流式增量。
 *
 * DSH 的 Gateway 把所有 Remote 流都挂在那个路由上，没有子协议、不加密、
 * 客户端帧必须加掩码；服务端可能把消息分片，也可能插 ping。
 * 这里只实现够用的一小部分：握手、掩码文本帧、分片重组、ping→pong、close。
 */
public final class DshWebSocket {

    public interface Listener {
        /** 一条完整的文本消息（已重组分片）。 */
        void onText(String message);
        /** 连接异常结束（正常 close 不回调）。 */
        void onFailure(String message);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int OP_TEXT = 0x1;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final Socket socket = new Socket();
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();
    private InputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile boolean closed;
    private Listener listener;

    public void connect(String host, int port, String path, String cookie, int timeoutMs, Listener l)
            throws IOException {
        this.listener = l;
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(0);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(':').append(port).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        // 不发送 Origin：DSH 的 Gateway 会拒绝跨站来源
        if (cookie != null && !cookie.isEmpty()) req.append("Cookie: ").append(cookie).append("\r\n");
        req.append("\r\n");
        synchronized (writeLock) {
            out.write(req.toString().getBytes(UTF8));
            out.flush();
        }

        String status = readLine(in);
        if (status == null || status.indexOf("101") < 0) {
            throw new IOException("websocket 握手失败: " + status);
        }
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) { /* 跳过响应头 */ }

        reader = new Thread(new Runnable() {
            @Override public void run() { readLoop(); }
        }, "dsh-ws");
        reader.setDaemon(true);
        reader.start();
    }

    public void sendText(String text) throws IOException {
        sendFrame(OP_TEXT, text.getBytes(UTF8));
    }

    public void close() {
        if (closed) return;
        closed = true;
        try { sendFrame(OP_CLOSE, new byte[0]); } catch (Throwable ignored) {}
        try { socket.close(); } catch (Throwable ignored) {}
    }

    public boolean isClosed() { return closed || socket.isClosed(); }

    // ── 内部 ───────────────────────────────────────────────────────────

    private void readLoop() {
        ByteArrayOutputStream fragment = null;
        int fragmentOp = 0;
        try {
            while (!closed) {
                int b0 = readByte(in);
                int b1 = readByte(in);
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = ((long) readByte(in) << 8) | readByte(in);
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
                }
                byte[] mask = masked ? readN(in, 4) : null;
                byte[] payload = readN(in, (int) len);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                }
                if (opcode == OP_PING) { sendFrame(OP_PONG, payload); continue; }
                if (opcode == OP_PONG) continue;
                if (opcode == OP_CLOSE) break;
                if (opcode == 0x0) {
                    if (fragment != null) fragment.write(payload);
                } else {
                    fragment = new ByteArrayOutputStream();
                    fragmentOp = opcode;
                    fragment.write(payload);
                }
                if (fin && fragment != null) {
                    byte[] message = fragment.toByteArray();
                    fragment = null;
                    if (fragmentOp == OP_TEXT && listener != null) {
                        listener.onText(new String(message, UTF8));
                    }
                }
            }
        } catch (Throwable t) {
            if (!closed && listener != null) listener.onFailure(String.valueOf(t.getMessage()));
        } finally {
            if (!closed && listener != null && !socket.isClosed()) listener.onFailure("websocket closed");
            closed = true;
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        if (socket.isClosed()) throw new IOException("websocket closed");
        byte[] body = payload == null ? new byte[0] : payload;
        ByteArrayOutputStream frame = new ByteArrayOutputStream(body.length + 14);
        frame.write(0x80 | opcode);
        int len = body.length;
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len < 65536) {
            frame.write(0x80 | 126);
            frame.write((len >>> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) frame.write((int) ((((long) len) >>> (8 * i)) & 0xFF));
        }
        frame.write(mask, 0, 4);
        for (int i = 0; i < len; i++) frame.write(body[i] ^ mask[i & 3]);
        synchronized (writeLock) {
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) throw new EOFException("websocket eof");
        return v;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("websocket eof");
            off += r;
        }
        return buf;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) return line.size() == 0 ? null : new String(line.toByteArray(), UTF8);
            if (prev == '\r' && c == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), UTF8);
            }
            line.write(c);
            prev = c;
        }
    }
}
