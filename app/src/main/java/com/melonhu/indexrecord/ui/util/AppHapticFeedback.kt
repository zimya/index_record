package com.melonhu.indexrecord.ui.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

class AppHapticFeedback(
    private val view: View
) {
    /**
     * Tab 切换触感反馈（Android 14+）。
     */
    fun tabSwitch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(
                HapticFeedbackConstants.SEGMENT_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
    }

    /**
     * 开关 Toggle 触感反馈（Android 14+）。
     */
    fun toggle(checked: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(
                if (checked) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
    }

    /**
     * 下拉刷新达到触发阈值触感反馈（Android 14+）。
     */
    fun refreshThreshold() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(
                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
    }
}

@Composable
fun rememberAppHapticFeedback(): AppHapticFeedback {
    val view = LocalView.current
    return remember(view) {
        AppHapticFeedback(view)
    }
}
