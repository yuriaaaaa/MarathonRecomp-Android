package com.marathonrecomp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Soleanna Hub's home screen and in-game menu (Back or a three-finger tap in town opens it):
 * Play / Back to Soleanna, Settings (which app each gate and mission giver opens, launching,
 * the game's own settings, files) and Quit. Ported from Mario Hub's launcher.
 */
public class HubActivity extends Activity {
    static final String EXTRA_FROM_GAME = "io.github.david.soleannahub.FROM_GAME";
    private static final int kBackground = 0xff0f1420;
    private static final int kCard = 0xff1b2333;
    private static final int kAccent = 0xff2f7de1;
    private static final int kGold = 0xffe9b949;
    private static final int kMuted = 0xffa3abb9;
    private static final String[] kTabs = {"Slots", "Launching", "Game", "About"};

    private boolean mFromGame;
    private boolean mShowingSettings;
    private boolean mStarting;
    private int mTab;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setBackgroundColor(kBackground);
        getWindow().setStatusBarColor(kBackground);
        getWindow().setNavigationBarColor(kBackground);
        mFromGame = getIntent().getBooleanExtra(EXTRA_FROM_GAME, false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mFromGame = intent.getBooleanExtra(EXTRA_FROM_GAME, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mShowingSettings) showSettings();
        else showHome();
    }

    @Override
    public void onBackPressed() {
        if (mShowingSettings) showHome();
        else if (mFromGame) backToGame();
        else super.onBackPressed();
    }

    // --- Storage -------------------------------------------------------------

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askFileAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                     Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // --- Views -----------------------------------------------------------------

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radius));
        shape.setColor(color);
        return shape;
    }

    private Button button(String label, boolean primary, View.OnClickListener click) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextSize(18);
        view.setTextColor(Color.WHITE);
        view.setBackground(rounded(primary ? kAccent : kCard, 14));
        view.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(10);
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(24), dp(24), dp(24));
        return layout;
    }

    private void setPage(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(kBackground);
        scroll.addView(content);
        setContentView(scroll);
    }

    // --- Home ------------------------------------------------------------------

    private void showHome() {
        mShowingSettings = false;
        LinearLayout page = column();
        page.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView image = new ImageView(this);
        image.setImageResource(R.mipmap.ic_launcher);
        page.addView(image, new LinearLayout.LayoutParams(dp(112), dp(112)));
        TextView title = text(getString(R.string.app_name), 26, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(16));
        page.addView(title);

        boolean access = hasAccess();
        int assigned = 0;
        if (access)
            for (Hub.Slot slot : Hub.kSlots) if (Hub.assignedPackage(this, slot.key) != null) assigned++;
        StringBuilder status = new StringBuilder();
        if (!access) {
            status.append("Allow access to all files: the hub keeps its settings, saves and logs in a ")
                  .append("SoleannaHub folder in your internal storage, and plays the game installed by Marathon Recompiled.");
        } else {
            status.append(assigned).append(" of ").append(Hub.kSlots.length)
                  .append(" gates and mission givers have a game. Walk into a stage gate, or say yes to a mission giver, "
                          + "to start its game; quit that game to come back to the same spot in Soleanna.\n\n");
            if (Paths.gameInstalled())
                status.append("Game files: ").append(Paths.game().getPath());
            else
                status.append("No game files yet. Install Sonic '06 with Marathon Recompiled first (it goes to "
                        + "MarathonRecomp/game), or tap Play to run the installer into SoleannaHub/game.");
            status.append("\nSettings, saves and logs: ").append(Paths.ROOT.getPath());
            status.append("\n\nStart Sonic's story (New Game, or Continue once you have a save) to arrive in Soleanna.");
        }
        TextView info = text(status.toString(), 14, kMuted, false);
        info.setPadding(dp(4), 0, dp(4), dp(8));
        page.addView(info);

        if (!access) {
            page.addView(button("Allow file access", true, v -> askFileAccess()));
        } else {
            page.addView(button(mFromGame ? "Back to Soleanna" : "Play", true, v -> {
                if (mFromGame) backToGame();
                else play();
            }));
            page.addView(button("Settings", false, v -> showSettings()));
            if (mFromGame) page.addView(button("Quit Soleanna Hub", false, v -> quit()));
        }
        TextView tip = text("In town, press Back or tap with three fingers to come here. "
                + "A four-finger tap opens the touch controls panel.", 12, kMuted, false);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(14), 0, 0);
        page.addView(tip);
        setPage(page);
    }

    private void backToGame() {
        Hub.log("Back to Soleanna");
        Intent game = new Intent(this, GameActivity.class);
        game.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(game);
        finish();
    }

    private void quit() {
        Hub.log("Quit from the menu");
        Intent game = new Intent(this, GameActivity.class);
        game.putExtra(GameActivity.EXTRA_QUIT, true);
        game.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(game);
        finishAffinity();
    }

    /** Puts the hub's town mission in place, prepares mods and patches, then starts the game. */
    private void play() {
        if (mStarting) return;
        if (!Paths.gameInstalled()) {
            launchGame();
            return;
        }
        mStarting = true;
        TextView message = text("Getting Soleanna ready…", 16, Color.WHITE, false);
        message.setPadding(dp(24), dp(24), dp(24), dp(24));
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(message).setCancelable(false).create();
        dialog.show();
        new Thread(() -> {
            try {
                HubMod.install(this);
                ModManager mods = new ModManager();
                mods.load();
                PatchesActivity.loadLibrary(this, mods);
                mods.apply(text -> runOnUiThread(() -> message.setText(text)));
                runOnUiThread(() -> {
                    dialog.dismiss();
                    mStarting = false;
                    if (!mods.problems.isEmpty())
                        Hub.log("Patch problems: " + mods.problems);
                    launchGame();
                });
            } catch (Exception e) {
                Hub.log("Could not prepare the hub: " + e);
                runOnUiThread(() -> {
                    dialog.dismiss();
                    mStarting = false;
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Soleanna couldn't be prepared")
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }, "HubPrepare").start();
    }

    private void launchGame() {
        Hub.log("Play");
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }

    // --- Settings --------------------------------------------------------------

    private void showSettings() {
        mShowingSettings = true;
        LinearLayout page = column();

        TextView title = text("Settings", 24, Color.WHITE, true);
        title.setPadding(dp(4), 0, 0, dp(12));
        page.addView(title);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < kTabs.length; i++) {
            final int index = i;
            TextView tab = text(kTabs[i], 13, i == mTab ? Color.WHITE : kMuted, i == mTab);
            tab.setSingleLine(true);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(2), dp(10), dp(2), dp(10));
            tab.setBackground(rounded(i == mTab ? kAccent : kCard, 12));
            tab.setOnClickListener(v -> { mTab = index; showSettings(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(dp(3), 0, dp(3), 0);
            tabs.addView(tab, params);
        }
        page.addView(tabs);

        switch (mTab) {
            case 0: slotsTab(page); break;
            case 1: launchingTab(page); break;
            case 2: gameTab(page); break;
            default: aboutTab(page); break;
        }
        page.addView(button(mFromGame ? "Back to Soleanna" : "Done", true, v -> {
            if (mFromGame) backToGame();
            else showHome();
        }));
        setPage(page);
    }

    private LinearLayout section(LinearLayout page, String name) {
        TextView label = text(name, 13, kGold, true);
        label.setPadding(dp(4), dp(18), 0, dp(6));
        page.addView(label);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(6), dp(16), dp(6));
        card.setBackground(rounded(kCard, 14));
        page.addView(card);
        return card;
    }

    private LinearLayout row(LinearLayout card, String name, String help) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(text(name, 16, Color.WHITE, false));
        if (help != null) row.addView(text(help, 12, kMuted, false));
        card.addView(row);
        return row;
    }

    private void launchingTab(LinearLayout page) {
        LinearLayout options = section(page, "LAUNCHING");
        LinearLayout skipRow = row(options, "Skip the game's start screen",
            "Entering a gate presses Play for you in ports that support it (Baka to Test, Black Rock Shooter, "
            + "Mario Hub-aware ports); others still show their start screen.");
        Switch skip = new Switch(this);
        skip.setChecked(Hub.setting(Hub.kSkipStartScreen, true));
        skip.setOnCheckedChangeListener((button, checked) -> Hub.setSetting(Hub.kSkipStartScreen, checked));
        skipRow.addView(skip);
        row(options, "Coming back", "Quit the other game (or switch back to Soleanna Hub) and Sonic is where you left him.");
    }

    private void gameTab(LinearLayout page) {
        LinearLayout card = section(page, "SONIC '06");
        row(card, "These are Marathon Recompiled's own screens, for this hub's copy of the game.", null);
        card.addView(button("Controls, GPU driver and FPS counter", false,
                v -> startActivity(new Intent(this, ControlsActivity.class))));
        card.addView(button("Mods", false, v -> startActivity(new Intent(this, ModsActivity.class))));
        card.addView(button("Patches and camera tweaks", false, v -> startActivity(new Intent(this, PatchesActivity.class))));
        TextView note = text("The Soleanna Hub mod is always on and loads first. Changes here apply the next time "
                + "the hub starts.", 12, kMuted, false);
        note.setPadding(dp(4), dp(8), 0, 0);
        page.addView(note);
    }

    private void aboutTab(LinearLayout page) {
        LinearLayout card = section(page, "FILES");
        row(card, "Hub folder", Paths.ROOT.getPath() + "\nsettings.ini (slots and options), Saves, Logs, mods");
        row(card, "Game files", Paths.game().getPath());
        row(card, "Logs", Paths.LOGS.getPath()
            + "\nlauncher.log lists every game a gate opened; the game's own logs sit next to it.");
        LinearLayout about = section(page, "ABOUT");
        row(about, "Version", getVersion());
        row(about, "How it works", "Sonic's first town mission is replaced by the hub's: all nine stage gates and "
            + "nineteen mission givers are out, every upgrade is on, and none of them load a stage. Each opens the app "
            + "you chose for it.");
        row(about, "Controller", "Bluetooth headsets (RB-HP160 / RB-HP180) are not game controllers.");
    }

    private String getVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // --- Slots tab ----------------------------------------------------------

    private void slotsTab(LinearLayout page) {
        TextView help = text("Tap a gate or mission giver to choose the game it opens. Long-press to clear it.", 13, kMuted, false);
        help.setPadding(dp(4), dp(14), 0, 0);
        page.addView(help);

        String where = null;
        LinearLayout card = null;
        for (Hub.Slot slot : Hub.kSlots) {
            if (!slot.where.equals(where)) {
                where = slot.where;
                card = section(page, where.toUpperCase(Locale.ROOT));
            }
            card.addView(slotRow(slot));
        }
    }

    private View slotRow(Hub.Slot slot) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        String pkg = Hub.assignedPackage(this, slot.key);
        ImageView icon = new ImageView(this);
        if (pkg != null) {
            try {
                icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
            } catch (PackageManager.NameNotFoundException e) {
                pkg = null;
            }
        }
        if (pkg == null) icon.setBackground(rounded(0xff2b3346, 10));
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, 0, 0);
        labels.addView(text(slot.name, 15, Color.WHITE, false));
        labels.addView(text(pkg != null ? Hub.appLabel(this, pkg) : "No game", 13, pkg != null ? kGold : kMuted, pkg != null));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.setOnClickListener(v -> pickApp(slot));
        row.setOnLongClickListener(v -> {
            Hub.assign(slot.key, null);
            showSettings();
            return true;
        });
        return row;
    }

    private static final class App {
        final String pkg, label;
        final boolean user;
        Drawable icon;
        App(String pkg, String label, boolean user) { this.pkg = pkg; this.label = label; this.user = user; }
    }

    /** Every app with a launcher icon, your own installs first, then the rest, each A to Z. */
    private List<App> launchableApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> apps = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(main, 0)) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || seen.contains(pkg)) continue;
            seen.add(pkg);
            ApplicationInfo app = info.activityInfo.applicationInfo;
            boolean user = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
            apps.add(new App(pkg, info.loadLabel(pm).toString(), user));
        }
        Collections.sort(apps, (a, b) -> {
            if (a.user != b.user) return a.user ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        return apps;
    }

    private void pickApp(Hub.Slot slot) {
        List<App> all = launchableApps();
        List<App> shown = new ArrayList<>(all);
        PackageManager pm = getPackageManager();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(8), dp(16), 0);
        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        layout.addView(search);
        ListView list = new ListView(this);
        layout.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));

        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int position) { return shown.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convert, ViewGroup parent) {
                App app = shown.get(position);
                LinearLayout item = new LinearLayout(HubActivity.this);
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(0, dp(8), 0, dp(8));
                ImageView icon = new ImageView(HubActivity.this);
                if (app.icon == null) {
                    try { app.icon = pm.getApplicationIcon(app.pkg); }
                    catch (PackageManager.NameNotFoundException e) { app.icon = null; }
                }
                icon.setImageDrawable(app.icon);
                item.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                TextView label = new TextView(HubActivity.this);
                label.setText(app.label);
                label.setTextSize(16);
                label.setPadding(dp(14), 0, 0, 0);
                item.addView(label);
                return item;
            }
        };
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (App app : all)
                    if (query.isEmpty() || app.label.toLowerCase(Locale.ROOT).contains(query)) shown.add(app);
                adapter.notifyDataSetChanged();
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(slot.name)
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("No game", (d, w) -> {
                Hub.assign(slot.key, null);
                showSettings();
            })
            .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            App app = shown.get(position);
            Hub.assign(slot.key, app.pkg);
            Hub.log("Slot " + slot.key + " set to " + app.pkg + " (" + app.label + ")");
            dialog.dismiss();
            showSettings();
        });
        dialog.show();
    }
}
