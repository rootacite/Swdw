package com.acite.swdw

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.compose.resources.painterResource
import swdw.shared.generated.resources.Res
import swdw.shared.generated.resources.ic_swdw_logo

fun main() = application {
    val windowState = rememberWindowState(width = 1080.dp, height = 820.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "SWD Audio Client",
        icon = painterResource(Res.drawable.ic_swdw_logo),
        state = windowState
    ) {
        App()
    }
}

