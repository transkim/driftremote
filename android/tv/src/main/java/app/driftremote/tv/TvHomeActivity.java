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
        int p = (int) (48 * d);
        root.setPadding(p, p, p, p);

        TextView title = text("Driftremote mouse", 34, 0xFFE9EFF1);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        status = text("", 20, 0xFF6CD4A8);
        root.addView(status);

        TextView steps = text("This lets the Driftremote app on your phone move a mouse pointer on this TV, "
                + "click, drag and scroll.\n\n"
                + "1. Select \"Turn on the mouse\" below.\n"
                + "2. In Accessibility, open \"Driftremote mouse\" and switch it on.\n"
                + "3. On your phone, open the remote and tap the mouse button. Type the code this TV shows.",
                18, 0xFFB8C3C8);
        steps.setGravity(Gravity.START);
        steps.setMaxWidth((int) (720 * d));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = (int) (28 * d);
        root.addView(steps, sl);

        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setTextSize(20);
        toggle.setOnClickListener(v -> openAccessibility());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = (int) (32 * d);
        root.addView(toggle, bl);

        TextView footer = text("Developed by " + getString(R.string.developer) + "  ·  Version " + BuildConfig.VERSION_NAME,
                14, 0xFF8D999F);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, -2);
        fl.topMargin = (int) (36 * d);
        root.addView(footer, fl);

        setContentView(root);
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

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
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
