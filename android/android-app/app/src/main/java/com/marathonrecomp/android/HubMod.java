package com.marathonrecomp.android;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * The hub's town mission ships in the APK (assets/hubmod, made by android/soleanna/make_hub_mod.py)
 * and is kept installed as mods/Soleanna Hub. ModManager.load keeps it switched on and first in
 * the load order, so other mods can't replace Sonic's first town mission.
 */
final class HubMod {
    static final String FOLDER = "Soleanna Hub";
    private static final String ASSETS = "hubmod";

    private HubMod() {}

    static File folder() {
        return new File(ModManager.MODS, FOLDER);
    }

    /** Copies the mod out of the APK when it is missing or differs. */
    static void install(Context context) throws IOException {
        copyTree(context, ASSETS, folder());
    }

    private static void copyTree(Context context, String asset, File target) throws IOException {
        String[] children = context.getAssets().list(asset);
        if (children != null && children.length > 0) {
            target.mkdirs();
            for (String child : children)
                copyTree(context, asset + "/" + child, new File(target, child));
            return;
        }
        byte[] data;
        try (InputStream in = context.getAssets().open(asset)) {
            data = in.readAllBytes();
        }
        if (target.isFile() && target.length() == data.length
                && Arrays.equals(java.nio.file.Files.readAllBytes(target.toPath()), data))
            return;
        target.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(data);
        }
    }
}
