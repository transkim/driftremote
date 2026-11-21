package app.driftremote;

import android.content.Context;

final class Ui {
    private Ui() {}

    @SuppressWarnings("deprecation")
    static int color(Context c, int id) {
        return c.getResources().getColor(id);
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
