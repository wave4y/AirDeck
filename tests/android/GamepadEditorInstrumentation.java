package com.airdeck.hid.layouttests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.MotionEvent;

import com.airdeck.hid.GamepadLayoutConfig;
import com.airdeck.hid.GamepadPreset;
import com.airdeck.hid.GamepadView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** Runs real Android View code without starting MainActivity or creating a HID transport. */
public final class GamepadEditorInstrumentation extends Instrumentation {
    private int assertions, cases;
    private Throwable failure;
    private IsolatedContext isolated;
    private String currentCase = "setup";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        runOnMainSync(() -> {
            isolated = new IsolatedContext(getTargetContext());
            try {
                check(GamepadPreset.values().length == 9, "all nine presets are present");
                int[][] sizes = {{840, 390}, {1680, 720}, {1200, 400}};
                for (GamepadPreset preset : GamepadPreset.values()) {
                    for (int[] size : sizes) {
                        currentCase = preset.name() + " " + size[0] + "x" + size[1];
                        runEditorCase(preset, size[0], size[1]);
                        cases++;
                    }
                }
            } catch (Throwable error) {
                failure = error;
            } finally {
                try { isolated.clear(); }
                catch (Throwable error) { if (failure == null) failure = error; }
            }
        });
        Bundle result = new Bundle();
        result.putInt("assertions", assertions);
        result.putInt("cases", cases);
        if (failure == null) {
            result.putString("stream", "\nPASS: " + assertions + " editor assertions across " + cases
                    + " preset/size cases. No HID transport was created.\n");
            result.putString("result", "PASS");
            finish(Activity.RESULT_OK, result);
        } else {
            result.putString("stream", "\nFAIL in " + currentCase + ": "
                    + android.util.Log.getStackTraceString(failure) + "\n");
            result.putString("result", "FAIL");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runEditorCase(GamepadPreset preset, int width, int height) throws Exception {
        SharedPreferences prefs = isolated.getSharedPreferences("gamepad_layouts", Context.MODE_PRIVATE);
        check(prefs.edit().clear().commit(), "clear isolated preferences");
        String key = "landscape_v1_" + preset.name();
        GamepadView view = newView(preset, width, height, true);
        Recorder reports = new Recorder();
        view.setListener(reports);
        int[] ids = view.getEditableControlIds();
        check(ids.length > 0, "editable controls exposed");
        check(ids.length == view.getEditableControlLabels().length, "labels align with controls");
        RectF originalFace = frame(view, 0);
        check(originalFace != null, "face control exists");
        check(view.beginLayoutEditing(), "landscape starts editing");
        check(view.isLayoutEditing(), "editing flag set");
        check(reports.calls == 1 && reports.nonNeutral == 0, "begin emits only one neutral release");
        int editingCalls = reports.calls;

        for (int id : ids) {
            view.selectEditableControl(id);
            check(view.getSelectedControlLabel() != null, "control selectable " + id);
            check(view.getSelectedControlSizePercent() == 100, "default scale " + id);
            view.resizeSelectedControl(.1f);
            check(view.getSelectedControlSizePercent() == 110, "control independently resizes " + id);
            assertInside(view, frame(view, id), "resized control " + id);
            check(reports.calls == editingCalls, "select/resize cannot dispatch game state " + id);
        }
        view.resetEditedLayout();
        check(reports.calls == editingCalls, "reset cannot dispatch game state");
        rect(originalFace, frame(view, 0), "reset restores default geometry");
        check(prefs.getString(key, null) == null, "draft resize/reset did not save");

        if (preset.hasRightStick()) {
            RectF leftBefore = frame(view, 20), rightBefore = frame(view, 21);
            view.selectEditableControl(20);
            view.resizeSelectedControl(.25f);
            near(frame(view, 20).width(), leftBefore.width() * 1.25f, "left stick radius changes");
            rect(rightBefore, frame(view, 21), "right stick unaffected by left resize");
            view.selectEditableControl(21);
            view.resizeSelectedControl(-.2f);
            near(frame(view, 21).width(), rightBefore.width() * .8f, "right stick radius changes");
            near(frame(view, 20).width(), leftBefore.width() * 1.25f, "left stick unaffected by right resize");
            check(reports.calls == editingCalls, "stick editing cannot dispatch game state");
            view.resetEditedLayout();
        }

        view.selectEditableControl(0);
        drag(view, originalFace.centerX(), originalFace.centerY(), 420, 210);
        RectF moved = frame(view, 0);
        near(moved.centerX(), 420, "drag updates center X");
        near(moved.centerY(), 210, "drag updates center Y");
        check(hit(view, moved.centerX(), moved.centerY()) == 0, "hit region follows dragged face");
        check(hit(view, originalFace.centerX(), originalFace.centerY()) != 0, "old face position no longer hits face");
        check(reports.calls == editingCalls, "drag cannot dispatch game state");
        draw(view);
        check(reports.calls == editingCalls, "drawing editor cannot dispatch game state");

        view.resizeSelectedControl(100);
        check(view.getSelectedControlSizePercent() == 150, "maximum size clamps");
        near(frame(view, 0).width(), originalFace.width() * GamepadLayoutConfig.MAX_SIZE, "maximum drawn frame");
        view.resizeSelectedControl(-100);
        check(view.getSelectedControlSizePercent() == 65, "minimum size clamps");
        near(frame(view, 0).width(), originalFace.width() * GamepadLayoutConfig.MIN_SIZE, "minimum drawn frame");
        view.resizeSelectedControl(.55f);
        check(view.getSelectedControlSizePercent() == 120, "normal scale restored");
        drag(view, frame(view, 0).centerX(), frame(view, 0).centerY(), -500, -500);
        assertInside(view, frame(view, 0), "drag outside top/left clamps");
        drag(view, frame(view, 0).centerX(), frame(view, 0).centerY(), 2000, 2000);
        assertInside(view, frame(view, 0), "drag outside bottom/right clamps");
        drag(view, frame(view, 0).centerX(), frame(view, 0).centerY(), 420, 210);
        check(reports.calls == editingCalls && reports.nonNeutral == 0, "all editor gestures stay silent");
        RectF saved = frame(view, 0);
        view.saveLayoutEditing();
        check(!view.isLayoutEditing(), "save leaves editor");
        check(reports.calls == editingCalls + 1 && reports.nonNeutral == 0, "save emits only neutral release");
        String persisted = prefs.getString(key, null);
        check(persisted != null, "save persists isolated layout");
        GamepadView reloaded = newView(preset, width, height, true);
        rect(saved, frame(reloaded, 0), "new view reloads saved geometry");
        check(hit(reloaded, saved.centerX(), saved.centerY()) == 0, "saved hit region reloads");
        draw(reloaded);

        Recorder reloadReports = new Recorder();
        reloaded.setListener(reloadReports);
        check(reloaded.beginLayoutEditing(), "saved view can edit again");
        reloaded.selectEditableControl(0);
        reloaded.resizeSelectedControl(.2f);
        reloaded.cancelLayoutEditing();
        rect(saved, frame(reloaded, 0), "cancel restores saved geometry");
        check(persisted.equals(prefs.getString(key, null)), "cancel does not persist draft");
        check(reloadReports.nonNeutral == 0, "cancel cannot emit pressed state");

        check(reloaded.beginLayoutEditing(), "begin reset draft");
        int beforeReset = reloadReports.calls;
        reloaded.resetEditedLayout();
        rect(defaultFrame(reloaded, 0), frame(reloaded, 0), "reset draft shows default frame");
        check(reloadReports.calls == beforeReset, "reset draft is silent");
        check(persisted.equals(prefs.getString(key, null)), "reset is not saved automatically");
        reloaded.cancelLayoutEditing();
        rect(saved, frame(reloaded, 0), "cancel reset restores saved override");
        rect(saved, frame(newView(preset, width, height, true), 0), "cancelled reset never changes storage");

        GamepadView portrait = newView(preset, 360, 760, false);
        rect(defaultFrame(portrait, 0), frame(portrait, 0), "portrait ignores landscape override");
        check(!portrait.beginLayoutEditing(), "portrait cannot start landscape editor");
        GamepadView narrowImmersive = newView(preset, 360, 760, true);
        rect(defaultFrame(narrowImmersive, 0), frame(narrowImmersive, 0), "narrow immersive surface ignores override");
        check(!narrowImmersive.beginLayoutEditing(), "narrow immersive surface cannot edit");
        GamepadView preview = newView(preset, width, height, false);
        rect(defaultFrame(preview, 0), frame(preview, 0), "non-immersive preview ignores landscape override");
        check(!preview.beginLayoutEditing(), "preview cannot edit");
        check(reports.nonNeutral == 0 && reloadReports.nonNeutral == 0, "no test editor operation sent game input");
        if (preset.hasShoulders()) verifyOverlapHit(preset, width, height);
    }

    private void verifyOverlapHit(GamepadPreset preset, int width, int height) throws Exception {
        GamepadView view = newView(preset, width, height, true);
        Recorder reports = new Recorder();
        view.setListener(reports);
        check(view.beginLayoutEditing(), "begin overlap regression");
        int editingCalls = reports.calls;
        view.selectEditableControl(0);
        RectF face = frame(view, 0), shoulder = frame(view, 4);
        drag(view, face.centerX(), face.centerY(), shoulder.centerX(), shoulder.centerY());
        check(reports.calls == editingCalls && reports.nonNeutral == 0, "overlap editing stays silent");
        view.saveLayoutEditing();
        check(reports.nonNeutral == 0, "saving overlap does not press any key");
        face = frame(view, 0);
        check(RectF.intersects(face, shoulder), "face visibly overlaps shoulder");
        draw(view);
        check(hit(view, face.centerX(), face.centerY()) == 0, "painted upper face wins overlap hit");
        long down = SystemClock.uptimeMillis();
        touch(view, down, down, MotionEvent.ACTION_DOWN, face.centerX(), face.centerY());
        check(reports.lastPressedButtons == (1 << preset.faceHidBit(0)), "gameplay overlap emits face only to fake listener");
        touch(view, down, down + 16, MotionEvent.ACTION_UP, face.centerX(), face.centerY());
        check(reports.lastButtons == 0, "gameplay touch releases face");
    }

    private GamepadView newView(GamepadPreset preset, int width, int height, boolean immersive) {
        GamepadView view = new GamepadView(isolated);
        view.setHapticFeedback(false);
        view.setPreset(preset);
        view.setImmersive(immersive);
        view.layout(0, 0, width, height);
        return view;
    }

    private void draw(GamepadView view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try { view.draw(new Canvas(bitmap)); }
        finally { bitmap.recycle(); }
        check(true, "real Canvas drawing completed");
    }

    private static Object field(GamepadView view, String name) throws Exception {
        Field field = GamepadView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(view);
    }

    private static float number(GamepadView view, String name) throws Exception { return ((Number) field(view, name)).floatValue(); }

    private static RectF frame(GamepadView view, int id) throws Exception {
        Method method = GamepadView.class.getDeclaredMethod("controlFrame", int.class);
        method.setAccessible(true);
        RectF value = (RectF) method.invoke(view, id);
        return value == null ? null : new RectF(value);
    }

    @SuppressWarnings("unchecked") private static RectF defaultFrame(GamepadView view, int id) throws Exception {
        return new RectF(((SparseArray<RectF>) field(view, "defaultFrames")).get(id));
    }

    private static int hit(GamepadView view, float x, float y) throws Exception {
        Method method = GamepadView.class.getDeclaredMethod("hit", float.class, float.class);
        method.setAccessible(true);
        return (Integer) method.invoke(view, x, y);
    }

    private void drag(GamepadView view, float fromX, float fromY, float toX, float toY) throws Exception {
        long down = SystemClock.uptimeMillis();
        touch(view, down, down, MotionEvent.ACTION_DOWN, fromX, fromY);
        touch(view, down, down + 16, MotionEvent.ACTION_MOVE, toX, toY);
        touch(view, down, down + 32, MotionEvent.ACTION_UP, toX, toY);
    }

    private void touch(GamepadView view, long down, long time, int action, float x, float y) throws Exception {
        float scale = number(view, "scale");
        MotionEvent event = MotionEvent.obtain(down, time, action,
                number(view, "offsetX") + x * scale, number(view, "offsetY") + y * scale, 0);
        try { check(view.onTouchEvent(event), "real MotionEvent handled"); }
        finally { event.recycle(); }
    }

    private void assertInside(GamepadView view, RectF frame, String label) throws Exception {
        check(frame != null && frame.left >= 0 && frame.top >= 0
                && frame.right <= number(view, "layoutW") && frame.bottom <= number(view, "layoutH"), label);
    }

    private void rect(RectF expected, RectF actual, String label) {
        check(expected != null && actual != null, label + " has frames");
        near(actual.left, expected.left, label + " left");
        near(actual.top, expected.top, label + " top");
        near(actual.right, expected.right, label + " right");
        near(actual.bottom, expected.bottom, label + " bottom");
    }

    private void near(float actual, float expected, String label) {
        check(Math.abs(actual - expected) < .05f, label + ": expected " + expected + ", actual " + actual);
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(currentCase + ": " + label);
    }

    private static final class Recorder implements GamepadView.Listener {
        int calls, nonNeutral, lastButtons, lastPressedButtons;
        @Override public void onState(int buttons, int hat, int lx, int ly, int rx, int ry, int lt, int rt) {
            calls++;
            lastButtons = buttons;
            if (buttons != 0) lastPressedButtons = buttons;
            if (buttons != 0 || hat != 8 || lx != 0 || ly != 0 || rx != 0 || ry != 0 || lt != 0 || rt != 0) nonNeutral++;
        }
    }

    private static final class IsolatedContext extends ContextWrapper {
        private final String prefix = "airdeck_editor_instrumentation_" + System.nanoTime() + "_";
        private final Set<String> created = new HashSet<>();
        IsolatedContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            String isolatedName = prefix + name;
            created.add(isolatedName);
            return getBaseContext().getSharedPreferences(isolatedName, mode);
        }
        void clear() {
            for (String name : created) getBaseContext().deleteSharedPreferences(name);
        }
    }
}
