package app.driftremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * A laptop-style trackpad for the TV pointer.
 * <ul>
 *   <li>Slide one finger: move the pointer (faster swipes go further)</li>
 *   <li>Tap: click. Touch and hold: long press</li>
 *   <li>Touch and hold, then slide: drag (drop by lifting)</li>
 *   <li>Two fingers, or one finger on the right-hand strip: scroll</li>
 *   <li>Two-finger tap: Back</li>
 * </ul>
 */
public class MouseView extends View {
    public interface Listener {
        void move(float dx, float dy);

        void click();

        void longPress();

        void dragStart();

        void dragMove(float dx, float dy);

        void dragEnd();

        void scrollStart();

        void scrollMove(float dx, float dy);

        void scrollEnd();

        void back();
    }

    private enum Mode { IDLE, PENDING, MOVE, HOLD, DRAG, SCROLL, TWO, DONE }

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private final int slop;
    private Listener listener;
    private Mode mode = Mode.IDLE;
    private float lastX, lastY, downX, downY;
    private long downTime, lastTime;
    private boolean edgeScroll, twoMoved, edgeStarted;
    private float speed = 1f;
    private String message;
    private final Runnable holdTimer = () -> {
        if (mode == Mode.PENDING) {
            mode = Mode.HOLD;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    };

    public MouseView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(density);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        setContentDescription("Mouse pad. Slide to move the pointer, tap to click, two fingers to scroll.");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setSpeed(float s) {
        speed = s;
    }

    /** A note drawn on the pad (e.g. while connecting); null for the normal hint. */
    public void setMessage(String m) {
        message = m;
        invalidate();
    }

    private float stripLeft() {
        return getWidth() - 44 * density;
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = 28 * density;
        rect.set(0, 0, getWidth(), getHeight());
        bg.setColor(mode == Mode.HOLD || mode == Mode.DRAG ? 0xFF1C2B27 : 0xFF1A2025);
        c.drawRoundRect(rect, r, r, bg);
        line.setColor(0xFF2E383F);
        rect.inset(density / 2, density / 2);
        c.drawRoundRect(rect, r, r, line);

        // scroll strip on the right edge
        float sl = stripLeft();
        c.drawLine(sl, 20 * density, sl, getHeight() - 20 * density, line);
        text.setColor(0xFF5E6A70);
        float mid = (sl + getWidth()) / 2f;
        c.drawText("▲", mid, 40 * density, text);
        c.drawText("▼", mid, getHeight() - 28 * density, text);

        float cx = sl / 2f, cy = getHeight() / 2f;
        if (message != null) {
            text.setColor(0xFFB8C3C8);
            drawLines(c, message, cx, cy);
        } else if (mode == Mode.HOLD || mode == Mode.DRAG) {
            text.setColor(0xFF6CD4A8);
            c.drawText(mode == Mode.DRAG ? "Dragging. Lift to drop" : "Slide to drag, or lift for a long press", cx, cy, text);
        } else {
            text.setColor(0xFF8D999F);
            drawLines(c, "Slide to move  ·  Tap to click\nHold then slide to drag\nTwo fingers to scroll  ·  Two-finger tap: Back", cx, cy);
        }
    }

    private void drawLines(Canvas c, String s, float x, float y) {
        String[] lines = s.split("\n");
        float h = text.getTextSize() * 1.6f;
        float top = y - h * (lines.length - 1) / 2f;
        for (int i = 0; i < lines.length; i++) c.drawText(lines[i], x, top + i * h, text);
    }

    private float accel(float dxPx, float dyPx, long dtMs) {
        float distDp = (float) Math.hypot(dxPx, dyPx) / density;
        float v = distDp / Math.max(dtMs, 4); // dp per ms
        return speed * (1f + Math.min(v * 0.9f, 2.6f)) / density;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null) return false;
        int action = e.getActionMasked();
        long now = e.getEventTime();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                downTime = lastTime = now;
                edgeScroll = downX >= stripLeft();
                mode = edgeScroll ? Mode.SCROLL : Mode.PENDING;
                edgeStarted = false;
                if (!edgeScroll) postDelayed(holdTimer, 450);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2 && (mode == Mode.PENDING || mode == Mode.MOVE)) {
                    removeCallbacks(holdTimer);
                    mode = Mode.TWO;
                    twoMoved = false;
                    lastX = (e.getX(0) + e.getX(1)) / 2f;
                    lastY = (e.getY(0) + e.getY(1)) / 2f;
                    downX = lastX;
                    downY = lastY;
                    downTime = now;
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                float x, y;
                if (mode == Mode.TWO && e.getPointerCount() >= 2) {
                    x = (e.getX(0) + e.getX(1)) / 2f;
                    y = (e.getY(0) + e.getY(1)) / 2f;
                } else {
                    x = e.getX();
                    y = e.getY();
                }
                float dx = x - lastX, dy = y - lastY;
                long dt = now - lastTime;
                switch (mode) {
                    case PENDING:
                        if (Math.hypot(x - downX, y - downY) > slop) {
                            removeCallbacks(holdTimer);
                            mode = Mode.MOVE;
                        } else {
                            return true;
                        }
                        // fall through into MOVE
                    case MOVE: {
                        float k = accel(dx, dy, dt);
                        listener.move(dx * k, dy * k);
                        break;
                    }
                    case HOLD:
                        if (Math.hypot(x - downX, y - downY) > slop) {
                            mode = Mode.DRAG;
                            listener.dragStart();
                            invalidate();
                        } else {
                            return true;
                        }
                        // fall through into DRAG
                    case DRAG: {
                        float k = speed * 1.1f / density;
                        listener.dragMove(dx * k, dy * k);
                        break;
                    }
                    case SCROLL:
                        if (!edgeStarted) {
                            if (Math.abs(y - downY) <= slop) return true;
                            edgeStarted = true;
                            listener.scrollStart();
                        }
                        listener.scrollMove(0, dy * 1.3f / density);
                        break;
                    case TWO:
                        if (!twoMoved && Math.hypot(x - downX, y - downY) > slop) {
                            twoMoved = true;
                            listener.scrollStart();
                        }
                        if (twoMoved) listener.scrollMove(dx * 1.3f / density, dy * 1.3f / density);
                        break;
                    default:
                        break;
                }
                lastX = x;
                lastY = y;
                lastTime = now;
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                if (mode == Mode.TWO) {
                    if (twoMoved) {
                        listener.scrollEnd();
                    } else if (now - downTime < 300) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        listener.back();
                    }
                    mode = Mode.DONE; // ignore the remaining finger
                }
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(holdTimer);
                switch (mode) {
                    case PENDING:
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        listener.click();
                        break;
                    case HOLD:
                        listener.longPress();
                        break;
                    case DRAG:
                        listener.dragEnd();
                        break;
                    case SCROLL:
                        if (edgeStarted) listener.scrollEnd();
                        break;
                    case TWO:
                        if (twoMoved) listener.scrollEnd();
                        break;
                    default:
                        break;
                }
                mode = Mode.IDLE;
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(holdTimer);
                if (mode == Mode.DRAG) listener.dragEnd();
                if ((mode == Mode.SCROLL && edgeStarted) || (mode == Mode.TWO && twoMoved)) listener.scrollEnd();
                mode = Mode.IDLE;
                invalidate();
                return true;

            default:
                return true;
        }
    }
}
