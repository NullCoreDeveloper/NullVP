package nullvp.presentation.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import nullvp.domain.model.CustomRoutingRule
import nullvp.i18n.AppStrings
import nullvp.presentation.components.NullVpTextField

@Composable
fun EditRuleDialog(
    rule: CustomRoutingRule?,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (CustomRoutingRule) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    var name by remember { mutableStateOf(rule?.name ?: "") }
    var outbound by remember { mutableStateOf(rule?.outbound ?: "direct") }
    var domainsText by remember { mutableStateOf(rule?.domains?.joinToString(", ") ?: "") }
    var ipsText by remember { mutableStateOf(rule?.ips?.joinToString(", ") ?: "") }
    var enabled by remember { mutableStateOf(rule?.enabled ?: true) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (rule == null) strings.addRule else strings.editRule,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(16.dp))

                // Name
                NullVpTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.ruleName) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(16.dp))

                // Outbound Action
                Text(
                    text = strings.ruleOutbound,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val actions = listOf(
                        "direct" to strings.outboundDirect,
                        "proxy" to strings.outboundProxy,
                        "block" to strings.outboundBlock
                    )
                    actions.forEach { (actionKey, label) ->
                        val selected = outbound == actionKey
                        val color = when (actionKey) {
                            "direct" -> Color(0xFF00E676)
                            "proxy" -> Color(0xFF00E5FF)
                            else -> Color(0xFFFF5252)
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { outbound = actionKey },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = color.copy(alpha = 0.25f),
                                selectedLabelColor = color
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = if (selected) color else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Domains
                NullVpTextField(
                    value = domainsText,
                    onValueChange = { domainsText = it },
                    label = { Text(strings.ruleDomains) },
                    placeholder = { Text("geosite:category-gov-ru, yandex.ru, vk.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4
                )

                Spacer(Modifier.height(12.dp))

                // IPs
                NullVpTextField(
                    value = ipsText,
                    onValueChange = { ipsText = it },
                    label = { Text(strings.ruleIps) },
                    placeholder = { Text("geoip:ru, 192.168.1.0/24") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4
                )

                Spacer(Modifier.height(12.dp))

                // Enabled Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.ruleEnabled,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rule != null && onDelete != null) {
                        TextButton(
                            onClick = {
                                onDelete(rule.id)
                                onDismiss()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(strings.delete)
                        }
                        Spacer(Modifier.weight(1f))
                    }

                    TextButton(onClick = onDismiss) {
                        Text(strings.cancel)
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val domainsList = domainsText.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                            val ipsList = ipsText.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                            val updated = CustomRoutingRule(
                                id = rule?.id ?: "rule_${System.currentTimeMillis()}",
                                name = name.ifBlank { "Rule ${domainsList.firstOrNull() ?: outbound}" },
                                outbound = outbound,
                                domains = domainsList,
                                ips = ipsList,
                                enabled = enabled
                            )
                            onSave(updated)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(strings.save)
                    }
                }
            }
        }
    }
}

