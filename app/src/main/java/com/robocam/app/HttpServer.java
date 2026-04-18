package com.robocam.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {

    private static final String TAG      = "HttpServer";
    private static final String BOUNDARY = "mjpegframe";

    private final Context context;
    private final Map<String, String> activeSessions = new HashMap<>();
    private MjpegStreamer       mjpegStreamer;
    private BluetoothEV3Manager ev3Manager;
    private RobotConfig         activeConfig;

    public HttpServer(Context context, int port) {
        super("0.0.0.0", port);
        this.context = context;
    }

    public boolean startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void setMjpegStreamer(MjpegStreamer streamer) { this.mjpegStreamer = streamer; }
    public void setEv3Manager(BluetoothEV3Manager manager) { this.ev3Manager = manager; }
    public void setActiveConfig(RobotConfig config) { this.activeConfig = config; }

    @Override
    public Response serve(IHTTPSession session) {
        String uri    = session.getUri();
        Method method = session.getMethod();

        if (Method.GET.equals(method) && "/cam".equals(uri))
            return handleCameraStream();

        if (Method.POST.equals(method)) {
            switch (uri) {
                case "/login/driver":    return handleLogin(session, "driver");
                case "/login/spectator": return handleLogin(session, "spectator");
                case "/joystick":        return handleJoystick(session);
                case "/keys":            return handleKeys(session);
            }
        }
        return serveStaticFile(uri);
    }

    // --- MJPEG stream -------------------------------------------------------

    /**
     * Each connected client gets its own NanoHTTPD thread. That thread blocks
     * in waitForNextFrame() until the camera delivers a new JPEG, then writes
     * it directly to the HTTP chunked response. The camera thread never blocks.
     *
     * When the client disconnects NanoHTTPD's socket write throws IOException;
     * NanoHTTPD calls close() on our InputStream in its finally block, which
     * sets closed=true and lets the streaming thread exit.
     */
    private Response handleCameraStream() {
        final MjpegStreamer streamer = mjpegStreamer;
        if (streamer == null)
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT, "Camera not ready");

        InputStream mjpegStream = new InputStream() {
            private byte[] pending       = null;
            private int    pendingOffset = 0;
            private int    lastSeq       = 0;
            private volatile boolean closed = false;

            private byte[] nextChunk() {
                while (!closed) {
                    try {
                        MjpegStreamer.FrameResult result =
                                streamer.waitForNextFrame(lastSeq, 1_000);
                        if (result == null) continue; // timeout, retry
                        lastSeq = result.seq;

                        String hdrStr = "--" + BOUNDARY + "\r\n"
                                + "Content-Type: image/jpeg\r\n"
                                + "Content-Length: " + result.data.length + "\r\n\r\n";
                        byte[] hdr     = hdrStr.getBytes("UTF-8");
                        byte[] trailer = "\r\n".getBytes("UTF-8");

                        byte[] chunk = new byte[hdr.length + result.data.length + trailer.length];
                        System.arraycopy(hdr,        0, chunk, 0,                                hdr.length);
                        System.arraycopy(result.data, 0, chunk, hdr.length,                      result.data.length);
                        System.arraycopy(trailer,     0, chunk, hdr.length + result.data.length, trailer.length);
                        return chunk;

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        closed = true;
                    } catch (Exception e) {
                        Log.e(TAG, "MJPEG stream error", e);
                        closed = true;
                    }
                }
                return null;
            }

            @Override public int read() throws IOException {
                byte[] b = new byte[1];
                int n = read(b, 0, 1);
                return n == -1 ? -1 : (b[0] & 0xFF);
            }

            @Override public int read(byte[] buf, int off, int len) throws IOException {
                if (closed && (pending == null || pendingOffset >= pending.length))
                    return -1;
                if (pending == null || pendingOffset >= pending.length) {
                    pending = nextChunk();
                    pendingOffset = 0;
                    if (pending == null) return -1;
                }
                int n = Math.min(len, pending.length - pendingOffset);
                System.arraycopy(pending, pendingOffset, buf, off, n);
                pendingOffset += n;
                return n;
            }

            @Override public void close() { closed = true; }
        };

        Response r = newChunkedResponse(Response.Status.OK,
                "multipart/x-mixed-replace; boundary=" + BOUNDARY, mjpegStream);
        r.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        r.addHeader("Pragma",        "no-cache");
        r.addHeader("Expires",       "0");
        r.addHeader("Connection",    "keep-alive");
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    // --- Login --------------------------------------------------------------

    private Response handleLogin(IHTTPSession session, String role) {
        try {
            String postData = getBodyData(session);
            String username = "", password = "";
            if (postData != null) {
                JSONObject json = new JSONObject(postData);
                username = json.optString("username", "");
                password = json.optString("password", "");
            }
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences("ServerSettings",
                            android.content.Context.MODE_PRIVATE);
            String targetUser = "driver".equals(role)
                    ? prefs.getString("driver_name", "admin")
                    : prefs.getString("spectator_name", "guest");
            String targetPass = "driver".equals(role)
                    ? prefs.getString("driver_password", "123")
                    : prefs.getString("spectator_password", "123");

            if (!username.equals(targetUser) || !password.equals(targetPass))
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED,
                        "application/json",
                        "{\"status\":\"error\",\"message\":\"Invalid credentials\"}");

            String sid = UUID.randomUUID().toString();
            activeSessions.put(sid, role);
            JSONObject resp = new JSONObject();
            resp.put("status", "success");
            resp.put("sessionId", sid);
            resp.put("role", role);
            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", resp.toString());
        } catch (JSONException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Login error");
        }
    }

    // --- Joystick / keys ----------------------------------------------------

    private Response handleJoystick(IHTTPSession session) {
        try {
            String body = getBodyData(session);
            if (body == null) return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing body");
            JSONObject json = new JSONObject(body);
            int index = json.getInt("index");
            double x = json.getDouble("x"), y = json.getDouble("y");
            if (ev3Manager != null && activeConfig != null)
                for (RobotConfig.JoystickConfig j : activeConfig.joysticks)
                    if (j.index == index) { ev3Manager.processJoystickInput(x, y, j); break; }
            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", "{\"status\":\"received\"}");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                    "application/json", "{\"error\":\"Invalid JSON\"}");
        }
    }

    private Response handleKeys(IHTTPSession session) {
        try {
            String body = getBodyData(session);
            if (body == null) return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing body");
            JSONObject json = new JSONObject(body);
            double x = json.optDouble("x", 0), y = json.optDouble("y", 0);
            if (ev3Manager != null && activeConfig != null)
                for (RobotConfig.KeyGroupConfig kg : activeConfig.keyGroups)
                    if (kg.active == 1) {
                        if (kg.mailbox != null && !kg.mailbox.isEmpty())
                            ev3Manager.sendMailboxMessage(kg.mailbox, "x:" + x + ",y:" + y);
                        else
                            ev3Manager.processKeyInput(x, y, kg);
                        break;
                    }
            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", "{\"status\":\"received\"}");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                    "application/json", "{\"error\":\"Invalid JSON\"}");
        }
    }

    // --- Static files -------------------------------------------------------

    private Response serveStaticFile(String uri) {
        if (uri.equals("/") || uri.equals("/def") || uri.equals("/def/"))
            uri = "/def/index.html";
        try {
            InputStream is = context.getAssets().open("web" + uri);
            return newChunkedResponse(Response.Status.OK, getMimeType(uri), is);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT, "404 Not Found");
        }
    }

    private static String getMimeType(String uri) {
        if (uri.endsWith(".html")) return "text/html; charset=UTF-8";
        if (uri.endsWith(".js"))   return "application/javascript; charset=UTF-8";
        if (uri.endsWith(".css"))  return "text/css";
        if (uri.endsWith(".png"))  return "image/png";
        if (uri.endsWith(".jpg") || uri.endsWith(".jpeg")) return "image/jpeg";
        if (uri.endsWith(".ico"))  return "image/x-icon";
        if (uri.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private String getBodyData(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
            return files.get("postData");
        } catch (IOException | ResponseException e) {
            return null;
        }
    }
}
