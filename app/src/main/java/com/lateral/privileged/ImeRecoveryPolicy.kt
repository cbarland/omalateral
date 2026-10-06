package com.lateral.privileged

internal object ImeRecoveryPolicy {
    fun confirmed(resultCode: Int, selectedImeId: String?, sessionImeId: String): Boolean =
        resultCode == 0 || resultCode == 6 ||
            (!selectedImeId.isNullOrBlank() && selectedImeId != sessionImeId)
}
