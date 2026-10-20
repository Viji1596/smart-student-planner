@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.profile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartstudent.planner.ui.common.ScreenTitle
import com.smartstudent.planner.ui.common.WhiteCard

enum class ProfilePage { ROOT, ROUTINE, TIMETABLE, IMPORT, HABITS, REMINDERS }

@Composable
fun ProfileScreen(userName: String?, onOpen: (ProfilePage) -> Unit, onSignOut: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Profile")

        WhiteCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (userName?.firstOrNull() ?: 'S').uppercaseChar().toString(),
                        color = scheme.onPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(userName ?: "Student", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Signed in with Google", fontSize = 14.sp, color = scheme.onSurfaceVariant)
                }
            }
        }

        Text(
            "My routine", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            color = scheme.onBackground, modifier = Modifier.padding(top = 8.dp),
        )
        ProfileRow(Icons.Default.Settings, "Daily routine", "Wake up, bedtime, meals") { onOpen(ProfilePage.ROUTINE) }
        ProfileRow(Icons.Default.DateRange, "Timetable", "Classes and fixed commitments") { onOpen(ProfilePage.TIMETABLE) }
        ProfileRow(Icons.Default.Refresh, "Habits", "Things you repeat every day or week") { onOpen(ProfilePage.HABITS) }
        ProfileRow(Icons.Default.Notifications, "Reminders", "Notifications before sessions, trips and meals") { onOpen(ProfilePage.REMINDERS) }

        Spacer(Modifier.height(8.dp))
        Surface(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            color = scheme.inverseSurface,
        ) {
            Box(Modifier.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text("Sign out", color = scheme.inverseOnSurface, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ProfileRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    WhiteCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(scheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = scheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 13.sp, color = scheme.onSurfaceVariant)
            }
            Text("›", fontSize = 26.sp, color = scheme.onSurfaceVariant)
        }
    }
}
