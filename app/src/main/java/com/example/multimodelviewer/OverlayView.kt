package com.example.multimodelviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

private enum class TouchMode { NONE, DRAG_OR_ROTATE, PINCH }
private enum class ButtonType { INTERACTION, LABELS, CLOSE }

/**
 * One transparent full-screen View sitting on top of the Filament
 * SurfaceView. It draws every container's border, its 3 buttons, and its
 * part labels, AND owns all touch handling.
 *
 * Using a single overlay (rather than one Android View per button, per
 * container) keeps the view tree flat regardless of how many models are on
 * screen -- drawing and hit-testing are just loops over plain data here,
 * not measure/layout passes.
 *
 * Gesture routing follows section 1.7 of the spec strictly: the touch
 * target and its mode are locked in on ACTION_DOWN and never re-evaluated
 * mid-gesture, so normal-mode and interaction-mode behaviour can never mix
 * within a single drag/pinch.
 */
class OverlayView(
    context: Context,
    private val sceneManager: SceneManager
) : View(context) {

    private val containers = mutableListOf<ModelContainer>()

    /** Fired whenever a container is expanded to fullscreen or restored, so
     *  MainActivity can hide/show the "Add model" button to match. */
    var onFullscreenChanged: ((Boolean) -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val buttonRadius = 18f * density
    private val buttonSpacing = 44f * density
    private val minContainerSize = 120f * density
    private val maxContainerSize = 1400f * density

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(180, 255, 255, 255)
    }
    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 30, 30, 30) }
    private val buttonBgActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 60, 140, 255) }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 20, 20) }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
    }
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 1.5f * density
    }

    fun addContainer(container: ModelContainer) {
        containers.add(container)
        invalidate()
    }

    fun removeContainer(container: ModelContainer) {
        val wasFullscreen = container.windowedRect != null
        containers.remove(container)
        sceneManager.removeModel(container)
        if (activeContainer === container) resetTouchState()
        if (wasFullscreen) onFullscreenChanged?.invoke(false)
        invalidate()
    }

    /** Called from MainActivity's system-back handling. Returns true if a
     *  fullscreen container was showing (and has now been collapsed), so
     *  the caller knows whether back should do anything else. */
    fun exitFullscreenIfShown(): Boolean {
        val fs = containers.firstOrNull { it.windowedRect != null } ?: return false
        exitFullscreen(fs)
        return true
    }

    private fun enterFullscreen(container: ModelContainer) {
        if (containers.any { it.windowedRect != null }) return // one at a time
        container.windowedRect = RectF(container.rect)
        container.rect.set(0f, 0f, width.toFloat(), height.toFloat())
        container.mode = ContainerMode.INTERACTION // drag should rotate, not move, once fullscreen
        sceneManager.onContainerResized(container)
        onFullscreenChanged?.invoke(true)
        invalidate()
    }

    private fun exitFullscreen(container: ModelContainer) {
        val saved = container.windowedRect ?: return
        container.rect.set(saved)
        container.windowedRect = null
        sceneManager.onContainerResized(container)
        onFullscreenChanged?.invoke(false)
        invalidate()
    }

    /** While a container is fullscreen, every gesture/hit-test targets it
     *  alone; otherwise the normal top-to-bottom z-order applies. */
    private fun hitTestOrder(): List<ModelContainer> {
        val fs = containers.firstOrNull { it.windowedRect != null }
        return if (fs != null) listOf(fs) else containers.asReversed()
    }

    // ---------------- drawing ----------------

    override fun onDraw(canvas: Canvas) {
        // Only the fullscreen container (if any) is drawn -- everything
        // else is hidden behind it and shouldn't show borders/buttons.
        val visible = containers.firstOrNull { it.windowedRect != null }
            ?.let { listOf(it) } ?: containers
        for (c in visible) {
            canvas.drawRect(c.rect, borderPaint)
            drawButtons(canvas, c)
            if (c.labelsVisible) drawLabels(canvas, c)
        }
    }

    private fun buttonCenters(c: ModelContainer): Map<ButtonType, PointF> {
        // Nudge buttons down a bit further when fullscreen so they clear
        // the status bar / camera cutout instead of sitting right at y=0.
        val topPad = if (c.windowedRect != null) 48f * density else 8f * density
        val y = c.rect.top + buttonRadius + topPad
        val startX = c.rect.right - buttonRadius - 8f * density
        return mapOf(
            ButtonType.CLOSE to PointF(startX, y),
            ButtonType.LABELS to PointF(startX - buttonSpacing, y),
            ButtonType.INTERACTION to PointF(startX - buttonSpacing * 2, y)
        )
    }

    private fun drawButtons(canvas: Canvas, c: ModelContainer) {
        for ((type, center) in buttonCenters(c)) {
            val active = (type == ButtonType.INTERACTION && c.mode == ContainerMode.INTERACTION) ||
                    (type == ButtonType.LABELS && c.labelsVisible)
            canvas.drawCircle(center.x, center.y, buttonRadius, if (active) buttonBgActivePaint else buttonBgPaint)
            // Plain-glyph placeholders -- swap in real vector icons
            // (rotate / tag / close) before shipping.
            val glyph = when (type) {
                ButtonType.INTERACTION -> "R"
                ButtonType.LABELS -> "L"
                ButtonType.CLOSE -> "X"
            }
            canvas.drawText(glyph, center.x, center.y + buttonTextPaint.textSize * 0.35f, buttonTextPaint)
        }
    }

    private fun drawLabels(canvas: Canvas, c: ModelContainer) {
        for (label in c.projectedLabels) {
            canvas.drawLine(label.anchorX, label.anchorY, label.screenX, label.screenY, connectorPaint)
            val pad = 6f * density
            val textWidth = labelTextPaint.measureText(label.text)
            canvas.drawRoundRect(
                RectF(
                    label.screenX - pad, label.screenY - labelTextPaint.textSize - pad,
                    label.screenX + textWidth + pad, label.screenY + pad
                ),
                6f, 6f, labelBgPaint
            )
            canvas.drawText(label.text, label.screenX, label.screenY, labelTextPaint)
        }
    }

    // ---------------- touch ----------------

    private var touchMode = TouchMode.NONE
    private var activeContainer: ModelContainer? = null
    private var pendingButton: Pair<ModelContainer, ButtonType>? = null

    private var lastX = 0f
    private var lastY = 0f
    private var startPinchDist = 0f
    private var startPinchAngleDeg = 0f
    private var startRoll = 0f
    private val startRect = RectF()
    private var startScale = 1f

    // A short, mostly-stationary down-then-up is treated as a tap (expand
    // or collapse fullscreen) rather than a drag/rotate.
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private val tapSlop = 10f * density
    private val tapTimeoutMs = 250L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleFirstDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handleSecondDown(event)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)
            MotionEvent.ACTION_UP -> handleUp(event)
            MotionEvent.ACTION_CANCEL -> resetTouchState()
        }
        return true
    }

    private fun topmostContainerAt(x: Float, y: Float): ModelContainer? =
        hitTestOrder().firstOrNull { it.rect.contains(x, y) }

    private fun buttonAt(x: Float, y: Float): Pair<ModelContainer, ButtonType>? {
        for (c in hitTestOrder()) {
            for ((type, center) in buttonCenters(c)) {
                if (hypot((x - center.x).toDouble(), (y - center.y).toDouble()) <= buttonRadius) {
                    return c to type
                }
            }
        }
        return null
    }

    private fun handleFirstDown(event: MotionEvent) {
        val x = event.x
        val y = event.y
        downX = x
        downY = y
        downTime = event.eventTime

        val hitButton = buttonAt(x, y)
        if (hitButton != null) {
            pendingButton = hitButton
            touchMode = TouchMode.NONE
            return
        }
        val target = topmostContainerAt(x, y) ?: return
        activeContainer = target
        touchMode = TouchMode.DRAG_OR_ROTATE
        lastX = x
        lastY = y
        startRect.set(target.rect)
        startScale = target.scale
    }

    private fun handleSecondDown(event: MotionEvent) {
        if (activeContainer == null || event.pointerCount < 2) return
        touchMode = TouchMode.PINCH
        startPinchDist = pinchDistance(event)
        startPinchAngleDeg = pinchAngleDeg(event)
        startRoll = activeContainer!!.rollDeg
        startRect.set(activeContainer!!.rect)
        startScale = activeContainer!!.scale
    }

    private fun pinchDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
    }

    /** Angle of the line between the two pointers, for twist-to-roll. */
    private fun pinchAngleDeg(event: MotionEvent): Float {
        val dx = (event.getX(1) - event.getX(0)).toDouble()
        val dy = (event.getY(1) - event.getY(0)).toDouble()
        return Math.toDegrees(atan2(dy, dx)).toFloat()
    }

    private fun handleMove(event: MotionEvent) {
        val container = activeContainer ?: return
        when (touchMode) {
            TouchMode.DRAG_OR_ROTATE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (container.mode == ContainerMode.NORMAL) {
                    // Normal mode: one-finger drag moves the container.
                    container.rect.offset(dx, dy)
                } else {
                    // Interaction mode: one-finger drag rotates the model;
                    // the container rect itself never moves here.
                    container.yawDeg = (container.yawDeg + dx * 0.4f) % 360f
                    container.pitchDeg = (container.pitchDeg + dy * 0.4f).coerceIn(-80f, 80f)
                    sceneManager.applyModelTransform(container)
                }
                invalidate()
            }
            TouchMode.PINCH -> {
                if (event.pointerCount < 2) return
                val ratio = pinchDistance(event) / startPinchDist
                if (container.mode == ContainerMode.NORMAL) {
                    // Normal mode: pinch resizes the container; content scales to fit.
                    val newW = (startRect.width() * ratio).coerceIn(minContainerSize, maxContainerSize)
                    val newH = (startRect.height() * ratio).coerceIn(minContainerSize, maxContainerSize)
                    val cx = startRect.centerX()
                    val cy = startRect.centerY()
                    container.rect.set(cx - newW / 2, cy - newH / 2, cx + newW / 2, cy + newH / 2)
                    sceneManager.onContainerResized(container)
                } else {
                    // Interaction mode: pinch zooms the 3D content and a
                    // two-finger twist rolls it around Z; the container
                    // itself does not resize here.
                    container.scale = (startScale * ratio).coerceIn(0.2f, 5f)
                    val angleDelta = pinchAngleDeg(event) - startPinchAngleDeg
                    container.rollDeg = (startRoll + angleDelta) % 360f
                    sceneManager.applyModelTransform(container)
                }
                invalidate()
            }
            TouchMode.NONE -> Unit
        }
    }

    private fun handlePointerUp(event: MotionEvent) {
        // Dropping from two fingers to one: keep manipulating with whichever
        // pointer remains instead of ending the gesture outright.
        if (touchMode == TouchMode.PINCH && event.pointerCount <= 2) {
            touchMode = TouchMode.DRAG_OR_ROTATE
            val remainingIndex = if (event.actionIndex == 0) 1 else 0
            lastX = event.getX(remainingIndex)
            lastY = event.getY(remainingIndex)
        }
    }

    private fun handleUp(event: MotionEvent) {
        val pending = pendingButton
        if (pending != null) {
            val (container, type) = pending
            val center = buttonCenters(container)[type]!!
            if (hypot((event.x - center.x).toDouble(), (event.y - center.y).toDouble()) <= buttonRadius) {
                performButtonAction(container, type)
            }
            resetTouchState()
            return
        }

        // A short, near-stationary tap on a container's body (not a drag,
        // not a button) toggles it in or out of fullscreen.
        val moved = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
        val elapsed = event.eventTime - downTime
        if (moved <= tapSlop && elapsed <= tapTimeoutMs) {
            val fullscreenContainer = containers.firstOrNull { it.windowedRect != null }
            if (fullscreenContainer != null) {
                exitFullscreen(fullscreenContainer)
            } else {
                topmostContainerAt(event.x, event.y)?.let { enterFullscreen(it) }
            }
        }
        resetTouchState()
    }

    private fun performButtonAction(container: ModelContainer, type: ButtonType) {
        when (type) {
            ButtonType.INTERACTION -> {
                container.mode = if (container.mode == ContainerMode.NORMAL)
                    ContainerMode.INTERACTION else ContainerMode.NORMAL
            }
            ButtonType.LABELS -> container.labelsVisible = !container.labelsVisible
            ButtonType.CLOSE -> removeContainer(container)
        }
        invalidate()
    }

    private fun resetTouchState() {
        touchMode = TouchMode.NONE
        activeContainer = null
        pendingButton = null
    }
}