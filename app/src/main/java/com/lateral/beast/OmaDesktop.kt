package com.lateral.beast

import android.view.KeyEvent
import android.app.ActivityOptions
import android.content.Intent
import android.view.Display
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import com.lateral.MainActivity
import dev.omalauncher.ui.LocalDesktopWindowHost
import dev.omalauncher.ui.LocalDesktopKeyboardHost
import kotlinx.coroutines.awaitCancellation
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.lateral.InputSettings
import com.lateral.OmarchyThemes
import dev.omalauncher.RuntimeOwner
import dev.omalauncher.ui.LauncherRoot
import dev.omalauncher.ui.LauncherState
import dev.omalauncher.ui.PromptRequest

/** The upstream launcher rendered in Beast's window, with Android launches owned by Beast. */
@OptIn(ExperimentalComposeUiApi::class)
class OmaDesktop(
    private val activity: ComponentActivity,
    private val onLaunch: () -> Unit,
    private val onOverlayChanged: (Boolean) -> Unit,
) : DesktopSession {
    private val runtime = (activity.application as RuntimeOwner).runtime
    private val state = LauncherState()
    private var started = false
    private val windows = DesktopWindows { activity.display?.displayId ?: Display.DEFAULT_DISPLAY }
    private var editor: DesktopTextInputSession.Editor? = null
    private val launch: (dev.omalauncher.AndroidApp) -> Unit = { app ->
        WorkspaceState.launchNew(app.component.packageName, app.component.className, app.label)
        onLaunch()
    }
    private val appearance: () -> Unit = {
        val selected = InputSettings.theme.id
        if (runtime.themes.picked != selected) {
            runtime.themes.bundled.firstOrNull { it.name == selected }?.let(runtime.themes::pick)
        }
    }

    override val view = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val interceptor = remember {
                PlatformTextInputInterceptor { request, next ->
                    if (activity.display?.displayId == Display.DEFAULT_DISPLAY) next.startInputMethod(request)
                    val session = openEditor(request::createInputConnection) { next.view.clearFocus() }
                    try {
                        awaitCancellation()
                    } finally {
                        DesktopTextInputSession.release(session)
                        if (editor === session) editor = null
                    }
                }
            }
            CompositionLocalProvider(
                LocalDesktopWindowHost provides if (activity.display?.displayId == Display.DEFAULT_DISPLAY) null else windows::attach,
                LocalDesktopKeyboardHost provides if (activity.display?.displayId == Display.DEFAULT_DISPLAY) null else ::openNativeEditor,
            ) {
                InterceptPlatformTextInput(interceptor) { LauncherRoot(runtime, state) }
            }
            LaunchedEffect(runtime) {
                snapshotFlow { runtime.themes.picked }.collect { name ->
                    val theme = OmarchyThemes.all.firstOrNull { it.id == name }
                    if (theme != null && InputSettings.theme.id != name) InputSettings.setTheme(activity, theme)
                }
            }
            LaunchedEffect(state) {
                snapshotFlow {
                    state.drawer.value > 0.01f || state.homeCovered || state.homeMenuOpen ||
                        state.editing != null || runtime.widgets.openScreen != null
                }.collect(onOverlayChanged)
            }
        }
    }

    init {
        runtime.androidLauncher = launch
        // The embedded desktop is ready without the optional Linux onboarding.
        runtime.finishWelcome()
        runtime.start()
        appearance()
        InputSettings.addAppearanceListener(appearance)
    }

    override fun visible(visible: Boolean) {
        if (started == visible) return
        started = visible
        if (visible) {
            runtime.widgets.homeStarted()
            runtime.screenStarted()
        } else {
            runtime.widgets.homeStopped()
            runtime.screenStopped()
        }
    }

    override fun search(query: String, submit: Boolean) {
        state.query = query
        state.promptRequest = if (submit) PromptRequest.Submit else PromptRequest.Type
    }

    override fun widgets() { state.storeOpen = true }
    override fun themes() { state.styleOpen = true }

    override fun dismissPanels() {
        // A queued Compose prompt request can otherwise refocus search after the
        // outside click has dismissed it, reopening a drawer over the taskbar.
        state.promptRequest = null
        editor?.let { DesktopTextInputSession.release(it) }
        state.closeNow()
        state.closeScreens()
        state.editing = null
        runtime.widgets.openScreen = null
        view.clearFocus()
    }

    override fun key(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || !event.isMetaPressed) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> runtime.terminal(null, "Terminal")
            KeyEvent.KEYCODE_SPACE -> state.toggle()
            KeyEvent.KEYCODE_A -> state.promptRequest = PromptRequest.Type
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> state.requestedPage = event.keyCode - KeyEvent.KEYCODE_1
            else -> return false
        }
        return true
    }

    override fun close() {
        editor?.let { DesktopTextInputSession.release(it) }
        windows.close()
        visible(false)
        InputSettings.removeAppearanceListener(appearance)
        if (runtime.androidLauncher === launch) runtime.androidLauncher = null
        view.disposeComposition()
    }

    override fun popupClick(x: Int, y: Int, button: Int) = windows.click(x, y, button)
    override fun popupPointer(x: Int, y: Int, action: Int, button: Int) = windows.pointer(x, y, action, button)
    override fun popupScroll(x: Int, y: Int, amount: Float) = windows.scroll(x, y, amount)

    private fun openNativeEditor(view: View): () -> Unit {
        val session = openEditor({ checkNotNull(view.onCreateInputConnection(it)) }) {
            (view as? dev.omalauncher.ui.KeyboardSink)?.hide()
            view.clearFocus()
        }
        return { DesktopTextInputSession.release(session); if (editor === session) editor = null }
    }

    private fun openEditor(factory: (EditorInfo) -> InputConnection, dismiss: () -> Unit): DesktopTextInputSession.Editor {
        val session = DesktopTextInputSession.begin(factory, dismiss)
        editor = session
        MainActivity.restorePhoneUiAfterExternalTaskBatch()
        activity.startActivity(Intent(activity, MainActivity::class.java).apply {
            action = MainActivity.ACTION_BEAST_LAUNCHER_SEARCH
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }.toBundle())
        return session
    }
}
