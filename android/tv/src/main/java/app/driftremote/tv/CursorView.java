package app.driftremote.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** The mouse pointer drawn over the TV picture. The tip of the arrow is the hotspot. */
class CursorView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float d;
    private float pulse;
    private boolean pressedLook;

    CursorView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        fill.setColor(0xFFFFFFFF);
        edge.setColor(0xFF101418);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.6f * d);
        edge.setStrokeJoin(Paint.Join.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * d);
        float h = hotspot(), s = 1.25f * d;
        arrow.moveTo(h, h);
        arrow.lineTo(h, h + 21 * s);
        arrow.lineTo(h + 5 * s, h + 16 * s);
        arrow.lineTo(h + 8.5f * s, h + 23.5f * s);
        arrow.lineTo(h + 11.5f * s, h + 22 * s);
        arrow.lineTo(h + 8 * s, h + 14.8f * s);
        arrow.lineTo(h + 15 * s, h + 14.8f * s);
        arrow.close();
    }

    int sizePx() {
        return (int) (56 * d);
    }

    int hotspot() {
        return (int) (14 * d);
    }

    void pulse() {
        pulse = 1f;
        invalidate();
    }

    @Override
    public void setPressed(boolean p) {
        pressedLook = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = hotspot();
        if (pressedLook || pulse > 0) {
            float r = pressedLook ? 11 * d : (6 + 8 * (1 - pulse)) * d;
            ring.setColor(pressedLook ? 0xCC6CD4A8 : ((int) (pulse * 220) << 24) | 0x6CD4A8);
            c.drawCircle(h, h, r, ring);
        }
        fill.setColor(pressedLook ? 0xFF6CD4A8 : 0xFFFFFFFF);
        c.drawPath(arrow, fill);
        c.drawPath(arrow, edge);
        if (pulse > 0) {
            pulse = Math.max(0, pulse - 0.08f);
            postInvalidateOnAnimation();
        }
    }
}
