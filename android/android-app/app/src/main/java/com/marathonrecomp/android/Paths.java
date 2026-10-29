package com.marathonrecomp.android;

import android.os.Environment;

import java.io.File;

/**
 * Where everything lives on shared storage. Marathon Recompiled keeps it all in
 * Internal storage/MarathonRecomp; Soleanna Hub (the "soleanna" build) has its own tidy folder
 * (settings.ini, Logs, Saves, mods) and plays the game already installed by Marathon Recompiled,
 * so the 7 GB game isn't copied twice. The game library reads the same places from the
 * environment GameActivity sets (os/android/android_paths.h).
 */
final class Paths {
    private Paths() {}

    static final File STORAGE = Environment.getExternalStorageDirectory();
    static final File MARATHON_ROOT = new File(STORAGE, "MarathonRecomp");

    static final File ROOT = BuildConfig.HUB ? new File(STORAGE, "SoleannaHub") : MARATHON_ROOT;
    static final File USER = new File(ROOT, BuildConfig.HUB ? "Saves" : "user");
    static final File LOGS = new File(ROOT, BuildConfig.HUB ? "Logs" : "logs");
    static final File IMPORT = new File(ROOT, BuildConfig.HUB ? "Import" : "import");

    /**
     * The folder holding game/ (and dlc/). The hub uses its own if a game was installed there,
     * else Marathon Recompiled's, else its own (the installer then installs into it).
     */
    static File gameParent() {
        if (!BuildConfig.HUB || new File(ROOT, "game/default.xex").isFile())
            return ROOT;
        if (new File(MARATHON_ROOT, "game/default.xex").isFile())
            return MARATHON_ROOT;
        return ROOT;
    }

    static File game() {
        return new File(gameParent(), "game");
    }

    static boolean gameInstalled() {
        return new File(game(), "default.xex").isFile();
    }
}
