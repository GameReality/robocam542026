package com.robocam.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;

public class EV3SettingsActivity extends AppCompatActivity {

    private EditText etName, etDescription, etUserProgram;
    private CheckBox cbStartUserProgram;
    private LinearLayout containerJoysticks, containerKeyGroups;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ev3_settings);

        etName = findViewById(R.id.etName);
        etDescription = findViewById(R.id.etDescription);
        etUserProgram = findViewById(R.id.etUserProgram);
        cbStartUserProgram = findViewById(R.id.cbStartUserProgram);
        containerJoysticks = findViewById(R.id.containerJoysticks);
        containerKeyGroups = findViewById(R.id.containerKeyGroups);
        Button btnAddJoystick = findViewById(R.id.btnAddJoystick);
        Button btnAddKeyGroup = findViewById(R.id.btnAddKeyGroup);
        Button btnSaveConfig = findViewById(R.id.btnSaveConfig);

        btnAddJoystick.setOnClickListener(v -> addJoystickView());
        btnAddKeyGroup.setOnClickListener(v -> addKeyGroupView());
        btnSaveConfig.setOnClickListener(v -> saveConfiguration());
    }

    private void addJoystickView() {
        View view = LayoutInflater.from(this).inflate(R.layout.item_joystick_config, containerJoysticks, false);
        view.findViewById(R.id.btnRemoveJoystick).setOnClickListener(v -> containerJoysticks.removeView(view));
        containerJoysticks.addView(view);
    }

    private void addKeyGroupView() {
        View view = LayoutInflater.from(this).inflate(R.layout.item_keygroup_config, containerKeyGroups, false);
        view.findViewById(R.id.btnRemoveKeyGroup).setOnClickListener(v -> containerKeyGroups.removeView(view));
        containerKeyGroups.addView(view);
    }

    private int safeParseInt(EditText field, int defaultValue) {
        try {
            String text = field.getText().toString().trim();
            if (text.isEmpty()) return defaultValue;
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void saveConfiguration() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Robot name is required", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            RobotConfig config = new RobotConfig();
            config.name = name;
            config.description = etDescription.getText().toString();
            config.startUserProgram = cbStartUserProgram.isChecked();
            config.userProgram = etUserProgram.getText().toString();

            // Parse Joysticks
            for (int i = 0; i < containerJoysticks.getChildCount(); i++) {
                View v = containerJoysticks.getChildAt(i);
                RobotConfig.JoystickConfig jc = new RobotConfig.JoystickConfig();
                jc.index = safeParseInt((EditText) v.findViewById(R.id.etJoyIndex), 0);
                jc.visible = ((CheckBox) v.findViewById(R.id.cbJoyVisible)).isChecked();
                String shapeText = ((Spinner) v.findViewById(R.id.spJoyShape)).getSelectedItem().toString();
                jc.shape = shapeText.contains("(c)") ? "c" : "a";
                jc.type = safeParseInt((EditText) v.findViewById(R.id.etJoyType), 2);

                if (((CheckBox) v.findViewById(R.id.cbPortA)).isChecked()) jc.outputPorts.add(createPort("A"));
                if (((CheckBox) v.findViewById(R.id.cbPortB)).isChecked()) jc.outputPorts.add(createPort("B"));
                if (((CheckBox) v.findViewById(R.id.cbPortC)).isChecked()) jc.outputPorts.add(createPort("C"));
                if (((CheckBox) v.findViewById(R.id.cbPortD)).isChecked()) jc.outputPorts.add(createPort("D"));

                config.joysticks.add(jc);
            }

            // Parse KeyGroups
            for (int i = 0; i < containerKeyGroups.getChildCount(); i++) {
                View v = containerKeyGroups.getChildAt(i);
                RobotConfig.KeyGroupConfig kg = new RobotConfig.KeyGroupConfig();
                kg.mailbox = ((EditText) v.findViewById(R.id.etMailbox)).getText().toString();
                kg.active = ((CheckBox) v.findViewById(R.id.cbKgActive)).isChecked() ? 1 : 0;
                kg.type = 2; // Default type
                kg.incX = safeParseInt((EditText) v.findViewById(R.id.etIncX), 15);
                kg.incY = safeParseInt((EditText) v.findViewById(R.id.etIncY), 15);
                kg.decX = safeParseInt((EditText) v.findViewById(R.id.etDecX), 50);
                kg.decY = safeParseInt((EditText) v.findViewById(R.id.etDecY), 50);

                // Placeholder key codes
                kg.upKeys.add(38); kg.upKeys.add(87);
                kg.downKeys.add(40); kg.downKeys.add(83);
                kg.leftKeys.add(37); kg.leftKeys.add(65);
                kg.rightKeys.add(39); kg.rightKeys.add(68);

                if (((CheckBox) v.findViewById(R.id.cbPortA)).isChecked()) kg.outputPorts.add(createPort("A"));
                if (((CheckBox) v.findViewById(R.id.cbPortB)).isChecked()) kg.outputPorts.add(createPort("B"));
                if (((CheckBox) v.findViewById(R.id.cbPortC)).isChecked()) kg.outputPorts.add(createPort("C"));
                if (((CheckBox) v.findViewById(R.id.cbPortD)).isChecked()) kg.outputPorts.add(createPort("D"));

                config.keyGroups.add(kg);
            }

            String filename = config.name.replaceAll("\\s+", "_") + ".xml";
            config.saveToInternal(this, filename);
            
            // Return the filename to MainActivity
            Intent resultIntent = new Intent();
            resultIntent.putExtra("config_file", filename);
            setResult(RESULT_OK, resultIntent);

            Toast.makeText(this, "Configuration Saved!", Toast.LENGTH_SHORT).show();
            finish();
        } catch (IOException e) {
            Toast.makeText(this, "Save Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private RobotConfig.OutputPort createPort(String number) {
        RobotConfig.OutputPort port = new RobotConfig.OutputPort();
        port.number = number;
        port.group = 0; // Default
        port.layer = 0; // Default
        return port;
    }
}
