package lastai.readaloud.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A stand-in for the companion app that speaks the protocol in companion/PROTOCOL.md. */
final class FakeCompanion implements AutoCloseable {
    final HttpServer server;
    volatile boolean ready = true;
    volatile boolean speaking;
    final List<JSONObject> spoken = new CopyOnWriteArrayList<JSONObject>();
    volatile int stops;
    volatile int missingHeader;
    /** The engine state when not ready ("loading" or "error"), as the real app reports it. */
    volatile String state = "loading";
    volatile String error = "";
    /** What GET /api/voices answers (ok, state, error and engine are filled in). */
    volatile JSONObject voicesView = new JSONObject();
    final List<JSONObject> settings = new CopyOnWriteArrayList<JSONObject>();

    FakeCompanion() throws IOException {
        this(0);
    }

    FakeCompanion(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                try {
                    handleIt(ex);
                } catch (Exception e) {
                    send(ex, 500, "{\"ok\":false,\"error\":\"" + e + "\"}");
                }
            }
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handleIt(HttpExchange ex) throws Exception {
        if (!"1".equals(ex.getRequestHeaders().getFirst("X-ReadAloud"))) {
            missingHeader++;
            send(ex, 403, "{\"ok\":false,\"error\":\"missing header\"}");
            return;
        }
        String path = ex.getRequestURI().getPath();
        String body = read(ex.getRequestBody());
        if (path.equals("/api/status")) {
            send(ex, 200, new JSONObject().put("ready", ready).put("speaking", speaking).put("engine", "fake")
                    .put("state", ready ? "ready" : state).put("error", error).toString());
        } else if (path.equals("/api/voices")) {
            JSONObject v = new JSONObject(voicesView.toString()).put("ok", true).put("state", ready ? "ready" : state)
                    .put("error", error).put("engine", "fake");
            send(ex, 200, v.toString());
        } else if (path.equals("/api/settings")) {
            settings.add(new JSONObject(body));
            send(ex, 200, "{\"ok\":true,\"restarted\":false}");
        } else if (path.equals("/api/speak")) {
            spoken.add(new JSONObject(body));
            speaking = true;
            send(ex, 200, "{\"ok\":true,\"id\":1}");
        } else if (path.equals("/api/stop")) {
            stops++;
            speaking = false;
            send(ex, 200, "{\"ok\":true}");
        } else {
            send(ex, 404, "{\"ok\":false}");
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        OutputStream o = ex.getResponseBody();
        o.write(bytes);
        o.close();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        server.stop(0);
    }

    private boolean closed;
}
