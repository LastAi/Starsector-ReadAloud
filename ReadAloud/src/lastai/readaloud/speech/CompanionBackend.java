package lastai.readaloud.speech;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Talks to the companion app over HTTP on 127.0.0.1 (see ReadAloudAI/companion/PROTOCOL.md).
 *
 * Only java.net plus the java.io stream classes the game allows are used: mods may
 * not touch files, but plain sockets are fine.
 */
public final class CompanionBackend implements SpeechBackend {
    /** Sent on every request. Browsers can't add it cross-site without permission, so web pages can't drive the app. */
    public static final String HEADER = "X-ReadAloud";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String base;
    private volatile boolean speaking;
    private volatile String engine = "";

    public CompanionBackend(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    @Override
    public String name() {
        return engine.isEmpty() ? "companion" : "companion (" + engine + ")";
    }

    /** Asks the app for its state. Returns null if it isn't running or didn't answer properly. */
    public JSONObject status() {
        try {
            JSONObject s = new JSONObject(request("GET", "/api/status", null, 400, 1500));
            speaking = s.optBoolean("speaking", false);
            engine = s.optString("engine", "");
            return s;
        } catch (Exception e) {
            speaking = false;
            return null;
        }
    }

    /** The engine name from the last answer ("" if none). */
    public String engine() {
        return engine;
    }

    /** The app's engine, voice list, chosen voices and pools (GET /api/voices), or null if it didn't answer. */
    public JSONObject voices() {
        try {
            JSONObject v = new JSONObject(request("GET", "/api/voices", null, 400, 2000));
            return v.optBoolean("ok", false) ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Changes the app's settings (POST /api/settings). True if it took them. */
    public boolean saveSettings(JSONObject body) {
        try {
            return new JSONObject(request("POST", "/api/settings", body.toString(), 400, 5000)).optBoolean("ok", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** True if the app answered and says it can speak right now. */
    public boolean ready() {
        JSONObject s = status();
        return s != null && s.optBoolean("ready", false);
    }

    @Override
    public void speak(SpeechRequest r) throws Exception {
        JSONObject res = new JSONObject(request("POST", "/api/speak", r.toJson().toString(), 400, 5000));
        if (!res.optBoolean("ok", false)) throw new IOException("companion refused: " + res.optString("error", "?"));
        speaking = true;
    }

    @Override
    public void stop() {
        speaking = false;
        try {
            request("POST", "/api/stop", "{}", 400, 1500);
        } catch (Exception ignored) {
            // not running: nothing to stop
        }
    }

    @Override
    public boolean isSpeaking() {
        return speaking;
    }

    /** Refreshes {@link #isSpeaking()} from the app. */
    public void poll() {
        status();
    }

    private String request(String method, String path, String body, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            c.setRequestProperty(HEADER, "1");
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.getBytes(UTF8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = c.getOutputStream();
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400) throw new IOException("HTTP " + code + " from companion: " + text);
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            r.close();
        }
    }
}
