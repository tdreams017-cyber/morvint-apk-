package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import com.example.model.ActionCardData
import com.example.ui.theme.MorventCyan
import com.example.ui.theme.MorventDarkBorder
import com.example.ui.theme.MorventDarkSurfaceElevated
import com.example.ui.theme.MorventEmerald
import com.example.ui.theme.MorventPurple
import com.example.ui.theme.MorventRose

@Composable
fun ActionCardContainer(
    cardData: ActionCardData,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .border(1.dp, MorventDarkBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated),
        shape = RoundedCornerShape(14.dp)
    ) {
        when (cardData) {
            is ActionCardData.BatteryCard -> BatteryCardView(cardData)
            is ActionCardData.DeviceInfoCard -> DeviceInfoCardView(cardData)
            is ActionCardData.VolumeCard -> VolumeCardView(cardData)
            is ActionCardData.MacroResultCard -> MacroResultCardView(cardData)
            is ActionCardData.AppLaunchedCard -> AppLaunchedCardView(cardData)
            is ActionCardData.ScreenInspectionCard -> ScreenInspectionCardView(cardData)
            is ActionCardData.InAppActionResultCard -> InAppActionResultCardView(cardData)
            is ActionCardData.AccountWorkflowCard -> AccountWorkflowCardView(cardData)
        }
    }
}

@Composable
private fun BatteryCardView(data: ActionCardData.BatteryCard) {
    Column(modifier = Modifier.padding(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MorventEmerald.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryChargingFull,
                        contentDescription = "Battery",
                        tint = MorventEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "مستوى البطارية",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.LightGray
                    )
                    Text(
                        text = if (data.isCharging) "قيد الشحن ⚡" else "استهلاك عادي",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (data.isCharging) MorventEmerald else Color.Gray
                    )
                }
            }
            Text(
                text = "${data.percentage}%",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MorventEmerald
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { data.percentage / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MorventEmerald,
            trackColor = Color(0xFF203247)
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricChip(icon = Icons.Default.Thermostat, label = "الحرارة", value = "${data.temperatureCelsius}°C")
            MetricChip(icon = Icons.Default.CheckCircle, label = "الصحة", value = data.health)
        }
    }
}

@Composable
private fun DeviceInfoCardView(data: ActionCardData.DeviceInfoCard) {
    Column(modifier = Modifier.padding(14.dp)) {
        Text(
            text = "مواصفات الجهاز وموارده",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MorventCyan
        )
        Text(
            text = "${data.deviceModel} • ${data.osVersion}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.LightGray
        )

        Spacer(modifier = Modifier.height(12.dp))

        // RAM Bar
        val ramUsed = (data.totalRamGb - data.freeRamGb).coerceAtLeast(0f)
        val ramFraction = if (data.totalRamGb > 0) (ramUsed / data.totalRamGb) else 0.5f
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("ذاكرة RAM المتوفرة", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("${data.freeRamGb} GB / ${data.totalRamGb} GB", style = MaterialTheme.typography.bodySmall, color = MorventPurple)
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { ramFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MorventPurple,
                trackColor = Color(0xFF26203D)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Storage Bar
        val storageUsed = (data.totalStorageGb - data.freeStorageGb).coerceAtLeast(0f)
        val storageFraction = if (data.totalStorageGb > 0) (storageUsed / data.totalStorageGb) else 0.5f
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("المساحة التخزينية الحرة", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("${data.freeStorageGb} GB حرة", style = MaterialTheme.typography.bodySmall, color = MorventCyan)
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { storageFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MorventCyan,
                trackColor = Color(0xFF132E42)
            )
        }
    }
}

@Composable
private fun VolumeCardView(data: ActionCardData.VolumeCard) {
    Column(modifier = Modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = MorventCyan, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("مستويات الصوت", style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
        Spacer(modifier = Modifier.height(10.dp))
        val mediaFraction = if (data.maxMediaVolume > 0) (data.mediaVolume.toFloat() / data.maxMediaVolume) else 0.5f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("صوت الوسائط", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            Text("${(mediaFraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MorventCyan)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { mediaFraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MorventCyan,
            trackColor = Color(0xFF162438)
        )
    }
}

@Composable
private fun MacroResultCardView(data: ActionCardData.MacroResultCard) {
    Column(modifier = Modifier.padding(14.dp)) {
        Text(
            text = "سيناريو التشغيل: ${data.title}",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MorventEmerald
        )
        Spacer(modifier = Modifier.height(8.dp))
        data.executedSteps.forEach { step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MorventEmerald,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
            }
        }
    }
}

@Composable
private fun AppLaunchedCardView(data: ActionCardData.AppLaunchedCard) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MorventPurple.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Apps, contentDescription = null, tint = MorventPurple, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = "تم فتح التطبيق",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
            Text(
                text = data.appName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
    }
}

@Composable
private fun ScreenInspectionCardView(data: ActionCardData.ScreenInspectionCard) {
    Column(modifier = Modifier.padding(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MorventCyan.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FindInPage,
                        contentDescription = null,
                        tint = MorventCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "تحليل شاشة التطبيق النشط",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = data.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MorventCyan,
                        fontSize = 10.sp
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MorventCyan.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "${data.totalElements} عنصر",
                    style = MaterialTheme.typography.labelSmall,
                    color = MorventCyan,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (data.buttonsFound.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "الأزرار التفاعلية المكتشفة (${data.buttonsFound.size}):",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                data.buttonsFound.take(4).forEach { btn ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF131D31),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MorventCyan.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = btn,
                            style = MaterialTheme.typography.labelSmall,
                            color = MorventCyan,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (data.textInputs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "حقول الإدخال: ${data.textInputs.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun InAppActionResultCardView(data: ActionCardData.InAppActionResultCard) {
    Column(modifier = Modifier.padding(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (data.isSuccess) MorventEmerald.copy(alpha = 0.2f) else MorventRose.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (data.isSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (data.isSuccess) MorventEmerald else MorventRose,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = data.actionTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = "الهدف: ${data.target}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (data.isSuccess) MorventEmerald else MorventRose
                    )
                }
            }
        }

        if (data.logs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            data.logs.forEach { log ->
                Text(
                    text = "• $log",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun AccountWorkflowCardView(data: ActionCardData.AccountWorkflowCard) {
    Column(modifier = Modifier.padding(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MorventPurple.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = MorventPurple,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = data.workflowName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = data.appName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MorventPurple,
                        fontSize = 10.sp
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (data.isSuccess) MorventEmerald.copy(alpha = 0.15f) else MorventRose.copy(alpha = 0.15f)
            ) {
                Text(
                    text = if (data.isSuccess) "تمت المصادقة" else "تحت المتابعة",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (data.isSuccess) MorventEmerald else MorventRose,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        data.stepsExecuted.forEach { step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MorventEmerald,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun SubStepsExpander(steps: List<String>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(Color(0xFF0C1322), RoundedCornerShape(8.dp))
            .border(0.5.dp, Color(0xFF1E2D48), RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "خطوات التنفيذ في النظام (${steps.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MorventCyan.copy(alpha = 0.85f),
                fontSize = 11.sp
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MorventCyan.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                steps.forEach { s ->
                    Text(
                        text = "• $s",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(icon: ImageVector, label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0xFF131C30), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MorventCyan, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 10.sp)
            Text(text = value, style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}
