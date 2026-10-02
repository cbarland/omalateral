package com.lateral.privileged

internal object PhoneWorkspaceFocusPolicy {
    const val PHONE_VIEW_ACTIVITY = "com.lateral.beast.BeastActivity"

    fun accepts(packageName: String, className: String, expectedPackage: String): Boolean =
        packageName == expectedPackage &&
            (className == "com.lateral.MainActivity" || className == PHONE_VIEW_ACTIVITY)

    /** Placement and foreground focus are separate facts on multi-display Android. */
    fun restoreVerified(
        targetDisplayId: Int?,
        expectedTargetDisplayId: Int,
        focusedPhoneTaskId: Int?,
        expectedPhoneTaskId: Int,
    ): Boolean = targetDisplayId == expectedTargetDisplayId &&
        focusedPhoneTaskId == expectedPhoneTaskId
}
