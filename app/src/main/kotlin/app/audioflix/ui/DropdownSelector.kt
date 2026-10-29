package app.audioflix.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * A single labelled dropdown for picking one of [options]. Used instead of tab rows and chip groups
 * so a screen reader meets one control rather than one stop per option. Every other option is also
 * a custom accessibility action, so TalkBack users can switch without opening the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownSelector(
  label: String,
  options: List<T>,
  selected: T,
  optionLabel: (T) -> String,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
    OutlinedTextField(
      value = optionLabel(selected),
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text(label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier
        .fillMaxWidth()
        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        .semantics {
          customActions = options.filter { it != selected }.map { option ->
            CustomAccessibilityAction("$label: ${optionLabel(option)}") {
              onSelect(option)
              true
            }
          }
        },
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { option ->
        val isSelected = option == selected
        DropdownMenuItem(
          text = { Text(optionLabel(option)) },
          leadingIcon = if (isSelected) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
          onClick = {
            onSelect(option)
            expanded = false
          },
          modifier = if (isSelected) Modifier.semantics { stateDescription = "Current" } else Modifier,
          contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
        )
      }
    }
  }
}
