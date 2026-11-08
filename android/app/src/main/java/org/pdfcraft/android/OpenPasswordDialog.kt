package org.pdfcraft.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun OpenPasswordDialog(reader: ReaderViewModel) {
    val uri = reader.passwordRequest ?: return
    // Kept in memory only, never in saved instance state or preferences.
    var password by remember(uri) { mutableStateOf("") }
    AlertDialog(onDismissRequest = reader::cancelPassword,
        title = { Text("Пароль для открытия PDF") },
        text = { Column {
            OutlinedTextField(password, onValueChange = { password = it }, label = { Text("Пароль") },
                enabled = !reader.busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            reader.passwordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (reader.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !reader.busy, onClick = { reader.open(uri, password) }) { Text("Открыть") } },
        dismissButton = { TextButton(enabled = !reader.busy, onClick = reader::cancelPassword) { Text("Отмена") } })
}
