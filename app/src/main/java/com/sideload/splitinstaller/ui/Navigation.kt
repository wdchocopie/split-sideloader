package com.sideload.splitinstaller.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sideload.splitinstaller.R

object Tabs {
    const val INSTALL = 0
    const val SOURCES = 1
    const val APPS = 2
    const val LOG = 3
    const val SETTINGS = 4
}

@Composable
fun AppNavigationBar(tab: Int, attention: Int, downloading: Int, onTab: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        NavItem(tab == Tabs.INSTALL, Icons.Rounded.InstallMobile, Icons.Outlined.InstallMobile, stringResource(R.string.tab_home)) {
            onTab(Tabs.INSTALL)
        }
        NavItem(tab == Tabs.SOURCES, Icons.Rounded.Explore, Icons.Outlined.Explore, stringResource(R.string.tab_sources), downloading.takeIf { it > 0 }) {
            onTab(Tabs.SOURCES)
        }
        NavItem(tab == Tabs.APPS, Icons.Rounded.Apps, Icons.Outlined.Apps, stringResource(R.string.tab_apps), attention.takeIf { it > 0 }) {
            onTab(Tabs.APPS)
        }
        NavItem(tab == Tabs.LOG, Icons.Rounded.Terminal, Icons.Outlined.Terminal, stringResource(R.string.tab_log)) {
            onTab(Tabs.LOG)
        }
        NavItem(tab == Tabs.SETTINGS, Icons.Rounded.Settings, Icons.Outlined.Settings, stringResource(R.string.tab_settings)) {
            onTab(Tabs.SETTINGS)
        }
    }
}

@Composable
private fun RowScope.NavItem(
    selected: Boolean,
    selectedIcon: ImageVector,
    icon: ImageVector,
    label: String,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            BadgedBox(badge = { if (badge != null) Badge { Text(badge.toString()) } }) {
                Icon(if (selected) selectedIcon else icon, null)
            }
        },
        label = { Text(label, maxLines = 1) },
    )
}
