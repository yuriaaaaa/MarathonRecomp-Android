package com.marathonrecomp.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Soleanna Hub: the town's stage gates and mission givers, the app chosen for each, and the
 * hub's settings.ini. The hub's town mission (android/soleanna/make_hub_mod.py) reports a slot
 * by key ("gate_wvo", "m1001"); GameActivity.hubOpenSlot opens the app chosen for it.
 *
 * Choices live in SoleannaHub/settings.ini (read fresh each time, because the game and the
 * menus run in different processes).
 */
final class Hub {
    static final class Slot {
        final String key, name, where;
        Slot(String key, String name, String where) { this.key = key; this.name = name; this.where = where; }
    }

    static final String kCity = "Soleanna (city)";
    static final String kNewCity = "New City";
    static final String kForest = "Forest";

    static final Slot[] kSlots = {
        new Slot("gate_wvo", "Wave Ocean gate (by the docks)", kCity),
        new Slot("gate_dtd", "Dusty Desert gate", kCity),
        new Slot("gate_aqa", "Aquatic Base gate", kCity),
        new Slot("m1001", "Mission giver 1001", kCity),
        new Slot("m1003", "Mission giver 1003", kCity),
        new Slot("m1004", "Mission giver 1004", kCity),
        new Slot("m1005", "Mission giver 1005", kCity),
        new Slot("m1006", "Mission giver 1006", kCity),
        new Slot("m1014", "Mission giver 1014", kCity),
        new Slot("m1017", "Mission giver 1017", kCity),
        new Slot("m1035", "Mission giver 1035", kCity),
        new Slot("gate_csc", "Crisis City gate", kNewCity),
        new Slot("gate_flc", "Flame Core gate", kNewCity),
        new Slot("gate_wap", "White Acropolis gate", kNewCity),
        new Slot("gate_rct", "Radical Train gate", kNewCity),
        new Slot("m1012", "Mission giver 1012", kNewCity),
        new Slot("m1013", "Mission giver 1013", kNewCity),
        new Slot("m1015", "Mission giver 1015", kNewCity),
        new Slot("m1018", "Mission giver 1018", kNewCity),
        new Slot("m1019", "Mission giver 1019", kNewCity),
        new Slot("m1020", "Mission giver 1020", kNewCity),
        new Slot("m1026", "Mission giver 1026", kNewCity),
        new Slot("m1033", "Mission giver 1033", kNewCity),
        new Slot("gate_tpj", "Tropical Jungle gate", kForest),
        new Slot("gate_kdv", "Kingdom Valley gate", kForest),
        new Slot("m1022", "Mission giver 1022", kForest),
        new Slot("m1023", "Mission giver 1023", kForest),
        new Slot("m1029", "Mission giver 1029", kForest),
    };

    /** settings.ini [hub] keys. */
    static final String kSkipStartScreen = "skip_start_screen";
    static final String kExtraFromHub = "io.github.david.soleannahub.FROM_HUB";
    static final String kExtraPlayNow = "io.github.david.soleannahub.PLAY_NOW";
    // Mario Hub's extras too, so ports already taught to skip their Play screen for it do here.
    static final String kMarioHubPlayNow = "io.github.david.mariohub.PLAY_NOW";
    static final String kPortableKitPlayNow = "io.github.teamgdb.portablekit.PLAY_NOW";

    private Hub() {}

    static Slot slot(String key) {
        for (Slot slot : kSlots) if (slot.key.equals(key)) return slot;
        return null;
    }

    // ------------------------------------------------------------------ settings.ini

    static File settingsFile() {
        return new File(Paths.ROOT, "settings.ini");
    }

    /** section -> key -> value, in file order. */
    static synchronized Map<String, Map<String, String>> readSettings() {
        Map<String, Map<String, String>> ini = new LinkedHashMap<>();
        ini.put("hub", new LinkedHashMap<>());
        ini.put("slots", new LinkedHashMap<>());
        Map<String, String> section = ini.get("hub");
        try {
            List<String> lines = java.nio.file.Files.readAllLines(settingsFile().toPath(), StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#"))
                    continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    String name = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                    section = ini.computeIfAbsent(name, k -> new LinkedHashMap<>());
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0)
                    section.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException ignored) {
            // No settings yet.
        }
        return ini;
    }

    static synchronized void writeSettings(Map<String, Map<String, String>> ini) {
        StringBuilder out = new StringBuilder();
        out.append("; Soleanna Hub settings. [slots] maps a gate or mission giver to an app's package name.\n");
        for (Map.Entry<String, Map<String, String>> section : ini.entrySet()) {
            out.append('[').append(section.getKey()).append("]\n");
            for (Map.Entry<String, String> entry : section.getValue().entrySet())
                out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            out.append('\n');
        }
        Paths.ROOT.mkdirs();
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(settingsFile()), StandardCharsets.UTF_8)) {
            writer.write(out.toString());
        } catch (IOException ignored) {
        }
    }

    static boolean setting(String key, boolean fallback) {
        String value = readSettings().get("hub").get(key);
        if (value == null) return fallback;
        return value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on");
    }

    static void setSetting(String key, boolean value) {
        Map<String, Map<String, String>> ini = readSettings();
        ini.get("hub").put(key, value ? "1" : "0");
        writeSettings(ini);
    }

    static void assign(String key, String pkg) {
        Map<String, Map<String, String>> ini = readSettings();
        if (pkg == null) ini.get("slots").remove(key);
        else ini.get("slots").put(key, pkg);
        writeSettings(ini);
    }

    /** The package chosen for a slot if it is still installed, else null. */
    static String assignedPackage(Context context, String key) {
        String pkg = readSettings().get("slots").get(key);
        if (pkg == null || pkg.isEmpty()) return null;
        return context.getPackageManager().getLaunchIntentForPackage(pkg) != null ? pkg : null;
    }

    static String appLabel(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ------------------------------------------------------------------ logging and launching

    /** Appends a line to Logs/launcher.log. */
    static synchronized void log(String line) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        Paths.LOGS.mkdirs();
        try (FileWriter out = new FileWriter(new File(Paths.LOGS, "launcher.log"), true)) {
            out.write(stamp + "  " + line + "\n");
        } catch (IOException ignored) {
        }
    }

    /** Opens the app for a slot in its own task, so quitting it comes back to Soleanna. */
    static boolean launch(Context context, String key) {
        String pkg = assignedPackage(context, key);
        if (pkg == null) return false;
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(kExtraFromHub, true);
        if (setting(kSkipStartScreen, true)) {
            intent.putExtra(kPortableKitPlayNow, true);
            intent.putExtra(kMarioHubPlayNow, true);
            intent.putExtra(kExtraPlayNow, true);
        }
        try {
            context.startActivity(intent);
            log("Slot " + key + " -> launched " + pkg);
            return true;
        } catch (RuntimeException e) {
            log("Slot " + key + " -> could not launch " + pkg + ": " + e);
            return false;
        }
    }
}
