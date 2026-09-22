package com.example.multimodelviewer

import android.graphics.RectF
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.google.android.filament.Filament
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Gltfio

/**
 * The whole app lives in this one Activity (spec 1.1): a full-screen
 * SurfaceView that Filament renders into, a transparent OverlayView on top
 * of it for borders/buttons/labels + all touch handling, and a single
 * "Add model" button.
 */
class MainActivity : AppCompatActivity(), UiHelper.RendererCallback {

    companion object {
        init {
            // Must run before any Filament class (Engine, Camera, etc.) is
            // touched anywhere in the app -- this loads libfilament-jni.so.
            Filament.init()
            // gltfio (AssetLoader, ResourceLoader, UbershaderProvider) ships
            // its OWN separate native library (libgltfio-jni.so) and is not
            // covered by Filament.init() above -- without this call you get
            // UnsatisfiedLinkError on UbershaderProvider's native methods.
            Gltfio.init()
        }
    }

    private lateinit var sceneManager: SceneManager
    private lateinit var overlayView: OverlayView
    private lateinit var uiHelper: UiHelper
    private lateinit var addButton: AppCompatButton
    private val choreographer = Choreographer.getInstance()
    private var containerCount = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            sceneManager.renderFrame(frameTimeNanos)
            overlayView.invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sceneManager = SceneManager(this)

        val root = FrameLayout(this)
        setContentView(root)

        val surfaceView = android.view.SurfaceView(this)
        root.addView(surfaceView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        overlayView = OverlayView(this, sceneManager)
        root.addView(overlayView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        overlayView.onFullscreenChanged = { isFullscreen ->
            // Nothing else to add/tap on top of a fullscreen 3D view.
            addButton.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        }

        addButton = AppCompatButton(this).apply {
            text = "Add model"
            setOnClickListener { showAddModelDialog() }
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = (24 * resources.displayMetrics.density).toInt()
        root.addView(addButton, lp)

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            renderCallback = this@MainActivity
            attachTo(surfaceView)
        }
    }

    private fun showAddModelDialog() {
        val models = assets.list("models")
            ?.filter { it.endsWith(".glb", ignoreCase = true) }
            ?.sorted()
            ?: emptyList()

        if (models.isEmpty()) {
            Toast.makeText(this, "No .glb files found in assets/models -- see the placeholder note there.", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Add a model")
            .setItems(models.toTypedArray()) { _, which -> addModel("models/${models[which]}") }
            .show()
    }

    private fun addModel(assetPath: String) {
        // Place each new container in its own grid cell so models never
        // overlap by default -- wraps to a new row once a row runs out of
        // screen width. The user can still drag/resize freely afterwards.
        val density = resources.displayMetrics.density
        val size = 420f * density / 2f
        val margin = 24f * density
        val topInset = 160f * density
        val columns = ((resources.displayMetrics.widthPixels - margin) / (size + margin))
            .toInt()
            .coerceAtLeast(1)

        val col = containerCount % columns
        val row = containerCount / columns
        val left = margin + col * (size + margin)
        val top = topInset + row * (size + margin)
        val rect = RectF(left, top, left + size, top + size)
        containerCount++

        val container = sceneManager.loadModel(assetPath, rect)
        overlayView.addContainer(container)
    }

    // ---------------- UiHelper.RendererCallback ----------------

    override fun onNativeWindowChanged(surface: Surface) {
        sceneManager.attachSurface(surface)
    }

    override fun onDetachedFromSurface() {
        sceneManager.detachSurface()
    }

    override fun onResized(width: Int, height: Int) {
        // The clear pass and every container's Viewport need to know the
        // real surface size to convert Android's top-left rects into
        // Filament's bottom-left Viewport coordinates -- see SceneManager.
        sceneManager.onSurfaceResized(width, height)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Let Back collapse a fullscreen model instead of leaving the
        // Activity, same as tapping it again would.
        if (!overlayView.exitFullscreenIfShown()) {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHelper.detach()
        sceneManager.destroy()
    }
}