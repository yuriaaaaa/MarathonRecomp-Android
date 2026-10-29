package com.marathonrecomp.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Mods for Marathon Recompiled on Android.
 *
 * Mods are folders in Internal storage/MarathonRecomp/mods, in either Sonic '06
 * Mod Manager format: the classic one (mod.ini, [Details] Merge/Read-only) or
 * the newer one (mod.json, "+name.arc" archives to merge, "#name" custom files).
 *
 * The game has a mod loader (cpkredir.ini → ModsDB.ini) that serves files from
 * mod folders in place of the game's, without touching the installed game.
 * What it can't do is merge archives, so before starting the game this class
 * builds the merged archives (and renamed custom files) into
 * mods/.marathon/merged, which is loaded with the highest priority.
 */
final class ModManager {
    static final File MODS = new File(LauncherActivity.ROOT, "mods");
    private static final File GENERATED = new File(MODS, ".marathon");
    private static final File MERGED = new File(GENERATED, "merged");
    private static final File STATE = new File(MODS, "mods.txt");
    private static final File GAME = Paths.game();
    private static final File CPKREDIR = new File(Paths.USER, "cpkredir.ini");
    /** Data writes into default.xex from patches; the game applies them in memory at startup. */
    static final File XEX_PATCHES = new File(GENERATED, "xex_patches.txt");
    static final File USER_PATCHES = new File(LauncherActivity.ROOT, "patches");

    /** Codes built into Marathon Recompiled that the mod loader can switch on. */
    static final String[] CODES = {
        "AntigravityRetainsMomentum", "ControllableBoundAttack", "ControllableSpinkick",
        "ControllableTeleportDash", "DisableEdgeGrabLeftover", "DisableKingdomValleyMist",
        "DisableLowResolutionFontOnCustomUI", "DisablePushState", "DisableTitleInputDelay",
        "FixPowerUpJingleDuration", "InfiniteLives", "MidairControlForMachSpeed",
        "MidairControlForSnowboards", "RestoreChainJumpFlips", "RestoreChaosBoostJump",
        "RestoreChaosSpearFlips", "RestoreContextualHUDColours", "RestoreDemoCameraMode",
        "RestoreSonicActionGauge", "SkipIntroLogos", "TailsGauge", "UnlimitedAntigravity",
        "UseOfficialAchievementText",
    };

    static final class Mod {
        String id;          // Folder path relative to mods/
        File directory;     // Folder whose layout mirrors the game folder
        boolean json;
        String title = "";
        String author = "";
        String version = "";
        String description = "";
        boolean merge;
        // Classic mod.ini lists, lower-case. The Mod Manager matched them by file name only.
        final Set<String> readOnly = new HashSet<>(); // archives replaced whole, never merged
        final Set<String> custom = new HashSet<>();   // new files the game doesn't have
        boolean enabled;
        final List<String> notes = new ArrayList<>();
    }

    interface Progress {
        void update(String message);
    }

    final List<Mod> mods = new ArrayList<>();
    final Set<String> codes = new LinkedHashSet<>();
    final Set<String> patches = new LinkedHashSet<>();          // enabled patch ids
    final PatchEngine.Tweaks tweaks = new PatchEngine.Tweaks();
    /** The patch library (set by the caller: bundled patches need the app's assets). */
    List<PatchEngine.Patch> library = new ArrayList<>();
    /** Patches or tweaks that couldn't be applied by the last apply(). */
    final List<String> problems = new ArrayList<>();

    // ---------------------------------------------------------------- loading

    void load() {
        MODS.mkdirs();
        Map<String, Mod> found = new LinkedHashMap<>();
        File[] entries = MODS.listFiles();
        if (entries != null) {
            List<File> sorted = new ArrayList<>();
            Collections.addAll(sorted, entries);
            sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File entry : sorted) {
                if (!entry.isDirectory() || entry.getName().startsWith("."))
                    continue;
                List<Mod> list = new ArrayList<>();
                findMods(entry, 0, list);
                // A download with no mod.ini anywhere (Khronos Resources, made to be extracted
                // into the game folder) is used as a plain mod laid out like the game folder.
                if (list.isEmpty() && containsAnyFile(entry))
                    list.add(parsePlainMod(entry));
                for (Mod mod : list)
                    found.put(mod.id, mod);
            }
        }

        mods.clear();
        codes.clear();
        patches.clear();
        for (String line : readLines(STATE)) {
            if (line.startsWith("code ")) {
                codes.add(line.substring(5).trim());
                continue;
            }
            if (line.startsWith("patch ")) {
                patches.add(line.substring(6).trim());
                continue;
            }
            if (line.startsWith("tweak ")) {
                String[] parts = line.substring(6).trim().split(" ");
                if (parts.length == 2) {
                    try {
                        int value = Integer.parseInt(parts[1]);
                        switch (parts[0]) {
                            case "CameraType": tweaks.cameraType = value; break;
                            case "CameraDistance": tweaks.cameraDistance = value; break;
                            case "CameraHeight": tweaks.cameraHeight = value; break;
                            case "AmyHammerRange": tweaks.hammerRange = value; break;
                            case "StartingRings": tweaks.startingRings = value; break;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }
            if (line.length() < 3 || line.charAt(1) != ' ')
                continue;
            Mod mod = found.remove(line.substring(2));
            if (mod != null) {
                mod.enabled = line.charAt(0) == '1';
                mods.add(mod);
            }
        }
        // New mods go to the bottom of the list, switched off.
        mods.addAll(found.values());

        if (BuildConfig.HUB) {
            // Soleanna Hub: the hub's town mission is always on and wins over every other mod.
            for (int i = 0; i < mods.size(); i++) {
                Mod mod = mods.get(i);
                if (mod.id.equals(HubMod.FOLDER)) {
                    mods.remove(i);
                    mod.enabled = true;
                    mods.add(0, mod);
                    break;
                }
            }
        }
    }

    void save() throws IOException {
        StringBuilder text = new StringBuilder();
        for (Mod mod : mods)
            text.append(mod.enabled ? '1' : '0').append(' ').append(mod.id).append('\n');
        for (String code : codes)
            text.append("code ").append(code).append('\n');
        for (String patch : patches)
            text.append("patch ").append(patch).append('\n');
        text.append("tweak CameraType ").append(tweaks.cameraType).append('\n');
        text.append("tweak CameraDistance ").append(tweaks.cameraDistance).append('\n');
        text.append("tweak CameraHeight ").append(tweaks.cameraHeight).append('\n');
        text.append("tweak AmyHammerRange ").append(tweaks.hammerRange).append('\n');
        text.append("tweak StartingRings ").append(tweaks.startingRings).append('\n');
        writeText(STATE, text.toString());
    }

    /** Enabled patches this build can apply, in library order. */
    List<PatchEngine.Patch> enabledPatches() {
        List<PatchEngine.Patch> out = new ArrayList<>();
        for (PatchEngine.Patch patch : library)
            if (patch.supported && patches.contains(patch.id))
                out.add(patch);
        return out;
    }

    int enabledCount() {
        int count = 0;
        for (Mod mod : mods)
            if (mod.enabled)
                count++;
        return count;
    }

    /**
     * A mod is a folder holding mod.ini or mod.json (or laid out like the game folder).
     * Downloads often wrap mods in a folder or two, sometimes several mods in one
     * (Complete Physics Overhaul ships with Physics Companion and Khronos), so look two levels down.
     */
    private void findMods(File folder, int depth, List<Mod> out) {
        File ini = childIgnoreCase(folder, "mod.ini");
        File json = childIgnoreCase(folder, "mod.json");
        if (json != null && json.isFile()) {
            out.add(parseJsonMod(folder, json));
            return;
        }
        if (ini != null && ini.isFile()) {
            out.add(parseIniMod(folder, ini));
            return;
        }
        if (childIgnoreCase(folder, "xenon") != null || childIgnoreCase(folder, "win32") != null) {
            out.add(parsePlainMod(folder));
            return;
        }
        if (depth >= 2)
            return;
        File[] children = folder.listFiles(File::isDirectory);
        if (children == null)
            return;
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, children);
        sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : sorted)
            if (!child.getName().startsWith("."))
                findMods(child, depth + 1, out);
    }

    private Mod newMod(File folder) {
        Mod mod = new Mod();
        mod.directory = folder;
        mod.id = MODS.toURI().relativize(folder.toURI()).getPath().replaceAll("/+$", "");
        mod.title = folder.getName();
        return mod;
    }

    private Mod parseIniMod(File folder, File iniFile) {
        Mod mod = newMod(folder);
        // Like the Mod Manager, read each key from whichever section has it.
        Map<String, Map<String, String>> ini = readIni(iniFile);

        mod.title = nonEmpty(iniKey(ini, "title"), mod.title);
        mod.author = nonEmpty(iniKey(ini, "author"), "");
        mod.version = nonEmpty(iniKey(ini, "version"), "");
        mod.description = nonEmpty(iniKey(ini, "description"), "").replace("\\n", "\n");
        mod.merge = isTrue(iniKey(ini, "merge"));
        addList(mod.readOnly, iniKey(ini, "read-only"));
        addList(mod.custom, iniKey(ini, "custom"));
        String platform = nonEmpty(iniKey(ini, "platform"), "").toLowerCase(Locale.ROOT);
        if (platform.contains("playstation") || platform.contains("ps3"))
            mod.notes.add("Made for the PlayStation 3 version; it may not work.");
        if (!nonEmpty(iniKey(ini, "save"), "").isEmpty())
            mod.notes.add("Uses its own save file; this build keeps using your normal save.");
        if (!nonEmpty(iniKey(ini, "requiredpatches"), "").isEmpty())
            mod.notes.add("Needs code patches (" + iniKey(ini, "requiredpatches") + "), which can't run on the recompiled game.");
        noteXex(mod);
        return mod;
    }

    /** A folder laid out like the game folder (xenon/, win32/) with no mod.ini: its files replace the game's. */
    private Mod parsePlainMod(File folder) {
        Mod mod = newMod(folder);
        mod.description = "No mod.ini: its files are used as if they were in the game folder.";
        noteXex(mod);
        return mod;
    }

    private static void noteXex(Mod mod) {
        if (childIgnoreCase(mod.directory, "default.xex") != null)
            mod.notes.add("Includes a default.xex; code changes in it can't apply to this build.");
    }

    private static String iniKey(Map<String, Map<String, String>> ini, String key) {
        for (String section : new String[] { "details", "filesystem" }) {
            Map<String, String> values = ini.get(section);
            if (values != null && values.containsKey(key))
                return values.get(key);
        }
        for (Map<String, String> values : ini.values())
            if (values.containsKey(key))
                return values.get(key);
        return null;
    }

    private static void addList(Set<String> out, String csv) {
        if (csv == null)
            return;
        for (String part : csv.split(",")) {
            String path = normalise(part.trim());
            if (!path.isEmpty())
                out.add(path);
        }
    }

    /** Matches a list entry by relative path or (as the Mod Manager did) by file name alone. */
    private static boolean listed(Set<String> list, String relative, String name) {
        return !list.isEmpty() && (list.contains(relative.toLowerCase(Locale.ROOT))
                || list.contains(name.toLowerCase(Locale.ROOT)));
    }

    private Mod parseJsonMod(File folder, File jsonFile) {
        Mod mod = newMod(folder);
        mod.json = true;
        try {
            JSONObject json = new JSONObject(readText(jsonFile));
            mod.title = nonEmpty(json.optString("Title"), mod.title);
            mod.author = json.optString("Author", "");
            mod.version = json.optString("Version", "");
            mod.description = json.optString("Description", "");
            String platform = json.optString("Platform", "");
            if (platform.equalsIgnoreCase("PlayStation"))
                mod.notes.add("Made for the PlayStation 3 version; it may not work.");
            JSONArray patches = json.optJSONArray("Patches");
            if (patches != null && patches.length() > 0)
                mod.notes.add("Has " + patches.length() + " code patch(es), which can't run on the recompiled game.");
        } catch (Exception e) {
            mod.notes.add("mod.json couldn't be read: " + e.getMessage());
        }
        if (containsFileWithExtension(folder, ".mlua"))
            mod.notes.add("Has Lua scripts, which this build can't run.");
        return mod;
    }

    // --------------------------------------------------------------- applying

    private static final int REPLACE = 0, MERGE = 1, COPY = 2;

    private static final class Operation {
        final int kind;
        final File file;

        Operation(int kind, File file) {
            this.kind = kind;
            this.file = file;
        }
    }

    /**
     * Writes the mod loader configuration for the enabled mods, codes, patches and tweaks.
     * Archives that need merging or patching are built into mods/.marathon/merged, which the
     * game's mod loader serves with the highest priority. Slow work only happens when inputs changed.
     */
    void apply(Progress progress) throws IOException {
        save();
        problems.clear();
        List<Mod> enabled = new ArrayList<>();
        for (Mod mod : mods)
            if (mod.enabled)
                enabled.add(mod);
        List<PatchEngine.Patch> activePatches = enabledPatches();

        if (enabled.isEmpty() && codes.isEmpty() && activePatches.isEmpty() && !tweaks.any()) {
            writeText(CPKREDIR, "[CPKREDIR]\nEnabled=\"0\"\n");
            deleteRecursively(MERGED);
            XEX_PATCHES.delete();
            return;
        }

        progress.update("Checking mods…");
        // Operations per game path, lowest-priority mod first.
        Map<String, List<Operation>> operations = new LinkedHashMap<>();
        Map<String, String> displayPaths = new HashMap<>();
        for (int m = enabled.size() - 1; m >= 0; m--) {
            Mod mod = enabled.get(m);
            List<File> files = new ArrayList<>();
            listFiles(mod.directory, files);
            String base = mod.directory.getAbsolutePath();
            for (File file : files) {
                String relative = file.getAbsolutePath().substring(base.length() + 1).replace('\\', '/');
                String name = file.getName();
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (lowerName.equals("mod.ini") || lowerName.equals("mod.json") || lowerName.endsWith(".mlua")
                        || lowerName.startsWith("thumbnail."))
                    continue;

                boolean archive = lowerName.endsWith(".arc");
                int kind = -1;
                String target = relative;
                if (mod.json && name.startsWith("#")) {
                    kind = COPY;
                    target = stripPrefix(relative);
                } else if (mod.json && name.startsWith("+") && archive) {
                    kind = MERGE;
                    target = stripPrefix(relative);
                } else if (mod.json && name.startsWith("-")) {
                    continue;
                } else if (archive) {
                    // Custom (new) and read-only archives are used whole; the rest of a Merge mod's are merged.
                    boolean whole = listed(mod.custom, relative, name) || listed(mod.readOnly, relative, name);
                    kind = (!mod.json && mod.merge && !whole) ? MERGE : REPLACE;
                }
                if (kind < 0)
                    continue; // Loose files: the game's mod loader serves them directly.

                String key = target.toLowerCase(Locale.ROOT);
                operations.computeIfAbsent(key, k -> new ArrayList<>()).add(new Operation(kind, file));
                displayPaths.putIfAbsent(key, target);
            }
        }

        // Archives that patches or tweaks edit, and a signature of those edits per archive.
        Map<String, StringBuilder> edits = new LinkedHashMap<>();
        for (PatchEngine.Patch patch : activePatches)
            for (String archive : patch.archives)
                edits.computeIfAbsent(archive, k -> new StringBuilder(PatchEngine.VERSION))
                        .append("|patch:").append(patch.id).append(':').append(patch.source.hashCode());
        for (String archive : tweaks.archives())
            edits.computeIfAbsent(archive, k -> new StringBuilder(PatchEngine.VERSION))
                    .append("|tweaks:").append(tweaks.signature());

        // Paths that need a built file: a merge after the last replacement, a renamed copy, or edits.
        Map<String, List<Operation>> toBuild = new LinkedHashMap<>();
        for (Map.Entry<String, List<Operation>> entry : operations.entrySet()) {
            List<Operation> list = entry.getValue();
            int last = list.get(list.size() - 1).kind;
            int lastReplace = -1;
            for (int i = 0; i < list.size(); i++)
                if (list.get(i).kind == REPLACE || list.get(i).kind == COPY)
                    lastReplace = i;
            boolean mergesAfterReplace = false;
            for (int i = lastReplace + 1; i < list.size(); i++)
                if (list.get(i).kind == MERGE)
                    mergesAfterReplace = true;
            if (last == COPY || mergesAfterReplace || edits.containsKey(entry.getKey()))
                toBuild.put(entry.getKey(), list.subList(Math.max(lastReplace, 0), list.size()));
        }
        for (String archive : edits.keySet())
            if (!toBuild.containsKey(archive))
                toBuild.put(archive, new ArrayList<>());

        Map<String, String> manifest = readManifest();
        Map<String, String> newManifest = new LinkedHashMap<>();
        Set<String> keep = new HashSet<>();
        int index = 0;
        for (Map.Entry<String, List<Operation>> entry : toBuild.entrySet()) {
            index++;
            String key = entry.getKey();
            List<Operation> list = entry.getValue();
            StringBuilder edit = edits.get(key);
            File gameFile = findIgnoreCase(GAME, key);
            String relative = gameFile != null
                    ? gameFile.getAbsolutePath().substring(GAME.getAbsolutePath().length() + 1)
                    : displayPaths.containsKey(key) ? displayPaths.get(key) : key;
            boolean startsFromGame = list.isEmpty() || list.get(0).kind == MERGE;
            if (startsFromGame && gameFile == null && list.isEmpty()) {
                problems.add("Patches for " + key + " were skipped: the game has no such archive.");
                continue;
            }
            File output = new File(MERGED, relative);
            keep.add(output.getAbsolutePath());

            StringBuilder signature = new StringBuilder();
            if (startsFromGame && gameFile != null)
                signature.append(describe(gameFile));
            for (Operation operation : list)
                signature.append('|').append(operation.kind).append(describe(operation.file));
            if (edit != null)
                signature.append('|').append(edit);
            newManifest.put(output.getAbsolutePath(), signature.toString());
            if (signature.toString().equals(manifest.get(output.getAbsolutePath())) && output.isFile()) {
                String earlier = manifest.get(output.getAbsolutePath() + "#problems");
                if (earlier != null && !earlier.isEmpty()) {
                    for (String problem : earlier.split("\u001f"))
                        problems.add(problem);
                    newManifest.put(output.getAbsolutePath() + "#problems", earlier);
                }
                continue;
            }

            progress.update((edit != null ? "Patching " : "Preparing ") + new File(relative).getName()
                    + " (" + index + " of " + toBuild.size() + ")…");
            output.getParentFile().mkdirs();
            File temporary = new File(output.getAbsolutePath() + ".tmp");
            Operation last = list.isEmpty() ? null : list.get(list.size() - 1);
            if (last != null && last.kind == COPY && edit == null) {
                copyFile(last.file, temporary);
            } else {
                U8Archive result;
                int first;
                if (startsFromGame && gameFile != null) {
                    result = U8Archive.read(gameFile);
                    first = 0;
                } else {
                    result = U8Archive.read(list.get(0).file);
                    first = 1;
                }
                for (int i = first; i < list.size(); i++)
                    result.mergeFrom(U8Archive.read(list.get(i).file));
                if (edit != null) {
                    List<String> found = new ArrayList<>();
                    PatchEngine.applyToArchive(result, key, activePatches,
                            tweaks.archives().contains(key) ? tweaks : null, found);
                    problems.addAll(found);
                    if (!found.isEmpty())
                        newManifest.put(output.getAbsolutePath() + "#problems", String.join("\u001f", found));
                }
                result.write(temporary);
            }
            if (output.exists() && !output.delete())
                throw new IOException("Can't replace " + output);
            if (!temporary.renameTo(output))
                throw new IOException("Can't write " + output);
        }

        // Drop built files that are no longer needed.
        List<File> existing = new ArrayList<>();
        listFiles(MERGED, existing);
        for (File file : existing)
            if (!keep.contains(file.getAbsolutePath()))
                file.delete();
        writeManifest(newManifest);

        // Writes into the executable's data, applied by the game in memory.
        StringBuilder xex = new StringBuilder();
        for (PatchEngine.Patch patch : activePatches) {
            if (patch.xexWrites.isEmpty())
                continue;
            xex.append("# ").append(patch.title).append('\n');
            for (PatchEngine.XexWrite write : patch.xexWrites) {
                xex.append(String.format(Locale.ROOT, "%08X ", write.address));
                for (byte b : write.bytes)
                    xex.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
                xex.append('\n');
            }
        }
        if (xex.length() > 0)
            writeText(XEX_PATCHES, xex.toString());
        else
            XEX_PATCHES.delete();

        // Mod loader configuration: one HMM-style ini per mod, highest priority first.
        GENERATED.mkdirs();
        StringBuilder db = new StringBuilder("[Main]\n");
        StringBuilder modsSection = new StringBuilder("[Mods]\n");
        int active = 0;
        if (!keep.isEmpty()) {
            writeModIni(new File(GENERATED, "merged.ini"), MERGED);
            db.append("ActiveMod").append(active++).append("=\"merged\"\n");
            modsSection.append("merged=\"").append(new File(GENERATED, "merged.ini").getAbsolutePath()).append("\"\n");
        }
        for (int i = 0; i < enabled.size(); i++) {
            File ini = new File(GENERATED, "mod" + i + ".ini");
            writeModIni(ini, enabled.get(i).directory);
            db.append("ActiveMod").append(active++).append("=\"mod").append(i).append("\"\n");
            modsSection.append("mod").append(i).append("=\"").append(ini.getAbsolutePath()).append("\"\n");
        }
        db.append("ActiveModCount=").append(active).append('\n');
        db.append(modsSection);
        db.append("[Codes]\nCodeCount=").append(codes.size()).append('\n');
        int c = 0;
        for (String code : codes)
            db.append("Code").append(c++).append("=\"").append(code).append("\"\n");
        File modsDb = new File(GENERATED, "ModsDB.ini");
        writeText(modsDb, db.toString());
        writeText(CPKREDIR, "[CPKREDIR]\nEnabled=\"1\"\nLogType=\"console\"\nModsDbIni=\"" + modsDb.getAbsolutePath() + "\"\n");
    }

    private static void writeModIni(File ini, File directory) throws IOException {
        writeText(ini, "[Main]\nIncludeDirCount=1\nIncludeDir0=\"" + directory.getAbsolutePath() + "\"\n");
    }

    // ---------------------------------------------------------------- helpers

    private static String describe(File file) {
        return file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
    }

    private static String stripPrefix(String relative) {
        int slash = relative.lastIndexOf('/');
        return relative.substring(0, slash + 1) + relative.substring(slash + 2);
    }

    private static String normalise(String path) {
        String result = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (result.startsWith("/"))
            result = result.substring(1);
        return result;
    }

    private static boolean isTrue(String value) {
        if (value == null || value.isEmpty())
            return false;
        char first = value.charAt(0);
        return first == 't' || first == 'T' || first == 'y' || first == 'Y' || first == '1';
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static File childIgnoreCase(File folder, String name) {
        File[] children = folder.listFiles();
        if (children == null)
            return null;
        for (File child : children)
            if (child.getName().equalsIgnoreCase(name))
                return child;
        return null;
    }

    static File findIgnoreCase(File base, String relative) {
        File current = base;
        for (String part : relative.split("/")) {
            if (part.isEmpty())
                continue;
            File exact = new File(current, part);
            if (exact.exists()) {
                current = exact;
                continue;
            }
            File match = childIgnoreCase(current, part);
            if (match == null)
                return null;
            current = match;
        }
        return current.isFile() ? current : null;
    }

    private static boolean containsAnyFile(File folder) {
        File[] children = folder.listFiles();
        if (children == null)
            return false;
        for (File child : children)
            if (child.isFile() || (child.isDirectory() && containsAnyFile(child)))
                return true;
        return false;
    }

    private static boolean containsFileWithExtension(File folder, String extension) {
        List<File> files = new ArrayList<>();
        listFiles(folder, files);
        for (File file : files)
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(extension))
                return true;
        return false;
    }

    private static void listFiles(File folder, List<File> out) {
        File[] children = folder.listFiles();
        if (children == null)
            return;
        for (File child : children) {
            if (child.isDirectory())
                listFiles(child, out);
            else
                out.add(child);
        }
    }

    private static Map<String, Map<String, String>> readIni(File file) {
        Map<String, Map<String, String>> sections = new HashMap<>();
        Map<String, String> section = null;
        for (String raw : readLines(file)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#"))
                continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = new HashMap<>();
                sections.put(line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT), section);
                continue;
            }
            int equals = line.indexOf('=');
            if (section == null || equals < 0)
                continue;
            String key = unquote(line.substring(0, equals).trim()).toLowerCase(Locale.ROOT);
            section.put(key, unquote(line.substring(equals + 1).trim()));
        }
        return sections;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
            return value.substring(1, value.length() - 1);
        return value;
    }

    private static List<String> readLines(File file) {
        List<String> lines = new ArrayList<>();
        if (!file.isFile())
            return lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.isEmpty() && line.startsWith("\uFEFF"))
                    line = line.substring(1);
                lines.add(line);
            }
        } catch (IOException ignored) {
        }
        return lines;
    }

    private static String readText(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String line : readLines(file))
            text.append(line).append('\n');
        return text.toString();
    }

    private static void writeText(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(text);
        }
    }

    private Map<String, String> readManifest() {
        Map<String, String> manifest = new HashMap<>();
        for (String line : readLines(new File(GENERATED, "merged.manifest"))) {
            int tab = line.indexOf('\t');
            if (tab > 0)
                manifest.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return manifest;
    }

    private void writeManifest(Map<String, String> manifest) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : manifest.entrySet())
            text.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        writeText(new File(GENERATED, "merged.manifest"), text.toString());
    }

    private static void copyFile(File from, File to) throws IOException {
        byte[] buffer = new byte[1 << 20];
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            int read;
            while ((read = in.read(buffer)) > 0)
                out.write(buffer, 0, read);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null)
            for (File child : children)
                deleteRecursively(child);
        file.delete();
    }
}
