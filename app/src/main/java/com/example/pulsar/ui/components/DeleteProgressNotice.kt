package com.example.pulsar.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pulsar.ui.theme.Dimens

/**
 * 删除进行中的提示行（工单 13）。
 *
 * 详情页的下载按钮与已下载列表的确认框共用一处：文案（「删除中…」）、进度环尺寸与
 * 2dp 线宽、与上文之间的间距都不各写一遍——两处都是「正文之后、按钮之前」的位置，
 * 所以间距 [Dimens.SpaceMidMedium] 由本组件自带。
 *
 * 只在**确认删除之后**显示：进度是给「已经点了删除、正在等」的人看的（用户故事 62）。
 */
@Composable
internal fun DeleteProgressNotice(modifier: Modifier = Modifier) {
    Spacer(Modifier.height(Dimens.SpaceMidMedium))
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(Dimens.SpaceMedium), strokeWidth = 2.dp)
        Spacer(Modifier.width(Dimens.SpaceSmall))
        Text("删除中…")
    }
}
