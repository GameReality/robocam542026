package com.robocam.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BluetoothEV3Manager {
    private static final String TAG = "BluetoothEV3Manager";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long WATCHDOG_TIMEOUT = 500; // ms

    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private String deviceAddress;
    private boolean isConnected = false;
    private boolean shouldReconnect = false;
    private RobotConfig activeConfig;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());

    private ConnectionListener connectionListener;

    public interface ConnectionListener {
        void onConnected(String deviceName);
        void onDisconnected();
        void onConnectionFailed(String error);
    }

    private final Runnable watchdogRunnable = () -> {
        Log.w(TAG, "Watchdog timeout - stopping all motors");
        stopAllMotors();
    };

    public BluetoothEV3Manager() {
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    public void setActiveConfig(RobotConfig config) {
        this.activeConfig = config;
    }

    private void resetWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable);
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT);
    }

    @SuppressLint("MissingPermission")
    public void connectPaired() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : pairedDevices) {
            if (device.getName() != null && device.getName().toUpperCase().contains("EV3")) {
                connect(device.getAddress());
                return;
            }
        }
    }

    @SuppressLint("MissingPermission")
    public void connect(String address) {
        this.deviceAddress = address;
        this.shouldReconnect = true;
        executor.execute(this::connectInternal);
    }

    @SuppressLint("MissingPermission")
    private void connectInternal() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled() || deviceAddress == null) {
            return;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            outputStream = socket.getOutputStream();
            isConnected = true;
            String deviceName = device.getName();
            Log.d(TAG, "Connected to " + deviceName);
            if (connectionListener != null) {
                connectionListener.onConnected(deviceName);
            }
            startUserProgramIfConfigured();
            resetWatchdog();
        } catch (IOException e) {
            Log.e(TAG, "Connection failed: " + e.getMessage());
            isConnected = false;
            if (connectionListener != null) {
                connectionListener.onConnectionFailed(e.getMessage());
            }
            handleReconnect();
        }
    }

    private void startUserProgramIfConfigured() {
        if (activeConfig == null) return;
        if (!activeConfig.startUserProgram) return;
        if (activeConfig.userProgram == null || activeConfig.userProgram.isEmpty()) return;

        String programPath = activeConfig.userProgram;
        // EV3 system command to start a program
        // opFILE = 0xC0, opPROGRAM_START = 0x03
        // We use the direct command approach for simplicity
        write(buildStartProgramCommand(programPath));
    }

    private byte[] buildStartProgramCommand(String programPath) {
        byte[] pathBytes = programPath.getBytes(StandardCharsets.UTF_8);
        int len = pathBytes.length + 8; // 2 byte length + 2 byte counter + 1 byte type + 1 byte header + 1 byte opcode + path + 1 byte null
        ByteBuffer buffer = ByteBuffer.allocate(len);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) (len - 2));
        buffer.putShort((short) 0x0000); // Counter
        buffer.put((byte) 0x80); // Direct command, no reply
        buffer.put((byte) 0x00); // Local variables
        buffer.put((byte) 0x03); // opPROGRAM_START
        buffer.put((byte) 0x01); // User slot?
        buffer.put(pathBytes);
        buffer.put((byte) 0); // Null terminator
        return buffer.array();
    }

    private void handleReconnect() {
        if (shouldReconnect) {
            mainHandler.postDelayed(() -> executor.execute(this::connectInternal), 5000);
        }
    }

    public void disconnect() {
        shouldReconnect = false;
        isConnected = false;
        watchdogHandler.removeCallbacks(watchdogRunnable);
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket", e);
        }
        if (connectionListener != null) {
            connectionListener.onDisconnected();
        }
    }

    public void processJoystickInput(double x, double y, RobotConfig.JoystickConfig config) {
        if (config == null) return;
        resetWatchdog();
        
        if (x == 0 && y == 0) {
            byte allPorts = 0;
            for (RobotConfig.OutputPort p : config.outputPorts) allPorts |= getPortMask(p.number);
            stopMotors(allPorts);
        } else {
            sendPowerCommands(x, y, config.outputPorts);
        }
    }

    public void processKeyInput(double x, double y, RobotConfig.KeyGroupConfig config) {
        if (config == null) return;
        resetWatchdog();
        sendPowerCommands(x / 100.0, y / 100.0, config.outputPorts);
    }

    private void sendPowerCommands(double x, double y, java.util.List<RobotConfig.OutputPort> outputPorts) {
        int leftPower = (int)((y + x) * 100);
        int rightPower = (int)((y - x) * 100);

        leftPower = Math.max(-100, Math.min(100, leftPower));
        rightPower = Math.max(-100, Math.min(100, rightPower));

        byte leftPorts = 0;
        byte rightPorts = 0;

        for (RobotConfig.OutputPort port : outputPorts) {
            byte mask = getPortMask(port.number);
            if (port.group == 0) leftPorts |= mask;
            else rightPorts |= mask;
        }

        if (leftPorts != 0) sendMotorCommand(leftPorts, leftPower);
        if (rightPorts != 0) sendMotorCommand(rightPorts, rightPower);
    }

    private byte getPortMask(String portLetter) {
        if (portLetter == null) return 0;
        switch (portLetter.toUpperCase()) {
            case "A": return 0x01;
            case "B": return 0x02;
            case "C": return 0x04;
            case "D": return 0x08;
            default: return 0;
        }
    }

    public void sendMotorCommand(byte portBitfield, int power) {
        byte[] command = new byte[] {
                0x0C, 0x00, 0x00, 0x00, (byte)0x80, 0x00, 0x00,
                (byte)0xA4, 0x00, portBitfield, (byte) power,
                (byte)0xA6, 0x00, portBitfield
        };
        write(command);
    }

    public void stopMotors(byte portBitfield) {
        // opOUTPUT_STOP = 0xA3
        byte[] command = new byte[] {
                0x09, 0x00, 0x00, 0x00, (byte)0x80, 0x00, 0x00,
                (byte)0xA3, 0x00, portBitfield, (byte)0x01 // Brake = 1
        };
        write(command);
    }

    public void stopAllMotors() {
        stopMotors((byte) 0x0F); // Ports A+B+C+D
    }

    public void sendMailboxMessage(String mailboxName, String message) {
        try {
            byte[] nameBytes = mailboxName.getBytes("UTF-8");
            byte[] msgBytes = message.getBytes("UTF-8");

            int payloadLength = 1 + (nameBytes.length + 1) + 2 + (msgBytes.length + 1);
            ByteBuffer buffer = ByteBuffer.allocate(payloadLength + 2);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            buffer.putShort((short) payloadLength);
            buffer.put((byte) 0x81);
            buffer.put((byte) 0x9E);
            buffer.put((byte) (nameBytes.length + 1));
            buffer.put(nameBytes);
            buffer.put((byte) 0);
            buffer.putShort((short) (msgBytes.length + 1));
            buffer.put(msgBytes);
            buffer.put((byte) 0);

            write(buffer.array());
        } catch (IOException e) {
            Log.e(TAG, "Error sending mailbox message", e);
        }
    }

    private synchronized void write(byte[] data) {
        if (!isConnected || outputStream == null) return;
        try {
            outputStream.write(data);
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Write failed", e);
            isConnected = false;
            handleReconnect();
        }
    }

    public boolean isConnected() {
        return isConnected;
    }
}