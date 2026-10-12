package pe.chambamatch

import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Archivo privado escrito de forma atómica; no envía el perfil ni las valoraciones a las fuentes. */
class LocalJobsRepository(file: File, private val sources: JobSources = JobSources()) {
    private val storage = AtomicFile(file)
    private val mutex = Mutex()
    private var cachedData: JSONObject? = null
    private var cachedStamp = -1L
    private val cpu = Dispatchers.Default.limitedParallelism(2)
    private fun empty() = JSONObject().put("perfil", JSONObject()).put("ofertas", JSONArray()).put("feedback", JSONObject()).put("busquedas", JSONObject())
    private fun read(): JSONObject {
        val stamp = storage.baseFile.lastModified()
        cachedData?.takeIf { stamp == cachedStamp }?.let { return it }
        val data = if (!storage.baseFile.exists()) empty() else storage.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        // Duplicate ids from an imported/old copy must never reach LazyColumn keys.
        data.put("ofertas", JSONArray(data.rows("ofertas").distinctBy { it.text("id") }))
        cachedData = data; cachedStamp = stamp
        return data
    }
    private fun write(data: JSONObject) {
        storage.baseFile.parentFile?.mkdirs()
        val stream = storage.startWrite()
        try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); storage.finishWrite(stream); cachedData = data; cachedStamp = storage.baseFile.lastModified() }
        catch (e: Exception) { storage.failWrite(stream); throw e }
    }
    private fun result(data: JSONObject, filters: JSONObject): JSONObject {
        val saved = data.strings("favoritos").toSet()
        val result = RecommendationEngine.results(data.rows("ofertas").map { it.copyJson().put("guardada", it.text("id") in saved).put("postulacion", data.optJSONObject("seguimiento")?.optJSONObject(it.text("id"))?.text("estado").orEmpty()).put("preparados", data.optJSONObject("seguimiento")?.optJSONObject(it.text("id"))?.optJSONArray("preparados") ?: JSONArray()) }, data.optJSONObject("perfil") ?: JSONObject(), filters, data.optJSONObject("feedback") ?: JSONObject())
        // Descriptions remain on disk. Avoid retaining another full copy in every UI snapshot.
        (result.rows("ofertas") + result.rows("alternativas") + result.rows("seguimiento") + result.rows("recomendaciones")).forEach { it.remove("descripcion") }
        return result.put("perfil", data.optJSONObject("perfil") ?: JSONObject()).put("busquedas", data.optJSONObject("busquedas") ?: JSONObject())
    }
    suspend fun description(id: String): String = withContext(Dispatchers.IO) { mutex.withLock { read().rows("ofertas").firstOrNull { it.text("id") == id }?.text("descripcion").orEmpty() } }
    suspend fun load(): JSONObject = withContext(Dispatchers.IO) { mutex.withLock {
        val data = read(); withContext(cpu) { result(data, data.optJSONObject("perfil")?.optJSONObject("preferencias") ?: defaultFilters()) }
    } }
    suspend fun filter(filters: JSONObject): JSONObject = withContext(Dispatchers.IO) { mutex.withLock { val data = read(); withContext(cpu) { result(data, filters) } } }
    private suspend fun update(filters: JSONObject, mutation: (JSONObject) -> Unit): JSONObject = withContext(Dispatchers.IO) { mutex.withLock {
        val data = read().copyJson(); mutation(data); write(data); withContext(cpu) { result(data, filters) }
    } }
    suspend fun saveProfile(profile: JSONObject, filters: JSONObject): JSONObject {
        for (field in listOf("meses_experiencia", "ciclo")) if (profile.text(field).isNotBlank()) {
            val value = profile.number(field)
            require(value != null && value >= 0 && value <= if (field == "ciclo") 20 else 1200) { "Revisa ${if (field == "ciclo") "el ciclo (0–20)" else "los meses de experiencia (0–1200)"}." }
        }
        return update(filters) { it.put("perfil", profile.copyJson().put("preferencias", filters.copyJson())) }
    }
    suspend fun saveJob(id: String, filters: JSONObject): JSONObject = update(filters) { data ->
        require(data.rows("ofertas").any { it.text("id") == id }) { "La oferta ya no está guardada." }
        val saved = data.strings("favoritos").toMutableSet()
        if (!saved.add(id)) saved.remove(id)
        data.put("favoritos", JSONArray(saved.toList()))
    }
    suspend fun track(id: String, state: String?, requirement: String?, filters: JSONObject): JSONObject = update(filters) { data ->
        require(data.rows("ofertas").any { it.text("id") == id }) { "La oferta ya no existe." }
        val all = data.optJSONObject("seguimiento") ?: JSONObject().also { data.put("seguimiento", it) }
        val entry = all.optJSONObject(id) ?: JSONObject().put("estado", "pendiente").also { all.put(id, it) }
        if (state != null) { require(state in listOf("pendiente", "preparando", "postulada", "descartada")); entry.put("estado", state) }
        if (requirement != null) { val ready = entry.strings("preparados").toMutableSet(); if (!ready.add(requirement)) ready.remove(requirement); entry.put("preparados", JSONArray(ready.toList())) }
        entry.put("actualizado", Instant.now().toString())
    }
    suspend fun vote(id: String, value: String, filters: JSONObject): JSONObject {
        require(value in listOf("", "interesa", "no_interesa"))
        return update(filters) { data ->
            require(data.rows("ofertas").any { it.text("id") == id }) { "La oferta ya no está guardada." }
            val feedback = data.optJSONObject("feedback") ?: JSONObject().also { data.put("feedback", it) }
            if (value.isEmpty()) feedback.remove(id) else feedback.put(id, value)
        }
    }
    suspend fun importJob(raw: JSONObject, filters: JSONObject): JSONObject {
        require(raw.text("titulo").isNotBlank() && raw.text("descripcion").isNotBlank()) { "Completa el título y la descripción." }
        require(raw.text("descripcion").length <= 20_000) { "La descripción admite hasta 20 000 caracteres." }
        for (key in listOf("publicado", "cierre")) require(raw.text(key).isBlank() || RecommendationEngine.date(raw.text(key)) != null) { "Revisa la fecha de ${if (key == "publicado") "publicación" else "cierre"}: usa AAAA-MM-DD." }
        val link = raw.text("url")
        require(link.isBlank() || runCatching { val uri = java.net.URI(link); uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() }.getOrDefault(false)) { "El enlace debe comenzar con https:// o http://." }
        val canonicalLinkedIn = linkedInUrl(link)
        val source = if (canonicalLinkedIn != null) "linkedin" else "manual"
        val now = Instant.now().toString()
        val job = RecommendationEngine.normalize(raw.copyJson().put("id", if (link.isBlank()) UUID.randomUUID().toString() else JobParser.id(canonicalLinkedIn ?: link.substringBefore('?')))
            .put("url", canonicalLinkedIn ?: link).put("fuente", source).put("detectado", now).put("revisado", now).put("salario", JSONObject.NULL).put("moneda", "").put("periodo", ""))
        return update(filters) { data ->
            val jobs = data.rows("ofertas").associateBy { it.text("id") }.toMutableMap()
            jobs[job.text("id")] = job; data.put("ofertas", JSONArray(jobs.values.toList()))
        }
    }
    suspend fun search(
        filters: JSONObject,
        refresh: Boolean = false,
        persistPreferences: Boolean = true,
        onSource: suspend (JSONObject) -> Unit = {},
        progress: (String) -> Unit
    ): JSONObject {
        require(filters.text("ambito") != "pais" || filters.text("pais").isNotBlank()) { "Escribe el país donde quieres buscar." }
        val country = searchCountry(filters)
        val peru = RecommendationEngine.canon(country) in listOf("peru", "perú")
        val selected = filters.strings("fuentes").filter { it in listOf("practicas", "convocatorias", "linkedin") }.filter { it == "linkedin" || peru }.distinct()
        require(selected.isNotEmpty()) { "Selecciona LinkedIn para buscar fuera de Perú, o una fuente peruana para Perú." }
        val initial = withContext(Dispatchers.IO) { mutex.withLock { read().copyJson() } }
        val profile = initial.optJSONObject("perfil") ?: JSONObject()
        val query = filters.text("cargo").ifBlank { profile.text("cargos_objetivo").ifBlank { profile.text("carrera") } }.take(200)
        val location = filters.text("ubicacion").take(200).takeUnless(CountryScope::isKnownCountry).orEmpty(); val type = filters.text("tipo")
        val sourceSlots = kotlinx.coroutines.sync.Semaphore(2)
        val limit = 12
        val found = coroutineScope { selected.map { source -> async {
            sourceSlots.withPermit {
                currentCoroutineContext().ensureActive()
                val previous = initial.optJSONObject("busquedas")?.optJSONObject(source)
                val age = previous?.text("revisado")?.let(RecommendationEngine::date)?.let { java.time.Duration.between(it, Instant.now()).seconds }
                val cached = !refresh && previous != null && previous.optBoolean("completa") && previous.optBoolean("region_seleccionada") == filters.optBoolean("region_seleccionada") && previous.text("pais") == country && previous.text("consulta") == query && previous.text("ubicacion") == location && previous.text("tipo") == type && previous.text("dias") == filters.text("dias") && previous.text("modalidad") == filters.text("modalidad") && previous.optInt("limite") == limit && age != null && age in 0..600 && previous.strings("ids").isNotEmpty()
                progress(if (cached) "${JobSources.name(source)}: usando anuncios recientes guardados…" else "Consultando ${JobSources.name(source)}…")
                val sourceResult = if (cached) SourceResult(source, initial.rows("ofertas").filter { it.text("id") in previous!!.strings("ids") }, listOf("${JobSources.name(source)}: se reutilizó la consulta de los últimos 10 minutos. Actualiza para volver a consultar."), cached = true)
                    else sources.search(source, query, location, type, limit, filters.number("dias")?.toInt(), filters.text("modalidad"), country, filters.optBoolean("region_seleccionada"))
                // Save each completed source independently; one slow source cannot hide the others.
                val snapshot = update(filters) { data ->
                    val jobs = data.rows("ofertas").associateBy { it.text("id") }.toMutableMap()
                    val searches = data.optJSONObject("busquedas") ?: JSONObject().also { data.put("busquedas", it) }
                    if (!sourceResult.cached) searches.put(source, JSONObject().put("encontradas", sourceResult.jobs.size).put("avisos", JSONArray(sourceResult.notes)).put("consulta", query).put("pais", country).put("region_seleccionada", filters.optBoolean("region_seleccionada")).put("ubicacion", location).put("tipo", type).put("limite", limit)
                        .put("dias", filters.text("dias")).put("modalidad", filters.text("modalidad"))
                        .put("ids", JSONArray(sourceResult.jobs.map { it.text("id") })).put("completa", sourceResult.complete && sourceResult.jobs.isNotEmpty()).put("revisado", Instant.now().toString()))
                    sourceResult.jobs.forEach { raw ->
                        val job = raw.copyJson(); val existing = jobs[job.text("id")]
                        if (existing != null) {
                            job.put("detectado", existing.opt("detectado") ?: raw.opt("detectado"))
                            for (field in listOf("descripcion", "publicado", "cierre", "empresa", "ubicacion")) if (job.text(field).isBlank() && existing.text(field).isNotBlank()) job.put(field, existing.opt(field))
                        }
                        jobs[job.text("id")] = job
                    }
                    data.put("ofertas", JSONArray(jobs.values.toList()))
                    val savedProfile = data.optJSONObject("perfil") ?: JSONObject().also { data.put("perfil", it) }
                    if (persistPreferences) savedProfile.put("preferencias", filters.copyJson())
                }
                onSource(snapshot)
                progress("${JobSources.name(source)}: ${sourceResult.jobs.size} anuncios recibidos. Puedes ver los resultados mientras continúa la búsqueda.")
                sourceResult
            }
        } }.awaitAll() }
        return filter(filters).put("cantidad", found.sumOf { it.jobs.size }).put("avisos", JSONArray(found.flatMap { it.notes }))
    }
    suspend fun refreshAgenda(filters: JSONObject, onUpdate: suspend (JSONObject) -> Unit): JSONObject {
        val jobs = withContext(Dispatchers.IO) { mutex.withLock {
            val data = read(); val liked = data.optJSONObject("feedback") ?: JSONObject()
            data.rows("ofertas").filter { it.text("id") in data.strings("favoritos") || liked.text(it.text("id")) == "interesa" || data.optJSONObject("seguimiento")?.has(it.text("id")) == true }.sortedBy { it.text("revisado") }.take(12).map { it.copyJson() }
        } }
        val notes = mutableListOf<String>()
        val completed = withTimeoutOrNull(45_000) { for (batch in jobs.chunked(2)) {
            val refreshed = coroutineScope { batch.map { job -> async { sources.refresh(job) } }.awaitAll() }
            notes.addAll(refreshed.flatMap { it.notes })
            val snapshot = update(filters) { data ->
                val existing = data.rows("ofertas").associateBy { it.text("id") }.toMutableMap()
                refreshed.flatMap { it.jobs }.forEach { fresh ->
                    existing[fresh.text("id")]?.let { old -> for (field in listOf("detectado", "publicado", "cierre", "empresa", "ubicacion")) if (fresh.text(field).isBlank() || field == "detectado") fresh.put(field, old.opt(field) ?: JSONObject.NULL) }
                    existing[fresh.text("id")] = fresh
                }
                data.put("ofertas", JSONArray(existing.values.toList()))
            }
            onUpdate(snapshot)
        }; true }
        if (completed == null) notes.add("La revisión llegó a su límite de 45 segundos; las actualizaciones recibidas se conservaron.")
        return filter(filters).put("revision_agenda", JSONArray(notes)).put("revisadas_agenda", jobs.size)
    }
    suspend fun export(): String = withContext(Dispatchers.IO) { mutex.withLock { read().toString(2) } }
    suspend fun restore(json: String): JSONObject = withContext(Dispatchers.IO) { mutex.withLock {
        require(json.toByteArray().size <= 8_000_000) { "La copia supera 8 MB." }
        val data = JSONObject(json)
        require(data.optJSONObject("perfil") != null && data.optJSONArray("ofertas") != null) { "El archivo no es una copia de ChambaMatch." }
        require(data.rows("ofertas").size == data.optJSONArray("ofertas")!!.length() && data.rows("ofertas").all { it.text("id").isNotBlank() && it.text("titulo").isNotBlank() }) { "La copia tiene anuncios inválidos." }
        if (data.optJSONObject("feedback") == null) data.put("feedback", JSONObject())
        data.put("ofertas", JSONArray(data.rows("ofertas").distinctBy { it.text("id") })); write(data); withContext(cpu) { result(data, data.optJSONObject("perfil")?.optJSONObject("preferencias") ?: defaultFilters()) }
    } }
}
