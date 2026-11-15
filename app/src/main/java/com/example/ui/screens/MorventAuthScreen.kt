package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MorventCyan
import com.example.ui.theme.MorventDarkBg
import com.example.ui.theme.MorventDarkBorder
import com.example.ui.theme.MorventDarkSurfaceElevated
import com.example.ui.theme.MorventEmerald
import com.example.ui.theme.MorventPurple
import com.example.ui.theme.MorventRose

@Composable
fun MorventAuthScreen(
    isSigningIn: Boolean,
    errorMessage: String?,
    onSignInWithGoogle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MorventDarkBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MorventDarkSurfaceElevated,
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .border(1.dp, MorventDarkBorder, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MorventCyan.copy(alpha = 0.18f))
                        .border(1.dp, MorventCyan.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "شعار مورڤينت",
                        tint = MorventCyan,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text(
                    text = "مرحباً بك في مورڤينت (Morvent)",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "سجّل الدخول بحساب Google لتفعيل المزامنة السحابية الآمنة عبر Firebase Firestore والوصول إلى مساعد Gemini الذكي ووكيل أتمتة الهاتف.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF101B2E),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MorventPurple.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "• محادثة وفهم حقيقي عبر Gemini AI مع إمكانية وضع مفتاح API مخصص",
                            style = MaterialTheme.typography.bodySmall,
                            color = MorventCyan
                        )
                        Text(
                            text = "• حفظ سجل المهام والمحادثات لكل مستخدم بشكل معزول في Firestore",
                            style = MaterialTheme.typography.bodySmall,
                            color = MorventEmerald
                        )
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MorventRose.copy(alpha = 0.14f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MorventRose),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MorventRose,
                            modifier = Modifier.padding(10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onSignInWithGoogle,
                    enabled = !isSigningIn,
                    colors = ButtonDefaults.buttonColors(containerColor = MorventCyan),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("google_sign_in_button")
                ) {
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "جاري تسجيل الدخول...",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Login,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "تسجيل الدخول عبر Google",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
