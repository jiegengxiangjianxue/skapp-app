package com.skapp.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skapp.app.ui.theme.SkColors
import com.skapp.app.ui.theme.SkSpace
import com.skapp.app.ui.theme.SkType

/**
 * 附件选择器：支持图片（拍照/相册）和文件选择。
 *
 * @param onAttachmentsSelected 选中附件后回调，传入 Uri 列表
 */
@Composable
fun AttachmentPicker(
    onAttachmentsSelected: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let { onAttachmentsSelected(listOf(it)) }
        },
    )
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let { onAttachmentsSelected(listOf(it)) }
        },
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SkSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图片按钮
        AttachmentButton(
            label = "图片",
            onClick = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        // 文件按钮
        AttachmentButton(
            label = "文件",
            onClick = {
                filePicker.launch("*/*")
            },
        )
    }
}

@Composable
private fun AttachmentButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SkColors.surface3)
            .clickable { onClick() }
            .padding(horizontal = SkSpace.m, vertical = SkSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+ $label",
            style = SkType.label.copy(fontSize = 12.sp),
            color = SkColors.primary,
            textAlign = TextAlign.Center,
        )
    }
}
