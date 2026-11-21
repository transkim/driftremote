package app.driftremote.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

import app.driftremote.core.MouseServer;

/**
 * Runs on the TV. Shows a mouse pointer over every app and performs clicks, long
 * presses, drags and scrolls at the pointer with accessibility gestures, driven by
 * the phone over the mouse link.
 */
@TargetApi(Build.VERSION_CODES.N)
public class PointerService extends AccessibilityService implements MouseServer.Sink {
    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private CursorView cursor;
    private WindowManager.LayoutParams cursorLp;
    private TextView codeView;
    private MouseServer server;
    private int screenW, screenH;
    private float x, y;
    private float scale;
    private Finger finger;
    private boolean dragging;
    private final Runnable hide = () -> {
        if (cursor != null) cursor.setVisibility(android.view.View.INVISIBLE);
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        screenW = Math.max(size.x, size.y);
        screenH = Math.min(size.x, size.y);
        scale = screenW / 520f;
        x = screenW / 2f;
        y = screenH / 2f;
        finger = new Finger(this);

        cursor = new CursorView(this);
        cursorLp = overlayParams(cursor.sizePx(), cursor.sizePx());
        cursorLp.gravity = Gravity.TOP | Gravity.LEFT;
        placeCursor();
        cursor.setVisibility(android.view.View.INVISIBLE);
        try {
            wm.addView(cursor, cursorLp);
        } catch (RuntimeException e) {
            cursor = null;
        }

        SharedPreferences prefs = getSharedPreferences("pointer", MODE_PRIVATE);
        server = new MouseServer(MouseServer.PORT, this, new MouseServer.Trust() {
            @Override
            public boolean isTrusted(String token) {
                return prefs.getStringSet("tokens", new HashSet<>()).contains(token);
            }

            @Override
            public void addTrusted(String token) {
                Set<String> s = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
                s.add(token);
                prefs.edit().putStringSet("tokens", s).apply();
            }
        });
        server.start();
    }

    private WindowManager.LayoutParams overlayParams(int w, int h) {
        int type = Build.VERSION.SDK_INT >= 22
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        return lp;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        running = false;
        if (server != null) server.stop();
        main.removeCallbacksAndMessages(null);
        try {
            if (cursor != null) wm.removeView(cursor);
            if (codeView != null) wm.removeView(codeView);
        } catch (RuntimeException ignored) {
        }
        super.onDestroy();
    }

    // ---- pointer ----

    private void placeCursor() {
        if (cursor == null) return;
        cursorLp.x = Math.round(x) - cursor.hotspot();
        cursorLp.y = Math.round(y) - cursor.hotspot();
        try {
            if (cursor.isAttachedToWindow()) wm.updateViewLayout(cursor, cursorLp);
        } catch (RuntimeException ignored) {
        }
    }

    private void showCursor() {
        if (cursor == null) return;
        cursor.setVisibility(android.view.View.VISIBLE);
        main.removeCallbacks(hide);
        main.postDelayed(hide, 8000);
    }

    private void moveBy(float dx, float dy) {
        x = Math.max(0, Math.min(screenW - 1, x + dx * scale));
        y = Math.max(0, Math.min(screenH - 1, y + dy * scale));
        placeCursor();
        showCursor();
    }

    @Override
    public void move(float dx, float dy) {
        main.post(() -> {
            moveBy(dx, dy);
            if (dragging) finger.moveTo(x, y);
        });
    }

    @Override
    public void click() {
        main.post(() -> {
            showCursor();
            if (cursor != null) cursor.pulse();
            tap(x, y, 40);
        });
    }

    @Override
    public void longPress() {
        main.post(() -> {
            showCursor();
            if (cursor != null) cursor.pulse();
            tap(x, y, 900);
        });
    }

    @Override
    public void dragStart() {
        main.post(() -> {
            showCursor();
            if (cursor != null) cursor.setPressed(true);
            dragging = true;
            // Hold briefly first so lists and launchers treat it as "pick up".
            finger.down(x, y, 500);
        });
    }

    @Override
    public void dragMove(float dx, float dy) {
        move(dx, dy);
    }

    @Override
    public void dragEnd() {
        main.post(() -> {
            dragging = false;
            if (cursor != null) cursor.setPressed(false);
            finger.up();
        });
    }

    // Scrolling = a finger swiping where the pointer is, while the pointer stays put.
    private float sx, sy;

    @Override
    public void scrollStart() {
        main.post(() -> {
            showCursor();
            sx = x;
            sy = y;
            finger.down(sx, sy, 0);
        });
    }

    @Override
    public void scrollMove(float dx, float dy) {
        main.post(() -> {
            sx = Math.max(1, Math.min(screenW - 2, sx + dx * scale));
            sy = Math.max(1, Math.min(screenH - 2, sy + dy * scale));
            finger.moveTo(sx, sy);
            showCursor();
        });
    }

    @Override
    public void scrollEnd() {
        main.post(() -> finger.up());
    }

    @Override
    public int[] screenSize() {
        return new int[]{screenW, screenH};
    }

    private void tap(float px, float py, long duration) {
        Path p = new Path();
        p.moveTo(px, py);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, duration)).build();
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCancelled(GestureDescription d) {
                clickNodeAt(px, py, duration > 500);
            }
        }, null);
        if (!sent) clickNodeAt(px, py, duration > 500);
    }

    /** Fallback when a touch can't be injected: click the item under the pointer directly. */
    private void clickNodeAt(float px, float py, boolean longClick) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo target = deepest(root, (int) px, (int) py, longClick);
        if (target != null) {
            target.performAction(longClick ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
        }
    }

    private AccessibilityNodeInfo deepest(AccessibilityNodeInfo node, int px, int py, boolean longClick) {
        if (node == null) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.contains(px, py)) return null;
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo hit = deepest(node.getChild(i), px, py, longClick);
            if (hit != null) return hit;
        }
        boolean ok = longClick ? node.isLongClickable() : (node.isClickable() || node.isFocusable());
        return ok ? node : null;
    }

    // ---- pairing code on screen ----

    @Override
    public void showCode(String phoneName, String code) {
        main.post(() -> {
            if (codeView != null) {
                try {
                    wm.removeView(codeView);
                } catch (RuntimeException ignored) {
                }
                codeView = null;
            }
            if (code == null) return;
            TextView t = new TextView(this);
            t.setText(phoneName + " wants to use the mouse.\nType this code on the phone:  " + code);
            t.setTextSize(22);
            t.setTextColor(0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            int pad = (int) (24 * getResources().getDisplayMetrics().density);
            t.setPadding(pad, pad, pad, pad);
            t.setBackgroundColor(0xE61A2025);
            WindowManager.LayoutParams lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = (int) (48 * getResources().getDisplayMetrics().density);
            try {
                wm.addView(t, lp);
                codeView = t;
            } catch (RuntimeException ignored) {
            }
            main.postDelayed(() -> showCode(null, null), 90000);
        });
    }
}
