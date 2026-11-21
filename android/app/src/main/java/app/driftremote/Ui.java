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

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
