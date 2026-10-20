package com.lateral.beast

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.SurfaceControl
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.privileged.IProtectedDisplayListener
import com.lateral.privileged.PrivilegedService
import com.lateral.privileged.UsbHelperLifecycleAndroidTest
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File

/** Production flat tile, real secure Android window, no secure capture permissions. */
class ProtectedTileAndroidTest {
    @Test fun protectedPresentationKeepsInputAndCapturePolicyAcrossResizeAndReattach() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 35)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bootstrap = UsbHelperLifecycleAndroidTest()
        assumeFalse("Do not replace an existing helper", bootstrap.currentService()?.asBinder()?.pingBinder() == true)
        val requested = InstrumentationRegistry.getArguments().getString("protectedDisplayId")?.toInt()
        val output = checkNotNull(context.getSystemService(DisplayManager::class.java).displays.firstOrNull {
            it.displayId != 0 && it.flags and Display.FLAG_SECURE != 0 && (requested == null || requested == it.displayId)
        }) { "A secure secondary emulator display is required" }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
        val directory=File(context.getExternalFilesDir(null),"test-captures/protected-flat").apply { mkdirs() }
        fun await(label: String, predicate: () -> Boolean) {
            val until = SystemClock.uptimeMillis()+20000
            while (SystemClock.uptimeMillis()<until) { if (predicate()) return; Thread.sleep(80) }
            if (label.contains("input") || label.contains("keyboard") || label.contains("tap")) {
                File(directory,"input-timeout.txt").writeText(buildString {
                    appendLine("Timed out: $label")
                    appendLine(shell("run-as ${instrumentation.context.packageName} cat shared_prefs/protected-fixture-input.xml"))
                    appendLine(shell("dumpsys input"))
                    appendLine("WINDOW MANAGER")
                    appendLine(shell("dumpsys window"))
                    appendLine("ACTIVITY TASKS")
                    appendLine(shell("dumpsys activity activities"))
                    appendLine(shell("dumpsys SurfaceFlinger"))
                })
            }
            error("Timed out: $label")
        }
        fun <T> ui(block: () -> T): T {
            var result: T? = null
            instrumentation.runOnMainSync { result = block() }
            @Suppress("UNCHECKED_CAST") return result as T
        }
        fun marker(bitmap: Bitmap): Double {
            var pixels=0; var markers=0
            for(y in 20 until bitmap.height-20 step 6) for(x in 20 until bitmap.width-20 step 6) {
                val c=bitmap.getPixel(x,y)
                if(Color.red(c)>180 && Color.green(c)<70 && Color.blue(c)>150) markers++
                pixels++
            }
            return markers.toDouble()/pixels.coerceAtLeast(1)
        }
        val helper=bootstrap.launch()
        var scenario: ActivityScenario<ProtectedTileFixtureActivity>?=null
        var tile: TaskSurfaceView?=null
        var ordinary: TaskSurfaceView?=null
        var source=-1
        var ordinarySource=-1
        try {
            await("helper ready") { PrivilegedService.isBridgeReady() }
            val service=checkNotNull(bootstrap.currentService())
            scenario=ActivityScenario.launch(Intent(context,ProtectedTileFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().apply { launchDisplayId=output.displayId }.toBundle())
            lateinit var root: FrameLayout
            var chromeTaps = 0
            scenario.onActivity { host ->
                host.window.insetsController?.hide(WindowInsets.Type.systemBars())
                root=FrameLayout(host).apply { setBackgroundColor(Color.DKGRAY) }
                tile=TaskSurfaceView(host).apply {
                    task=BeastTask(-890L,instrumentation.context.packageName,SecureFixtureActivity::class.java.name,"Secure fixture","SECURE")
                    onDisplayReady={ source=it }
                }.also { root.addView(it,FrameLayout.LayoutParams(480,640).apply { leftMargin=40; topMargin=80 }) }
                ordinary=TaskSurfaceView(host).apply {
                    task=BeastTask(-891L,instrumentation.context.packageName,SecureSecondFixtureActivity::class.java.name,"Ordinary","NORMAL")
                    onDisplayReady={ ordinarySource=it }
                }.also { root.addView(it,FrameLayout.LayoutParams(360,640).apply { leftMargin=620; topMargin=80 }) }
                root.addView(View(host).apply { setBackgroundColor(Color.GREEN); setOnClickListener { chromeTaps++ } },
                    FrameLayout.LayoutParams(100,70).apply { leftMargin=100; topMargin=150 })
                host.setContentView(root)
            }
            await("both hosted app displays") { source>=0 && ordinarySource>=0 }
            val component="${instrumentation.context.packageName}/${SecureFixtureActivity::class.java.name}"
            fun secure(enabled: Boolean) {
                val result=shell("am start --display $source --activity-single-top -n $component --ez protected $enabled --ez editor true")
                assertFalse(result,result.contains("Error:"))
            }
            secure(false)
            await("ordinary app pixels before secure flag") {
                ui { tile!!.captureBitmap()?.let { image -> val value=marker(image); image.recycle(); value>.85 } ?: false }
            }
            val parent=ui { SurfaceControl.Builder().setName("rejected output fixture").setBufferSize(1,1).build() }
            val listener=object:IProtectedDisplayListener.Stub(){ override fun onProtectedDisplays(ids:IntArray){} }
            try {
                assertNull("Phone output rejected",service.openProtectedOutput(0,source,parent,parent,Binder(),listener))
                assertNull("Unknown source rejected",service.openProtectedOutput(output.displayId,Int.MAX_VALUE,parent,parent,Binder(),listener))
                assertNull("Owned nonsecure output rejected",service.openProtectedOutput(ordinarySource,source,parent,parent,Binder(),listener))
                assertNull("Unrelated host surface rejected",service.openProtectedOutput(output.displayId,source,parent,parent,Binder(),listener))
            } finally { parent.release() }
            secure(true)
            await("secure capture disabled") { ui { !tile!!.canCapture() } }
            await("protected clone composed") {
                shell("dumpsys SurfaceFlinger").substringAfter("Composition list").substringBefore("Input list")
                    .contains("CLONE | SENSITIVE_FOR_PRIVACY")
            }
            val sf=shell("dumpsys SurfaceFlinger")
            File(directory,"protected-layers.txt").writeText(sf)
            val headers=Regex("(?m)^Display (\\d+) \\([^\\n]*\\)\\n").findAll(sf).toList()
            val physical=headers.withIndex().first { (i,h) -> sf.substring(h.range.last+1,
                headers.getOrNull(i+1)?.range?.first ?: sf.length).contains("layerStack=${output.displayId} ") }.value.groupValues[1]
            val capture=File(directory,"secure-capture.png")
            shell("screencap -p -d $physical ${capture.absolutePath}")
            val image=checkNotNull(android.graphics.BitmapFactory.decodeFile(capture.absolutePath))
            val firstTile=Bitmap.createBitmap(image,40,80,540,640)
            assertTrue("Protected marker excluded from screenshot",marker(firstTile)<.001)
            firstTile.recycle()
            assertEquals("Chrome above native tile remains visible",Color.GREEN,image.getPixel(150,180))
            image.recycle()
            assertNull("Phone screen cannot be captured through the external helper",service.captureExternalDisplay(0))
            assertNull("Owned app displays cannot be captured through the external helper",service.captureExternalDisplay(source))
            val composed=ParcelFileDescriptor.AutoCloseInputStream(checkNotNull(service.captureExternalDisplay(output.displayId))).use {
                checkNotNull(android.graphics.BitmapFactory.decodeStream(it))
            }
            val composedFirstTile=Bitmap.createBitmap(composed,40,80,540,640)
            assertTrue("Helper capture excludes protected tile",marker(composedFirstTile)<.001)
            composedFirstTile.recycle()
            assertEquals("Helper capture includes workspace chrome",Color.GREEN,composed.getPixel(150,180))
            composed.recycle()
            fun inputCount(name: String, prefs: String = "protected-fixture-input"): Int {
                val text=shell("run-as ${instrumentation.context.packageName} cat shared_prefs/$prefs.xml")
                return Regex("name=\"$name\" value=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            }
            fun hostTrusted(): Boolean {
                val layers=shell("dumpsys SurfaceFlinger").substringAfter("Input list")
                val host=Regex("(?m)^  Layer \\[[^\\n]+] com\\.omalateral/com\\.lateral\\.beast\\.ProtectedTileFixtureActivity#[^\\n]*\\n").find(layers)
                    ?: error("Host input layer missing")
                return layers.substring(host.range.last+1).substringBefore("\n  Layer").contains("TRUSTED_OVERLAY")
            }
            await("scoped host trust acquired") { hostTrusted() }
            shell("input -d ${output.displayId} tap 150 180")
            await("chrome above secure tile stays clickable") { ui { chromeTaps == 1 } }
            val directBefore=inputCount("taps")
            shell("input -d ${output.displayId} tap 300 500")
            await("direct external-display touch") { inputCount("taps")>directBefore }
            repeat(3) { index ->
                val before=inputCount("taps")
                service.clickTouch(source,150+index*40,350)
                await("protected tap $index") { inputCount("taps")>before }
            }
            var before=inputCount("scrolls")
            service.injectScroll(source,250,350,1f)
            await("protected scroll") { inputCount("scrolls")>before }
            before=inputCount("keys")
            service.key(source,KeyEvent.KEYCODE_A)
            await("protected keyboard") { inputCount("keys")>before }
            service.text(source,"hello")
            await("protected text") { shell("run-as ${instrumentation.context.packageName} cat shared_prefs/protected-fixture-input.xml").contains("hello") }
            val initialMode=checkNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(source)).mode
            ui { tile!!.layoutParams=FrameLayout.LayoutParams(540,540).apply { leftMargin=40; topMargin=80 } }
            Thread.sleep(700)
            val protectedMode=checkNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(source)).mode
            assertEquals("Protected source width stays stable while the tile changes",initialMode.physicalWidth,protectedMode.physicalWidth)
            assertEquals("Protected source height stays stable while the tile changes",initialMode.physicalHeight,protectedMode.physicalHeight)
            before=inputCount("taps")
            shell("input -d ${output.displayId} tap 60 500")
            Thread.sleep(250)
            assertEquals("Letterbox bar does not target the protected app",before,inputCount("taps"))
            shell("input -d ${output.displayId} tap 300 500")
            await("fitted secure content remains touchable") { inputCount("taps")>before }
            before=inputCount("taps")
            service.clickTouch(source,240,300)
            await("input after resize") { inputCount("taps")>before }
            ui { tile!!.visibility=View.GONE }
            Thread.sleep(400)
            ui { tile!!.visibility=View.VISIBLE }
            await("secure presentation after restore") { ui { !tile!!.canCapture() } }
            Thread.sleep(500)
            before=inputCount("taps")
            service.clickTouch(source,240,300)
            await("input after restore") { inputCount("taps")>before }
            val second="${instrumentation.context.packageName}/${SecureSecondFixtureActivity::class.java.name}"
            shell("am start --display $ordinarySource --activity-single-top -n $second --ez protected true")
            await("second protected tile") { ui { !ordinary!!.canCapture() } }
            ui { tile!!.visibility=View.GONE }
            Thread.sleep(500)
            assertTrue("Other tile retains the shared host trust lease",hostTrusted())
            val secondBefore=inputCount("taps","protected-second-input")
            service.clickTouch(ordinarySource,240,300)
            await("second protected tile input") { inputCount("taps","protected-second-input")>secondBefore }
            ui { ordinary!!.visibility=View.GONE }
            await("last hidden tile releases host trust") { !hostTrusted() }
            ui { tile!!.visibility=View.VISIBLE; ordinary!!.visibility=View.VISIBLE }
            await("both protected tiles restored") { ui { !tile!!.canCapture() && !ordinary!!.canCapture() } }
            await("restored host trust") { hostTrusted() }
            shell("am start --display $ordinarySource --activity-single-top -n $second --ez protected false")
            await("second tile ordinary again") { ui { ordinary!!.canCapture() } }
            assertTrue("First tile still holds trust",hostTrusted())
            secure(false)
            await("ordinary rendering restored") { ui { tile!!.captureBitmap()?.let {
                image -> val value=marker(image); image.recycle(); value>.85 } ?: false } }
            await("ordinary source resize resumes") {
                checkNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(source)).mode.let {
                    it.physicalWidth != initialMode.physicalWidth && it.physicalHeight != initialMode.physicalHeight
                }
            }
            assertTrue(ui { ordinary!!.canCapture() })
            await("last ordinary tile releases host trust") { !hostTrusted() }
            secure(true)
            await("protected presentation before helper shutdown") { hostTrusted() }
            helper.eof()
            await("owned helper shut down") { !service.asBinder().pingBinder() }
            await("helper shutdown removed host trust") { !hostTrusted() }
            File(directory,"report.json").writeText("""{"passed":true,"secureDisplay":${output.displayId},"protectedReadbackBlocked":true,"input":true,"resize":true,"restore":true,"chromeInput":true,"directInput":true,"sharedHostCleanup":true,"helperShutdownCleanup":true,"hardwareVerified":false}""")
        } finally {
            // Remove only fixture tasks before their displays are released back to display 0.
            val service=bootstrap.currentService()
            for(id in listOf(source,ordinarySource).filter { it>=0 }) {
                val stacks=shell("am stack list").split(Regex("(?m)(?=^RootTask)"))
                stacks.filter { it.lineSequence().firstOrNull()?.contains("displayId=$id ")==true }.forEach { block ->
                    Regex("taskId=(\\d+)").findAll(block).forEach { service?.removeTask(it.groupValues[1].toInt()) }
                }
            }
            ui { tile?.release(); ordinary?.release() }
            scenario?.close()
            helper.close()
        }
    }
}
