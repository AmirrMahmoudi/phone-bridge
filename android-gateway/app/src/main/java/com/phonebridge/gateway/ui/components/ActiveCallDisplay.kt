package com.phonebridge.gateway.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonebridge.gateway.viewmodel.CallState
import kotlinx.coroutines.delay

/**
 * نمایش تماس فعال با تایمر و دکمه اسپیکر
 */
@Composable
fun ActiveCallDisplay(
    callState: CallState,
    speakerEnabled: Boolean,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier
) {
    var callDuration by remember { mutableStateOf(0L) }
    
    // تایمر برای نمایش مدت زمان تماس
    LaunchedEffect(callState) {
        while (true) {
            callDuration = (System.currentTimeMillis() - callState.startTime) / 1000
            delay(1000)
        }
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // عنوان
            Text(
                text = "تماس فعال",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            
            // اطلاعات تماس
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = callState.callerName ?: callState.callerNumber,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (callState.callerName != null) {
                        Text(
                            text = callState.callerNumber,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
                
                // آیکون تلفن
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = "Active Call",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            
            // تایمر مدت زمان
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = "Duration",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = formatDuration(callDuration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            
            Divider()
            
            // دکمه اسپیکر
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FilledTonalIconButton(
                    onClick = onToggleSpeaker,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (speakerEnabled) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.surface,
                        contentColor = if (speakerEnabled)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = if (speakerEnabled) 
                            Icons.Default.VolumeUp 
                        else 
                            Icons.Default.VolumeDown,
                        contentDescription = "Toggle Speaker",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            Text(
                text = if (speakerEnabled) "بلندگو روشن است" else "بلندگو خاموش است",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

/**
 * فرمت کردن مدت زمان به صورت MM:SS
 */
private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", minutes, secs)
}
