package com.robocam.app;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class ImportActivity extends AppCompatActivity {

    private TextView tvStatus;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            importConfiguration(uri);
                        }
                    }
                });

        setContentView(R.layout.activity_import);

        Button btnImport = findViewById(R.id.btnImport);
        Button btnExport = findViewById(R.id.btnExport);
        tvStatus = findViewById(R.id.tvStatus);

        btnImport.setOnClickListener(v -> openFilePicker());
        btnExport.setOnClickListener(v -> exportConfiguration());
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*"); // use */* not text/xml for better device compatibility
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(Intent.createChooser(intent, "Select XML File"));
    }

    private void exportConfiguration() {
        RobotConfig config = buildDemoConfig();

        String filename = "Exported_" + config.name.replace(" ", "_") + ".xml";

        try {
            // Generate XML content
            String xmlContent = config.toXml();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ use MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/xml");
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(xmlContent.getBytes("UTF-8"));
                        }
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    showSnackbar("Exported to Downloads/" + filename);
                }
            } else {
                // Android 9 and below use direct file write
                File dir = new File(Environment.getExternalStorageDirectory(), "RoboCamConfigs");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(xmlContent.getBytes("UTF-8"));
                }
                showSnackbar("Exported to /RoboCamConfigs/" + filename);
            }
        } catch (Exception e) {
            showSnackbar("Export failed: " + e.getMessage());
        }
    }

    private RobotConfig buildDemoConfig() {
        RobotConfig config = new RobotConfig();
        config.name = "EV3 Researcher";
        config.description = "A wheeled robot.";
        config.startUserProgram = true;
        config.userProgram = "../prjs/MyProject 4/MyProgram.rbf";

        RobotConfig.JoystickConfig j = new RobotConfig.JoystickConfig();
        j.index = 0;
        j.visible = true;
        j.shape = "c";
        j.type = 2;
        RobotConfig.OutputPort p1 = new RobotConfig.OutputPort();
        p1.group = 0;
        p1.layer = 0;
        p1.number = "B";
        RobotConfig.OutputPort p2 = new RobotConfig.OutputPort();
        p2.group = 1;
        p2.layer = 0;
        p2.number = "C";
        j.outputPorts.add(p1);
        j.outputPorts.add(p2);
        config.joysticks.add(j);

        RobotConfig.KeyGroupConfig k = new RobotConfig.KeyGroupConfig();
        k.type = 2;
        k.active = 1;
        k.mailbox = "keys";
        k.incX = 15;
        k.incY = 15;
        k.decX = 50;
        k.decY = 50;
        k.upKeys.add(38);
        k.leftKeys.add(65);
        k.downKeys.add(83);
        k.rightKeys.add(68);
        k.outputPorts.add(p1);
        k.outputPorts.add(p2);
        config.keyGroups.add(k);
        return config;
    }

    private void importConfiguration(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is != null) {
                RobotConfig config = RobotConfig.fromXml(is);
                is.close();

                if (config != null) {
                    String filename = config.name.replace(" ", "_") + "_imported.xml";

                    // Save to internal storage
                    config.saveToInternal(this, filename);

                    // Write to SharedPreferences so MainActivity picks it up automatically on next resume
                    getSharedPreferences("RoboCamPrefs", MODE_PRIVATE)
                            .edit()
                            .putString("last_config_file", filename)
                            .apply();

                    showSnackbar("Import successful: " + config.name + "\nConfig will activate on return");
                    tvStatus.setText("Last imported: " + config.name);
                }
            }
        } catch (Exception e) {
            showSnackbar("Import failed: " + e.getMessage());
        }
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(R.id.importRoot), message, Snackbar.LENGTH_LONG).show();
    }
}
