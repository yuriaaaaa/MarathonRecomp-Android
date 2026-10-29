package com.marathonrecomp.android;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * Runs the recompiled game (libmain.so, SDL statically linked into it), with the on-screen
 * touch controls on top. A four-finger tap opens the touch controls panel; the system back
 * button asks whether to exit to the launcher.
 *
 * Runs in its own process (":game", see the manifest), so exiting can end the game process
 * while the launcher, in the app's main process, opens again.
 */
public class GameActivity extends SDLActivity {
    public static final String EXTRA_ARGS = "args";

    /** Soleanna Hub: Intent extra that closes the game (the hub menu's Quit). */
    public static final String EXTRA_QUIT = "quit";

    private static final int PANEL_FINGERS = 4;
    private static final int HUB_MENU_FINGERS = 3;
    private static final long PANEL_TAP_WINDOW_MS = 400;

    private TouchControlsView controls;
    private ControlsSettings settings;
    private AlertDialog panel;
    private ExitDialog exitDialog;
    private boolean panelGesture;
    private boolean exiting;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Asks the game to quit the way the PC version does on closing the window (it saves its settings). */
    private static native void nativeRequestExit();

    /** Frames the game has presented so far (for the FPS counter). */
    private static native long nativeGetPresentCount();

    /** Soleanna Hub (os/android/hub_android.cpp): lets the game call hubOpenSlot. */
    private static native void nativeHubInit();

    /** The window is going away: the swap chain must be rebuilt for the next one. */
    private static native void nativeWindowLost();

    private static GameActivity sRunning;
    private long lastResume;
    private long lastSlotMessage;
    private long lastHubMenu;
    private boolean hubMenuGesture;

    static long presentCount() {
        return nativeGetPresentCount();
    }

    private FpsCounterView fpsCounter;

    @Override
    protected String[] getLibraries() {
        return new String[] { "c++_shared", "main" };
    }

    @Override
    protected String[] getArguments() {
        String[] args = getIntent().getStringArrayExtra(EXTRA_ARGS);
        return args != null ? args : new String[0];
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        passPaths();
        passDriverChoice();
        super.onCreate(savedInstanceState);
        sRunning = this;
        try {
            nativeHubInit();
        } catch (UnsatisfiedLinkError ignored) {
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        settings = ControlsSettings.load(this);
        // The installer is driven by touch, so the controls only appear once the game is installed.
        String[] args = getArguments();
        boolean installer = !Paths.gameInstalled()
                || (args.length > 0 && args[0].equals("--install-dlc"));
        // The game gets the touch controller whenever the overlay exists, so it can be switched
        // on from the four-finger panel; while hidden it simply sends nothing.
        TouchControlsView.configure(this, !installer, settings.rumble);

        if (!installer && mLayout != null) {
            controls = new TouchControlsView(this, settings);
            controls.setVisibility(settings.enabled ? View.VISIBLE : View.GONE);
            mLayout.addView(controls, new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setFpsCounterVisible(settings.showFps);
    }

    /** Where the game keeps its files (os/android/android_paths.h), read when the library loads. */
    private void passPaths() {
        try {
            Os.setenv("MARATHON_ANDROID_ROOT", Paths.ROOT.getAbsolutePath(), true);
            Os.setenv("MARATHON_GAME_PARENT", Paths.gameParent().getAbsolutePath(), true);
            Os.setenv("MARATHON_USER_DIR", Paths.USER.getAbsolutePath(), true);
            Os.setenv("MARATHON_LOG_DIR", Paths.LOGS.getAbsolutePath(), true);
        } catch (ErrnoException ignored) {
        }
    }

    /**
     * Tells the game (through its environment, read in gpu_android.cpp before Vulkan starts)
     * which GPU driver to load. This runs in the game's own process, before SDL starts the game.
     */
    private void passDriverChoice() {
        try {
            DriverManager.folder(this).mkdirs();
            Os.setenv("MARATHON_NATIVE_LIB_DIR", getApplicationInfo().nativeLibraryDir, true);
            Os.setenv("MARATHON_DRIVER_STATUS", DriverManager.lastStartFile(this).getAbsolutePath(), true);
            DriverManager.Driver driver = DriverManager.active(this);
            if (driver != null)
                Os.setenv("MARATHON_CUSTOM_DRIVER", driver.library.getAbsolutePath(), true);
            else
                Os.unsetenv("MARATHON_CUSTOM_DRIVER");
        } catch (ErrnoException ignored) {
        }
    }

    private void setFpsCounterVisible(boolean visible) {
        if (visible && fpsCounter == null && mLayout != null) {
            fpsCounter = new FpsCounterView(this);
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            params.addRule(RelativeLayout.CENTER_HORIZONTAL);
            params.topMargin = ControlsPanel.dp(this, 10);
            mLayout.addView(fpsCounter, params);
            fpsCounter.start();
        } else if (!visible && fpsCounter != null) {
            fpsCounter.stop();
            mLayout.removeView(fpsCounter);
            fpsCounter = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastResume = System.currentTimeMillis();
    }

    @Override
    protected void onPause() {
        try {
            nativeWindowLost();
        } catch (UnsatisfiedLinkError ignored) {
        }
        super.onPause();
        if (controls != null)
            controls.releaseAll();
        TouchControlsView.stopRumble();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_QUIT, false))
            quitGame();
    }

    @Override
    protected void onDestroy() {
        if (sRunning == this)
            sRunning = null;
        if (panel != null)
            panel.dismiss();
        if (exitDialog != null)
            exitDialog.dismiss();
        TouchControlsView.stopRumble();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ physical controllers

    private static boolean fromController(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void controllerUsed() {
        if (controls != null && settings.enabled && settings.hideWithController)
            controls.fade();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The system back button/gesture asks to exit. SDL would otherwise pass it to the game
        // (trapped as a key), which ignores it. Back from a controller still goes to the game.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && isSystemBack(event.getSource())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                onBackRequested();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && fromController(event.getSource())
                && event.getKeyCode() != KeyEvent.KEYCODE_BACK)
            controllerUsed();
        return super.dispatchKeyEvent(event);
    }

    /** Back from the navigation bar or gesture: not from a controller, D-pad or mouse. */
    private static boolean isSystemBack(int source) {
        int controllerBits = (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_DPAD)
                & ~InputDevice.SOURCE_CLASS_BUTTON & ~InputDevice.SOURCE_CLASS_JOYSTICK;
        boolean controller = (source & controllerBits) != 0;
        boolean mouse = (source & InputDevice.SOURCE_CLASS_POINTER) != 0;
        return !controller && !mouse;
    }

    @Override
    public void onBackPressed() {
        onBackRequested();
    }

    private void onBackRequested() {
        if (exiting)
            return;
        if (BuildConfig.HUB && (panel == null || !panel.isShowing())) {
            openHubMenu("Back");
            return;
        }
        if (panel != null && panel.isShowing()) {
            panel.dismiss();
        } else if (exitDialog != null && exitDialog.isShowing()) {
            exitDialog.dismiss();
        } else {
            exitDialog = new ExitDialog(this, this::exitToLauncher);
            exitDialog.show();
        }
    }

    // ------------------------------------------------------------------ Soleanna Hub

    /**
     * Called by the game thread (hub_android.cpp) when Sonic enters a gate or accepts a mission.
     * Opens the app chosen for that slot; Sonic stays in town, so coming back lands at that spot.
     */
    public static int hubOpenSlot(String key) {
        GameActivity activity = sRunning;
        if (activity == null)
            return 0;
        activity.runOnUiThread(() -> activity.openSlot(key));
        return 1;
    }

    private void openSlot(String key) {
        long now = System.currentTimeMillis();
        // Sonic is still standing in the gate when the hub comes back: don't send him straight off again.
        if (now - lastResume < 2500) {
            Hub.log("Slot " + key + " ignored right after coming back");
            return;
        }
        if (Hub.launch(this, key))
            return;
        if (now - lastSlotMessage < 2500)
            return;
        lastSlotMessage = now;
        Hub.Slot slot = Hub.slot(key);
        String name = slot != null ? slot.name : key;
        Hub.log("Slot " + key + " has no app");
        Toast.makeText(this, "No game on the " + name + " yet. Press Back, then Slots to pick one.",
                Toast.LENGTH_LONG).show();
    }

    /** The hub menu (Back to Soleanna / Slots / Settings / Quit) over the game, which keeps its place. */
    private void openHubMenu(String why) {
        long now = System.currentTimeMillis();
        if (now - lastHubMenu < 700 || isFinishing() || exiting)
            return;
        lastHubMenu = now;
        if (controls != null)
            controls.releaseAll();
        Hub.log("Menu opened (" + why + ")");
        Intent menu = new Intent(this, HubActivity.class);
        menu.putExtra(HubActivity.EXTRA_FROM_GAME, true);
        startActivity(menu);
    }

    /** The hub menu's Quit: save settings and end the game process. */
    private void quitGame() {
        if (exiting)
            return;
        exiting = true;
        if (controls != null)
            controls.releaseAll();
        TouchControlsView.stopRumble();
        finish();
        requestExit(0);
    }

    /** Closes the game and opens the launcher again. */
    private void exitToLauncher() {
        if (exiting)
            return;
        exiting = true;
        if (controls != null)
            controls.releaseAll();
        TouchControlsView.stopRumble();
        startActivity(new Intent(this, BuildConfig.HUB ? HubActivity.class : LauncherActivity.class));
        finish();
        requestExit(0);
    }

    private void requestExit(int attempt) {
        try {
            // Normally doesn't return: the game saves its settings and ends this process.
            nativeRequestExit();
        } catch (UnsatisfiedLinkError ignored) {
        }
        // Still here: the game is saving (it won't quit mid-save) or hasn't started. Try again,
        // then end the process anyway after a few seconds.
        if (attempt < 20)
            handler.postDelayed(() -> requestExit(attempt + 1), 250);
        else
            Process.killProcess(Process.myPid());
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            float x = event.getAxisValue(MotionEvent.AXIS_X), y = event.getAxisValue(MotionEvent.AXIS_Y);
            float z = event.getAxisValue(MotionEvent.AXIS_Z), rz = event.getAxisValue(MotionEvent.AXIS_RZ);
            if (Math.abs(x) > 0.5f || Math.abs(y) > 0.5f || Math.abs(z) > 0.5f || Math.abs(rz) > 0.5f)
                controllerUsed();
        }
        return super.dispatchGenericMotionEvent(event);
    }

    // ------------------------------------------------------------------ four-finger panel

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                panelGesture = false;
                hubMenuGesture = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (BuildConfig.HUB && !panelGesture && event.getPointerCount() == HUB_MENU_FINGERS
                        && event.getEventTime() - event.getDownTime() <= PANEL_TAP_WINDOW_MS) {
                    // Wait for the tap to end: a fourth finger means the controls panel instead.
                    hubMenuGesture = true;
                } else if (event.getPointerCount() > HUB_MENU_FINGERS) {
                    hubMenuGesture = false;
                }
                if (!panelGesture && controls != null && event.getPointerCount() >= PANEL_FINGERS
                        && event.getEventTime() - event.getDownTime() <= PANEL_TAP_WINDOW_MS
                        && (panel == null || !panel.isShowing())) {
                    panelGesture = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    showPanel();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (hubMenuGesture && event.getActionMasked() == MotionEvent.ACTION_UP && !panelGesture
                        && event.getEventTime() - event.getDownTime() <= 700) {
                    hubMenuGesture = false;
                    openHubMenu("three-finger tap");
                }
                hubMenuGesture = false;
                if (panelGesture) {
                    panelGesture = false;
                    return true;
                }
                break;
        }
        if (panelGesture)
            return true;
        return super.dispatchTouchEvent(event);
    }

    private void showPanel() {
        panel = ControlsPanel.show(this, settings, () -> {
            if (panel != null)
                panel.dismiss();
            onBackRequested();
        }, new ControlsPanel.Listener() {
            @Override
            public void onChanged(ControlsSettings s) {
                controls.setVisibility(s.enabled ? View.VISIBLE : View.GONE);
                if (!s.enabled)
                    controls.releaseAll();
                controls.setOpacity(s.opacity);
                controls.setScale(s.scale);
                controls.setHaptics(s.buttonHaptics);
                TouchControlsView.setRumble(s.rumble);
                setFpsCounterVisible(s.showFps);
            }
        });
    }
}
