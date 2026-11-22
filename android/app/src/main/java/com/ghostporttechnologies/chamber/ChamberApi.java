package com.ghostporttechnologies.chamber;

import android.webkit.CookieManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Calls to Chamber as the person signed in to the app's WebView. The session is read for each call and never
 * copied, stored or logged. All the server's rules (screen lock, password re-check, who may answer) apply.
 */
final class ChamberApi {

    static final String ORIGIN = "https://chamber.ghostporttechnologies.com";
    private static final int TIMEOUT_MS = 4000;

    static final class Reply {
        final int status;
        final JSONObject body;

        Reply(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }
    }

    private ChamberApi() {}

    static String session() {
        String cookies = CookieManager.getInstance().getCookie(ORIGIN);
        if (cookies == null) return null;
        for (String part : cookies.split(";")) {
            String cookie = part.trim();
            if (cookie.startsWith("chamber_session=") && cookie.length() > "chamber_session=".length()) return cookie;
        }
        return null;
    }

    static Reply get(String path) {
        return call(path, null);
    }

    static Reply post(String path, JSONObject body) {
        return call(path, body);
    }

    /** Status 0: Chamber couldn't be reached. 401: not signed in on this phone. */
    private static Reply call(String path, JSONObject body) {
        String session = session();
        if (session == null) return new Reply(401, new JSONObject());
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ORIGIN + path).openConnection();
            connection.setInstanceFollowRedirects(false); // the session goes to this origin only
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cookie", session);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            return new Reply(status, read(status < 400 ? connection.getInputStream() : connection.getErrorStream()));
        } catch (IOException e) {
            return new Reply(0, new JSONObject());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject read(InputStream stream) throws IOException {
        if (stream == null) return new JSONObject();
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
