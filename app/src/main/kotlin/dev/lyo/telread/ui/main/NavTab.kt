package dev.lyo.telread.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavTab(val label: String, val icon: ImageVector) {
    Feed("Стрічка", Icons.Outlined.Home),
    Channels("Канали", Icons.Outlined.Forum),
    Saved("Збережене", Icons.Outlined.Bookmark),
    Profile("Профіль", Icons.Outlined.Person),
}
