package com.lateral

import com.lateral.privileged.PrivilegedService
import com.lateral.beast.AndroidTaskSynchronizer
import com.lateral.beast.WorkspaceState
import com.lateral.agent.AgentController
import com.lateral.privileged.PrivilegedHotkeys
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.embedded.EmbeddedImeRuntime

class LateralApp : FlorisApplication() {
    lateinit var agentController: AgentController
        private set

    override fun onCreate() {
        EmbeddedImeRuntime.enableHostMode()
        super.onCreate()
        agentController = AgentController(this)
        PrivilegedService.init(this)
        PrivilegedService.hotkeyHandler = { code ->
            when (code) {
                PrivilegedHotkeys.HK_FOCUS_LEFT -> WorkspaceState.focusRelative(-1)
                PrivilegedHotkeys.HK_FOCUS_RIGHT -> WorkspaceState.focusRelative(1)
                else -> WorkspaceShortcutDispatcher.dispatch(code)
            }
        }
        PhoneView.init(this)
        AndroidTaskSynchronizer.init(this)
        PrivilegedService.ensureRunning()
    }
}
