package com.phonebridge.gateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonebridge.gateway.ui.components.ActiveCallDisplay
import com.phonebridge.gateway.ui.components.StatusCard
import com.phonebridge.gateway.viewmodel.ActivityItem
import com.phonebridge.gateway.viewmodel.ActivityType
import com.phonebridge.gateway.viewmodel.PhoneBridgeViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * صفحه اصلی اپلیکیشن Phone Bridge
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PhoneBridgeViewModel,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bleActive by viewModel.bleActive.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val audioStreaming by viewModel.audioStreaming.collectAsState()
    val activeCall by viewModel.activeCall.collectAsState()
    val speakerEnabled by viewModel.speakerEnabled.collectAsState()
    val activityItems by viewModel.activityItems.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val signalStrength by viewModel.signalStrength.collectAsState()
    val carrierName by viewModel.carrierName.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone Bridge") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // کارت وضعیت
            item {
                StatusCard(
                    bleActive = bleActive,
                    connectedDevice = connectedDevice,
                    audioStreaming = audioStreaming,
                    batteryLevel = batteryLevel,
                    signalStrength = signalStrength,
                    carrierName = carrierName
                )
            }
            
            // نمایش تماس فعال (در صورت وجود)
            if (activeCall != null) {
                item {
                    ActiveCallDisplay(
                        callState = activeCall!!,
                        speakerEnabled = speakerEnabled,
                        onToggleSpeaker = onToggleSpeaker
                    )
                }
            }
            
            // عنوان لیست فعالیت‌ها
            item {
                Text(
                    text = "فعالیت‌های اخیر",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            // لیست فعالیت‌ها
            if (activityItems.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                        ) {
                            Text(
                                text = "هنوز فعالیتی ثبت نشده است",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            } else {
                items(activityItems) { item ->
                    ActivityItemCard(item)
                }
            }
        }
    }
}

/**
 * کارت نمایش آیتم فعالیت
 */
@Composable
private fun ActivityItemCard(item: ActivityItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // آیکون بر اساس نوع فعالیت
            Icon(
                imageVector = when (item.type) {
                    ActivityType.CONNECTION -> Icons.Default.Bluetooth
                    ActivityType.CALL -> Icons.Default.Phone
                    ActivityType.SMS -> Icons.Default.Message
                },
                contentDescription = null,
                tint = when (item.type) {
                    ActivityType.CONNECTION -> MaterialTheme.colorScheme.primary
                    ActivityType.CALL -> MaterialTheme.colorScheme.secondary
                    ActivityType.SMS -> MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.size(24.dp)
            )
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (item.details != null) {
                    Text(
                        text = item.details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2
                    )
                }
                
                Text(
                    text = formatTimestamp(item.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * فرمت کردن timestamp به فرمت قابل خواندن
 */
private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
