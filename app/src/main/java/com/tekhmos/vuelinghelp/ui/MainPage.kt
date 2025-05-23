package com.tekhmos.vuelinghelp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.tekhmos.vuelinghelp.R

@Composable
fun VisualUI1() {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        val maxWidth = maxWidth

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Logo",
                modifier = Modifier
                    .size((maxWidth * 0.5f).coerceAtLeast(150.dp))
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Iniciando...",
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = ((maxWidth * 0.05f).coerceAtLeast(16.dp).value).sp
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}