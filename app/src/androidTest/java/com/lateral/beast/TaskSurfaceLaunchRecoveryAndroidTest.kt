package com.lateral.beast

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TaskSurfaceLaunchRecoveryAndroidTest {
    @Test fun watchdogWaitsForTaskBindingBeforeRecoveringASlowLaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TaskSurfaceView(instrumentation.targetContext)
            val texture = SurfaceTexture(false)
            fun field(name: String) = TaskSurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }
            val recover = TaskSurfaceView::class.java.getDeclaredMethod("recoverMissingFrame", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val task = BeastTask(-1, "fixture", "fixture", "Fixture", "fixture", placement = TaskPlacement.PENDING_BEAST)
            view.task = task
            // No real display/helper is needed: pending launch recovery must leave
            // the existing display untouched, even through repeated watchdog ticks.
            field("ownedDisplayId").set(view, Int.MAX_VALUE)
            field("launchSent").setBoolean(view, true)
            field("outputSurface").set(view, Surface(texture))
            try {
                repeat(6) { recover.invoke(view, 0) }
                assertEquals(Int.MAX_VALUE, field("ownedDisplayId").get(view))
                assertTrue(field("launchSent").getBoolean(view))
                assertEquals(0, field("frameRecoveryAttempts").getInt(view))
                task.androidTaskId = 42
                recover.invoke(view, 0)
                assertEquals("Recovery resumes after exact task binding", 1, field("frameRecoveryAttempts").getInt(view))
            } finally {
                field("ownedDisplayId").set(view, null)
                view.release()
                texture.release()
            }
        }
    }
}
