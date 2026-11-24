package com.phonebridge.gateway.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * کارت نمایش وضعیت اتصال BLE و دستگاه
 */
@Composable
fun StatusCard(
    bleActive: Boolean,
    connectedDevice: String?,
    audioStreaming: Boolean,
    batteryLevel: Int,
    signalStrength: Int,
    carrierName: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (bleActive) MaterialTheme.colorScheme.primaryContainer 
                            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // عنوان کارت
            Text(
                text = "وضعیت اتصال",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // وضعیت BLE
            StatusRow(
                icon = Icons.Default.Bluetooth,
                label = "سرور BLE",
                value = if (bleActive) "فعال" else "غیرفعال",
                iconTint = if (bleActive) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            
            // دستگاه متصل
            if (connectedDevice != null) {
                StatusRow(
                    icon = Icons.Default.PhoneIphone,
                    label = "دستگاه متصل",
                    value = connectedDevice,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            }
            
            // وضعیت صوت
            if (audioStreaming) {
                StatusRow(
                    icon = Icons.Default.GraphicEq,
                    label = "استریم صوتی",
                    value = "در حال انتقال",
                    iconTint = MaterialTheme.colorScheme.secondary
                )
            }
            
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            
            // اطلاعات دستگاه اندروید
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // باتری
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            batteryLevel > 80 -> Icons.Default.BatteryFull
                            batteryLevel > 50 -> Icons.Default.Battery80
                            batteryLevel > 20 -> Icons.Default.Battery50
                            else -> Icons.Default.Battery20
                        },
                        contentDescription = "Battery",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$batteryLevel%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                // سیگنال و اپراتور
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = "Signal",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = carrierName.ifEmpty { "بدون سیگنال" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/**
 * سطر نمایش وضعیت با آیکون
 */
@Composable
private fun StatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    iconTint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = iconTint
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
