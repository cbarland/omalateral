package com.lateral.beast

import android.app.ActivityOptions
import android.os.ParcelFileDescriptor
import com.lateral.privileged.PrivilegedService
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device regression for real PhoneUI and Beast activities on separate displays. */
@RunWith(AndroidJUnit4::class)
class BeastUsbBootstrapUiAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun phoneFocusAndChromeSurviveRepeatedMinimizeRestoreAndExplicitHome() {
        val displays = context.getSystemService(DisplayManager::class.java)
        val workspaceDisplay = checkNotNull(displays.displays.firstOrNull {
            it.displayId != 0 && it.name == "Emulator 2D Display"
        }) { "Create the emulator secondary display with adb emu multidisplay add 1 1920 1080 160 0" }
        val displayId = workspaceDisplay.displayId
        var beast: BeastActivity? = null
        var phone: MainActivity? = null
        var helperPid = ""
        try {
            val apk = context.applicationInfo.sourceDir
            val pipes = instrumentation.uiAutomation.executeShellCommandRw("sh")
            val input = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
            val output = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader()
            input.write("echo \$\$; exec env CLASSPATH='$apk' app_process /system/bin --nice-name=lateral_usb_test com.lateral.privileged.PrivilegedServer --usb-adb-bootstrap\n".toByteArray())
            input.flush()
            val startup = java.util.concurrent.FutureTask {
                helperPid = checkNotNull(output.readLine()).trim()
                check(output.readLine() == "LATERAL_USB_READY") { "Production helper did not become ready" }
            }
            Thread(startup, "emulator-usb-bootstrap").apply { isDaemon = true; start() }
            startup.get(20, java.util.concurrent.TimeUnit.SECONDS)
            input.close()
            output.close()
            waitUntil("USB bootstrap READY") { PrivilegedService.bridgeTransport() == PrivilegedService.BridgeTransport.USB }
            PrivilegedService.taskSnapshots().filter { it.packageName == instrumentation.context.packageName }
                .forEach { PrivilegedService.removeTask(it.taskId) }
            instrumentation.runOnMainSync {
                WorkspaceState.closeAllCandidates().filter { it.packageName == instrumentation.context.packageName }.forEach {
                    it.androidTaskId?.let { taskId -> PrivilegedService.removeTask(taskId) }
                    WorkspaceState.removeClosed(it.id)
                }
            }
            // Launch PhoneUI on display 0 and prove a routed touch changes visible UI state.
            val phoneIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .setClass(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            instrumentation.runOnMainSync { context.startActivity(phoneIntent) }
            waitUntil("PhoneUI resumes") {
                phone = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
                phone != null
            }
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
            val beastIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .setClass(context, BeastActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            instrumentation.runOnMainSync { context.startActivity(beastIntent, options.toBundle()) }
            waitUntil("Beast activity resumes") {
                beast = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<BeastActivity>().firstOrNull()
                beast != null
            }
            Thread.sleep(1500)
            assertEquals(displayId, beast?.display?.displayId)
            fun launcher() = field(checkNotNull(beast), "launcher")
            val openApps = findTextView(checkNotNull(beast).window.decorView, "+APPS")
            assertNotNull("Beast +APPS control", openApps)

            // Use a real view touch sequence routed through Android's input dispatcher.
            tapView(checkNotNull(beast), checkNotNull(openApps))
            waitUntil("launcher opens") { launcher().visibility == View.VISIBLE }
            tapView(checkNotNull(beast), checkNotNull(findTextView(checkNotNull(beast).window.decorView, "+APPS")))
            waitUntil("launcher closes, desktop stays visible") { launcher().translationZ == 0f && !LauncherSearchSession.active }

            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            fun phoneDialogVisible(): Boolean = instrumentation.uiAutomation.windows.any {
                it.root?.findAccessibilityNodeInfosByText("LATERAL_ / config")?.isNotEmpty() == true
            }
            fun checkPhoneTouch() {
                assertEquals(Display.DEFAULT_DISPLAY, phone?.display?.displayId)
                tapView(checkNotNull(phone), field(checkNotNull(phone), "connectionText"))
                waitUntil("PhoneUI settings opens after restore", onMain = false) { phoneDialogVisible() }
                waitUntil("Phone dialog gets display-0 focus", onMain = false) {
                    shell("dumpsys window").contains("mTopFocusedDisplayId=0")
                }
                shell("input -d 0 keyevent KEYCODE_BACK")
                waitUntil("PhoneUI settings dismisses", onMain = false) { !phoneDialogVisible() }
            }
            checkPhoneTouch()
            val fixturePackage = instrumentation.context.packageName
            lateinit var first: BeastTask
            lateinit var second: BeastTask
            instrumentation.runOnMainSync {
                first = WorkspaceState.launchNew(fixturePackage, FixtureOneActivity::class.java.name, "Fixture One")
                second = WorkspaceState.launchNew(fixturePackage, FixtureTwoActivity::class.java.name, "Fixture Two")
            }
            waitUntil("two hosted fixture tasks") { first.androidTaskId != null && second.androidTaskId != null && first.placement == TaskPlacement.BEAST_VISIBLE && second.placement == TaskPlacement.BEAST_VISIBLE }
            repeat(5) { cycle ->
                tapView(checkNotNull(beast), checkNotNull(findTextView(checkNotNull(beast).window.decorView, "MIN ALL")))
                waitUntil("minimize cycle $cycle") { first.minimized && second.minimized }
                for (task in listOf(first, second)) {
                    tapView(checkNotNull(beast), checkNotNull(findTextView(field(checkNotNull(beast), "taskTabs"), "%02d".format(WorkspaceState.tasks.indexOfFirst { it.id == task.id } + 1))))
                    waitUntil("restore ${task.shortLabel} cycle $cycle") { !task.minimized && WorkspaceState.focusedTaskId == task.id }
                    tapView(checkNotNull(beast), checkNotNull(findTextView(checkNotNull(beast).window.decorView, "+APPS")))
                    waitUntil("launcher opens after restore") { launcher().visibility == View.VISIBLE }
                    tapView(checkNotNull(beast), checkNotNull(findTextView(checkNotNull(beast).window.decorView, "+APPS")))
                    waitUntil("launcher dismisses") { launcher().translationZ == 0f && !LauncherSearchSession.active }
                    checkPhoneTouch()
                }
            }

            shell("input -d 0 keyevent KEYCODE_HOME")
            Thread.sleep(3000)
            assertTrue("Explicit Home must not be stolen by focus recovery",
                shell("dumpsys window").lineSequence().any {
                    it.contains("mCurrentFocus=") && it.contains("NexusLauncherActivity")
                })

        } finally {
            PrivilegedService.taskSnapshots().filter { it.packageName == instrumentation.context.packageName }
                .forEach { runCatching { PrivilegedService.removeTask(it.taskId) } }
            instrumentation.runOnMainSync { WorkspaceState.closeAllCandidates().filter { it.packageName == instrumentation.context.packageName }.forEach { it.androidTaskId?.let { id -> runCatching { PrivilegedService.removeTask(id) } }; WorkspaceState.removeClosed(it.id) } }
            if (helperPid.matches(Regex("[0-9]+"))) shell("kill $helperPid")
            instrumentation.runOnMainSync {
                phone?.finishAndRemoveTask()
                beast?.finishAndRemoveTask()
            }

        }
    }

    private fun waitUntil(label: String, onMain: Boolean = true, predicate: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 20000
        while (android.os.SystemClock.uptimeMillis() < end) {
            var ready = false
            if (onMain) instrumentation.runOnMainSync { ready = predicate() } else ready = predicate()
            if (ready) return
            Thread.sleep(100)
        }
        fail("Timed out: $label; tasks=${WorkspaceState.tasks.map { "${it.label}:${it.placement}:${it.androidTaskId}" }}")
    }
    private fun field(activity: android.app.Activity, name: String): View =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity) as View

    private fun findTextView(root: View, text: String): android.widget.TextView? {
        if (root is android.widget.TextView && root.text.toString() == text) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) {
            findTextView(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun tapView(activity: android.app.Activity, view: View) {
        val rect = android.graphics.Rect()
        Thread.sleep(BeastControlDebouncer.DEBOUNCE_MS + 100)
        waitUntil("tap target ready: ${(view as? android.widget.TextView)?.text}") {
            val current = (view as? android.widget.TextView)?.text?.toString()?.let {
                findTextView(activity.window.decorView, it)
            } ?: view
            if (!current.isAttachedToWindow || !current.isLaidOut || current.isLayoutRequested ||
                !current.getGlobalVisibleRect(rect)) false else {
                val location = IntArray(2)
                current.getLocationOnScreen(location)
                rect.set(location[0], location[1], location[0] + current.width, location[1] + current.height)
                true
            }
        }
        if (activity is BeastActivity) {
            var handled = false
            instrumentation.runOnMainSync {
                handled = BeastInputRouter.click(activity.display!!.displayId, rect.centerX(), rect.centerY(), MotionEvent.BUTTON_PRIMARY)
            }
            if (!handled) {
                PrivilegedService.clickMouse(activity.display!!.displayId, rect.centerX(), rect.centerY(), MotionEvent.BUTTON_PRIMARY)
            }
        } else {
            shell("input -d ${activity.display?.displayId ?: 0} tap ${rect.centerX()} ${rect.centerY()}")

        }
    }

    private fun shell(command: String): String = instrumentation.uiAutomation
        .executeShellCommand(command).let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8) } }
}
