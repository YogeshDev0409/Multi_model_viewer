package com.example.multimodelviewer

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal, dependency-free .glb parser whose only job is reading the
 * embedded JSON chunk to pull out { node.name -> node.extras.prop }.
 *
 * Filament's gltfio importer preserves node *names* (asset.getName(entity)
 * returns them after loading) but does not surface arbitrary "extras"
 * fields on nodes. So we read the label text ourselves directly from the
 * binary, once at load time, and correlate it back to the Filament entity
 * by name after AssetLoader has parsed the model.
 *
 * GLB layout (see the glTF 2.0 binary spec):
 *   [4 bytes magic "glTF"] [4 bytes version] [4 bytes total length]
 *   then one or more chunks, each:
 *   [4 bytes chunk length] [4 bytes chunk type] [chunk data]
 *   The first chunk is always type "JSON".
 */
object GlbLabelParser {

    private const val MAGIC_GLTF = 0x46546C67   // "glTF" little-endian
    private const val CHUNK_TYPE_JSON = 0x4E4F534A // "JSON" little-endian

    /** Returns node name -> label text, for every node with extras.prop set. */
    fun extractLabelsByNodeName(glbBytes: ByteArray): Map<String, String> {
        val buffer = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)

        require(buffer.remaining() >= 20) { "File too small to be a valid .glb" }
        val magic = buffer.int
        require(magic == MAGIC_GLTF) { "Not a valid .glb file (bad magic header)" }
        buffer.int // version - unused
        buffer.int // total file length - unused

        val chunkLength = buffer.int
        val chunkType = buffer.int
        require(chunkType == CHUNK_TYPE_JSON) { "First .glb chunk is not JSON as required by spec" }

        val jsonBytes = ByteArray(chunkLength)
        buffer.get(jsonBytes)
        val json = JSONObject(String(jsonBytes, Charsets.UTF_8))

        val result = mutableMapOf<String, String>()
        val nodes = json.optJSONArray("nodes") ?: return result
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val name = node.optString("name", "").ifEmpty { null } ?: continue
            val extras = node.optJSONObject("extras") ?: continue
            val prop = extras.optString("prop", "").ifEmpty { null } ?: continue
            result[name] = prop
        }
        return result
    }
}
