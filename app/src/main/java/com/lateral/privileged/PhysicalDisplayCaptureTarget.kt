package com.lateral.privileged

/** SurfaceFlinger uses an output token, not DisplayManager's logical display id. */
internal object PhysicalDisplayCaptureTarget {
    fun forLayerStack(dump: String, displayId: Int): String? {
        val formats = listOf(
            // Recent SurfaceFlinger dumps describe physical and virtual outputs alike.
            Regex("(?m)^Display (\\d+) \\((?:physical|virtual),[^\\r\\n]*\\)\\r?$") to
                Regex("\\blayerFilter=\\{layerStack=$displayId\\b"),
            Regex("(?m)^Display (\\d+) \\(HWC display \\d+\\)[^\\r\\n]*\\r?$") to
                Regex("\\blayerStack=$displayId\\b"),
        )
        val displayHeaders = Regex("(?m)^Display \\d+ \\([^\\r\\n]*\\)[^\\r\\n]*\\r?$")
            .findAll(dump).map { it.range.first }.toList()
        for ((headerPattern, stackPattern) in formats) {
            val headers = headerPattern.findAll(dump).toList()
            headers.firstOrNull { header ->
                val end = displayHeaders.firstOrNull { it > header.range.first } ?: dump.length
                val section = dump.substring(header.range.last + 1, end)
                stackPattern.containsMatchIn(section)
            }?.let { return it.groupValues[1] }
        }
        return null
    }
}
