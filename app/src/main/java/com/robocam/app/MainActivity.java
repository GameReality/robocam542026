package com.robocam.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.ImageReader;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final String PREFS_NAME = "RoboCamPrefs";
    private static final String KEY_LAST_CONFIG = "last_config_file";

    private ImageButton btnStartStop, btnRobotSelect, btnSettings;
    private TextView tvTopStatus, tvBottomStatus;
    private TextureView cameraPreview;

    private ServerService serverService;
    private boolean isBound = false;
    private boolean pendingMjpegSetup = false;
    private ActivityResultLauncher<Intent> robotSettingsLauncher;
    private ActivityResultLauncher<Intent> importLauncher;

    private ConnectivityManager.NetworkCallback networkCallback;

    // Camera2 variables
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ServerService.ServerBinder binder = (ServerService.ServerBinder) service;
            serverService = binder.getService();
            isBound = true;
            
            serverService.setStatusListener(new ServerService.ServerStatusListener() {
                @Override
                public void onServerStarted(int port) {
                    runOnUiThread(() -> {
                        String ip = getWifiIPAddress();
                        tvTopStatus.setText("RoboCam server is working:\n" + "http://" + ip + ":" + port);
                        setBoxStroke(tvTopStatus, Color.parseColor("#4CAF50"));
                        btnStartStop.setBackgroundResource(R.drawable.bg_circle_dark_gray);
                        recreateCameraSessionWithMjpeg();
                    });
                }

                @Override
                public void onServerStopped() {
                    runOnUiThread(() -> {
                        tvTopStatus.setText("RoboCam server is off");
                        setBoxStroke(tvTopStatus, Color.parseColor("#F44336"));
                        btnStartStop.setBackgroundResource(R.drawable.bg_circle_green);
                    });
                }

                @Override
                public void onServerError(String error) {
                    runOnUiThread(() -> {
                        tvTopStatus.setText("Server error: " + error);
                        setBoxStroke(tvTopStatus, Color.parseColor("#F44336"));
                        Toast.makeText(MainActivity.this, "Server error: " + error, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onConnected(String deviceName) {
                    runOnUiThread(() -> {
                        tvBottomStatus.setText("Connected to EV3:\n" + deviceName);
                        setBoxStroke(tvBottomStatus, Color.parseColor("#E91E8C"));
                    });
                }

                @Override
                public void onDisconnected() {
                    runOnUiThread(() -> {
                        tvBottomStatus.setText("Bluetooth: not connected");
                        setBoxStroke(tvBottomStatus, Color.GRAY);
                    });
                }

                @Override
                public void onConnectionFailed(String error) {
                    runOnUiThread(() -> {
                        tvBottomStatus.setText("Connection failed: " + error);
                        setBoxStroke(tvBottomStatus, Color.parseColor("#F44336"));
                    });
                }
            });

            loadLastConfig();
            updateUI();

            if (pendingMjpegSetup && cameraDevice != null) {
                pendingMjpegSetup = false;
                recreateCameraSessionWithMjpeg();
            } else if (serverService.isRunning() && cameraDevice != null) {
                recreateCameraSessionWithMjpeg();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serverService = null;
            isBound = false;
            updateUI();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        robotSettingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String configFile = result.getData().getStringExtra("config_file");
                        if (configFile != null) {
                            saveLastConfig(configFile);
                            loadLastConfig();
                        }
                    }
                });

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // Always reload config when returning from import
                    // because ImportActivity may have saved a new config
                    // to SharedPreferences
                    if (serverService != null) {
                        loadLastConfig();
                    }
                });

        setContentView(R.layout.activity_main);

        tvTopStatus = findViewById(R.id.tvTopStatus);
        tvBottomStatus = findViewById(R.id.tvBottomStatus);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnRobotSelect = findViewById(R.id.btnRobotSelect);
        btnSettings = findViewById(R.id.btnSettings);
        cameraPreview = findViewById(R.id.cameraPreview);

        tvBottomStatus.setText("Bluetooth: not connected");
        setBoxStroke(tvBottomStatus, Color.GRAY);

        btnStartStop.setOnClickListener(v -> toggleServer());
        btnRobotSelect.setOnClickListener(v -> showBluetoothDevicePicker());
        btnSettings.setOnClickListener(this::showSettingsMenu);

        cameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                openCamera();
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        });

        checkAndRequestPermissions();
        registerNetworkCallback();
    }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            
            networkCallback = 
                new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLinkPropertiesChanged(
                        Network network, 
                        LinkProperties linkProperties) {
                    super.onLinkPropertiesChanged(
                        network, linkProperties);
                    // IP address changed - update display
                    runOnUiThread(() -> updateUI());
                }
            };
            
            try {
                cm.registerDefaultNetworkCallback(
                    networkCallback);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S 
                && networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(
                        Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(
                        networkCallback);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            networkCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isBound) {
            // Not bound - bind now, onServiceConnected will
            // call loadLastConfig() and updateUI()
            Intent intent = new Intent(this, ServerService.class);
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } else {
            // Already bound - just refresh the server status UI
            // Do NOT call loadLastConfig() here as it overwrites
            // the Bluetooth connection status display
            updateUI();
        }
        // Restart camera preview if it stopped while backgrounded
        if (cameraDevice == null && cameraPreview.isAvailable()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Only unbind if server is NOT running
        // If server is running, keep the service bound so status callbacks continue working
        if (isBound && (serverService == null || !serverService.isRunning())) {
            unbindService(connection);
            isBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        unregisterNetworkCallback();
        super.onDestroy();
        // Always unbind on destroy
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        // Release camera resources
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraPreview");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) return;
            startBackgroundThread();
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCameraPreviewSession();
                }
                @Override public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); cameraDevice = null; }
                @Override public void onError(@NonNull CameraDevice camera, int error) { camera.close(); cameraDevice = null; }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = cameraPreview.getSurfaceTexture();
            texture.setDefaultBufferSize(cameraPreview.getWidth(), cameraPreview.getHeight());
            Surface previewSurface = new Surface(texture);

            // Get ImageReader from ServerService/MjpegStreamer
            ImageReader mjpegReader = serverService != null ? serverService.getMjpegImageReader() : null;

            if (mjpegReader == null && serverService == null) {
                // Service not bound yet - flag for setup when it connects
                pendingMjpegSetup = true;
            }

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            if (mjpegReader != null) {
                surfaces.add(mjpegReader.getSurface());
            }

            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            if (mjpegReader != null) {
                builder.addTarget(mjpegReader.getSurface());
            }

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) return;
                    captureSession = session;
                    try {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this, "Camera session failed", Toast.LENGTH_SHORT).show();
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void recreateCameraSessionWithMjpeg() {
        if (cameraDevice == null) return;
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        createCameraPreviewSession();
    }

    private void toggleServer() {
        if (serverService != null && serverService.isRunning()) {
            Intent intent = new Intent(this, ServerService.class);
            intent.setAction("STOP");
            startService(intent);
        } else {
            Intent intent = new Intent(this, ServerService.class);
            intent.setAction("START");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        }
    }

    private void updateUI() {
        runOnUiThread(() -> {
            boolean isRunning = serverService != null && serverService.isRunning();
            
            // Top Status Box
            String ip = getWifiIPAddress();
            
            SharedPreferences settingsPrefs = getSharedPreferences("ServerSettings", MODE_PRIVATE);
            int port = settingsPrefs.getInt("port", 8088);

            if (isRunning) {
                tvTopStatus.setText(String.format("RoboCam server is working:\nhttp://%s:%d", ip, port));
                tvTopStatus.setBackgroundResource(R.drawable.status_box_green);
                btnStartStop.setBackgroundResource(R.drawable.bg_circle_green);
                btnStartStop.setColorFilter(Color.parseColor("#4CAF50")); // Green icon
            } else {
                tvTopStatus.setText("RoboCam server is off");
                tvTopStatus.setBackgroundResource(R.drawable.status_box_green);
                btnStartStop.setBackgroundResource(R.drawable.bg_circle_green);
                btnStartStop.setColorFilter(Color.parseColor("#4CAF50")); // Green icon
            }

            // Bottom Status Box (Simulating the text from the image)
            // In a real scenario, this would check if EV3 is actually connected
            tvBottomStatus.setText("EV3 is not connected:\nEV3 Researcher");
            btnRobotSelect.setColorFilter(Color.parseColor("#E91E8C")); // Magenta icon
            btnSettings.setColorFilter(Color.WHITE); // White icon for gray button
        });
    }

    private void setBoxStroke(View v, int color) {
        android.graphics.drawable.Drawable background = v.getBackground();
        if (background instanceof GradientDrawable) {
            ((GradientDrawable) background).setStroke(dpToPx(3), color);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void showBluetoothDevicePicker() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Enable Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestPermissions();
            return;
        }
        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
        List<String> names = new ArrayList<>();
        for (BluetoothDevice d : pairedDevices) names.add(d.getName() + "\n" + d.getAddress());
        
        new AlertDialog.Builder(this)
                .setTitle("Select EV3")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    String line = names.get(which);
                    String address = line.split("\n")[1];
                    String name = line.split("\n")[0];
                    
                    if (serverService != null) {
                        serverService.connectToEV3(address);
                    }
                    
                    // Update bottom status immediately to show connecting
                    tvBottomStatus.setText("Connecting to: " + name);
                    setBoxStroke(tvBottomStatus, Color.parseColor("#FF9800")); // orange = connecting

                }).show();
    }

    private void showSettingsMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenu().add("Robot settings");
        popup.getMenu().add("Server settings");
        popup.getMenu().add("Global settings");
        popup.getMenu().add("Import");
        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Robot settings")) {
                robotSettingsLauncher.launch(new Intent(this, EV3SettingsActivity.class));
            } else if (title.equals("Server settings")) {
                startActivity(new Intent(this, ServerSettingsActivity.class));
            } else if (title.equals("Global settings")) {
                startActivity(new Intent(this, GlobalSettingsActivity.class));
            } else if (title.equals("Import")) {
                importLauncher.launch(new Intent(this, ImportActivity.class));
            }
            return true;
        });
        popup.show();
    }

    private void loadLastConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastConfigFile = prefs.getString(KEY_LAST_CONFIG, null);
        
        if (lastConfigFile == null || serverService == null) return;
        
        try {
            // Load XML config file from internal storage
            File configFile = new File(getFilesDir(), lastConfigFile);
            if (!configFile.exists()) return;
            
            FileInputStream fis = new FileInputStream(configFile);
            RobotConfig config = RobotConfig.fromXml(fis);
            fis.close();
            
            if (config != null) {
                serverService.setActiveConfig(config);
                saveLastConfig(lastConfigFile); // confirm persistence
                
                // Update bottom status to show loaded config name
                runOnUiThread(() -> {
                    tvBottomStatus.setText("Config loaded: " + config.name + "\nBluetooth: not connected");
                    setBoxStroke(tvBottomStatus, Color.GRAY);
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            // Config file corrupted or missing - ignore silently
        }
    }

    private void saveLastConfig(String configFileName) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_LAST_CONFIG, configFileName)
            .apply();
    }

    private String getWifiIPAddress() {

        // METHOD 1: Android 12+ - try ALL networks, not just active
        // On Samsung Android 16, getActiveNetwork() may have empty
        // link properties even when WiFi is working
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    // First try the active network
                    Network[] networks = cm.getAllNetworks();
                    for (Network network : networks) {
                        NetworkCapabilities caps = 
                            cm.getNetworkCapabilities(network);
                        if (caps == null) continue;
                        
                        // Only consider WiFi or ethernet networks
                        // Skip mobile data, VPN, etc.
                        if (!caps.hasTransport(
                                NetworkCapabilities.TRANSPORT_WIFI) &&
                            !caps.hasTransport(
                                NetworkCapabilities.TRANSPORT_ETHERNET))
                            continue;
                        
                        LinkProperties lp = 
                            cm.getLinkProperties(network);
                        if (lp == null) continue;
                        
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress addr = la.getAddress();
                            
                            if (!(addr instanceof Inet4Address)) 
                                continue;
                            if (addr.isLoopbackAddress()) continue;
                            if (addr.isLinkLocalAddress()) continue;
                            if (addr.isMulticastAddress()) continue;
                            
                            int prefixLen = la.getPrefixLength();
                            if (prefixLen < 8 || prefixLen > 30) 
                                continue;
                            
                            String hostAddr = addr.getHostAddress();
                            if (hostAddr == null) continue;
                            if (hostAddr.endsWith(".255")) continue;
                            if (hostAddr.endsWith(".0")) continue;
                            
                            return hostAddr;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // METHOD 2: Android 9-11 - WifiManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                WifiManager wifiManager = (WifiManager)
                    getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null && 
                        wifiManager.isWifiEnabled()) {
                    WifiInfo wifiInfo = 
                        wifiManager.getConnectionInfo();
                    int ipInt = wifiInfo.getIpAddress();
                    if (ipInt != 0) {
                        return String.format(Locale.US,
                            "%d.%d.%d.%d",
                            (ipInt & 0xff),
                            (ipInt >> 8 & 0xff),
                            (ipInt >> 16 & 0xff),
                            (ipInt >> 24 & 0xff));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // METHOD 3: NetworkInterface fallback - all versions
        try {
            Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface intf = 
                        interfaces.nextElement();
                    if (!intf.isUp() || intf.isLoopback()) 
                        continue;
                    String ifName = 
                        intf.getName().toLowerCase(Locale.US);
                    if (!ifName.startsWith("wlan") &&
                        !ifName.startsWith("eth") &&
                        !ifName.startsWith("ap")) continue;
                    
                    Enumeration<InetAddress> addrs = 
                        intf.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (addr.isLoopbackAddress()) continue;
                        if (addr.isLinkLocalAddress()) continue;
                        if (addr.isMulticastAddress()) continue;
                        if (!(addr instanceof Inet4Address)) continue;
                        String hostAddr = addr.getHostAddress();
                        if (hostAddr == null) continue;
                        if (hostAddr.endsWith(".255")) continue;
                        if (hostAddr.endsWith(".0")) continue;
                        return hostAddr;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return "0.0.0.0";
    }

    private void checkAndRequestPermissions() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        
        // CAMERA is a dangerous permission - must request at runtime
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listPermissionsNeeded.add(Manifest.permission.CAMERA);
        }
        
        // Bluetooth permissions only needed on Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        
        // Do NOT add ACCESS_WIFI_STATE here - it is a normal permission declared in manifest only, not a runtime permission
        
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean cameraGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.CAMERA) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    cameraGranted = true;
                }
            }
            if (cameraGranted && cameraDevice == null) {
                if (cameraPreview.isAvailable()) {
                    openCamera();
                }
            }
        }
    }
}
