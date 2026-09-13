package com.fatemmo.mobile.world

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Native (Canvas-based, not OpenGL) top-down map view. Renders the real
 * rasterized ground image and moves the real character sprite over it,
 * constrained by the real GAT walkability grid — all three converted from
 * the operator's actual Fate MMO client assets (see
 * docs/FATE_MMO_MOBILE_ASSETS.md).
 *
 * **Movement here is client-local only** — it does NOT send anything to the
 * map-server. Sending `CZ_NOTIFY_ACTORINIT`/`CZ_REQUEST_MOVE` requires being
 * able to safely handle the resulting flood of server→client gameplay
 * packets (inventory, entity spawns, etc.), which is unverified and
 * explicitly deferred — see docs/FATE_MMO_MOBILE_PROTOCOL.md §5.6 and
 * docs/FATE_MMO_MOBILE_ROADMAP.md Phase 3. This view exists to prove the
 * asset pipeline and give a genuinely walkable (if not yet server-synced)
 * view of the real map, not to claim networked movement that isn't there.
 */
class GameMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // Confirmed in docs/FATE_MMO_MOBILE_ASSETS.md: GND grid is exactly half
        // of GAT resolution, and MapRasterizer.java baked the ground image at
        // 16px per GND cube -> 8px per GAT cell.
        private const val PIXELS_PER_GAT_CELL = 8f
        private const val MOVE_SPEED_CELLS_PER_SEC = 4f
        private const val FRAME_INTERVAL_MS = 16L
    }

    private var groundBitmap: Bitmap? = null
    private var gat: GatFile? = null
    private var spriteBitmap: Bitmap? = null

    private var playerGatX = 0f
    private var playerGatY = 0f

    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val joystickBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
    }
    private val joystickStickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 201, 162, 75) // fate_accent
    }

    private var joystickCenterX = 0f
    private var joystickCenterY = 0f
    private val joystickRadius = 110f
    private var joystickActive = false
    private var joystickDx = 0f
    private var joystickDy = 0f
    private var joystickPointerId = -1

    private val handler = Handler(Looper.getMainLooper())
    private var lastFrameTimeNs = 0L
    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            if (lastFrameTimeNs != 0L) {
                val dt = (now - lastFrameTimeNs) / 1_000_000_000f
                updateMovement(dt)
            }
            lastFrameTimeNs = now
            invalidate()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    fun loadMap(groundJpegBytes: ByteArray, gatBytes: ByteArray, spawnX: Int, spawnY: Int) {
        groundBitmap = BitmapFactory.decodeByteArray(groundJpegBytes, 0, groundJpegBytes.size)
        gat = GatFile.parse(gatBytes)
        playerGatX = spawnX.toFloat()
        playerGatY = spawnY.toFloat()
        invalidate()
    }

    fun setSprite(bitmap: Bitmap) {
        spriteBitmap = bitmap
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameTimeNs = 0L
        handler.post(tickRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tickRunnable)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        joystickCenterX = joystickRadius + 48f
        joystickCenterY = h - joystickRadius - 48f
    }

    private fun updateMovement(dt: Float) {
        val g = gat ?: return
        if (!joystickActive) return
        val mag = hypot(joystickDx, joystickDy)
        if (mag < 0.15f) return // dead zone

        val nx = joystickDx / mag
        val ny = joystickDy / mag
        val distance = MOVE_SPEED_CELLS_PER_SEC * dt * mag.coerceAtMost(1f)

        val targetX = playerGatX + nx * distance
        val targetY = playerGatY + ny * distance

        // Real wall collision against the real GAT data — check the destination
        // cell (and each axis independently so sliding along walls works).
        if (g.isWalkable(targetX.toInt(), targetY.toInt())) {
            playerGatX = targetX
            playerGatY = targetY
        } else if (g.isWalkable(targetX.toInt(), playerGatY.toInt())) {
            playerGatX = targetX
        } else if (g.isWalkable(playerGatX.toInt(), targetY.toInt())) {
            playerGatY = targetY
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = groundBitmap

        if (bitmap == null) {
            canvas.drawColor(Color.BLACK)
            return
        }

        val playerPxX = playerGatX * PIXELS_PER_GAT_CELL
        val playerPxY = playerGatY * PIXELS_PER_GAT_CELL

        val halfW = width / 2f
        val halfH = height / 2f
        val src = RectF(
            playerPxX - halfW,
            playerPxY - halfH,
            playerPxX + halfW,
            playerPxY + halfH
        )
        // Clamp the viewport to the bitmap bounds so we don't sample outside it.
        val clampedSrc = Rect(
            src.left.toInt().coerceIn(0, bitmap.width),
            src.top.toInt().coerceIn(0, bitmap.height),
            src.right.toInt().coerceIn(0, bitmap.width),
            src.bottom.toInt().coerceIn(0, bitmap.height)
        )
        val destLeft = (clampedSrc.left - src.left)
        val destTop = (clampedSrc.top - src.top)
        val dest = RectF(destLeft, destTop, destLeft + clampedSrc.width(), destTop + clampedSrc.height())

        canvas.drawColor(Color.BLACK)
        if (!clampedSrc.isEmpty) {
            canvas.drawBitmap(bitmap, clampedSrc, dest, groundPaint)
        }

        spriteBitmap?.let { sprite ->
            val left = halfW - sprite.width / 2f
            val top = halfH - sprite.height / 2f
            canvas.drawBitmap(sprite, left, top, groundPaint)
        }

        drawJoystick(canvas)
    }

    private fun drawJoystick(canvas: Canvas) {
        canvas.drawCircle(joystickCenterX, joystickCenterY, joystickRadius, joystickBasePaint)
        val stickX = joystickCenterX + (if (joystickActive) joystickDx.coerceIn(-1f, 1f) else 0f) * joystickRadius * 0.6f
        val stickY = joystickCenterY + (if (joystickActive) joystickDy.coerceIn(-1f, 1f) else 0f) * joystickRadius * 0.6f
        canvas.drawCircle(stickX, stickY, joystickRadius * 0.4f, joystickStickPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val x = event.getX(idx)
                val y = event.getY(idx)
                if (!joystickActive && distance(x, y, joystickCenterX, joystickCenterY) <= joystickRadius * 1.8f) {
                    joystickActive = true
                    joystickPointerId = event.getPointerId(idx)
                    setJoystickVector(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (joystickActive) {
                    val idx = event.findPointerIndex(joystickPointerId)
                    if (idx >= 0) setJoystickVector(event.getX(idx), event.getY(idx))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                if (joystickActive && event.getPointerId(idx) == joystickPointerId) {
                    joystickActive = false
                    joystickDx = 0f
                    joystickDy = 0f
                    joystickPointerId = -1
                }
            }
        }
        return true
    }

    private fun setJoystickVector(x: Float, y: Float) {
        val dx = (x - joystickCenterX) / joystickRadius
        val dy = (y - joystickCenterY) / joystickRadius
        val mag = hypot(dx, dy)
        if (mag > 1f) {
            joystickDx = dx / mag
            joystickDy = dy / mag
        } else {
            joystickDx = dx
            joystickDy = dy
        }
    }

    private fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x0 - x1
        val dy = y0 - y1
        return sqrt(dx * dx + dy * dy)
    }
}
