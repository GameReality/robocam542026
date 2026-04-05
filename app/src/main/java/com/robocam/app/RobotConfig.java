package com.robocam.app;

import android.content.Context;
import android.os.Environment;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class RobotConfig {
    public String name;
    public String description;
    public boolean startUserProgram;
    public String userProgram;

    public List<JoystickConfig> joysticks = new ArrayList<>();
    public List<KeyGroupConfig> keyGroups = new ArrayList<>();

    public static class JoystickConfig {
        public int index;
        public boolean visible;
        public String shape;
        public int type;
        public List<OutputPort> outputPorts = new ArrayList<>();
    }

    public static class KeyGroupConfig {
        public int type;
        public int active;
        public String mailbox;
        public int incX, incY, decX, decY;
        public List<Integer> upKeys = new ArrayList<>();
        public List<Integer> leftKeys = new ArrayList<>();
        public List<Integer> downKeys = new ArrayList<>();
        public List<Integer> rightKeys = new ArrayList<>();
        public List<OutputPort> outputPorts = new ArrayList<>();
    }

    public static class OutputPort {
        public int group;
        public int layer;
        public String number;
        public Integer joystickType;
        public Integer power;
        public Integer invert;
    }

    public static RobotConfig parseFromXml(InputStream inputStream) {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(inputStream, null);

            RobotConfig config = new RobotConfig();
            int eventType = parser.getEventType();

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();

                    if ("EV3".equals(tagName)) {
                        config.name = parser.getAttributeValue(null, "Name");
                        config.description = parser.getAttributeValue(null, "Description");
                        String sup = parser.getAttributeValue(null, "StartUserProgram");
                        config.startUserProgram = sup != null && sup.equals("1");
                        config.userProgram = parser.getAttributeValue(null, "UserProgram");

                    } else if ("Joystick".equals(tagName)) {
                        RobotConfig.JoystickConfig joy = new RobotConfig.JoystickConfig();
                        joy.index = Integer.parseInt(parser.getAttributeValue(null, "Index"));
                        joy.visible = "1".equals(parser.getAttributeValue(null, "Visible"));
                        joy.shape = parser.getAttributeValue(null, "Shape");
                        joy.type = Integer.parseInt(parser.getAttributeValue(null, "Type"));
                        config.joysticks.add(joy);

                    } else if ("KeyGroup".equals(tagName)) {
                        RobotConfig.KeyGroupConfig kg = new RobotConfig.KeyGroupConfig();
                        kg.type = Integer.parseInt(parser.getAttributeValue(null, "Type"));
                        String active = parser.getAttributeValue(null, "Active");
                        kg.active = active != null ? Integer.parseInt(active) : 0;
                        kg.mailbox = parser.getAttributeValue(null, "Mailbox");
                        String incX = parser.getAttributeValue(null, "IncX");
                        kg.incX = incX != null ? Integer.parseInt(incX) : 15;
                        String incY = parser.getAttributeValue(null, "IncY");
                        kg.incY = incY != null ? Integer.parseInt(incY) : 15;
                        String decX = parser.getAttributeValue(null, "DecX");
                        kg.decX = decX != null ? Integer.parseInt(decX) : 15;
                        String decY = parser.getAttributeValue(null, "DecY");
                        kg.decY = decY != null ? Integer.parseInt(decY) : 15;
                        config.keyGroups.add(kg);

                    } else if ("OutputPort".equals(tagName)) {
                        RobotConfig.OutputPort port = new RobotConfig.OutputPort();
                        port.group = Integer.parseInt(parser.getAttributeValue(null, "Group"));
                        port.layer = Integer.parseInt(parser.getAttributeValue(null, "Layer"));
                        port.number = parser.getAttributeValue(null, "Number");
                        // Add to the last joystick or keygroup
                        if (!config.joysticks.isEmpty()) {
                            config.joysticks.get(config.joysticks.size() - 1).outputPorts.add(port);
                        } else if (!config.keyGroups.isEmpty()) {
                            config.keyGroups.get(config.keyGroups.size() - 1).outputPorts.add(port);
                        }
                    }
                }
                eventType = parser.next();
            }
            return config;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static RobotConfig fromXml(InputStream inputStream) throws XmlPullParserException, IOException {
        return parseFromXml(inputStream);
    }

    public String toXml() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        saveToStream(baos);
        return baos.toString("UTF-8");
    }

    public void saveToInternal(Context context, String filename) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(new File(context.getFilesDir(), filename))) {
            saveToStream(fos);
        }
    }

    private void saveToStream(OutputStream out) throws IOException {
        try {
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, "UTF-8");
            serializer.startDocument(null, null);
            serializer.startTag(null, "EV3");
            serializer.attribute(null, "Name", name != null ? name : "");
            serializer.attribute(null, "Description", description != null ? description : "");
            serializer.attribute(null, "StartUserProgram", startUserProgram ? "1" : "0");
            serializer.attribute(null, "UserProgram", userProgram != null ? userProgram : "");

            for (JoystickConfig j : joysticks) {
                serializer.startTag(null, "Joystick");
                serializer.attribute(null, "Index", String.valueOf(j.index));
                serializer.attribute(null, "Visible", j.visible ? "1" : "0");
                serializer.attribute(null, "Shape", j.shape != null ? j.shape : "c");
                serializer.attribute(null, "Type", String.valueOf(j.type));
                for (OutputPort p : j.outputPorts) {
                    serializer.startTag(null, "OutputPort");
                    serializer.attribute(null, "Group", String.valueOf(p.group));
                    serializer.attribute(null, "Layer", String.valueOf(p.layer));
                    serializer.attribute(null, "Number", p.number != null ? p.number : "");
                    serializer.endTag(null, "OutputPort");
                }
                serializer.endTag(null, "Joystick");
            }

            for (KeyGroupConfig kg : keyGroups) {
                serializer.startTag(null, "KeyGroup");
                serializer.attribute(null, "Type", String.valueOf(kg.type));
                serializer.attribute(null, "Active", String.valueOf(kg.active));
                if (kg.mailbox != null && !kg.mailbox.isEmpty()) {
                    serializer.attribute(null, "Mailbox", kg.mailbox);
                }
                serializer.attribute(null, "IncX", String.valueOf(kg.incX));
                serializer.attribute(null, "IncY", String.valueOf(kg.incY));
                serializer.attribute(null, "DecX", String.valueOf(kg.decX));
                serializer.attribute(null, "DecY", String.valueOf(kg.decY));
                for (int key : kg.upKeys) {
                    serializer.startTag(null, "UpKey");
                    serializer.text(String.valueOf(key));
                    serializer.endTag(null, "UpKey");
                }
                for (int key : kg.leftKeys) {
                    serializer.startTag(null, "LeftKey");
                    serializer.text(String.valueOf(key));
                    serializer.endTag(null, "LeftKey");
                }
                for (int key : kg.downKeys) {
                    serializer.startTag(null, "DownKey");
                    serializer.text(String.valueOf(key));
                    serializer.endTag(null, "DownKey");
                }
                for (int key : kg.rightKeys) {
                    serializer.startTag(null, "RightKey");
                    serializer.text(String.valueOf(key));
                    serializer.endTag(null, "RightKey");
                }
                for (OutputPort p : kg.outputPorts) {
                    serializer.startTag(null, "OutputPort");
                    serializer.attribute(null, "Group", String.valueOf(p.group));
                    serializer.attribute(null, "Layer", String.valueOf(p.layer));
                    serializer.attribute(null, "Number", p.number != null ? p.number : "");
                    serializer.endTag(null, "OutputPort");
                }
                serializer.endTag(null, "KeyGroup");
            }

            serializer.endTag(null, "EV3");
            serializer.endDocument();
            out.flush();
        } catch (Exception e) {
            throw new IOException("XML serialization failed", e);
        }
    }

    public static RobotConfig loadFromInternal(Context context, String filename) throws Exception {
        try (FileInputStream fis = context.openFileInput(filename)) {
            return fromXml(fis);
        }
    }

    public void exportToExternal(String filename) throws IOException {
        File dir = new File(Environment.getExternalStorageDirectory(), "RoboCamConfigs");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            saveToStream(fos);
        }
    }

    public static RobotConfig importFromExternal(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            return fromXml(fis);
        }
    }
}
