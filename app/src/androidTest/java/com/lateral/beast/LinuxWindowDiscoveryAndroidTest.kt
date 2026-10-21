package com.lateral.beast

import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.privileged.PrivilegedService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxWindowDiscoveryAndroidTest {
    @Test fun linuxTaskIsVisibleButPhoneControllerIsNot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidTaskSynchronizer.init(context)
        val task = PrivilegedService.TaskSnapshot(
            taskId = 20, overviewRank = 0, lastActiveTime = 0, isRunning = true,
            hasBeenVisible = true, displayId = 1, isVisible = true, isFocused = true,
            activityType = 1, excluded = false, supportsMultiWindow = true,
            packageName = context.packageName, activityName = "dev.omalauncher.TerminalWindow",
            title = "Terminal", isAvailable = true,
        )
        assertTrue(AndroidTaskSynchronizer.isOmalauncherDesktopTask(task))
        assertTrue(AndroidTaskSynchronizer.isOmalauncherDesktopTask(task.copy(activityName = "dev.omalauncher.FilesActivity")))
        assertFalse(AndroidTaskSynchronizer.isOmalauncherDesktopTask(task.copy(activityName = "com.lateral.MainActivity")))
    }
}
