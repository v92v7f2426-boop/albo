package de.albo.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BrandBlue = Color(0xFF005BBB)
private val PageBackground = Color(0xFFEAF0F6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AlboApp(applicationContext) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlboApp(context: android.content.Context) {
    val repository = remember { AlboRepository(context) }
    var revision by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var showJobEditor by remember { mutableStateOf(false) }
    var showReportEditor by remember { mutableStateOf(false) }
    var reportJobID by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(repository.root.optJSONObject("connection")?.optString("server", "") ?: "") }
    var token by remember { mutableStateOf(repository.root.optJSONObject("connection")?.optString("token", "") ?: "") }
    var syncMessage by remember { mutableStateOf("") }
    var syncBusy by remember { mutableStateOf(false) }
    var clockTick by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { delay(1000); clockTick++ } }
    val tabs = listOf("Start", "Baustellen", "Berichte", "Zeiten", "Mehr")
    val icons = listOf(Icons.Default.Home, Icons.Default.Build, Icons.Default.Assignment, Icons.Default.PlayArrow, Icons.Default.MoreHoriz)
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = BrandBlue, secondary = BrandBlue, background = PageBackground)) {
        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            topBar = { TopAppBar(title = { Text("ALBO Connect", fontWeight = FontWeight.Bold) }) },
            bottomBar = { NavigationBar { tabs.forEachIndexed { index, title -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icons[index], contentDescription = title) }, label = { Text(title) }) } } },
            floatingActionButton = {
                when (tab) {
                    1 -> if (repository.canManage) FloatingActionButton(onClick = { showJobEditor = true }) { Text("+") }
                    2 -> FloatingActionButton(onClick = { reportJobID = repository.jobs.optJSONObject(0)?.optString("id").orEmpty(); showReportEditor = true }) { Text("+") }
                }
            }
        ) { inset ->
            Box(Modifier.fillMaxSize().background(PageBackground).padding(inset)) {
                when (tab) {
                    0 -> HomeScreen(repository, revision, clockTick) { tab = it }
                    1 -> JobsScreen(repository, revision, clockTick, onOpenReport = { reportJobID = it; showReportEditor = true }, onRefresh = { revision++ })
                    2 -> ReportsScreen(repository, revision, onNew = { reportJobID = it; showReportEditor = true })
                    3 -> TimeScreen(repository, revision, clockTick, onChanged = { revision++ })
                    else -> MoreScreen(repository, revision, server, token, syncMessage, syncBusy,
                        onServer = { server = it }, onToken = { token = it },
                        onSave = { try { repository.saveConnection(server, token); scope.launch { snack.showSnackbar("PC-Verbindung gespeichert.") } } catch (e: Exception) { scope.launch { snack.showSnackbar(e.message ?: "Verbindungsdaten ungültig.") } } },
                        onSync = { scope.launch { syncBusy = true; syncMessage = "Synchronisierung läuft …"; try { repository.saveConnection(server, token); syncMessage = repository.synchronize(); revision++ } catch (e: Exception) { syncMessage = e.message ?: "Synchronisierung fehlgeschlagen." }; syncBusy = false } })
                }
            }
        }
    }
    if (showJobEditor) JobEditorDialog(onDismiss = { showJobEditor = false }, onSave = { customer, title, address -> repository.addJob(customer, title, address); revision++; showJobEditor = false })
    if (showReportEditor) {
        val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) pendingSpokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        }
        ReportEditorDialog(repository, reportJobID, pendingSpokenText,
            onDismiss = { showReportEditor = false; pendingSpokenText = "" },
            onSpeak = { pendingSpokenText = ""; try { speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE"); putExtra(RecognizerIntent.EXTRA_PROMPT, "Bericht diktieren") }) } catch (_: Exception) { scope.launch { snack.showSnackbar("Spracherkennung ist auf diesem Gerät nicht verfügbar.") } } },
            onSave = { jobID, title, text, notes, materials, checks, customer, workerSignature, customerSignature -> repository.addReport(jobID, title, text, notes, materials, checks, customer, workerSignature, customerSignature); revision++; showReportEditor = false; pendingSpokenText = "" })
    }
}

private var pendingSpokenText by mutableStateOf("")

@Composable
private fun HomeScreen(repo: AlboRepository, revision: Int, tick: Int, navigate: (Int) -> Unit) {
    val clock = repo.database.objectValue("clocks").optJSONObject(repo.employee)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Guten Tag, ${repo.employee}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Baustellendokumentation · Zeiterfassung · Berichte")
        if (clock != null) Card(colors = CardDefaults.cardColors(containerColor = BrandBlue)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Arbeitszeit läuft", color = Color.White, fontWeight = FontWeight.Bold)
                Text(clockLabel(repo, clock, tick), color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Text(jobName(repo, clock.optString("jobID")), color = Color.White)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Baustellen", repo.jobs.length().toString(), Modifier.weight(1f))
            StatCard("Berichte", repo.reports.length().toString(), Modifier.weight(1f))
        }
        Button(onClick = { navigate(3) }, modifier = Modifier.fillMaxWidth()) { Text(if (clock == null) "Arbeitszeit starten" else "Zeiterfassung öffnen") }
        Button(onClick = { navigate(1) }, modifier = Modifier.fillMaxWidth()) { Text("Baustellen anzeigen") }
        Button(onClick = { navigate(2) }, modifier = Modifier.fillMaxWidth()) { Text("Berichte öffnen") }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp)) { Text(label); Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) } }
}

@Composable
private fun JobsScreen(repo: AlboRepository, revision: Int, tick: Int, onOpenReport: (String) -> Unit, onRefresh: () -> Unit) {
    val jobs = (0 until repo.jobs.length()).mapNotNull { repo.jobs.optJSONObject(it) }
    val clock = repo.database.objectValue("clocks").optJSONObject(repo.employee)
    LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (jobs.isEmpty()) item { Text("Noch keine Baustellen. Verbinde dich unter Mehr mit der PC-Verwaltung oder lege lokal eine Baustelle an.", Modifier.padding(16.dp)) }
        items(jobs, key = { it.optString("id") }) { job ->
            val id = job.optString("id")
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(job.optString("customer"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(job.optString("title"))
                    Text(job.optString("deliveryAddress").ifBlank { job.optString("address") }, style = MaterialTheme.typography.bodySmall)
                    Text(job.optString("status", "Geplant"), color = BrandBlue)
                    Text("Deine Projektzeit: ${formatDuration(projectSeconds(repo, id))}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val runningHere = clock != null && clock.optString("jobID") == id && clock.has("segmentStart")
                        Button(onClick = { if (clock != null && clock.optString("jobID") == id && !clock.has("segmentStart")) repo.start(id) else if (clock == null || clock.optString("jobID") != id) repo.start(id); onRefresh() }, enabled = !runningHere) { Text(if (clock?.optString("jobID") == id) "Weiterarbeiten" else "Hier starten") }
                        OutlinedButton(onClick = { onOpenReport(id) }) { Text("Bericht schreiben") }
                    }
                    if (clock != null && clock.optString("jobID") == id) Text(clockLabel(repo, clock, tick), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ReportsScreen(repo: AlboRepository, revision: Int, onNew: (String) -> Unit) {
    val reports = (0 until repo.reports.length()).mapNotNull { repo.reports.optJSONObject(it) }.sortedByDescending { it.optDouble("date") }
    LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (reports.isEmpty()) item { Text("Noch keine Berichte gespeichert.", Modifier.padding(16.dp)) }
        items(reports, key = { it.optString("id") }) { report ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(report.optString("title"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("${jobName(repo, report.optString("jobID"))} · ${formatDate(report.optDouble("date"))}", style = MaterialTheme.typography.bodySmall)
                Text(report.optString("text"), maxLines = 3)
                if (!report.optString("internalNotes").isBlank()) Text("Interne Notiz vorhanden", color = BrandBlue, style = MaterialTheme.typography.labelSmall)
            } }
        }
        item { Button(onClick = { repo.jobs.optJSONObject(0)?.optString("id")?.let(onNew) }, enabled = repo.jobs.length() > 0, modifier = Modifier.fillMaxWidth()) { Text("Bericht erstellen") } }
    }
}

@Composable
private fun TimeScreen(repo: AlboRepository, revision: Int, tick: Int, onChanged: () -> Unit) {
    val clock = repo.database.objectValue("clocks").optJSONObject(repo.employee)
    val jobs = (0 until repo.jobs.length()).mapNotNull { repo.jobs.optJSONObject(it) }
    val recent = (0 until repo.entries.length()).mapNotNull { repo.entries.optJSONObject(it) }.filter { it.optString("employee") == repo.employee }.sortedByDescending { it.optDouble("start") }.take(40)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Stempeln", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (clock != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(jobName(repo, clock.optString("jobID")), fontWeight = FontWeight.Bold)
                Text(if (clock.has("segmentStart")) "Arbeitszeit läuft" else "Pause")
                Text(clockLabel(repo, clock, tick), style = MaterialTheme.typography.headlineMedium, color = BrandBlue)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { repo.togglePause(); onChanged() }) { Icon(if (clock.has("segmentStart")) Icons.Default.Pause else Icons.Default.PlayArrow, null); Text(if (clock.has("segmentStart")) "Pause" else "Weiter") }
                    OutlinedButton(onClick = { repo.stop(); onChanged() }) { Icon(Icons.Default.Stop, null); Text("Gehen") }
                }
            } }
        } else {
            Text("Baustelle auswählen oder Werkstattzeit starten.")
            jobs.forEach { job -> Button(onClick = { repo.start(job.optString("id")); onChanged() }, modifier = Modifier.fillMaxWidth()) { Text("${job.optString("customer")} · ${job.optString("title")} starten") } }
            OutlinedButton(onClick = { repo.start(null); onChanged() }, modifier = Modifier.fillMaxWidth()) { Text("Werkstatt-Arbeitszeit starten") }
        }
        Text("Gespeicherte Zeiten", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        recent.forEach { entry -> Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(formatDate(entry.optDouble("start")), fontWeight = FontWeight.SemiBold); Text(jobName(repo, entry.optString("jobID")), style = MaterialTheme.typography.bodySmall) }
            Text(formatDuration(entry.optDouble("paidSeconds")))
        } } }
    }
}

@Composable
private fun MoreScreen(repo: AlboRepository, revision: Int, server: String, token: String, syncMessage: String, syncBusy: Boolean, onServer: (String) -> Unit, onToken: (String) -> Unit, onSave: () -> Unit, onSync: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mehr", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PC-Verbindung", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Adresse und einmaligen Mitarbeiter-Kopplungsschlüssel aus der PC-Verwaltung eintragen. Arbeitsdaten bleiben lokal verfügbar und werden bei Verbindung abgeglichen.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(server, onServer, label = { Text("PC-Adresse, z. B. https://…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(token, onToken, label = { Text("Kopplungsschlüssel") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSave) { Text("Verbindung speichern") }
                Button(onClick = onSync, enabled = !syncBusy && server.isNotBlank() && token.isNotBlank()) { Text(if (syncBusy) "Bitte warten …" else "Jetzt synchronisieren") }
            }
            if (syncMessage.isNotBlank()) Text(syncMessage, color = BrandBlue)
        } }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Kunden", fontWeight = FontWeight.Bold)
            val customers = repo.database.optJSONArray("customers") ?: JSONArray()
            if (customers.length() == 0) Text("Kunden werden mit dem PC abgeglichen.", style = MaterialTheme.typography.bodySmall)
            else (0 until customers.length()).mapNotNull { customers.optJSONObject(it) }.forEach { customer -> Text(customer.optString("companyName").ifBlank { customer.optString("name") } + " · " + customer.optString("address"), style = MaterialTheme.typography.bodySmall) }
        } }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Offline-Daten", fontWeight = FontWeight.Bold)
            Text("Die lokale Datenbank wird verschlüsselt auf diesem Gerät gespeichert. Ohne Verbindung kannst du Baustellen ansehen, Zeiten stempeln und Berichte entwerfen.", style = MaterialTheme.typography.bodySmall)
            Text("Mitarbeiter: ${repo.employee}", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable
private fun JobEditorDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var customer by remember { mutableStateOf("") }; var title by remember { mutableStateOf("") }; var address by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Baustelle anlegen") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(customer, { customer = it }, label = { Text("Kunde") }); OutlinedTextField(title, { title = it }, label = { Text("Art der Arbeit / Projekt") }); OutlinedTextField(address, { address = it }, label = { Text("Adresse") })
    } }, confirmButton = { TextButton(onClick = { onSave(customer, title, address) }, enabled = customer.isNotBlank() && title.isNotBlank() && address.isNotBlank()) { Text("Speichern") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } })
}

@Composable
private fun ReportEditorDialog(repo: AlboRepository, initialJobID: String, spoken: String, onDismiss: () -> Unit, onSpeak: () -> Unit, onSave: (String, String, String, String, String, Set<String>, String, String, String) -> Unit) {
    val jobs = (0 until repo.jobs.length()).mapNotNull { repo.jobs.optJSONObject(it) }
    val materials = repo.database.optJSONArray("materials") ?: JSONArray()
    val services = repo.database.optJSONArray("services") ?: JSONArray()
    val templates = repo.database.optJSONArray("templates") ?: JSONArray()
    val checklists = repo.database.optJSONArray("checklists") ?: JSONArray()
    var selected by remember { mutableStateOf(initialJobID.ifBlank { jobs.firstOrNull()?.optString("id").orEmpty() }) }
    var title by remember { mutableStateOf("") }; var text by remember { mutableStateOf(spoken) }; var notes by remember { mutableStateOf("") }; var materialsText by remember { mutableStateOf("") }; var customer by remember { mutableStateOf("") }; var quantity by remember { mutableStateOf("1") }; var selectedChecks by remember { mutableStateOf(setOf<String>()) }; var workerSignature by remember { mutableStateOf("") }; var customerSignature by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }
    LaunchedEffect(spoken) { if (spoken.isNotBlank()) text = if (text.isBlank()) spoken else text + "\n" + spoken }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Monteurbericht") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Baustelle auswählen", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { jobs.forEach { job -> TextButton(onClick = { selected = job.optString("id") }) { Text((if (selected == job.optString("id")) "✓ " else "") + job.optString("customer") + " · " + job.optString("title")) } } }
            if (templates.length() > 0) {
                Text("Vorlage übernehmen", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { (0 until templates.length()).mapNotNull { templates.optJSONObject(it) }.forEach { template -> TextButton(onClick = { title = template.optString("name"); text = template.optString("text") }) { Text(template.optString("name")) } } }
            }
            OutlinedTextField(title, { title = it }, label = { Text("Überschrift") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it }, label = { Text("Durchgeführte Arbeiten") }, modifier = Modifier.fillMaxWidth().height(130.dp))
            OutlinedButton(onClick = onSpeak) { Icon(Icons.Default.Mic, null); Text("Satz diktieren") }
            Text("Leistungskatalog", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { (0 until services.length()).mapNotNull { services.optJSONObject(it) }.forEach { item -> TextButton(onClick = { materialsText += (if (materialsText.isBlank()) "" else "\n") + "Leistung: " + item.optString("name") + " × " + quantity.ifBlank { "1" } + " " + item.optString("unit", "") }) { Text(item.optString("name")) } } }
            Text("Materialkatalog", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { (0 until materials.length()).mapNotNull { materials.optJSONObject(it) }.forEach { item -> TextButton(onClick = { materialsText += (if (materialsText.isBlank()) "" else "\n") + "Material: " + item.optString("name") + " × " + quantity.ifBlank { "1" } + " " + item.optString("unit", "") }) { Text(item.optString("name")) } } }
            OutlinedTextField(quantity, { quantity = it }, label = { Text("Menge für Katalogauswahl") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(materialsText, { materialsText = it }, label = { Text("Material und Leistungen · ohne Preise") }, modifier = Modifier.fillMaxWidth().height(90.dp))
            if (checklists.length() > 0) {
                Text("Checklisten", fontWeight = FontWeight.SemiBold)
                (0 until checklists.length()).mapNotNull { checklists.optJSONObject(it) }.forEach { checklist ->
                    val checklistID = checklist.optString("id")
                    TextButton(onClick = { selectedChecks = if (checklistID in selectedChecks) selectedChecks - checklistID else selectedChecks + checklistID }) { Text((if (checklistID in selectedChecks) "☑ " else "☐ ") + checklist.optString("name")) }
                }
            }
            OutlinedTextField(notes, { notes = it }, label = { Text("Interne Notizen · erscheinen nicht im Kundenbericht") }, modifier = Modifier.fillMaxWidth().height(80.dp))
            OutlinedTextField(customer, { customer = it }, label = { Text("Kundenname für Abschluss") }, modifier = Modifier.fillMaxWidth())
            SignaturePad("Unterschrift Monteur", workerSignature, { workerSignature = it })
            SignaturePad("Unterschrift Kunde", customerSignature, { customerSignature = it })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = { if (selected.isBlank()) error = "Bitte zuerst eine Baustelle synchronisieren." else if (title.isBlank() || text.isBlank()) error = "Überschrift und Arbeitsbeschreibung fehlen." else onSave(selected, title, text, notes, materialsText, selectedChecks, customer, workerSignature, customerSignature) }) { Text(if (customer.isNotBlank() && workerSignature.isNotBlank() && customerSignature.isNotBlank()) "Unterschreiben & abschließen" else "Entwurf speichern") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } })
}

@Composable
private fun SignaturePad(label: String, value: String, onSave: (String) -> Unit) {
    val paths = remember { mutableStateListOf<MutableList<Offset>>() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Canvas(Modifier.fillMaxWidth().height(110.dp).background(Color.White).onSizeChanged { size = it }.pointerInput(Unit) {
            detectDragGestures(onDragStart = { paths.add(mutableListOf(it)) }, onDrag = { change, _ ->
                change.consume()
                if (paths.isNotEmpty()) { val current = paths.last().toMutableList(); current.add(change.position); paths[paths.lastIndex] = current }
            })
        }) {
            paths.forEach { points ->
                val path = Path()
                points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
                points.drop(1).forEach { path.lineTo(it.x, it.y) }
                drawPath(path, Color.Black, style = Stroke(width = 3.dp.toPx()))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { paths.clear(); onSave("") }) { Text("Löschen") }
            Button(onClick = { val encoded = encodeSignature(paths.toList(), size.width, size.height); if (encoded.isNotBlank()) onSave(encoded) }, enabled = paths.isNotEmpty()) { Text(if (value.isBlank()) "Unterschrift übernehmen" else "Unterschrift aktualisieren") }
        }
        if (value.isNotBlank()) Text("Unterschrift gespeichert", color = BrandBlue, style = MaterialTheme.typography.labelSmall)
    }
}

private fun encodeSignature(paths: List<List<Offset>>, width: Int, height: Int): String {
    if (width <= 0 || height <= 0 || paths.isEmpty()) return ""
    val bitmap = android.graphics.Bitmap.createBitmap(width.coerceAtMost(1200), height.coerceAtMost(500), android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    paths.forEach { points ->
        val path = android.graphics.Path()
        points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
        points.drop(1).forEach { path.lineTo(it.x, it.y) }
        canvas.drawPath(path, paint)
    }
    val bytes = java.io.ByteArrayOutputStream().use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
    bitmap.recycle()
    return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

private fun jobName(repo: AlboRepository, jobID: String): String {
    if (jobID.isBlank() || jobID == "null") return "Werkstatt"
    return (0 until repo.jobs.length()).mapNotNull { repo.jobs.optJSONObject(it) }.firstOrNull { it.optString("id").equals(jobID, true) }?.let { "${it.optString("customer")} · ${it.optString("title")}" } ?: "Baustelle"
}
private fun projectSeconds(repo: AlboRepository, jobID: String): Double {
    val saved = (0 until repo.entries.length()).mapNotNull { repo.entries.optJSONObject(it) }.filter { it.optString("jobID").equals(jobID, true) }.sumOf { it.optDouble("paidSeconds", it.optDouble("end") - it.optDouble("start")) }
    val current = repo.database.objectValue("clocks").optJSONObject(repo.employee)
    val running = if (current != null && current.optString("jobID").equals(jobID, true)) clockSeconds(repo, current) else 0.0
    return saved + running
}
private fun clockSeconds(repo: AlboRepository, clock: JSONObject): Double = clock.optDouble("accumulated", 0.0) + if (clock.has("segmentStart")) (AlboRepository.nowSeconds() - clock.optDouble("segmentStart")).coerceAtLeast(0.0) else 0.0
private fun clockLabel(repo: AlboRepository, clock: JSONObject, tick: Int): String { @Suppress("UNUSED_VARIABLE") val redraw = tick; return formatDuration(clockSeconds(repo, clock)) }
private fun formatDuration(seconds: Double): String { val value = seconds.toLong().coerceAtLeast(0); return String.format(Locale.GERMANY, "%02d:%02d:%02d", value / 3600, (value / 60) % 60, value % 60) }
private fun formatDate(seconds: Double): String { val millis = ((seconds + 978307200.0) * 1000).toLong(); return SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale.GERMANY).format(Date(millis)) }
