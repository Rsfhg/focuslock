package com.local.focuslock.ui

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.local.focuslock.appContainer
import com.local.focuslock.data.AppEntry
import com.local.focuslock.domain.*
import com.local.focuslock.permissions.ProtectionHealth
import com.local.focuslock.enforcement.GuardDiagnostics
import com.local.focuslock.enforcement.FocusAccessibilityService
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF246553), secondary = Color(0xFF52665E), background = Color(0xFFF5F8F5))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppScreen() }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        FocusAccessibilityService.mainUiOpened()
        appContainer.scope.launch { try { appContainer.repository.reconcile(); appContainer.scheduler.reschedule(appContainer.repository.allSessions(), appContainer.clock.sample()) } catch (_: Exception) { } }
    }
    override fun onPause() { FocusAccessibilityService.mainUiClosed(); super.onPause() }
    private fun openSettings(action: String, data: Uri? = null) {
        try { startActivity(Intent(action, data)) } catch (_: Exception) { Toast.makeText(this, "请在系统设置中搜索对应权限。", Toast.LENGTH_LONG).show() }
    }
    @Composable private fun AppScreen() {
        val c = appContainer
        val scope = rememberCoroutineScope()
        val rules by c.repository.rules.collectAsState(initial = emptyList())
        val sessions by c.repository.sessions.collectAsState(initial = emptyList())
        var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
        var route by remember { mutableStateOf("home") }
        var selected by remember { mutableStateOf<String?>(null) }
        var tick by remember { mutableLongStateOf(0) }
        LaunchedEffect(Unit) { launch { apps = withContext(Dispatchers.IO) { c.catalog.load() } }; while (true) { tick++; delay(1000) } }
        val now = remember(tick) { c.clock.sample() }
        val health = remember(tick) { ProtectionHealth.inspect(this) }
        fun label(pkg: String) = apps.find { it.packageName == pkg }?.label ?: c.catalog.label(pkg)
        fun message(result: MutationResult) = Toast.makeText(this, when (result) {
            MutationResult.APPLIED -> "已保存"
            MutationResult.ACTIVE_SESSION -> "使用或冷却期间不能修改，请结束后再试。"
            MutationResult.INVALID_INPUT -> "请输入 1–1440 的整数分钟，系统必要应用不能被限制。"
            MutationResult.NOT_FOUND -> "此应用尚未配置限制。"
            MutationResult.STORAGE_ERROR -> "保存失败，原规则和限制仍然保留，请重试。"
        }, Toast.LENGTH_LONG).show()
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (route == "home") "限时用" else if (route == "setup") "开启保护" else if (selected != null) "应用规则" else "添加应用", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (route != "home") TextButton(onClick = { route = "home"; selected = null }) { Text("返回") }
            }
            when {
                route == "setup" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("只获取计时和拦截所需权限。你可以随时从系统设置关闭。") }
                    item { Button(onClick = { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, Modifier.fillMaxWidth()) { Text(if (health.accessibility) "无障碍已开启 · 查看" else "开启无障碍服务") } }
                    item { OutlinedButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !health.notifications) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    }, Modifier.fillMaxWidth()) { Text(if (health.notifications) "通知已开启 · 查看" else "允许到期提醒通知") } }
                    item { if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")) }, Modifier.fillMaxWidth()) { Text(if (health.exactAlarms) "精确提醒已允许 · 查看" else "允许闹钟和提醒") } }
                    item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("小米 HyperOS 配置", fontWeight = FontWeight.Bold)
                        Text("在设置中搜索“自启动”，允许限时用自启动；在应用耗电或电池设置中允许后台运行。若无障碍显示受限，请先到应用信息的菜单允许受限设置。")
                        Text("系统撤销权限、强行停止、清除数据或卸载可解除限制。小窗、分屏、分身和第二空间需单独验证。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")) }) { Text("打开应用信息") }
                    } } }
                    item { Text("缺少无障碍时不能拦截；通知关闭时不会弹出系统提醒。重启后首次解锁恢复原截止时间。", style = MaterialTheme.typography.bodySmall) }
                }
                selected != null -> {
                    val pkg = selected!!
                    val existing = rules.find { it.packageName == pkg }
                    val status = SessionPolicy.status(sessions.find { it.packageName == pkg }, now)
                    RuleEditor(pkg, label(pkg), existing, status,
                        onSave = { r -> scope.launch { val result = c.repository.saveRule(r); message(result); if (result == MutationResult.APPLIED) { route = "home"; selected = null } } },
                        onRemove = { scope.launch { val result = c.repository.removeRule(pkg); message(result); if (result == MutationResult.APPLIED) { route = "home"; selected = null } } })
                }
                route == "picker" -> {
                    var search by remember { mutableStateOf("") }
                    OutlinedTextField(search, { search = it }, label = { Text("搜索应用") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    LazyColumn(Modifier.weight(1f)) {
                        items(apps.filter { a -> rules.none { it.packageName == a.packageName } && (a.label.contains(search, true) || a.packageName.contains(search, true)) }, key = { it.packageName }) { app ->
                            ListItem(headlineContent = { Text(app.label) }, supportingContent = { Text(app.packageName) }, modifier = Modifier.clickable { selected = app.packageName })
                        }
                        if (apps.isEmpty()) item { Text("正在读取可管理应用…", Modifier.padding(16.dp)) }
                    }
                }
                else -> {
                    Card(Modifier.fillMaxWidth().clickable { route = "setup" }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (health.accessibility && !FocusAccessibilityService.ready) "正在加载保护规则…" else if (!health.accessibility) "保护未开启" else if (!health.notifications || !health.exactAlarms) "保护已开启 · 提醒能力受限" else "保护已开启", fontWeight = FontWeight.Bold)
                            Text("${if (health.accessibility) "无障碍 ✓" else "无障碍未开"}  ·  ${if (health.notifications) "通知 ✓" else "通知未开"}  ·  ${if (health.exactAlarms) "精确提醒 ✓" else "精确提醒未开"}", style = MaterialTheme.typography.bodySmall)
                            Text("点此检查权限与小米后台设置", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("先决定用多久，再开始", Modifier.padding(vertical = 18.dp), style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { route = "picker" }, Modifier.fillMaxWidth()) { Text("＋ 添加受限应用") }
                    TextButton(onClick = {
                        val report = GuardDiagnostics.export() + "\nprotection=$health"
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("限时用诊断", report))
                        Toast.makeText(this@MainActivity, "诊断已复制，包含应用包名和拦截状态，不含页面文字或输入内容。", Toast.LENGTH_LONG).show()
                    }, Modifier.fillMaxWidth()) { Text("复制拦截诊断") }
                    LazyColumn(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (rules.isEmpty()) item { Text("还没有添加应用。每个应用可以独立设置使用上限和冷却规则。", Modifier.padding(vertical = 20.dp)) }
                        items(rules, key = { it.packageName }) { r ->
                            val s = SessionPolicy.status(sessions.find { it.packageName == r.packageName }, now)
                            Card(Modifier.fillMaxWidth().clickable { selected = r.packageName; route = "edit" }) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(label(r.packageName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("${if (r.mode == Mode.FREE) "自由模式" else "冷却 ${r.cooldownMinutes} 分钟"} · 单次最多 ${r.maxMinutes} 分钟")
                                    Text(when (s.phase) { Phase.READY -> "可申请使用"; Phase.USING -> "使用中 · 剩余 ${formatTime(s.remainingMs)}"; Phase.COOLING -> "冷却中 · 剩余 ${formatTime(s.remainingMs)}" }, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
fun formatTime(ms: Long): String { val sec = (ms.coerceAtLeast(0) + 999) / 1000; return "%02d:%02d".format(sec / 60, sec % 60) }

@Composable private fun RuleEditor(pkg: String, label: String, existing: Rule?, status: Status, onSave: (Rule) -> Unit, onRemove: () -> Unit) {
    var mode by remember(pkg, existing) { mutableStateOf(existing?.mode) }
    var maximum by remember(pkg, existing) { mutableStateOf((existing?.maxMinutes ?: 15).toString()) }
    var cooling by remember(pkg, existing) { mutableStateOf((existing?.cooldownMinutes ?: 15).toString()) }
    val locked = status.phase != Phase.READY
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(label, style = MaterialTheme.typography.titleLarge) }
        if (locked) item { Text("${if (status.phase == Phase.USING) "本次正在使用" else "正在冷却"}，剩余 ${formatTime(status.remainingMs)}。本轮结束后才能修改或移除。", color = MaterialTheme.colorScheme.error) }
        item { Text("到期后的规则") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == Mode.FREE, enabled = !locked, onClick = { mode = Mode.FREE }, label = { Text("自由模式") })
            FilterChip(selected = mode == Mode.COOLDOWN, enabled = !locked, onClick = { mode = Mode.COOLDOWN }, label = { Text("冷却模式") })
        } }
        item { Text(if (mode == Mode.COOLDOWN) "用完后必须等冷却结束，才能再次申请。" else "自由模式到期后可立即重新申请。", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(maximum, { maximum = it }, enabled = !locked, label = { Text("单次上限（1–1440 分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (mode == Mode.COOLDOWN) item { OutlinedTextField(cooling, { cooling = it }, enabled = !locked, label = { Text("冷却时长（1–1440 分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Text("持续计时，切换或锁屏不暂停。每次必须输入时长，开始后不能加时。", style = MaterialTheme.typography.bodySmall) }
        item { Button(enabled = !locked && mode != null && DurationInput.parse(maximum, 1440) != null && DurationInput.parse(cooling, 1440) != null, onClick = { onSave(Rule(pkg, mode!!, DurationInput.parse(maximum, 1440)!!, DurationInput.parse(cooling, 1440)!!)) }, modifier = Modifier.fillMaxWidth()) { Text("保存规则") } }
        if (existing != null) item { OutlinedButton(onClick = onRemove, enabled = !locked, modifier = Modifier.fillMaxWidth()) { Text("移出受限列表") } }
    }
}
