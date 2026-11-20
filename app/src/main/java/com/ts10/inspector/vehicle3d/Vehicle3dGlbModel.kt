package com.ts10.inspector.vehicle3d

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for the launcher GLB exported by `exportar_glb_launcher_v45.py --runtime`.
 *
 * It accepts exactly what that exporter writes (triangles, float attributes, 16-bit indices, one
 * binary buffer, PNG images) and rejects anything else instead of rendering it wrongly.
 */
internal class Vehicle3dGlbModel(
    val nodes: List<GlbNode>,
    val roots: List<Int>,
    val meshes: List<List<GlbPrimitive>>,
    val materials: List<GlbMaterial>,
    /** PNG bytes of every image, indexed like the glTF `images` array. */
    val images: List<ByteArray>,
    /** The single BIN chunk; attribute and index offsets point into it. */
    val binary: ByteBuffer,
) {
    fun nodeIndex(name: String): Int = nodes.indexOfFirst { it.name == name }

    companion object {
        private const val MAGIC = 0x46546C67 // "glTF"
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942
        private const val FLOAT = 5126
        private const val UNSIGNED_SHORT = 5123
        private const val TRIANGLES = 4

        fun parse(bytes: ByteArray): Vehicle3dGlbModel {
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(data.getInt(0) == MAGIC && data.getInt(4) == 2) { "No es GLB 2.0" }
            val jsonLength = data.getInt(12)
            require(data.getInt(16) == CHUNK_JSON) { "Falta el bloque JSON" }
            val json = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8))
            val binHeader = 20 + jsonLength
            require(data.getInt(binHeader + 4) == CHUNK_BIN) { "Falta el bloque BIN" }
            val binLength = data.getInt(binHeader)
            val binary = ByteBuffer.allocateDirect(binLength).order(ByteOrder.nativeOrder())
            binary.put(bytes, binHeader + 8, binLength).position(0)

            val views = json.getJSONArray("bufferViews")
            val accessors = json.getJSONArray("accessors")
            fun accessor(index: Int, componentType: Int, type: String): GlbAccessor {
                val accessor = accessors.getJSONObject(index)
                require(accessor.getInt("componentType") == componentType && accessor.getString("type") == type) {
                    "Accessor $index inesperado"
                }
                val view = views.getJSONObject(accessor.getInt("bufferView"))
                require(!view.has("byteStride")) { "Atributos entrelazados no admitidos" }
                return GlbAccessor(
                    offset = view.optInt("byteOffset") + accessor.optInt("byteOffset"),
                    count = accessor.getInt("count"),
                    min = accessor.optJSONArray("min")?.toFloats(),
                    max = accessor.optJSONArray("max")?.toFloats(),
                )
            }

            val meshes = json.getJSONArray("meshes").map { mesh ->
                mesh.getJSONArray("primitives").map { primitive ->
                    require(primitive.optInt("mode", TRIANGLES) == TRIANGLES) { "Triangles only" }
                    val attributes = primitive.getJSONObject("attributes")
                    GlbPrimitive(
                        position = accessor(attributes.getInt("POSITION"), FLOAT, "VEC3"),
                        normal = accessor(attributes.getInt("NORMAL"), FLOAT, "VEC3"),
                        uv = if (attributes.has("TEXCOORD_0")) accessor(attributes.getInt("TEXCOORD_0"), FLOAT, "VEC2") else null,
                        indices = accessor(primitive.getInt("indices"), UNSIGNED_SHORT, "SCALAR"),
                        material = primitive.optInt("material", -1),
                    )
                }
            }

            val textures = json.optJSONArray("textures")
            val materials = json.getJSONArray("materials").map { material ->
                val pbr = material.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
                val emissiveStrength = material.optJSONObject("extensions")
                    ?.optJSONObject("KHR_materials_emissive_strength")
                    ?.optDouble("emissiveStrength", 1.0)?.toFloat() ?: 1f
                GlbMaterial(
                    name = material.optString("name"),
                    baseColor = pbr.optJSONArray("baseColorFactor")?.toFloats() ?: floatArrayOf(1f, 1f, 1f, 1f),
                    metallic = pbr.optDouble("metallicFactor", 1.0).toFloat(),
                    roughness = pbr.optDouble("roughnessFactor", 1.0).toFloat(),
                    emissive = (material.optJSONArray("emissiveFactor")?.toFloats() ?: floatArrayOf(0f, 0f, 0f))
                        .map { it * emissiveStrength }.toFloatArray(),
                    blend = material.optString("alphaMode", "OPAQUE") == "BLEND",
                    baseColorImage = pbr.optJSONObject("baseColorTexture")?.let { texture ->
                        textures?.getJSONObject(texture.getInt("index"))?.optInt("source", -1)?.takeIf { it >= 0 }
                    },
                )
            }

            val images = json.optJSONArray("images")?.map { image ->
                val view = views.getJSONObject(image.getInt("bufferView"))
                val start = binHeader + 8 + view.optInt("byteOffset")
                bytes.copyOfRange(start, start + view.getInt("byteLength"))
            }.orEmpty()

            val nodes = json.getJSONArray("nodes").map { node ->
                require(!node.has("matrix")) { "Nodes with a matrix are not supported" }
                GlbNode(
                    name = node.optString("name"),
                    translation = node.optJSONArray("translation")?.toFloats() ?: floatArrayOf(0f, 0f, 0f),
                    rotation = node.optJSONArray("rotation")?.toFloats() ?: floatArrayOf(0f, 0f, 0f, 1f),
                    scale = node.optJSONArray("scale")?.toFloats() ?: floatArrayOf(1f, 1f, 1f),
                    mesh = node.optInt("mesh", -1),
                    children = node.optJSONArray("children")?.let { array -> List(array.length()) { array.getInt(it) } }.orEmpty(),
                )
            }
            val scene = json.getJSONArray("scenes").getJSONObject(json.optInt("scene", 0))
            val roots = scene.getJSONArray("nodes").let { array -> List(array.length()) { array.getInt(it) } }
            return Vehicle3dGlbModel(nodes, roots, meshes, materials, images, binary)
        }

        private fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> = List(length()) { transform(getJSONObject(it)) }

        private fun JSONArray.toFloats(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }
    }
}

internal data class GlbNode(
    val name: String,
    val translation: FloatArray,
    val rotation: FloatArray,
    val scale: FloatArray,
    val mesh: Int,
    val children: List<Int>,
)

internal data class GlbAccessor(val offset: Int, val count: Int, val min: FloatArray?, val max: FloatArray?)

internal data class GlbPrimitive(
    val position: GlbAccessor,
    val normal: GlbAccessor,
    val uv: GlbAccessor?,
    val indices: GlbAccessor,
    val material: Int,
)

internal data class GlbMaterial(
    val name: String,
    val baseColor: FloatArray,
    val metallic: Float,
    val roughness: Float,
    /** Linear emissive colour already multiplied by KHR_materials_emissive_strength. */
    val emissive: FloatArray,
    val blend: Boolean,
    val baseColorImage: Int?,
)
