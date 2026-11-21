package app.driftremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import app.driftremote.core.Keys;

/** Swipe to move the TV's focus, tap to select, touch and hold for a long press of OK. */
public class TouchpadView extends View {
    public interface Listener {
        void onMove(int dpadKey);

        void onTap();

        void onHoldStart();

        void onHoldEnd();
    }

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float step;
    private final int slop;
    private Listener listener;
    private float lastX, lastY, accX, accY, downX, downY;
    private boolean moved, holding;
    private final Runnable hold = () -> {
        holding = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (listener != null) listener.onHoldStart();
    };

    public TouchpadView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundResource(R.drawable.bg_touchpad);
        step = 48 * getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        text.setColor(0xFF8D999F);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        setContentDescription("Touchpad. Swipe to move, tap to select.");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawText("Swipe to move  ·  Tap to select", getWidth() / 2f, getHeight() / 2f, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                accX = accY = 0;
                moved = holding = false;
                postDelayed(hold, 600);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = e.getX(), y = e.getY();
                if (!moved && Math.hypot(x - downX, y - downY) > slop) {
                    moved = true;
                    removeCallbacks(hold);
                }
                if (moved && !holding) {
                    accX += x - lastX;
                    accY += y - lastY;
                    while (Math.abs(accX) >= step || Math.abs(accY) >= step) {
                        int key;
                        if (Math.abs(accX) >= Math.abs(accY)) {
                            key = accX > 0 ? Keys.DPAD_RIGHT : Keys.DPAD_LEFT;
                            accX -= Math.signum(accX) * step;
                            accY *= 0.5f;
                        } else {
                            key = accY > 0 ? Keys.DPAD_DOWN : Keys.DPAD_UP;
                            accY -= Math.signum(accY) * step;
                            accX *= 0.5f;
                        }
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        if (listener != null) listener.onMove(key);
                    }
                }
                lastX = x;
                lastY = y;
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(hold);
                if (holding) {
                    if (listener != null) listener.onHoldEnd();
                } else if (!moved) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null) listener.onTap();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(hold);
                if (holding && listener != null) listener.onHoldEnd();
                return true;
            default:
                return true;
        }
    }
}
