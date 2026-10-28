package com.wallhub.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WallHubSharedUiCompatibilityProbe(
    route: String = "/",
    persistedClicks: Int = 0,
    reducedMotion: Boolean = false,
    onClicksChanged: (Int) -> Unit = {},
    onOpenDetail: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var clicks by remember { mutableIntStateOf(persistedClicks) }
    var showDialog by remember { mutableStateOf(false) }

    WallHubSharedTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "WallHub Compose/Wasm compatibility probe",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "中文输入、Material 3、状态与浏览器渲染验证",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索创意工坊") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        clicks += 1
                        onClicksChanged(clicks)
                    },
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Text("真实状态切换 · $clicks")
                }
                Button(
                    onClick = { showDialog = true },
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Text("打开焦点对话框")
                }
                Button(
                    onClick = onOpenDetail,
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Text("浏览器历史测试")
                }
                Text(
                    text = "当前输入：$query",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "当前路由：$route",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "减少动态效果：${if (reducedMotion) "开启" else "关闭"}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("焦点与对话框验证") },
                text = { Text("键盘焦点应被限制在此对话框中，并在关闭后返回触发按钮。") },
                confirmButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("确认")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }
    }
}
