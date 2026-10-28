package com.wallhub.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wallhub.shared.ui.generated.resources.Res
import com.wallhub.shared.ui.generated.resources.noto_sans_sc_wallhub
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font

@Composable
@OptIn(ExperimentalResourceApi::class)
fun WallHubSharedUiCompatibilityProbe() {
    var query by remember { mutableStateOf("") }
    var clicks by remember { mutableIntStateOf(0) }
    val wallHubFont =
        FontFamily(
            Font(Res.font.noto_sans_sc_wallhub, FontWeight.Normal),
            Font(Res.font.noto_sans_sc_wallhub, FontWeight.Medium),
            Font(Res.font.noto_sans_sc_wallhub, FontWeight.SemiBold),
            Font(Res.font.noto_sans_sc_wallhub, FontWeight.Bold),
        )
    val typography =
        Typography(
            headlineSmall =
                TextStyle(
                    fontFamily = wallHubFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    lineHeight = 32.sp,
                ),
            bodyLarge =
                TextStyle(
                    fontFamily = wallHubFont,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                ),
            labelLarge =
                TextStyle(
                    fontFamily = wallHubFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
        )
    val colors =
        darkColorScheme(
            primary = Color.White,
            onPrimary = Color(0xFF242424),
            primaryContainer = Color(0xFF2C2C2C),
            onPrimaryContainer = Color.White,
            background = Color(0xFF121212),
            onBackground = Color.White,
            surface = Color(0xFF1C1C1C),
            onSurface = Color.White,
            surfaceContainer = Color(0xFF1C1C1C),
            surfaceContainerHigh = Color(0xFF242424),
            onSurfaceVariant = Color(0xFFCACACA),
        )

    MaterialTheme(
        colorScheme = colors,
        typography = typography,
    ) {
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
                    onClick = { clicks += 1 },
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Text("真实状态切换 · $clicks")
                }
                Text(
                    text = "当前输入：$query",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
