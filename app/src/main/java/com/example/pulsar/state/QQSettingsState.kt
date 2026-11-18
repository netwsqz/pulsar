package com.example.pulsar.state

import com.example.pulsar.data.qqdownload.QQDownloadSettings

/**
 * QQ 导入服务的用户偏好 slice（`docs/qq-download-state-refactor.md` / #31）。
 *
 * 与 [QQServiceState] 的分界：这里是**用户设的**（落盘在 `QQDownloadPreferences`），
 * 那里是**服务回报的**。所以换服务（[com.example.pulsar.QQDownloadUiState.cleared]）
 * **不清这一块**——「用哪档音质」与「装在哪台电脑上」无关；文件操作密钥虽然是那台电脑的，
 * 但本 slice 只是落盘偏好的界面镜像，清掉界面反而与盘上的值不一致。
 */
data class QQSettingsState(
    /** 默认下载音质；下载时优先用这档，音源没有则自动降级。 */
    val quality: String = QQDownloadSettings.DEFAULT_QUALITY,
    /** 已保存的文件操作密钥（删歌/改名用，见工单 06）。 */
    val fileOpsKey: String = "",
    /** 密钥输入框的当前值；与 [fileOpsKey] 不同才允许保存。 */
    val fileOpsKeyInput: String = "",
)
