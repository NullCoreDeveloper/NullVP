package nullvp.presentation.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nullvp.domain.model.CustomRoutingRule
import nullvp.i18n.AppStrings
import nullvp.presentation.components.NullVpTextField
import nullvp.theme.*

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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(18.dp)),
            color = DarkSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (rule == null) strings.addRule else strings.editRule,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnDarkSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (enabled) "Вкл" else "Выкл",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enabled) NeonCyan else OnDarkSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Rule Name
                    NullVpTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(strings.ruleName) },
                        placeholder = { Text("Например: Discord или Telegram") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        showPasteButton = true
                    )

                    // Outbound Action
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = strings.ruleOutbound,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = OnDarkSurface
                        )

                        val actions = listOf(
                            listOf("direct", "Прямо", "Direct", Color(0xFF00E676)),
                            listOf("proxy", "Прокси", "Proxy", Color(0xFF00E5FF)),
                            listOf("block", "Блок", "Block", Color(0xFFFF5252))
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            actions.forEach { item ->
                                val actionKey = item[0] as String
                                val labelRu = item[1] as String
                                val labelEn = item[2] as String
                                val color = item[3] as Color
                                val selected = outbound == actionKey
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) color.copy(alpha = 0.2f) else DarkSurfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) color else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { outbound = actionKey }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = labelRu,
                                            fontSize = 13.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (selected) color else OnDarkSurface,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = labelEn,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = if (selected) color.copy(alpha = 0.8f) else OnDarkSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Domains
                    NullVpTextField(
                        value = domainsText,
                        onValueChange = { domainsText = it },
                        label = { Text(strings.ruleDomains) },
                        placeholder = { Text("geosite:category-gov-ru, yandex.ru, vk.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 4,
                        showPasteButton = true
                    )

                    // IPs
                    NullVpTextField(
                        value = ipsText,
                        onValueChange = { ipsText = it },
                        label = { Text(strings.ruleIps) },
                        placeholder = { Text("geoip:ru, 192.168.1.0/24") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 4,
                        showPasteButton = true
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Bottom Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rule != null && onDelete != null) {
                        TextButton(
                            onClick = {
                                onDelete(rule.id)
                                onDismiss()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = CyberRed)
                        ) {
                            Text(strings.delete)
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text(strings.cancel, color = OnDarkSurfaceVariant)
                        }

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
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                        ) {
                            Text(strings.save, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
