package com.phonebridge.gateway.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonebridge.gateway.util.BatteryOptimizationHelper

/**
 * کارت نمایش وضعیت Battery Optimization با راهنمایی
 */
@Composable
fun BatteryOptimizationCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var dozeStatus by remember { mutableStateOf(BatteryOptimizationHelper.getDozeStatus(context)) }
    var showGuide by remember { mutableStateOf(false) }
    
    // به‌روزرسانی وضعیت هر بار که card compose می‌شود
    LaunchedEffect(Unit) {
        dozeStatus = BatteryOptimizationHelper.getDozeStatus(context)
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = when (dozeStatus) {
            BatteryOptimizationHelper.DozeStatus.EXEMPTED, 
            BatteryOptimizationHelper.DozeStatus.NOT_APPLICABLE -> 
                CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.1f))
            BatteryOptimizationHelper.DozeStatus.OPTIMIZED -> 
                CardDefaults.cardColors(containerColor = Color(0xFFF57C00).copy(alpha = 0.1f))
            BatteryOptimizationHelper.DozeStatus.IN_DOZE_MODE -> 
                CardDefaults.cardColors(containerColor = Color(0xFFD32F2F).copy(alpha = 0.1f))
            else -> 
                CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // عنوان و آیکون
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (dozeStatus) {
                            BatteryOptimizationHelper.DozeStatus.EXEMPTED, 
                            BatteryOptimizationHelper.DozeStatus.NOT_APPLICABLE -> Icons.Default.CheckCircle
                            BatteryOptimizationHelper.DozeStatus.OPTIMIZED -> Icons.Default.Warning
                            BatteryOptimizationHelper.DozeStatus.IN_DOZE_MODE -> Icons.Default.BatteryAlert
                            else -> Icons.Default.Help
                        },
                        contentDescription = null,
                        tint = when (dozeStatus) {
                            BatteryOptimizationHelper.DozeStatus.EXEMPTED, 
                            BatteryOptimizationHelper.DozeStatus.NOT_APPLICABLE -> Color(0xFF4CAF50)
                            BatteryOptimizationHelper.DozeStatus.OPTIMIZED -> Color(0xFFF57C00)
                            BatteryOptimizationHelper.DozeStatus.IN_DOZE_MODE -> Color(0xFFD32F2F)
                            else -> Color.Gray
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "مدیریت باتری",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // دکمه راهنما
                if (dozeStatus == BatteryOptimizationHelper.DozeStatus.OPTIMIZED) {
                    IconButton(onClick = { showGuide = !showGuide }) {
                        Icon(
                            imageVector = if (showGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "نمایش راهنما"
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // توضیح وضعیت
            Text(
                text = BatteryOptimizationHelper.getStatusDescription(dozeStatus),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // دکمه‌ها برای وضعیت OPTIMIZED
            if (dozeStatus == BatteryOptimizationHelper.DozeStatus.OPTIMIZED ||
                dozeStatus == BatteryOptimizationHelper.DozeStatus.IN_DOZE_MODE) {
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF57C00)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("باز کردن تنظیمات")
                    }
                    
                    OutlinedButton(
                        onClick = {
                            dozeStatus = BatteryOptimizationHelper.getDozeStatus(context)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("بررسی مجدد")
                    }
                }
            }
            
            // راهنمای قدم به قدم
            if (showGuide && (dozeStatus == BatteryOptimizationHelper.DozeStatus.OPTIMIZED ||
                              dozeStatus == BatteryOptimizationHelper.DozeStatus.IN_DOZE_MODE)) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Divider()
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "راهنمای گام به گام:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                BatteryOptimizationHelper.getStepByStepGuide().forEach { step ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
