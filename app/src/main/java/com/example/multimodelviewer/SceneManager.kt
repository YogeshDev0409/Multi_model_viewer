package com.example.multimodelviewer

import android.content.Context
import android.graphics.RectF
import android.opengl.Matrix
import android.view.Surface
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer

/**
 * Owns the ONE shared Filament Engine/Renderer/SwapChain for the whole
 * Activity. Every loaded model gets its own cheap Camera + Scene + View,
 * all rendered into disjoint rectangles of the same physical Surface within
 * a single frame. This is the core performance decision in the app: it
 * avoids paying for N separate EGL contexts / render threads, which is what
 * actually kills frame rate on 2-3GB devices when several 3D views are on
 * screen at once.
 *
 * NOTE: exact method names on View.DynamicResolutionOptions and friends can
 * shift slightly between Filament releases -- check them against whatever
 * version is pinned in app/build.gradle.kts if this doesn't compile as-is.
 */
class SceneManager(private val context: Context) {

    val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val assetLoader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)

    private var swapChain: SwapChain? = null

    // Filament only clears the viewport it's told to render into -- nothing
    // ever paints over the rest of the shared SurfaceView's buffer. Without
    // this, any screen area not currently covered by a container's own
    // Viewport keeps whatever was already sitting in that graphics memory
    // (visible as persistent multicolored static), and a newly-added model
    // can be effectively invisible against it. This empty, always-full-size
    // View is rendered first every frame purely to paint the whole surface
    // black before any container draws on top of it.
    private val clearScene: Scene = engine.createScene()
    private val clearCameraEntity = EntityManager.get().create()
    private val clearCamera: Camera = engine.createCamera(clearCameraEntity)
    private val clearView: View = engine.createView().apply {
        scene = clearScene
        camera = clearCamera
        blendMode = View.BlendMode.OPAQUE
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val containers = mutableListOf<ModelContainer>()
    private var nextId = 0

    fun attachSurface(surface: Surface) {
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = engine.createSwapChain(surface)
    }

    fun detachSurface() {
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
    }

    /** Called from MainActivity.onResized() with the real pixel size of the
     *  Filament-backed Surface. Needed both to size the full-screen clear
     *  pass and to convert each container's Android (top-left-origin) rect
     *  into Filament's (bottom-left-origin) Viewport coordinates. */
    fun onSurfaceResized(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        clearView.viewport = Viewport(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    /** Loads one bundled .glb and returns a new on-screen container for it. */
    fun loadModel(assetPath: String, initialRect: RectF): ModelContainer {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val labelsByName = GlbLabelParser.extractLabelsByNodeName(bytes)

        val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }
        val asset = assetLoader.createAsset(buffer) ?: error("gltfio failed to parse $assetPath")
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()

        val labeledNodes = asset.entities.toList().mapNotNull { entity ->
            val name = asset.getName(entity) ?: return@mapNotNull null
            val text = labelsByName[name] ?: return@mapNotNull null
            LabeledNode(entity, text)
        }
        val scene = engine.createScene()
        scene.addEntities(asset.entities)
        if (asset.lightEntities.isNotEmpty()) {
            scene.addEntities(asset.lightEntities)
        }

        // One simple directional light per model. Shadows are deliberately
        // off (castShadows(false)) -- shadow maps are one of the more
        // expensive things to multiply by 5 on a weak GPU.
        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f)
            .intensity(90_000f)
            .direction(-0.5f, -1f, -0.3f)
            .castShadows(false)
            .build(engine, sun)
        scene.addEntity(sun)

        val cameraEntity = EntityManager.get().create()
        val camera = engine.createCamera(cameraEntity)
        camera.setExposure(16f, 1f / 125f, 100f)

        val filamentView = engine.createView()
        filamentView.scene = scene
        filamentView.camera = camera
        filamentView.blendMode = View.BlendMode.OPAQUE
        applyLowEndRenderSettings(filamentView)

        val container = ModelContainer(
            id = "model_${nextId++}",
            displayName = assetPath.substringAfterLast('/'),
            filamentView = filamentView,
            camera = camera,
            scene = scene,
            asset = asset,
            labeledNodes = labeledNodes,
            initialRect = initialRect
        )

        frameCameraToAsset(container)
        applyModelTransform(container)
        containers += container
        return container
    }

    /** Every optimisation here trades a bit of visual fidelity for frame
     *  time; see the README for the full rationale on each one. */
    private fun applyLowEndRenderSettings(view: View) {
        view.dynamicResolutionOptions = View.DynamicResolutionOptions().apply {
            enabled = true
            quality = View.QualityLevel.LOW
            minScale = 0.5f
            maxScale = 1.0f
        }
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = false
        }
        view.antiAliasing = View.AntiAliasing.NONE
        view.bloomOptions = View.BloomOptions().apply { enabled = false }
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply { enabled = false }
    }

    /** Frames the camera so the model's whole bounding box is visible. */
    private fun frameCameraToAsset(container: ModelContainer) {
        val box = container.asset.boundingBox
        val center = box.center
        val halfExtent = box.halfExtent
        val radius = maxOf(halfExtent[0], halfExtent[1], halfExtent[2]).coerceAtLeast(0.01f)

        container.camera.lookAt(
            center[0].toDouble(), center[1].toDouble(), (center[2] + radius * 3.0).toDouble(),
            center[0].toDouble(), center[1].toDouble(), center[2].toDouble(),
            0.0, 1.0, 0.0
        )
        container.camera.setProjection(
            45.0, aspectFor(container.rect), 0.05, radius * 20.0, Camera.Fov.VERTICAL
        )
    }

    private fun aspectFor(rect: RectF): Double {
        val w = rect.width().coerceAtLeast(1f)
        val h = rect.height().coerceAtLeast(1f)
        return (w / h).toDouble()
    }

    /** Call after a container's rect changes size (pinch-resize in NORMAL
     *  mode) so the camera's aspect ratio keeps matching its viewport. */
    fun onContainerResized(container: ModelContainer) {
        val box = container.asset.boundingBox
        val radius = maxOf(box.halfExtent[0], box.halfExtent[1], box.halfExtent[2]).coerceAtLeast(0.01f)
        container.camera.setProjection(
            45.0, aspectFor(container.rect), 0.05, radius * 20.0, Camera.Fov.VERTICAL
        )
    }

    /** Applies yaw/pitch/scale to the model's root transform. Called once
     *  right after load, and again every time interaction-mode gestures
     *  change one of those values. */
    fun applyModelTransform(container: ModelContainer) {
        val tm = engine.transformManager
        val instance = tm.getInstance(container.asset.root)
        if (instance == 0) return

        val transform = FloatArray(16)
        Matrix.setIdentityM(transform, 0)
        Matrix.scaleM(transform, 0, container.scale, container.scale, container.scale)
        Matrix.rotateM(transform, 0, container.pitchDeg, 1f, 0f, 0f)
        Matrix.rotateM(transform, 0, container.yawDeg, 0f, 1f, 0f)
        Matrix.rotateM(transform, 0, container.rollDeg, 0f, 0f, 1f)
        tm.setTransform(instance, transform)
    }

    fun removeModel(container: ModelContainer) {
        containers.remove(container)
        engine.destroyView(container.filamentView)
        engine.destroyCameraComponent(container.camera.entity)
        EntityManager.get().destroy(container.camera.entity)
        engine.destroyScene(container.scene)
        assetLoader.destroyAsset(container.asset)
    }

    // Reused scratch buffers so the per-frame label projection allocates
    // nothing -- allocation churn is a direct source of GC-pause frame
    // drops on weak CPUs, which is exactly what the 30fps target rules out.
    private val projD = DoubleArray(16)
    private val viewD = DoubleArray(16)
    private val proj = FloatArray(16)
    private val viewM = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val worldTransform = FloatArray(16)
    private val worldPos = FloatArray(4)
    private val clip = FloatArray(4)

    /** Renders every active container into its own viewport of the single
     *  shared Surface, then updates each container's label projections.
     *  Call this from a Choreographer frame callback. */
    fun renderFrame(frameTimeNanos: Long) {
        val sc = swapChain ?: return
        if (!renderer.beginFrame(sc, frameTimeNanos)) return

        // Paint the whole surface black first. Otherwise any pixel not
        // covered by one of the containers below keeps whatever was
        // previously in that graphics buffer.
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            renderer.render(clearView)
        }

        // Guard against rendering before the real surface size is known --
        // without this, a container added in that brief window would get a
        // negative/garbage Viewport y and could land on top of others.
        if (surfaceHeight <= 0) {
            renderer.endFrame()
            return
        }

        // While one container is expanded fullscreen, the others sit behind
        // it doing nothing useful -- skip rendering them entirely.
        val fullscreenContainer = containers.firstOrNull { it.windowedRect != null }
        val visible = fullscreenContainer?.let { listOf(it) } ?: containers

        for (container in visible) {
            val r = container.rect
            // Filament's Viewport is (left, bottom, width, height) measured
            // from the BOTTOM-left of the render target, while `r` is an
            // Android RectF measured from the TOP-left -- flip here so a
            // container's rendered content lands under its own drawn
            // border/buttons instead of some other part of the screen.
            container.filamentView.viewport = Viewport(
                r.left.toInt(),
                (surfaceHeight - r.bottom).toInt(),
                r.width().toInt().coerceAtLeast(1),
                r.height().toInt().coerceAtLeast(1)
            )
            renderer.render(container.filamentView)
            updateProjectedLabels(container)
        }
        renderer.endFrame()
    }

    private fun updateProjectedLabels(container: ModelContainer) {
        if (!container.labelsVisible || container.labeledNodes.isEmpty()) {
            if (container.projectedLabels.isNotEmpty()) container.projectedLabels.clear()
            return
        }

        container.camera.getProjectionMatrix(projD)
        container.camera.getViewMatrix(viewD)
        for (i in 0 until 16) {
            proj[i] = projD[i].toFloat()
            viewM[i] = viewD[i].toFloat()
        }
        Matrix.multiplyMM(vpMatrix, 0, proj, 0, viewM, 0)

        val tm = engine.transformManager
        container.projectedLabels.clear()
        val r = container.rect

        for (node in container.labeledNodes) {
            val instance = tm.getInstance(node.entity)
            if (instance == 0) continue
            tm.getWorldTransform(instance, worldTransform)
            worldPos[0] = worldTransform[12]
            worldPos[1] = worldTransform[13]
            worldPos[2] = worldTransform[14]
            worldPos[3] = 1f
            Matrix.multiplyMV(clip, 0, vpMatrix, 0, worldPos, 0)
            if (clip[3] <= 0f) continue // behind the camera

            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]
            val anchorX = r.left + (ndcX * 0.5f + 0.5f) * r.width()
            val anchorY = r.top + (1f - (ndcY * 0.5f + 0.5f)) * r.height()

            container.projectedLabels += ProjectedLabel(
                text = node.text,
                anchorX = anchorX,
                anchorY = anchorY,
                screenX = anchorX + 24f,
                screenY = anchorY - 24f
            )
        }
    }

    fun destroy() {
        containers.toList().forEach { removeModel(it) }
        engine.destroyView(clearView)
        engine.destroyCameraComponent(clearCameraEntity)
        EntityManager.get().destroy(clearCameraEntity)
        engine.destroyScene(clearScene)
        resourceLoader.destroy()
        assetLoader.destroy()
        detachSurface()
        engine.destroyRenderer(renderer)
        engine.destroy()
    }
}