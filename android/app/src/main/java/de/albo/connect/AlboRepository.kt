package de.albo.connect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val SWIFT_EPOCH = 978307200L
private fun id() = UUID.randomUUID().toString().uppercase()
private fun JSONObject.copyObject() = JSONObject(toString())
private fun JSONObject.array(name: String) = optJSONArray(name) ?: JSONArray().also { put(name, it) }
private fun JSONObject.objectValue(name: String) = optJSONObject(name) ?: JSONObject().also { put(name, it) }

class LocalVault(context: Context) {
    private val file = File(context.filesDir, "albo-local.enc")
    private val atomicFile = AtomicFile(file)
    private val alias = "de.albo.connect.localdata"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    fun read(): JSONObject {
        if (!atomicFile.baseFile.exists()) return freshRoot()
        val bytes = atomicFile.openRead().use { it.readBytes() }
        require(bytes.size > 28) { "Die verschlüsselte lokale Datenbank ist beschädigt." }
        val iv = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    fun write(root: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        val output = cipher.iv + encrypted
        val stream = atomicFile.startWrite()
        try { stream.write(output); atomicFile.finishWrite(stream) }
        catch (error: Exception) { atomicFile.failWrite(stream); throw error }
    }
    private fun freshRoot(): JSONObject {
        val db = JSONObject()
        listOf("jobs", "reports", "entries", "employees", "customers", "appointments", "materials", "services", "checklists", "absences", "overtimeUses", "templates").forEach { db.put(it, JSONArray()) }
        db.put("clocks", JSONObject()); db.put("employee", "Salvatore Albo")
        val templates = db.getJSONArray("templates")
        templates.put(JSONObject().put("id", "A1B00000-0000-4000-8000-000000001001").put("name", "Wartung").put("text", "Anlage / Gerät:\nDurchgeführte Wartung:\nMesswerte:\nFestgestellte Mängel:\nEmpfohlene Maßnahmen:"))
        templates.put(JSONObject().put("id", "A1B00000-0000-4000-8000-000000001002").put("name", "Reparatur").put("text", "Gemeldete Störung:\nUrsache:\nDurchgeführte Arbeiten:\nFunktionsprüfung:\nWeitere Maßnahmen:"))
        templates.put(JSONObject().put("id", "A1B00000-0000-4000-8000-000000001003").put("name", "Kundendienst").put("text", "Kundenauftrag:\nDurchgeführte Arbeiten:\nErgebnis / Übergabe:\nOffene Punkte:"))
        return JSONObject().put("database", db).put("sync", JSONObject())
    }
}

data class SyncIdentity(val role: String, val employeeID: String?)

class AlboRepository(context: Context) {
    private val vault = LocalVault(context)
    var root: JSONObject = vault.read()
        private set
    val database: JSONObject get() = root.getJSONObject("database")
    val employee: String get() = database.optString("employee", "Salvatore Albo")
    val canManage: Boolean get() = root.optJSONObject("identity")?.optString("role") != "worker"
    val jobs: JSONArray get() = database.array("jobs")
    val entries: JSONArray get() = database.array("entries")
    val reports: JSONArray get() = database.array("reports")
    private fun commit() { vault.write(root) }
    fun saveConnection(server: String, token: String) {
        validateServer(server)
        require(token.length == 64 && token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Der Kopplungsschlüssel muss 64 Hex-Zeichen enthalten." }
        root.put("connection", JSONObject().put("server", server.trimEnd('/')).put("token", token.trim()))
        commit()
    }
    private fun validateServer(raw: String): URL {
        val url = URL(raw.trim().trimEnd('/'))
        val host = url.host.lowercase()
        require(url.userInfo == null && url.query == null && url.ref == null) { "Bitte nur eine Serveradresse ohne Zugangsdaten oder Zusatzparameter eingeben." }
        if (url.protocol == "https") return url
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        val privateAddress = parts.size == 4 && parts.all { it in 0..255 } && (parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31))
        require(url.protocol == "http" && (host == "localhost" || host.endsWith(".local") || privateAddress)) { "Unverschlüsseltes HTTP ist nur für lokale Testadressen erlaubt. Im regulären Netzwerkbetrieb HTTPS verwenden." }
        return url
    }
    suspend fun synchronize(): String = withContext(Dispatchers.IO) {
        val connection = root.optJSONObject("connection") ?: error("Unter Mehr → PC-Verbindung zuerst Serveradresse und Kopplungsschlüssel eintragen.")
        val server = validateServer(connection.getString("server"))
        val token = connection.getString("token")
        fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
            val url = URL(server.toString().trimEnd('/') + "/api/" + path)
            val http = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                instanceFollowRedirects = false
                if (body != null) { doOutput = true; outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            }
            val code = http.responseCode
            val stream = if (code in 200..299) http.inputStream else http.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            http.disconnect()
            val result = JSONObject(text)
            if (code !in 200..299) error(result.optString("error", "Serverfehler $code. Lokale Daten bleiben erhalten."))
            return result
        }
        val remote = request("workspace")
        val remoteDb = remote.getJSONObject("backup").getJSONObject("database")
        val sync = root.optJSONObject("sync") ?: JSONObject()
        val same = sync.optString("workspaceID").equals(remote.optString("workspaceID"), true)
        val baseRevision = if (same) sync.optInt("revision", -1) else -1
        val payload = JSONObject().put("workspaceID", remote.getString("workspaceID"))
            .put("baseRevision", if (same && baseRevision >= 0) baseRevision else JSONObject.NULL)
            .put("backup", JSONObject().put("version", 1).put("database", database.copyObject()).put("photos", JSONObject()))
        if (same && sync.has("baseline") && sync.has("proof")) {
            payload.put("baseSnapshot", JSONObject().put("revision", baseRevision).put("proof", sync.getString("proof")).put("database", JSONObject(sync.getString("baseline"))))
        } else {
            val hasLocalWork = listOf("jobs", "reports", "entries", "customers", "absences", "overtimeUses").any { database.array(it).length() > 0 } || database.objectValue("clocks").length() > 0
            if (hasLocalWork) error("Auf diesem Gerät gibt es noch keinen PC-Ausgangsstand. Sichere die lokalen Daten und führe den ersten Abgleich mit einem leeren Mitarbeitergerät durch.")
            root.put("database", remoteDb)
            root.put("sync", JSONObject().put("workspaceID", remote.getString("workspaceID")).put("revision", remote.getInt("revision")).put("proof", remote.optString("proof")).put("baseline", remoteDb.toString()))
            remote.optJSONObject("identity")?.let { root.put("identity", it) }
            remoteDb.optJSONArray("employees")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }?.let { root.getJSONObject("database").put("employee", it) }
            vault.write(root)
            return@withContext "Erster Abgleich abgeschlossen. Die PC-Daten wurden geladen."
        }
        val updated = request("sync", "POST", payload)
        val updatedDb = updated.getJSONObject("backup").getJSONObject("database")
        root.put("database", updatedDb)
        root.put("sync", JSONObject().put("workspaceID", updated.getString("workspaceID")).put("revision", updated.getInt("revision")).put("proof", updated.optString("proof")).put("baseline", updatedDb.toString()))
        updated.optJSONObject("identity")?.let { identity ->
            root.put("identity", identity)
            val employees = updatedDb.optJSONArray("employees")
            employees?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }?.let { updatedDb.put("employee", it) }
        }
        vault.write(root)
        "Synchronisiert · Stand ${updated.optInt("revision")}"
    }
    fun addJob(customer: String, title: String, address: String) {
        check(canManage) { "Mitarbeiterkonten dürfen keine Baustellen anlegen." }
        require(customer.isNotBlank() && title.isNotBlank() && address.isNotBlank())
        jobs.put(JSONObject().put("id", id()).put("customer", customer.trim()).put("title", title.trim()).put("address", address.trim()).put("deliveryAddress", "").put("deliveryAddressDifferent", false).put("timeBillingMode", "effort").put("notes", "").put("status", "Geplant"))
        commit()
    }
    fun start(jobID: String?) {
        val clocks = database.objectValue("clocks")
        val previous = clocks.optJSONObject(employee)
        val now = nowSeconds()
        if (previous != null) {
            val oldJob = previous.optString("jobID").takeUnless { it == "null" || it.isBlank() }
            if (oldJob == jobID) {
                if (previous.opt("segmentStart") == JSONObject.NULL || !previous.has("segmentStart")) {
                    val pause = previous.optDouble("pauseStarted", Double.NaN)
                    if (pause.isFinite()) {
                        previous.array("breaks").put(JSONObject().put("start", pause).put("end", now))
                        previous.remove("pauseStarted")
                    }
                    previous.put("segmentStart", now); commit(); return
                }
                error("Die Arbeitszeit läuft bereits.")
            }
            stop()
        }
        val clock = JSONObject().put("jobID", jobID ?: JSONObject.NULL).put("category", if (jobID == null) "Werkstatt" else "Projekt")
            .put("started", now).put("segmentStart", now).put("accumulated", 0).put("breaks", JSONArray())
        database.objectValue("clocks").put(employee, clock); commit()
    }
    fun togglePause() {
        val clock = database.objectValue("clocks").optJSONObject(employee) ?: error("Es läuft keine Arbeitszeit.")
        val now = nowSeconds()
        if (clock.has("segmentStart") && clock.opt("segmentStart") != JSONObject.NULL) {
            val start = clock.optDouble("segmentStart", now)
            clock.put("accumulated", clock.optDouble("accumulated", 0.0) + (now - start).coerceAtLeast(0.0))
            clock.remove("segmentStart"); clock.put("pauseStarted", now)
        } else {
            val pause = clock.optDouble("pauseStarted", Double.NaN)
            if (pause.isFinite()) clock.array("breaks").put(JSONObject().put("start", pause).put("end", now))
            clock.remove("pauseStarted"); clock.put("segmentStart", now)
        }
        commit()
    }
    fun stop() {
        val clocks = database.objectValue("clocks")
        val clock = clocks.optJSONObject(employee) ?: return
        val now = nowSeconds()
        val elapsed = clock.optDouble("accumulated", 0.0) + if (clock.has("segmentStart") && clock.opt("segmentStart") != JSONObject.NULL) (now - clock.optDouble("segmentStart", now)).coerceAtLeast(0.0) else 0.0
        if (clock.has("pauseStarted")) clock.array("breaks").put(JSONObject().put("start", clock.optDouble("pauseStarted")).put("end", now))
        val entry = JSONObject().put("id", id()).put("jobID", clock.opt("jobID") ?: JSONObject.NULL).put("category", clock.optString("category"))
            .put("start", clock.optDouble("started")).put("end", now).put("paidSeconds", elapsed).put("breaks", clock.array("breaks")).put("employee", employee)
        entries.put(entry); clocks.remove(employee); commit()
    }
    fun addReport(jobID: String, title: String, text: String, internalNotes: String, materials: String, checklistIDs: Set<String>, customerName: String, workerSignature: String, customerSignature: String) {
        require(title.isNotBlank() && text.isNotBlank()) { "Bitte Überschrift und Arbeitsbeschreibung eintragen." }
        reports.put(JSONObject().put("id", id()).put("jobID", jobID).put("date", nowSeconds()).put("author", employee).put("title", title.trim()).put("text", text.trim())
            .put("internalNotes", internalNotes.trim()).put("checklistIDs", JSONArray(checklistIDs.toList())).put("materials", materials.trim()).put("photos", JSONArray()).put("measurements", JSONArray()).put("positions", JSONArray())
            .put("customerName", customerName.trim()).put("completed", customerName.isNotBlank() && workerSignature.isNotBlank() && customerSignature.isNotBlank()).apply { if (workerSignature.isNotBlank()) put("workerSignature", workerSignature); if (customerSignature.isNotBlank()) put("customerSignature", customerSignature) })
        commit()
    }
    fun updateFrom(transform: (JSONObject) -> Unit) { transform(database); commit() }
    companion object { fun nowSeconds() = System.currentTimeMillis() / 1000.0 - SWIFT_EPOCH }
}
