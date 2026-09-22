package com.example.multimodelviewer

import android.graphics.RectF
import com.google.android.filament.Camera
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.android.filament.gltfio.FilamentAsset

enum class ContainerMode { NORMAL, INTERACTION }

/**
 * Everything needed to render and manipulate one loaded model.
 *
 * Each container owns its own Filament Camera + Scene + View so it can be
 * rotated/zoomed independently of every other model on screen -- but all
 * containers share ONE Engine/Renderer/SwapChain (see SceneManager). That
 * sharing is what keeps 5 simultaneous models cheap: you pay for 5 small
 * Views, not 5 GL contexts.
 */
class ModelContainer(
    val id: String,
    val displayName: String,
    val filamentView: View,
    val camera: Camera,
    val scene: Scene,
    val asset: FilamentAsset,
    val labeledNodes: List<LabeledNode>,
    initialRect: RectF
) {
    /** Screen-space bounds of this model's container. Dragging/resizing just
     *  mutates this rect -- nothing about the Android view tree changes,
     *  which is what keeps drag/resize free of layout-pass lag. */
    val rect: RectF = RectF(initialRect)

    var mode: ContainerMode = ContainerMode.NORMAL
    var labelsVisible: Boolean = false

    // Orientation/zoom state, applied to the model's root transform each
    // time a gesture changes it (see SceneManager.applyModelTransform).
    var yawDeg: Float = 0f     // rotation around Y (one-finger horizontal drag)
    var pitchDeg: Float = 0f   // rotation around X (one-finger vertical drag)
    var rollDeg: Float = 0f    // rotation around Z (two-finger twist)
    var scale: Float = 1f

    /** Non-null while this container is expanded to fill the screen; holds
     *  the rect to restore it to on exit. Null means "normal, windowed". */
    var windowedRect: RectF? = null

    /** Recomputed every frame by SceneManager.renderFrame(); OverlayView
     *  reads this list directly, it never recomputes projections itself. */
    val projectedLabels = mutableListOf<ProjectedLabel>()
}

/** A glTF node that had extras.prop set, resolved to its Filament entity. */
data class LabeledNode(
    val entity: Int,
    val text: String
)

/** One label's current screen-space position, ready to draw. */
data class ProjectedLabel(
    val text: String,
    val anchorX: Float,   // where the part is on screen (connector line start)
    val anchorY: Float,
    val screenX: Float,   // where the label text is drawn (connector line end)
    val screenY: Float
)