package com.robocam.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;

public class ServerService extends Service {
    private static final String CHANNEL_ID = "ServerServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private HttpServer httpServer;
    private MjpegStreamer mjpegStreamer;
    private BluetoothEV3Manager ev3Manager;
    private RobotConfig activeConfig;
    
    private final IBinder binder = new ServerBinder();
    private boolean isRunning = false;
    private ServerStatusListener statusListener;

    public interface ServerStatusListener {
        void onServerStarted(int port);
        void onServerStopped();
        void onServerError(String error);
        void onConnected(String deviceName);
        void onDisconnected();
        void onConnectionFailed(String error);
    }

    public void setStatusListener(ServerStatusListener listener) {
        this.statusListener = listener;
    }

    public class ServerBinder extends Binder {
        ServerService getService() {
            return ServerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        createDefaultConfigIfNeeded();
    }

    private void createDefaultConfigIfNeeded() {
        SharedPreferences prefs = getSharedPreferences("RoboCamPrefs", MODE_PRIVATE);
        String lastConfig = prefs.getString("last_config_file", null);
        if (lastConfig != null) return; // already has a config
        
        // Create the default EV3 Researcher config
        RobotConfig config = new RobotConfig();
        config.name = "EV3 Researcher";
        config.description = "A simple wheeled robot with a controlled phone holder.";
        config.startUserProgram = true;
        config.userProgram = "../prjs/MyProject 4/MyProgram.rbf";
        
        // Joystick 0 - drives wheels B and C (tank steering)
        RobotConfig.JoystickConfig j0 = new RobotConfig.JoystickConfig();
        j0.index = 0; j0.visible = true; 
        j0.shape = "c"; j0.type = 2;
        RobotConfig.OutputPort j0p1 = new RobotConfig.OutputPort();
        j0p1.group = 0; j0p1.layer = 0; j0p1.number = "B";
        RobotConfig.OutputPort j0p2 = new RobotConfig.OutputPort();
        j0p2.group = 1; j0p2.layer = 0; j0p2.number = "C";
        j0.outputPorts.add(j0p1);
        j0.outputPorts.add(j0p2);
        config.joysticks.add(j0);
        
        // Joystick 1 - controls phone holder motor A
        RobotConfig.JoystickConfig j1 = new RobotConfig.JoystickConfig();
        j1.index = 1; j1.visible = true;
        j1.shape = "c"; j1.type = 2;
        RobotConfig.OutputPort j1p1 = new RobotConfig.OutputPort();
        j1p1.group = 1; j1p1.layer = 0; j1p1.number = "A";
        // JoystickType=1, Power=50, Invert=1 for the tilt motor
        j1p1.joystickType = 1;
        j1p1.power = 50;
        j1p1.invert = 1;
        j1.outputPorts.add(j1p1);
        config.joysticks.add(j1);
        
        // KeyGroup - WASD keyboard control with mailbox "keys"
        RobotConfig.KeyGroupConfig kg = new RobotConfig.KeyGroupConfig();
        kg.type = 2; kg.active = 1; kg.mailbox = "keys";
        kg.incX = 15; kg.incY = 15; kg.decX = 50; kg.decY = 50;
        kg.upKeys.add(38); kg.upKeys.add(87);   // up, W
        kg.leftKeys.add(65); kg.leftKeys.add(37); // A, left
        kg.downKeys.add(83); kg.downKeys.add(40); // S, down
        kg.rightKeys.add(68); kg.rightKeys.add(39); // D, right
        kg.outputPorts.add(j0p1);
        kg.outputPorts.add(j0p2);
        config.keyGroups.add(kg);
        
        try {
            String filename = "EV3_Researcher.xml";
            config.saveToInternal(this, filename);
            prefs.edit()
                .putString("last_config_file", filename)
                .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setActiveConfig(RobotConfig config) {
        this.activeConfig = config;
        if (httpServer != null) {
            httpServer.setActiveConfig(config);
        }
        if (ev3Manager != null) {
            ev3Manager.setActiveConfig(config);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if ("START".equals(action)) {
            startServer();
        } else if ("STOP".equals(action)) {
            stopServer();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startServer() {
        if (isRunning) return;

        ev3Manager = new BluetoothEV3Manager();
        ev3Manager.setActiveConfig(activeConfig);
        ev3Manager.setConnectionListener(new BluetoothEV3Manager.ConnectionListener() {
            @Override
            public void onConnected(String deviceName) {
                if (statusListener != null) statusListener.onConnected(deviceName);
            }

            @Override
            public void onDisconnected() {
                if (statusListener != null) statusListener.onDisconnected();
            }

            @Override
            public void onConnectionFailed(String error) {
                if (statusListener != null) statusListener.onConnectionFailed(error);
            }
        });
        ev3Manager.connectPaired(); // Auto-connect to paired EV3

        mjpegStreamer = new MjpegStreamer(this);
        
        SharedPreferences settingsPrefs = getSharedPreferences("ServerSettings", MODE_PRIVATE);
        // On older Android versions (like Android 9), ensure defaults are explicitly set
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            SharedPreferences.Editor editor = settingsPrefs.edit();
            boolean changed = false;
            if (!settingsPrefs.contains("port")) { editor.putInt("port", 8088); changed = true; }
            if (!settingsPrefs.contains("driver_name")) { editor.putString("driver_name", "admin"); changed = true; }
            if (!settingsPrefs.contains("driver_password")) { editor.putString("driver_password", "123"); changed = true; }
            if (!settingsPrefs.contains("spectator_name")) { editor.putString("spectator_name", "guest"); changed = true; }
            if (!settingsPrefs.contains("spectator_password")) { editor.putString("spectator_password", "123"); changed = true; }
            if (changed) editor.apply();
        }
        int port = settingsPrefs.getInt("port", 8088);

        httpServer = new HttpServer(this, port);
        httpServer.setMjpegStreamer(mjpegStreamer);
        httpServer.setEv3Manager(ev3Manager);
        httpServer.setActiveConfig(activeConfig);
        
        try {
            httpServer.start();
            isRunning = true;
            
            Notification notification = getNotification("Server is working");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            if (statusListener != null) {
                statusListener.onServerStarted(port);
            }
        } catch (IOException e) {
            Log.e("ServerService", "Failed to start server", e);
            isRunning = false;
            if (statusListener != null) {
                statusListener.onServerError(e.getMessage());
            }
        }
    }

    private void stopServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        if (mjpegStreamer != null) {
            mjpegStreamer.stop();
            mjpegStreamer = null;
        }
        if (ev3Manager != null) {
            ev3Manager.disconnect();
            ev3Manager = null;
        }
        isRunning = false;
        if (statusListener != null) {
            statusListener.onServerStopped();
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public ImageReader getMjpegImageReader() {
        if (mjpegStreamer != null) {
            return mjpegStreamer.getImageReader();
        }
        return null;
    }

    public void connectToEV3(String bluetoothAddress) {
        if (ev3Manager != null) {
            ev3Manager.connect(bluetoothAddress);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "RoboCam Server Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification getNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RoboCam Server")
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }
}
