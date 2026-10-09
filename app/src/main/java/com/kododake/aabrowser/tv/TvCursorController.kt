package com.kododake.aabrowser.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Drives a virtual mouse pointer over the WebView so a D-pad remote can browse the web.
 * Only active on Android TV devices.
 */
class TvCursorController(
    private val context: Context,
    private val container: FrameLayout,
    private val webViewProvider: () -> WebView?,
    private val isStartPageShowing: () -> Boolean,
    private val shouldHandle: () -> Boolean,
    private val onLongPressCenter: () -> Unit,
    private val onPointerActivity: () -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val cursorView = CursorView(context).apply { visibility = View.GONE }
    private var x = 0f
    private var y = 0f
    private var positioned = false
    private var holdStartedAt = 0L
    private var longPressFired = false
    private var repeatStep = 0
    private var edgeHits = 0

    init {
        container.addView(
            cursorView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    /** @return true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isTv(context) || !shouldHandle()) {
            cursorView.visibility = View.GONE
            return false
        }
        if (!positioned) {
            x = container.width / 2f
            y = container.height / 2f
            positioned = true
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(event, 0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(event, 0, 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> move(event, -1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(event, 1, 0)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> center(event)
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                if (event.action == KeyEvent.ACTION_UP) onLongPressCenter()
                true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> scrollPage(event, -1)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> scrollPage(event, 1)
            else -> false
        }
    }

    private fun move(event: KeyEvent, dx: Int, dy: Int): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        repeatStep = if (event.repeatCount == 0) 0 else repeatStep + 1
        val step = (BASE_STEP + minOf(repeatStep, 20) * ACCEL) * density
        val w = container.width.toFloat()
        val h = container.height.toFloat()
        var nx = x + dx * step
        var ny = y + dy * step
        // Pushing against an edge scrolls the page; pushing again with nothing left to scroll
        // opens the browser menu (address bar, back, home, bookmarks).
        val edge = EDGE * density
        val scroll = (step * 2).toInt()
        var pushedEdge = false
        if (ny < edge && dy < 0) { pushedEdge = true; if (!scrollBy(0, -scroll)) edgeHit(); ny = edge }
        if (ny > h - edge && dy > 0) { pushedEdge = true; if (!scrollBy(0, scroll)) edgeHit(); ny = h - edge }
        if (nx < edge && dx < 0) { scrollBy(-scroll, 0); nx = edge }
        if (nx > w - edge && dx > 0) { scrollBy(scroll, 0); nx = w - edge }
        if (!pushedEdge) edgeHits = 0
        x = nx.coerceIn(0f, w)
        y = ny.coerceIn(0f, h)
        cursorView.show(x, y)
        sendHover()
        onPointerActivity()
        return true
    }

    private fun scrollPage(event: KeyEvent, dir: Int): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) scrollBy(0, dir * (container.height * 0.8f).toInt())
        return true
    }

    private fun center(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    holdStartedAt = SystemClock.uptimeMillis()
                    longPressFired = false
                } else if (!longPressFired && SystemClock.uptimeMillis() - holdStartedAt >= LONG_PRESS_MS) {
                    longPressFired = true
                    onLongPressCenter()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (!longPressFired) click()
                longPressFired = false
            }
        }
        cursorView.show(x, y)
        return true
    }

    private fun decor(): View = (context as android.app.Activity).window.decorView

    /** Pointer position in decor-view coordinates. */
    private fun pointerInDecor(): Pair<Float, Float> {
        val c = IntArray(2)
        val d = IntArray(2)
        container.getLocationInWindow(c)
        decor().getLocationInWindow(d)
        return (x + c[0] - d[0]) to (y + c[1] - d[1])
    }

    /** Clicks whatever is under the pointer (web page, start page or floating buttons). */
    private fun click() {
        val (px, py) = pointerInDecor()
        val down = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, px, py, 0)
        val u = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, px, py, 0)
        decor().dispatchTouchEvent(d)
        decor().dispatchTouchEvent(u)
        d.recycle()
        u.recycle()
        webViewProvider()?.takeIf { !isStartPageShowing() }?.requestFocus()
        onPointerActivity()
    }

    /** @return false if there was nothing left to scroll in that direction. */
    private fun scrollBy(dx: Int, dy: Int): Boolean {
        val webView = webViewProvider()
        if (webView != null && !isStartPageShowing()) {
            if (dy != 0 && !webView.canScrollVertically(dy)) return false
            webView.scrollBy(dx, dy)
            return true
        }
        if (dy != 0) swipe(dy)
        return true
    }

    /** Synthesises a short vertical drag, used to scroll the Compose start page. */
    private fun swipe(dy: Int) {
        val (px, _) = pointerInDecor()
        val startY = decor().height / 2f
        val t0 = SystemClock.uptimeMillis()
        val steps = 4
        fun send(action: Int, yy: Float, t: Long) {
            val e = MotionEvent.obtain(t0, t, action, px, yy, 0)
            decor().dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, startY, t0)
        for (i in 1..steps) send(MotionEvent.ACTION_MOVE, startY - dy * i / steps.toFloat(), t0 + i * 8L)
        send(MotionEvent.ACTION_UP, startY - dy, t0 + (steps + 1) * 8L)
    }

    private fun edgeHit() {
        edgeHits++
        if (edgeHits >= 2) {
            edgeHits = 0
            onLongPressCenter()
        }
    }

    private fun sendHover() {
        val webView = webViewProvider() ?: return
        if (isStartPageShowing()) return
        val loc = IntArray(2)
        webView.getLocationInWindow(loc)
        val containerLoc = IntArray(2)
        container.getLocationInWindow(containerLoc)
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE,
            x + containerLoc[0] - loc[0], y + containerLoc[1] - loc[1], 0)
        e.source = InputDevice.SOURCE_MOUSE
        webView.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    fun hide() { cursorView.visibility = View.GONE }

    private class CursorView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * resources.displayMetrics.density
        }
        private val path = Path()
        private var cx = 0f
        private var cy = 0f
        private val hideRunnable = Runnable { visibility = GONE }

        init {
            isFocusable = false
            isClickable = false
        }

        fun show(x: Float, y: Float) {
            cx = x; cy = y
            visibility = VISIBLE
            invalidate()
            removeCallbacks(hideRunnable)
            postDelayed(hideRunnable, HIDE_AFTER_MS)
        }

        override fun onDraw(canvas: Canvas) {
            val s = resources.displayMetrics.density * 22f
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx, cy + s)
            path.lineTo(cx + s * 0.3f, cy + s * 0.72f)
            path.lineTo(cx + s * 0.55f, cy + s * 1.15f)
            path.lineTo(cx + s * 0.75f, cy + s * 1.05f)
            path.lineTo(cx + s * 0.5f, cy + s * 0.65f)
            path.lineTo(cx + s * 0.85f, cy + s * 0.65f)
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }

        override fun onTouchEvent(event: MotionEvent?) = false
    }

    companion object {
        private const val BASE_STEP = 8f
        private const val ACCEL = 2f
        private const val EDGE = 24f
        private const val LONG_PRESS_MS = 700L
        private const val HIDE_AFTER_MS = 4000L

        fun isTv(context: Context): Boolean {
            val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }
    }
}
