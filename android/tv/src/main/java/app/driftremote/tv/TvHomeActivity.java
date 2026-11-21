package app.driftremote.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;


/** What the user sees when opening Driftremote on the TV: turn the mouse on. */
public class TvHomeActivity extends Activity {
    private TextView status;
    private Button toggle;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0E1215);
        // TVs often crop the screen edges (overscan): keep text well inside.
        int side = (int) (getResources().getDisplayMetrics().widthPixels * 0.12f);
        root.setPadding(side, (int) (40 * d), side, (int) (40 * d));

        TextView title = text("Driftremote mouse", 30, 0xFFE9EFF1);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        status = text("", 20, 0xFF6CD4A8);
        root.addView(status);

        TextView steps = text("This lets the Driftremote app on your phone move a mouse pointer on this TV, "
                + "click, drag and scroll.\n\n"
                + "1. Select \"Turn on the mouse\" below.\n"
                + "2. In Accessibility, open \"Driftremote mouse\" and switch it on.\n"
                + "3. On your phone, open the remote and tap the mouse button. Type the code this TV shows.",
                16, 0xFFB8C3C8);
        steps.setGravity(Gravity.START);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = (int) (28 * d);
        root.addView(steps, sl);

        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setTextSize(20);
        toggle.setFocusable(true);
        toggle.setTextColor(0xFFE9EFF1);
        toggle.setPadding((int) (32 * d), (int) (12 * d), (int) (32 * d), (int) (12 * d));
        toggle.setBackground(buttonBackground(d));
        toggle.setOnClickListener(v -> {
            if (canSwitchDirectly()) {
                switchOnDirectly();
                status.setText("Turning the mouse on…");
                status.postDelayed(this::onResume, 1500);
            } else {
                openAccessibility();
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = (int) (32 * d);
        root.addView(toggle, bl);

        TextView manual = text("Or by hand: Settings > System (or Device Preferences) > Accessibility > Driftremote mouse",
                14, 0xFF8D999F);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = (int) (14 * d);
        root.addView(manual, ml);

        TextView footer = text("Developed by " + getString(R.string.developer) + "  ·  Version " + BuildConfig.VERSION_NAME,
                14, 0xFF8D999F);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, -2);
        fl.topMargin = (int) (36 * d);
        root.addView(footer, fl);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF0E1215);
        scroll.addView(root);
        setContentView(scroll);
        toggle.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isEnabled();
        status.setText(on ? "The mouse is on. You can control it from your phone." : "The mouse is off.");
        toggle.setText(on ? "Accessibility settings" : "Turn on the mouse");
    }

    private boolean isEnabled() {
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(list)) return PointerService.running;
        String me = new ComponentName(this, PointerService.class).flattenToString();
        for (String s : list.split(":")) if (s.equalsIgnoreCase(me)) return true;
        return PointerService.running;
    }

    /** True once a computer has run: adb shell pm grant app.driftremote.tv android.permission.WRITE_SECURE_SETTINGS */
    private boolean canSwitchDirectly() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Adds the mouse service to the TV's list of enabled accessibility services. */
    private void switchOnDirectly() {
        try {
            android.content.ContentResolver cr = getContentResolver();
            String me = new ComponentName(this, PointerService.class).flattenToString();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean has = false;
            if (!TextUtils.isEmpty(list)) for (String x : list.split(":")) if (x.equalsIgnoreCase(me)) has = true;
            if (!has) list = TextUtils.isEmpty(list) ? me : list + ":" + me;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (RuntimeException e) {
            openAccessibility();
        }
    }

    /**
     * TV makers move the Accessibility screen around, and some (e.g. Hisense) don't
     * answer the standard shortcut. Try the known screens, then explain the way by hand.
     */
    private void openAccessibility() {
        Intent[] tries = {
                new Intent().setClassName("com.android.tv.settings",
                        "com.android.tv.settings.accessibility.AccessibilityActivity"),
                new Intent().setClassName("com.android.tv.settings",
                        "com.android.tv.settings.accessibility.AccessibilitySettingsActivity"),
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.Settings$AccessibilitySettingsActivity"),
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        };
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
            if (ri == null || ri.activityInfo == null || !ri.activityInfo.exported) continue;
            try {
                startActivity(i);
                return;
            } catch (RuntimeException ignored) {
                // try the next one
            }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Turn on the mouse by hand")
                .setMessage("This TV doesn't open Accessibility directly. Press the Settings (gear) button on the TV remote and go to:\n\n"
                        + "Settings > System (or Device Preferences) > Accessibility > Driftremote mouse > On\n\n"
                        + "If the switch is greyed out or says \"Restricted setting\", it can be turned on from a computer instead "
                        + "(ask in the Driftremote chat).")
                .setPositiveButton("Open Settings", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException ignored) {
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Grey normally, bright green with a white ring when the TV remote's focus is on it. */
    private static android.graphics.drawable.Drawable buttonBackground(float d) {
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
        normal.setColor(0xFF2E383F);
        normal.setCornerRadius(12 * d);
        android.graphics.drawable.GradientDrawable focused = new android.graphics.drawable.GradientDrawable();
        focused.setColor(0xFF1F8A6B);
        focused.setCornerRadius(12 * d);
        focused.setStroke((int) (3 * d), 0xFFFFFFFF);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        s.addState(new int[]{}, normal);
        return s;
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }
}
