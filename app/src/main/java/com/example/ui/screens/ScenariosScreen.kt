package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DeviceStatus
import com.example.model.MorventMacro
import com.example.ui.theme.MorventCyan
import com.example.ui.theme.MorventDarkBorder
import com.example.ui.theme.MorventDarkSurface
import com.example.ui.theme.MorventDarkSurfaceElevated
import com.example.ui.theme.MorventEmerald
import com.example.ui.theme.MorventPurple
import com.example.ui.theme.MorventRose

@Composable
fun ScenariosScreen(
    macros: List<MorventMacro>,
    deviceStatus: DeviceStatus,
    onRunMacro: (MorventMacro) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MorventDarkSurface)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "الأوضاع الذكية وسيناريوهات التحكم",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Text(
                text = "تنفيذ سلاسل أوامر مؤتمتة بنقرة واحدة لتكييف الهاتف بالكامل",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        // Live Sensors telemetry
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MorventCyan.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sensors, contentDescription = null, tint = MorventCyan, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "مستشعرات الهاتف الحية (Live Telemetry)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MorventCyan
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SensorTelemetryTile(
                        title = "مستشعر الضوء",
                        value = "${deviceStatus.lightSensorLux.toInt()} Lux",
                        sub = if (deviceStatus.lightSensorLux < 20) "إضاءة خافتة" else "إضاءة ساطعة",
                        color = MorventCyan
                    )
                    SensorTelemetryTile(
                        title = "مستشعر التسارع Z",
                        value = String.format("%.2f m/s²", deviceStatus.accelerometerZ),
                        sub = if (deviceStatus.accelerometerZ > 8) "الهاتف مستوٍ" else "الهاتف مائل",
                        color = MorventPurple
                    )
                    SensorTelemetryTile(
                        title = "حرارة البطارية",
                        value = "${deviceStatus.batteryTemp}°C",
                        sub = "حالة ممتازة",
                        color = MorventEmerald
                    )
                }
            }
        }

        Text(
            text = "السيناريوهات الجاهزة للتنفيذ",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )

        // List of Macros
        macros.forEach { macro ->
            MacroCard(macro = macro, onExecute = { onRunMacro(macro) })
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun SensorTelemetryTile(
    title: String,
    value: String,
    sub: String,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF131D31),
        modifier = Modifier.width(100.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 9.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = color)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = sub, style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 9.sp)
        }
    }
}

@Composable
private fun MacroCard(
    macro: MorventMacro,
    onExecute: () -> Unit
) {
    val (icon, color) = when (macro.id) {
        "sleep" -> Pair(Icons.Default.Bedtime, MorventPurple)
        "work" -> Pair(Icons.Default.Work, MorventCyan)
        "power_save" -> Pair(Icons.Default.Bolt, MorventEmerald)
        "emergency" -> Pair(Icons.Default.Warning, MorventRose)
        else -> Pair(Icons.Default.Speed, MorventCyan)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MorventDarkBorder, RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = macro.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = macro.titleEn,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                }

                Button(
                    onClick = onExecute,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = color),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "تشغيل",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "تشغيل",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = macro.description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                macro.commands.forEach { cmd ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF131D31)
                    ) {
                        Text(
                            text = cmd,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
