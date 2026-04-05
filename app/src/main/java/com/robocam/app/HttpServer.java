package com.robocam.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {

    private static final String TAG = "HttpServer";
    private final Context context;
    private final Map<String, String> activeSessions = new HashMap<>(); // Session ID -> Role (driver/spectator)
    private MjpegStreamer mjpegStreamer;
    private BluetoothEV3Manager ev3Manager;
    private RobotConfig activeConfig;

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

    public void setMjpegStreamer(MjpegStreamer streamer) {
        this.mjpegStreamer = streamer;
    }

    public void setEv3Manager(BluetoothEV3Manager manager) {
        this.ev3Manager = manager;
    }

    public void setActiveConfig(RobotConfig config) {
        this.activeConfig = config;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (Method.POST.equals(method)) {
            if ("/login/driver".equals(uri)) {
                return handleLogin(session, "driver");
            } else if ("/login/spectator".equals(uri)) {
                return handleLogin(session, "spectator");
            } else if ("/joystick".equals(uri)) {
                return handleJoystick(session);
            } else if ("/keys".equals(uri)) {
                return handleKeys(session);
            }
        } else if (Method.GET.equals(method)) {
            if ("/cam".equals(uri)) {
                return handleCameraStream();
            }
        }

        return serveStaticFile(uri);
    }

    private Response handleCameraStream() {
        if (mjpegStreamer == null) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Camera not initialized");
        }
        
        PipedOutputStream pipedOutputStream = new PipedOutputStream();
        PipedInputStream pipedInputStream;
        try {
            pipedInputStream = new PipedInputStream(pipedOutputStream, 65536);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Stream error");
        }
        
        mjpegStreamer.addOutputStream(pipedOutputStream);
        
        Response response = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace;boundary=frame", pipedInputStream);
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.addHeader("Pragma", "no-cache");
        response.addHeader("Expires", "0");
        response.addHeader("Connection", "keep-alive");
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    private Response handleLogin(IHTTPSession session, String role) {
        try {
            String postData = getBodyData(session);
            String username = "";
            String password = "";
            
            if (postData != null) {
                JSONObject json = new JSONObject(postData);
                username = json.optString("username", "");
                password = json.optString("password", "");
            }

            android.content.SharedPreferences prefs = context.getSharedPreferences("ServerSettings", android.content.Context.MODE_PRIVATE);
            
            String targetUser, targetPass;
            if ("driver".equals(role)) {
                targetUser = prefs.getString("driver_name", "admin");
                targetPass = prefs.getString("driver_password", "123");
            } else {
                targetUser = prefs.getString("spectator_name", "guest");
                targetPass = prefs.getString("spectator_password", "123");
            }

            if (!username.equals(targetUser) || !password.equals(targetPass)) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"status\":\"error\",\"message\":\"Invalid credentials\"}");
            }

            String sessionId = UUID.randomUUID().toString();
            activeSessions.put(sessionId, role);

            JSONObject responseJson = new JSONObject();
            responseJson.put("status", "success");
            responseJson.put("sessionId", sessionId);
            responseJson.put("role", role);

            return newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString());
        } catch (JSONException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Login Error");
        }
    }

    private Response handleJoystick(IHTTPSession session) {
        try {
            String postData = getBodyData(session);
            if (postData == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing Body");

            JSONObject json = new JSONObject(postData);
            int index = json.getInt("index");
            double x = json.getDouble("x");
            double y = json.getDouble("y");

            if (ev3Manager != null && activeConfig != null) {
                RobotConfig.JoystickConfig joyConfig = null;
                for (RobotConfig.JoystickConfig j : activeConfig.joysticks) {
                    if (j.index == index) {
                        joyConfig = j;
                        break;
                    }
                }
                if (joyConfig != null) {
                    ev3Manager.processJoystickInput(x, y, joyConfig);
                }
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"received\"}");
        } catch (Exception e) {
            Log.e(TAG, "Error handling joystick", e);
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid JSON\"}");
        }
    }

    private Response handleKeys(IHTTPSession session) {
        try {
            String postData = getBodyData(session);
            if (postData == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing Body");

            JSONObject json = new JSONObject(postData);
            double x = json.optDouble("x", 0);
            double y = json.optDouble("y", 0);

            if (ev3Manager != null && activeConfig != null) {
                RobotConfig.KeyGroupConfig kgConfig = null;
                for (RobotConfig.KeyGroupConfig kg : activeConfig.keyGroups) {
                    if (kg.active == 1) {
                        kgConfig = kg;
                        break;
                    }
                }

                if (kgConfig != null) {
                    if (kgConfig.mailbox != null && !kgConfig.mailbox.isEmpty()) {
                        ev3Manager.sendMailboxMessage(kgConfig.mailbox, "x:" + x + ",y:" + y);
                    } else {
                        ev3Manager.processKeyInput(x, y, kgConfig);
                    }
                }
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"received\"}");
        } catch (Exception e) {
            Log.e(TAG, "Error handling keys", e);
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid JSON\"}");
        }
    }

    private boolean isAuthenticated(IHTTPSession session, String requiredRole) {
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String sessionId = authHeader.substring(7);
            String role = activeSessions.get(sessionId);
            return role != null && (requiredRole == null || requiredRole.equals(role) || "driver".equals(role));
        }
        return false;
    }

    private String getBodyData(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
            return files.get("postData");
        } catch (IOException | ResponseException e) {
            Log.e(TAG, "Error parsing body", e);
            return null;
        }
    }

    private Response serveStaticFile(String uri) {
        if (uri.equals("/") || uri.equals("/def") || uri.equals("/def/")) {
            uri = "/def/index.html";
        }

        String path = "web" + uri;
        AssetManager assetManager = context.getAssets();
        try {
            InputStream inputStream = assetManager.open(path);
            return newChunkedResponse(Response.Status.OK, getLocalMimeType(uri), inputStream);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
        }
    }

    private String getLocalMimeType(String uri) {
        if (uri.endsWith(".html")) return "text/html";
        if (uri.endsWith(".js")) return "application/javascript";
        if (uri.endsWith(".css")) return "text/css";
        if (uri.endsWith(".png")) return "image/png";
        if (uri.endsWith(".jpg") || uri.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }
}
