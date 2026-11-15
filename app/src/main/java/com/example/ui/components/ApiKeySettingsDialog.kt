package com.example.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ai.ApiKeyManager
import com.example.nlp.ApiConnectionTestResult
import com.example.ui.theme.MorventCyan
import com.example.ui.theme.MorventDarkBorder
import com.example.ui.theme.MorventDarkSurface
import com.example.ui.theme.MorventDarkSurfaceElevated
import com.example.ui.theme.MorventEmerald
import com.example.ui.theme.MorventPurple
import com.example.ui.theme.MorventRose

@Composable
fun ApiKeySettingsDialog(
    currentApiKey: String,
    selectedModel: String,
    connectionStatus: String,
    isTestingConnection: Boolean,
    testResult: ApiConnectionTestResult?,
    userDisplayName: String?,
    userEmail: String?,
    syncedTasksCount: Int,
    onSaveApiKey: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onTestConnection: (String) -> Unit,
    onResetDefaultKey: () -> Unit,
    onClearKey: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    var draftKey by remember(currentApiKey) { mutableStateOf(currentApiKey) }
    var showKeyPlain by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MorventDarkSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MorventCyan.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MorventCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "إعدادات Gemini API و Firebase",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.Gray)
                    }
                }

                // Current status badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MorventDarkSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MorventDarkBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = connectionStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connectionStatus.contains("✓") || connectionStatus.contains("متصل")) MorventEmerald else MorventCyan,
                        modifier = Modifier.padding(10.dp),
                        fontSize = 12.sp
                    )
                }

                Text(
                    text = "مفتاح Gemini API (Google AI Studio)",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                OutlinedTextField(
                    value = draftKey,
                    onValueChange = { draftKey = it },
                    placeholder = {
                        Text("أدخل مفتاح API هنا (AIzaSy...)", color = Color.Gray, fontSize = 12.sp)
                    },
                    singleLine = true,
                    visualTransformation = if (showKeyPlain) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKeyPlain = !showKeyPlain }) {
                            Icon(
                                imageVector = if (showKeyPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "إظهار أو إخفاء المفتاح",
                                tint = Color.Gray
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MorventCyan,
                        unfocusedBorderColor = MorventDarkBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = MorventCyan
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input")
                )

                Text(
                    text = "النموذج النشط:",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.LightGray
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApiKeyManager.SUPPORTED_MODELS.forEach { model ->
                        val selected = model == selectedModel
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) MorventCyan.copy(alpha = 0.16f) else MorventDarkSurfaceElevated,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selected) MorventCyan else MorventDarkBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectModel(model) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = model,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (selected) MorventCyan else Color.LightGray
                                )
                                if (selected) {
                                    Text(
                                        text = "نشط ✓",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MorventEmerald
                                    )
                                }
                            }
                        }
                    }
                }

                // Action buttons: Save + Test
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSaveApiKey(draftKey) },
                        colors = ButtonDefaults.buttonColors(containerColor = MorventCyan),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_api_key_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("حفظ المفتاح", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            onSaveApiKey(draftKey)
                            onTestConnection(draftKey)
                        },
                        enabled = !isTestingConnection,
                        colors = ButtonDefaults.buttonColors(containerColor = MorventEmerald),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_api_key_button")
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("اختبار الاتصال", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onResetDefaultKey() },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = MorventPurple, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("المفتاح الافتراضي", color = MorventPurple, fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            draftKey = ""
                            onClearKey()
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MorventRose, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("مسح المفتاح", color = MorventRose, fontSize = 11.sp)
                    }
                }

                if (testResult != null) {
                    val bg = if (testResult.isSuccess) MorventEmerald.copy(alpha = 0.14f) else MorventRose.copy(alpha = 0.14f)
                    val border = if (testResult.isSuccess) MorventEmerald else MorventRose
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = bg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = if (testResult.isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = border,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = testResult.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                HorizontalDivider(color = MorventDarkBorder)

                // Firebase Cloud Sync account info
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MorventDarkSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MorventDarkBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudDone, contentDescription = null, tint = MorventEmerald, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "مزامنة Firebase Firestore",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                            Text(
                                text = "$syncedTasksCount مهام محفوظة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MorventCyan
                            )
                        }

                        Text(
                            text = userDisplayName?.takeIf { it.isNotBlank() }
                                ?.let { "$it (${userEmail ?: ""})" }
                                ?: (userEmail ?: "حساب Google متصل"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )

                        OutlinedButton(
                            onClick = onSignOut,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sign_out_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MorventRose, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تسجيل الخروج من Firebase", color = MorventRose, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
