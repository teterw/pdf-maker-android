package com.pdfmaker.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.pdfmaker.app.MainViewModel

enum class HomeTab(val label: String, val icon: ImageVector) {
    BUILD("Create PDF", Icons.Filled.PictureAsPdf),
    EXTRACT("PDF → Images", Icons.Filled.Archive)
}

/**
 * Top-level shell. Each tab owns its own scaffold — they have different app bars and
 * different bottom actions — and shares only the tab bar at the very bottom.
 */
@Composable
fun HomeScreen(viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.BUILD) }

    when (tab) {
        HomeTab.BUILD -> MainScreen(
            viewModel = viewModel,
            currentTab = tab,
            onTabChange = { tab = it }
        )

        HomeTab.EXTRACT -> ExtractScreen(
            viewModel = viewModel,
            currentTab = tab,
            onTabChange = { tab = it }
        )
    }
}

@Composable
fun HomeTabBar(current: HomeTab, onTabChange: (HomeTab) -> Unit) {
    NavigationBar {
        HomeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { if (tab != current) onTabChange(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) }
            )
        }
    }
}

/** Hands the exported file to whatever the user picked in the share sheet. */
fun shareFile(context: Context, uri: Uri, mimeType: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, title)) }
}
