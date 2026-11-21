package app.driftremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import app.driftremote.core.Keys;

/** A round d-pad with an OK button in the middle. Reports the key under the finger. */
public class DpadView extends View {
    public interface Listener {
        void onKeyDown(int key);

        void onKeyUp(int key);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevron = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private Listener listener;
    private int pressed;
    private float cx, cy, r;

    public DpadView(Context c, AttributeSet a) {
        super(c, a);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.5f));
        chevron.setStyle(Paint.Style.STROKE);
        chevron.setStrokeWidth(dp(3));
        chevron.setStrokeCap(Paint.Cap.ROUND);
        chevron.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("Direction pad. Tap the middle for OK.");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        r = Math.min(w, h) / 2f - dp(4);
    }

    @Override
    protected void onDraw(Canvas c) {
        fill.setColor(0xFF1A2025);
        c.drawCircle(cx, cy, r, fill);
        if (pressed != 0 && pressed != Keys.DPAD_CENTER) {
            fill.setColor(0xFF2B353C);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            float start = pressed == Keys.DPAD_RIGHT ? -45 : pressed == Keys.DPAD_DOWN ? 45 : pressed == Keys.DPAD_LEFT ? 135 : 225;
            c.drawArc(oval, start, 90, true, fill);
        }
        stroke.setColor(0xFF2E383F);
        c.drawCircle(cx, cy, r, stroke);

        float inner = r * 0.40f;
        fill.setColor(pressed == Keys.DPAD_CENTER ? 0xFF2F5E50 : 0xFF252D33);
        c.drawCircle(cx, cy, inner, fill);
        stroke.setColor(0xFF34414A);
        c.drawCircle(cx, cy, inner, stroke);

        chevron.setColor(0xFFE9EFF1);
        float d = r * 0.71f, s = r * 0.09f;
        drawChevron(c, cx, cy - d, s, 0);
        drawChevron(c, cx + d, cy, s, 90);
        drawChevron(c, cx, cy + d, s, 180);
        drawChevron(c, cx - d, cy, s, 270);

        fill.setColor(0xFFE9EFF1);
        fill.setTextAlign(Paint.Align.CENTER);
        fill.setTextSize(inner * 0.42f);
        fill.setFakeBoldText(true);
        c.drawText("OK", cx, cy + inner * 0.15f, fill);
    }

    private void drawChevron(Canvas c, float x, float y, float s, float deg) {
        c.save();
        c.rotate(deg, x, y);
        path.reset();
        path.moveTo(x - s, y + s * 0.5f);
        path.lineTo(x, y - s * 0.5f);
        path.lineTo(x + s, y + s * 0.5f);
        c.drawPath(path, chevron);
        c.restore();
    }

    private int zoneAt(float x, float y) {
        float dx = x - cx, dy = y - cy;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > r * 1.1) return 0;
        if (dist < r * 0.40f) return Keys.DPAD_CENTER;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? Keys.DPAD_RIGHT : Keys.DPAD_LEFT;
        return dy > 0 ? Keys.DPAD_DOWN : Keys.DPAD_UP;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int z = zoneAt(e.getX(), e.getY());
                if (z == 0) return false;
                pressed = z;
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                if (listener != null) listener.onKeyDown(z);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressed != 0 && listener != null) listener.onKeyUp(pressed);
                pressed = 0;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
