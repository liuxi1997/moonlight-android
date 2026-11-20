package com.limelight.pointerengine;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * 指针引擎 v1（M1 Increment 1 · remote_desk fork）
 *
 * 整手状态机（区别于上游 per-pointer TouchContext），手势语义 v1：
 *   单指移动     → 相对指针增量（AccelerationCurve 加速曲线）
 *   单指点按     → 左键（tap 时限 + 拾辍范围内）
 *   单指长按     → 拖拽锁定（左键按下，抬指释放）
 *   双指点按     → 右键
 *   双指滑动     → 滚轮（质心增量，无惯性）
 *   第三指       → 保留（未来：切换被控显示器手势），当前取消全部状态
 *
 * 接入：Game.java 在 TouchContext 分发前按偏好开关旁路（pointer_engine_v1，默认开）。
 * 协议边界：仅调用 NvConnection 的鼠标发送接口（L2），不触碰解码/渲染。
 */
public final class PointerEngine {

    private static final int MAX_FINGERS = 2;
    private static final int TAP_TIMEOUT = 180;          // ms
    private static final int TAP_SLOP = 18;              // px（点按最大位移）
    private static final int LONGPRESS_TIMEOUT = 400;    // ms
    private static final int SCROLL_SLOP = 14;           // px（滚动启动位移）
    private static final float SCROLL_FACTOR = 3.0f;     // 质心 px → 滚轮刻度
    private static final int RIGHT_CLICK_UP_DELAY = 60;  // ms

    private enum Mode { IDLE, PENDING, MOVE, DRAG, TWO_FINGER, SWALLOW }

    public interface CursorListener {
        void onCursorMoved(float x, float y);
    }

    private final NvConnection conn;
    private final Handler handler;
    private AccelerationCurve curve;
    private float scrollSpeed;

    // 虚拟指针（客户端自绘光标，UU 式）
    private CursorListener cursorListener;
    private float curX, curY;
    private int viewportW, viewportH;
    private boolean cursorInit = false;

    // 指针槽（按 slot 存，pointer id 稳定）
    private final int[] fingerId = new int[MAX_FINGERS];
    private final int[] fx = new int[MAX_FINGERS];
    private final int[] fy = new int[MAX_FINGERS];
    private int fingerCount = 0;

    private Mode mode = Mode.IDLE;
    private boolean swallowUntilAllUp = false;
    private int swallowRemaining = 0;

    // 单指态
    private int startX, startY;
    private long downTime;
    private float accX, accY;            // 亚像素累积
    private int lastX, lastY;
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mode == Mode.PENDING) {
                mode = Mode.DRAG;
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
            }
        }
    };

    // 双指态
    private long twoFingerDownTime;
    private boolean scrolling;
    private int lastCx, lastCy;
    private int firstLiftSlot = -1;
    private boolean dragAbortedBySecondFinger;

    public PointerEngine(NvConnection conn, PointerEnginePrefs prefs) {
        this.conn = conn;
        this.handler = new Handler(Looper.getMainLooper());
        applyPrefs(prefs);
    }

    public void applyPrefs(PointerEnginePrefs prefs) {
        this.curve = prefs.newCurve();
        this.scrollSpeed = prefs.getScrollSpeed();
    }

    /** 设置视口尺寸（串流视图大小）；首次调用时虚拟指针归位屏幕中心 */
    public void setViewport(int w, int h) {
        if (w <= 0 || h <= 0) return;
        viewportW = w;
        viewportH = h;
        if (!cursorInit) {
            curX = w / 2.0f;
            curY = h / 2.0f;
            cursorInit = true;
            notifyCursor();
        }
    }

    public void setCursorListener(CursorListener listener) {
        this.cursorListener = listener;
    }

    private void notifyCursor() {
        if (cursorListener != null) {
            cursorListener.onCursorMoved(curX, curY);
        }
    }

    private float clampCursor(float v, float max) {
        if (max <= 0) return v;
        return Math.max(0f, Math.min(max - 1f, v));
    }

    // ---------------- 事件入口（Game.java 旁路调用） ----------------

    public boolean onTouchEvent(MotionEvent event, float xOffset, float yOffset) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                onPointerDown(
                        event.getPointerId(event.getActionIndex()),
                        (int) (event.getX(event.getActionIndex()) + xOffset),
                        (int) (event.getY(event.getActionIndex()) + yOffset),
                        event.getEventTime());
                return true;

            case MotionEvent.ACTION_MOVE:
                for (int h = 0; h < event.getHistorySize(); h++) {
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        onPointerMove(
                                event.getPointerId(i),
                                (int) (event.getHistoricalX(i, h) + xOffset),
                                (int) (event.getHistoricalY(i, h) + yOffset),
                                event.getHistoricalEventTime(h));
                    }
                }
                for (int i = 0; i < event.getPointerCount(); i++) {
                    onPointerMove(
                            event.getPointerId(i),
                            (int) (event.getX(i) + xOffset),
                            (int) (event.getY(i) + yOffset),
                            event.getEventTime());
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                onPointerUp(
                        event.getPointerId(event.getActionIndex()),
                        (int) (event.getX(event.getActionIndex()) + xOffset),
                        (int) (event.getY(event.getActionIndex()) + yOffset),
                        event.getEventTime());
                return true;

            case MotionEvent.ACTION_UP:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    onPointerUp(
                            event.getPointerId(i),
                            (int) (event.getX(i) + xOffset),
                            (int) (event.getY(i) + yOffset),
                            event.getEventTime());
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                onCancel();
                return true;

            default:
                return false;
        }
    }

    public void onCancel() {
        resetAll();
    }

    // ---------------- 内部状态机 ----------------

    private int slotOf(int id) {
        for (int s = 0; s < MAX_FINGERS; s++) {
            if (fingerCount > s && fingerId[s] == id) return s;
        }
        return -1;
    }

    private void onPointerDown(int id, int x, int y, long time) {
        if (swallowUntilAllUp) {
            swallowRemaining++;
            return;
        }
        if (fingerCount >= MAX_FINGERS) {
            resetAll(); // 第三指：保留为未来"切换显示器"手势，当前清态
            return;
        }

        if (mode == Mode.IDLE) {
            fingerId[0] = id; fx[0] = x; fy[0] = y;
            startX = x; startY = y; downTime = time;
            lastX = x; lastY = y;
            accX = accY = 0;
            fingerCount = 1;
            mode = Mode.PENDING;
            handler.postDelayed(longPressRunnable, LONGPRESS_TIMEOUT);
        } else if (mode == Mode.PENDING || mode == Mode.MOVE || mode == Mode.DRAG) {
            // 第二指按下：单指语义全部终止
            handler.removeCallbacks(longPressRunnable);
            if (mode == Mode.DRAG) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                dragAbortedBySecondFinger = true;
            }
            fingerId[1] = id; fx[1] = x; fy[1] = y;
            fingerCount = 2;
            mode = Mode.TWO_FINGER;
            twoFingerDownTime = time;
            scrolling = false;
            lastCx = (fx[0] + fx[1]) / 2;
            lastCy = (fy[0] + fy[1]) / 2;
        } else {
            // TWO_FINGER / SWALLOW 中第三指：静默吞掉
            swallowRemaining++;
        }
    }

    private void onPointerMove(int id, int x, int y, long time) {
        int slot = slotOf(id);
        if (slot < 0 || swallowUntilAllUp) return;

        switch (mode) {
            case PENDING:
                fx[slot] = x; fy[slot] = y;
                if (dist(startX, startY, x, y) > TAP_SLOP) {
                    handler.removeCallbacks(longPressRunnable);
                    mode = Mode.MOVE;
                    lastX = x; lastY = y;
                    accX = accY = 0;
                }
                break;

            case MOVE:
                sendRelative(lastX, lastY, x, y);
                lastX = x; lastY = y;
                fx[slot] = x; fy[slot] = y;
                break;

            case DRAG:
                sendRelative(lastX, lastY, x, y);
                lastX = x; lastY = y;
                fx[slot] = x; fy[slot] = y;
                break;

            case TWO_FINGER:
                fx[slot] = x; fy[slot] = y;
                int cx = (fx[0] + fx[1]) / 2;
                int cy = (fy[0] + fy[1]) / 2;
                int dxc = cx - lastCx;
                int dyc = cy - lastCy;
                if (!scrolling && dist(lastCx, lastCy, cx, cy) > SCROLL_SLOP) {
                    scrolling = true;
                }
                if (scrolling) {
                    // 符号沿用上游：手指向下（+dy）→ 滚轮正值
                    conn.sendMouseHighResScroll((short) (dyc * SCROLL_FACTOR * scrollSpeed));
                }
                lastCx = cx; lastCy = cy;
                break;

            default:
                break;
        }
    }

    private void onPointerUp(int id, int x, int y, long time) {
        int slot = slotOf(id);
        if (slot < 0) return;

        if (swallowUntilAllUp) {
            if (--swallowRemaining <= 0) resetAll();
            return;
        }

        switch (mode) {
            case PENDING:
                handler.removeCallbacks(longPressRunnable);
                boolean isTap = (time - downTime) <= TAP_TIMEOUT
                        && dist(startX, startY, x, y) <= TAP_SLOP;
                removeFinger(slot);
                mode = Mode.IDLE;
                if (isTap) {
                    tapLeft();
                }
                break;

            case MOVE:
                removeFinger(slot);
                mode = Mode.IDLE;
                break;

            case DRAG:
                removeFinger(slot);
                mode = Mode.IDLE;
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                break;

            case TWO_FINGER:
                boolean canRightTap = !scrolling
                        && (time - twoFingerDownTime) <= TAP_TIMEOUT + 100;
                if (canRightTap) {
                    // 双指点按 → 右键（立即按下，延迟抬起保证事件成对）
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }, RIGHT_CLICK_UP_DELAY);
                }
                // 剩余那根手指进入吞咽态，直到全部抬起（避免残余位移/点按）
                swallowUntilAllUp = true;
                swallowRemaining = fingerCount - 1;
                removeFinger(slot);
                fingerCount = Math.max(0, fingerCount - 1);
                mode = Mode.SWALLOW;
                if (swallowRemaining <= 0) resetAll();
                break;

            default:
                break;
        }
    }

    private void resetAll() {
        handler.removeCallbacks(longPressRunnable);
        // 若处于拖拽锁定，释放左键防止"卡键"
        if (mode == Mode.DRAG) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        }
        fingerCount = 0;
        mode = Mode.IDLE;
        swallowUntilAllUp = false;
        swallowRemaining = 0;
        dragAbortedBySecondFinger = false;
    }

    private void tapLeft() {
        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
            }
        }, RIGHT_CLICK_UP_DELAY);
    }

    private void sendRelative(int fromX, int fromY, int toX, int toY) {
        float dx = toX - fromX + accX;
        float dy = toY - fromY + accY;
        short outX = curve.applyX(dx, dy);
        short outY = curve.applyY(dx, dy);
        accX = dx - outX;
        accY = dy - outY;
        if (outX != 0 || outY != 0) {
            conn.sendMouseMove(outX, outY);
            // 虚拟指针同步位移（UU 式：客户端自绘光标跟随相对增量）
            curX = clampCursor(curX + outX, viewportW);
            curY = clampCursor(curY + outY, viewportH);
            notifyCursor();
        }
    }

    private void removeFinger(int slot) {
        // 用最后一个槽补位，保持 fingerCount 连续
        int last = fingerCount - 1;
        if (slot != last) {
            fingerId[slot] = fingerId[last];
            fx[slot] = fx[last];
            fy[slot] = fy[last];
        }
        fingerCount = last;
    }

    private static int dist(int x1, int y1, int x2, int y2) {
        int dx = x2 - x1, dy = y2 - y1;
        return (int) Math.sqrt(dx * (double) dx + dy * (double) dy);
    }
}
