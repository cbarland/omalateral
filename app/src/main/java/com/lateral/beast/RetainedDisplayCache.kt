package com.lateral.beast

import java.util.concurrent.ConcurrentHashMap

internal class RetainedDisplayCache {
    data class RetainedDisplay(val helperGeneration: Long, val displayId: Int)
    private val displays = ConcurrentHashMap<Long, RetainedDisplay>()
    fun retain(taskId: Long, displayId: Int, generation: Long) { displays[taskId] = RetainedDisplay(generation, displayId) }
    fun take(taskId: Long, generation: Long): Int? = displays.remove(taskId)?.takeIf {
        it.helperGeneration == generation
    }?.displayId
}
