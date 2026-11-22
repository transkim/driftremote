package app.driftremote;

import android.content.Context;

final class Ui {
    private Ui() {}

    @SuppressWarnings("deprecation")
    static int color(Context c, int id) {
        return c.getResources().getColor(id);
    }

    /**
     * Android 15 draws apps under the status and navigation bars. Pad the screen's root
     * view by the bars' size so nothing hides behind them.
     */
    @SuppressWarnings("deprecation")
    static void fitSystemBars(android.view.View root) {
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(l + in.getSystemWindowInsetLeft(), t + in.getSystemWindowInsetTop(),
                    r + in.getSystemWindowInsetRight(), b + in.getSystemWindowInsetBottom());
            return in;
        });
        root.requestApplyInsets();
    }

    /** The About screen: app, version, developer and what the app does. */
    /** If the app closed unexpectedly last time, offers the report so it can be sent to the developer. */
    static void showCrashReport(android.app.Activity a) {
        String report = App.get().takeCrashReport();
        if (report == null) return;
        android.widget.TextView t = new android.widget.TextView(a);
        t.setText(report);
        t.setTextSize(11);
        t.setTextColor(0xCCFFFFFF);
        t.setTextIsSelectable(true);
        int p = dp(a, 20);
        t.setPadding(p, p / 2, p, p / 2);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(t);
        new android.app.AlertDialog.Builder(a)
                .setTitle("Driftremote closed last time")
                .setMessage("Copy this report and send it to the developer so it can be fixed.")
                .setView(sv)
                .setPositiveButton("Copy report", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Driftremote report", report));
                    android.widget.Toast.makeText(a, "Report copied", android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    static void showAbout(android.app.Activity a) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int p = dp(a, 24);
        box.setPadding(p, p, p, dp(a, 8));

        android.widget.ImageView icon = new android.widget.ImageView(a);
        icon.setImageResource(R.drawable.ic_launcher);
        box.addView(icon, new android.widget.LinearLayout.LayoutParams(dp(a, 72), dp(a, 72)));

        box.addView(text(a, a.getString(R.string.app_name), 24, R.color.text, true, 12));
        box.addView(text(a, "Version " + BuildConfig.VERSION_NAME, 14, R.color.muted, false, 2));
        box.addView(text(a, "Developed by", 13, R.color.muted, false, 20));
        box.addView(text(a, a.getString(R.string.developer), 19, R.color.accent, true, 2));
        box.addView(text(a, "A remote for Android TV and Google TV that works over your home Wi-Fi. "
                + "Nothing to install on the TV: pair once with the code on the TV screen.", 14, R.color.text, false, 20));
        android.widget.TextView link = text(a, "github.com/transkim/driftremote", 14, R.color.accent, false, 16);
        link.setOnClickListener(v -> {
            try {
                a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/transkim/driftremote")));
            } catch (RuntimeException ignored) {
            }
        });
        box.addView(link);
        box.addView(text(a, "\u00A9 2026 " + a.getString(R.string.developer), 12, R.color.muted, false, 16));

        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new android.app.AlertDialog.Builder(a).setView(sv).setPositiveButton("Close", null).show();
    }

    private static android.widget.TextView text(android.content.Context c, String s, float size, int color, boolean bold, int topDp) {
        android.widget.TextView t = new android.widget.TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color(c, color));
        t.setGravity(android.view.Gravity.CENTER);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, topDp);
        t.setLayoutParams(lp);
        return t;
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
