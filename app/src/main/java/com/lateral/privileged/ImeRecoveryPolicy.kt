package com.lateral.privileged

internal object ImeRecoveryPolicy {
    enum class RestoreAction { DONE, RETRY, SELECT_PREVIOUS }

    fun restoreAction(active: String?, previous: String?, selected: String): RestoreAction = when {
        active.isNullOrBlank() || previous.isNullOrBlank() || previous == active -> RestoreAction.DONE
        selected.isBlank() -> RestoreAction.RETRY
        selected != active -> RestoreAction.DONE
        else -> RestoreAction.SELECT_PREVIOUS
    }

    fun confirmed(resultCode: Int, selectedImeId: String?, sessionImeId: String): Boolean =
        resultCode == 0 || resultCode == 6 ||
            (!selectedImeId.isNullOrBlank() && selectedImeId != sessionImeId)
}
