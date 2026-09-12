package com.lateral

/** Physical mode dimensions, not rotated window bounds or a transient Android display ID. */
data class DisplayProfileKey(val identity: String, val width: Int, val height: Int) {
    val storageKey get() = "${identity.length}:$identity:$width:$height"
}

data class DisplayProfile(
    val phoneAspect: Float = 9f / 19.5f,
    val tabletAspect: Float = 1.45f,
    val supersampling: Float = 1.25f,
    val uiScale: Float = 1.1f,
    val hostedScale: Float = 1f,
) {
    fun sanitized() = copy(
        phoneAspect = phoneAspect.safe(.25f, 3f, 9f / 19.5f),
        tabletAspect = tabletAspect.safe(.25f, 3f, 1.45f),
        supersampling = supersampling.safe(1f, 2f, 1.25f),
        uiScale = uiScale.safe(.85f, 5f, 1.1f),
        hostedScale = hostedScale.safe(.6f, 5f, 1f),
    )
    companion object {
        fun parseAspect(text: String): Float? {
            val parts = text.trim().split(':')
            val ratio = when (parts.size) {
                1 -> parts[0].trim().toFloatOrNull()
                2 -> {
                    val width = parts[0].trim().toFloatOrNull() ?: return null
                    val height = parts[1].trim().toFloatOrNull() ?: return null
                    if (width <= 0 || height <= 0) return null
                    width / height
                }
                else -> null
            } ?: return null
            return ratio.takeIf { it.isFinite() && it in .25f..3f }
        }
        private fun Float.safe(min: Float, max: Float, fallback: Float) =
            if (isFinite()) coerceIn(min, max) else fallback
    }
}

/** Storage-independent codec: all fields are scoped to the same display/mode key. */
internal class DisplayProfileStore(
    private val read: (String, Float) -> Float,
    private val write: (Map<String, Float>) -> Unit,
) {
    fun load(key: DisplayProfileKey, defaults: DisplayProfile): DisplayProfile {
        fun value(name: String, default: Float) = read("${key.storageKey}/$name", default)
        return DisplayProfile(value("phone", defaults.phoneAspect), value("tablet", defaults.tabletAspect),
            value("sampling", defaults.supersampling), value("ui", defaults.uiScale),
            value("hosted", defaults.hostedScale)).sanitized()
    }
    fun save(key: DisplayProfileKey, value: DisplayProfile) {
        val p = value.sanitized()
        write(mapOf("phone" to p.phoneAspect, "tablet" to p.tabletAspect, "sampling" to p.supersampling,
            "ui" to p.uiScale, "hosted" to p.hostedScale).mapKeys { "${key.storageKey}/${it.key}" })
    }
}
