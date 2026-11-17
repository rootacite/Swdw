package com.acite.swdw

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.acite.swdw.client.SwdClientEngine
import com.acite.swdw.theme.HanaTheme
import com.acite.swdw.ui.SwdMainScreen

@Composable
fun App(engine: SwdClientEngine = remember { SwdClientEngine() }) {
    HanaTheme {
        SwdMainScreen(engine = engine)
    }
}
