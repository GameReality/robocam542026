package com.robocam.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class ServerSettingsActivity extends AppCompatActivity {

    private EditText etPort, etDriverName, etDriverPassword, etSpectatorName, etSpectatorPassword;
    private CheckBox cbAllowSpectators;
    private SeekBar sbCameraQuality;
    private TextView tvQualityLabel;
    private Button btnSave;

    private static final String PREFS_NAME = "ServerSettings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_settings);

        etPort = findViewById(R.id.etPort);
        etDriverName = findViewById(R.id.etDriverName);
        etDriverPassword = findViewById(R.id.etDriverPassword);
        etSpectatorName = findViewById(R.id.etSpectatorName);
        etSpectatorPassword = findViewById(R.id.etSpectatorPassword);
        cbAllowSpectators = findViewById(R.id.cbAllowSpectators);
        sbCameraQuality = findViewById(R.id.sbCameraQuality);
        tvQualityLabel = findViewById(R.id.tvQualityLabel);
        btnSave = findViewById(R.id.btnSaveServerSettings);

        sbCameraQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvQualityLabel.setText("Camera JPEG Quality: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        loadSettings();

        btnSave.setOnClickListener(v -> saveSettings());
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etPort.setText(String.valueOf(prefs.getInt("port", 8088)));
        etDriverName.setText(prefs.getString("driver_name", "admin"));
        etDriverPassword.setText(prefs.getString("driver_password", "123"));
        etSpectatorName.setText(prefs.getString("spectator_name", "guest"));
        etSpectatorPassword.setText(prefs.getString("spectator_password", "123"));
        cbAllowSpectators.setChecked(prefs.getBoolean("allow_spectators", true));
        int quality = prefs.getInt("camera_quality", 50);
        sbCameraQuality.setProgress(quality);
        tvQualityLabel.setText("Camera JPEG Quality: " + quality + "%");
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        try {
            int port = Integer.parseInt(etPort.getText().toString());
            editor.putInt("port", port);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
            return;
        }

        editor.putString("driver_name", etDriverName.getText().toString());
        editor.putString("driver_password", etDriverPassword.getText().toString());
        editor.putString("spectator_name", etSpectatorName.getText().toString());
        editor.putString("spectator_password", etSpectatorPassword.getText().toString());
        editor.putBoolean("allow_spectators", cbAllowSpectators.isChecked());
        editor.putInt("camera_quality", sbCameraQuality.getProgress());

        editor.apply();
        Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show();
        finish();
    }
}
