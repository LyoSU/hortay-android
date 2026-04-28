package dev.lyo.telread.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavTab(val label: String, val icon: ImageVector) {
    Feed("Стрічка", Icons.Rounded.Home),
    Channels("Канали", Icons.Rounded.Forum),
    Saved("Збережене", Icons.Rounded.Bookmark),
    Profile("Профіль", Icons.Rounded.Person),
}
