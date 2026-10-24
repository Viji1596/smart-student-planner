package com.smartstudent.planner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartstudent.planner.data.local.ThemeMode
import com.smartstudent.planner.ui.RootViewModel
import com.smartstudent.planner.ui.auth.LoginScreen
import com.smartstudent.planner.ui.AppShell
import com.smartstudent.planner.ui.theme.PlannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PlannerApp).container
        setContent {
            val themeMode by container.appearanceStore.mode.collectAsState(initial = ThemeMode.SYSTEM)
            PlannerTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root(container)
                }
            }
        }
    }
}

@Composable
private fun Root(container: AppContainer) {
    val rootViewModel: RootViewModel = viewModel(factory = RootViewModel.factory(container))
    val signedIn by rootViewModel.signedIn.collectAsState()

    when (signedIn) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        false -> LoginScreen(container)
        true -> AppShell(container)
    }
}
