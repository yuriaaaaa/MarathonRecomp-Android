package com.marathonrecomp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/**
 * First screen: makes sure the app can use Internal storage/MarathonRecomp,
 * explains where the game files go, then starts the game. The game itself
 * runs its own installer on first start.
 */
public class LauncherActivity extends Activity {
    static final File ROOT = Paths.ROOT;

    private TextView status;
    private TextView steps;
    private Button accessButton;
    private Button startButton;
    private Button dlcButton;
    private Button modsButton;
    private Button patchesButton;
    private Button controlsButton;
    private boolean starting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        column.setPadding(pad, pad, pad, pad);

        TextView title = text("Marathon Recompiled", 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);
        column.addView(text("Sonic the Hedgehog (2006) \u00b7 personal Android build", 15, 0xFFB0BEC5));

        status = text("", 15, 0xFFE0E0E0);
        status.setPadding(0, dp(18), 0, dp(6));
        column.addView(status);

        steps = text("", 15, 0xFFCFD8DC);
        steps.setLineSpacing(0, 1.2f);
        column.addView(steps);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(18), 0, 0);
        accessButton = button("Allow file access", 0xFF1565C0);
        accessButton.setOnClickListener(v -> requestAccess());
        startButton = button("Start", 0xFF2E7D32);
        startButton.setOnClickListener(v -> startGame(new String[0]));
        dlcButton = button("Install DLC", 0xFF455A64);
        dlcButton.setOnClickListener(v -> startGame(new String[] { "--install-dlc" }));
        modsButton = button("Mods", 0xFF6A1B9A);
        modsButton.setOnClickListener(v -> startActivity(new Intent(this, ModsActivity.class)));
        patchesButton = button("Patches", 0xFF00695C);
        patchesButton.setOnClickListener(v -> startActivity(new Intent(this, PatchesActivity.class)));
        buttons.addView(accessButton);
        buttons.addView(startButton);
        buttons.addView(modsButton);
        buttons.addView(patchesButton);
        controlsButton = button("Settings", 0xFF4E342E);
        controlsButton.setOnClickListener(v -> startActivity(new Intent(this, ControlsActivity.class)));
        buttons.addView(controlsButton);
        buttons.addView(dlcButton);
        HorizontalScrollView buttonScroll = new HorizontalScrollView(this);
        buttonScroll.setHorizontalScrollBarEnabled(false);
        buttonScroll.addView(buttons);
        column.addView(buttonScroll);

        TextView footer = text("Logs and saves: Internal storage/MarathonRecomp (logs, user). "
                + "Plays with a controller or the on-screen touch controls (in game, a four-finger tap "
                + "opens their settings). Don't switch apps while the game is running.", 13, 0xFF90A4AE);
        footer.setPadding(0, dp(18), 0, 0);
        column.addView(footer);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101418);
        scroll.addView(column);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** " · GPU driver: name" when a custom driver is selected. */
    private String driverStatus() {
        DriverManager.Driver driver = DriverManager.active(this);
        if (driver == null)
            return "";
        DriverManager.LastStart last = DriverManager.lastStart(this);
        boolean failed = last != null && last.status.equals("custom_failed")
                && last.driver.equals(driver.library.getAbsolutePath());
        return " \u00b7 GPU driver: " + driver.name + (failed ? " (didn't start last time)" : "");
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        boolean access = hasAccess();
        boolean installed = false;
        int importCount = 0;
        if (access) {
            Paths.IMPORT.mkdirs();
            Paths.LOGS.mkdirs();
            installed = Paths.gameInstalled();
            String[] names = Paths.IMPORT.list();
            if (names != null) {
                for (String name : names) {
                    if (!name.startsWith(".")) importCount++;
                }
            }
        }

        accessButton.setVisibility(access ? View.GONE : View.VISIBLE);
        startButton.setEnabled(access);
        startButton.setAlpha(access ? 1f : 0.4f);
        dlcButton.setVisibility(access && installed ? View.VISIBLE : View.GONE);
        modsButton.setVisibility(access && installed ? View.VISIBLE : View.GONE);
        patchesButton.setVisibility(access && installed ? View.VISIBLE : View.GONE);
        controlsButton.setVisibility(access ? View.VISIBLE : View.GONE);

        if (!access) {
            status.setText("File access: not allowed yet");
            steps.setText("1. Tap Allow file access and switch it on for Marathon Recompiled.\n"
                    + "2. Come back here.");
        } else if (!installed) {
            status.setText("File access: allowed \u00b7 Game: not installed \u00b7 import folder: "
                    + importCount + (importCount == 1 ? " item" : " items"));
            steps.setText("1. Copy your Sonic '06 disc image (.iso), or the extracted game folder, into\n"
                    + "    Internal storage/MarathonRecomp/import\n"
                    + "2. Tap Start. The installer opens: choose Add Files for an .iso, or Add Folder for an "
                    + "extracted folder, then install. It copies the game into MarathonRecomp/game "
                    + "(needs about 7 GB free; you can delete the import copy afterwards).");
        } else {
            ModManager mods = new ModManager();
            mods.load();
            PatchesActivity.loadLibrary(this, mods);
            int patchCount = mods.enabledPatches().size();
            status.setText("File access: allowed \u00b7 Game: installed \u00b7 Mods on: " + mods.enabledCount()
                    + (patchCount == 0 ? "" : " \u00b7 Patches on: " + patchCount)
                    + (mods.codes.isEmpty() ? "" : " \u00b7 Codes on: " + mods.codes.size())
                    + (mods.tweaks.any() ? " \u00b7 Tweaks on" : "")
                    + driverStatus());
            steps.setText("Tap Start to play. Mods go in MarathonRecomp/mods; choose them under Mods. "
                    + "Camera tweaks, Mod Manager patches and codes are under Patches. "
                    + "To add DLC episodes, copy them into MarathonRecomp/import and tap Install DLC.");
        }
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[] { android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1);
        }
    }

    private void startGame(String[] args) {
        if (starting)
            return;
        if (!Paths.gameInstalled()) {
            launch(args);
            return;
        }

        // Write the mod loader configuration (and merge archives) before the game starts.
        starting = true;
        TextView message = text("Checking mods and patches\u2026", 16, 0xFF202020);
        int pad = dp(24);
        message.setPadding(pad, pad, pad, pad);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(message).setCancelable(false).create();
        dialog.show();
        new Thread(() -> {
            ModManager mods = new ModManager();
            mods.load();
            PatchesActivity.loadLibrary(this, mods);
            try {
                mods.apply(text -> runOnUiThread(() -> message.setText(text)));
                runOnUiThread(() -> {
                    dialog.dismiss();
                    starting = false;
                    if (mods.problems.isEmpty()) {
                        launch(args);
                        return;
                    }
                    StringBuilder list = new StringBuilder();
                    for (String problem : mods.problems)
                        list.append("\u2022 ").append(problem).append('\n');
                    new AlertDialog.Builder(this)
                            .setTitle("Some patches couldn't be applied")
                            .setMessage(list.toString().trim() + "\n\nThe rest are ready. Start anyway?")
                            .setPositiveButton("Start", (d, w) -> launch(args))
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dialog.dismiss();
                    starting = false;
                    new AlertDialog.Builder(this)
                            .setTitle("Mods and patches couldn't be prepared")
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton("Start without them", (d, w) -> {
                                new File(Paths.USER, "cpkredir.ini").delete();
                                ModManager.XEX_PATCHES.delete();
                                launch(args);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            }
        }, "ModPrepare").start();
    }

    private void launch(String[] args) {
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_ARGS, args);
        startActivity(intent);
        finish();
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, int color) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(8));
        view.setBackground(background);
        view.setPadding(dp(20), dp(10), dp(20), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(12));
        params.gravity = Gravity.CENTER_VERTICAL;
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
