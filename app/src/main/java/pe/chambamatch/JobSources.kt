package pe.chambamatch

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Lecturas HTTPS públicas, acotadas, sin credenciales ni mecanismos para eludir bloqueos. */
class PublicPages {
    private val allowed = setOf("www.practicas.pe", "practicas.pe", "www.convocatoriasdetrabajo.com", "convocatoriasdetrabajo.com", "www.linkedin.com", "linkedin.com", "pe.linkedin.com")
    companion object {
        private val slots = Semaphore(2)
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }
    suspend fun read(address: String): Document = withContext(Dispatchers.IO) {
        slots.withPermit {
            var url = address
            repeat(4) {
                currentCoroutineContext().ensureActive()
                val uri = URI(url)
                require(uri.scheme == "https" && uri.host in allowed && uri.userInfo == null && uri.port in listOf(-1, 443)) { "El enlace no pertenece a una fuente permitida." }
                val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (compatible; ChambaMatch/2.1)").header("Accept-Language", if (uri.path == "/jobs-guest/api/typeaheadHits") "en-US,en;q=0.9" else "es-PE,es;q=0.9").build())
                val response = coroutineScope {
                    // Cancellation closes an active socket, including while the body is being read.
                    val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                    try {
                        val response = suspendCancellableCoroutine<Response> { continuation ->
                            continuation.invokeOnCancellation { call.cancel() }
                            call.enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
                                override fun onResponse(call: Call, response: Response) {
                                    continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
                                }
                            })
                        }
                        response.use {
                            if (it.code in listOf(301, 302, 303, 307, 308)) {
                                url = uri.resolve(it.header("Location") ?: error("Redirección sin destino")).toString()
                                null
                            } else {
                                check(it.isSuccessful) { "La fuente no permitió la consulta (HTTP ${it.code})." }
                                val bytes = it.body?.byteStream()?.use { input ->
                                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); check(output.size() <= 8_000_000) { "La página supera el límite de lectura." } }
                                    output.toByteArray()
                                } ?: error("La fuente devolvió una página vacía.")
                                Jsoup.parse(bytes.inputStream(), null, url)
                            }
                        }
                    } finally { cancelWatcher.cancel() }
                }
                if (response != null) return@withPermit response
            }
            error("Demasiadas redirecciones.")
        }
    }
}

data class SourceResult(val source: String, val jobs: List<JSONObject>, val notes: List<String>, val complete: Boolean = true, val cached: Boolean = false)

object JobParser {
    private val months = mapOf("jan" to 1, "fev" to 2, "mar" to 3, "apr" to 4, "mai" to 5, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "ago" to 8, "out" to 10, "oct" to 10, "nov" to 11, "dez" to 12, "dec" to 12, "ene" to 1, "feb" to 2, "mar" to 3, "abr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "ago" to 8, "sep" to 9, "set" to 9, "oct" to 10, "nov" to 11, "dic" to 12)
    fun id(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    fun textDate(text: String): String? {
        val n = RecommendationEngine.canon(text)
        Regex("\\b(\\d{1,2})\\s+(?:de\\s+)?([a-z]+)\\s+(?:del?\\s+)?(20\\d{2})\\b").find(n)?.let { m ->
            val month = months[m.groupValues[2].take(3)] ?: return@let
            return runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()).toString() }.getOrNull()
        }
        Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})\\b").find(text)?.let { m ->
            val year = m.groupValues[3].toInt().let { if (it < 100) it + 2000 else it }
            return runCatching { LocalDate.of(year, m.groupValues[2].toInt(), m.groupValues[1].toInt()).toString() }.getOrNull()
        }
        return null
    }
    fun deadline(text: String): String? {
        val labels = Regex("Plazo para postular\\s*:|Puede postular hasta|Finaliza el|Vigente hasta\\s*:|Fecha (?:l[ií]mite|de cierre)\\s*:|Application deadline\\s*:|Closing date\\s*:|Prazo (?:para|de) inscri[cç][aã]o\\s*:", RegexOption.IGNORE_CASE)
        for (match in labels.findAll(text)) {
            val block = text.substring(match.range.last + 1).take(300).split(Regex("COMO POSTULAR|¿Cómo Postular|Recomendaciones|PUBLICIDAD", RegexOption.IGNORE_CASE))[0]
            val date = textDate(block) ?: Regex("20\\d{2}-\\d{2}-\\d{2}").find(block)?.value?.takeIf { RecommendationEngine.date(it) != null } ?: continue
            val hm = Regex("\\b(\\d{1,2}):(\\d{2})(?::\\d{2})?\\b").findAll(block).lastOrNull()
            if (hm != null) {
                var hour = hm.groupValues[1].toInt(); val minute = hm.groupValues[2].toInt()
                if (Regex("p\\.?\\s*m", RegexOption.IGNORE_CASE).containsMatchIn(block) && hour < 12) hour += 12
                if (Regex("a\\.?\\s*m", RegexOption.IGNORE_CASE).containsMatchIn(block) && hour == 12) hour = 0
                if (hour in 0..23 && minute in 0..59) return LocalDate.parse(date).atTime(LocalTime.of(hour, minute)).atZone(RecommendationEngine.lima).toOffsetDateTime().toString()
            }
            return date
        }
        return null
    }
    fun lines(doc: Document): String = doc.clone().apply {
        select("script,style,nav,footer").remove()
        select("p,div,li,h1,h2,h3,tr,br").forEach { it.prependText("\n") }
    }.wholeText().replace(Regex("[\\t ]+"), " ").replace(Regex("\\n[\\t ]*\\n+"), "\n").trim()
    private fun objects(value: Any?): Sequence<JSONObject> = sequence {
        when (value) {
            is JSONObject -> { yield(value); value.keys().forEach { yieldAll(objects(value.opt(it))) } }
            is JSONArray -> for (i in 0 until value.length()) yieldAll(objects(value.opt(i)))
        }
    }
    fun jobPosting(doc: Document): JSONObject? = doc.select("script[type=application/ld+json]").asSequence().flatMap {
        objects(runCatching { JSONTokener(it.data()).nextValue() }.getOrNull())
    }.firstOrNull { it.text("@type") == "JobPosting" || "JobPosting" in it.strings("@type") }
    private fun confirmedDate(value: String): Any = value.takeIf { RecommendationEngine.date(it) != null } ?: JSONObject.NULL
    fun detail(doc: Document, url: String, source: String, fallback: String = "", now: Instant = Instant.now()): JSONObject {
        val raw = lines(doc); val jd = jobPosting(doc)
        fun field(label: String): String = Regex("(?:^|\\n)\\s*$label\\s*:\\s*([^\\n]{1,300})", setOf(RegexOption.IGNORE_CASE)).find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val title = jd?.text("title").orEmpty().ifBlank { doc.selectFirst("h1")?.text().orEmpty() }.ifBlank { fallback }.ifBlank { "Oferta sin título confirmado" }
        val desc = jd?.text("description").orEmpty().ifBlank { raw }.let { if ('<' in it) lines(Jsoup.parse(it)) else it }.take(20_000)
        val location = jd?.optJSONObject("jobLocation") ?: jd?.optJSONArray("jobLocation")?.optJSONObject(0)
        val org = jd?.optJSONObject("hiringOrganization")?.text("name").orEmpty()
        val salaryText = field("(?:Remuneración|Subvención(?: económica)?)")
        val salary = Regex("S\\s*/\\.?\\s*([\\d,]+(?:\\.\\d{1,2})?)").find(salaryText)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        val salaryData = jd?.optJSONObject("baseSalary"); val value = salaryData?.optJSONObject("value")
        val amount = salary ?: value?.number("minValue") ?: value?.number("value")
        val currency = if (salary != null) "PEN" else salaryData?.text("currency").orEmpty()
        val period = if (salary != null) "mensual" else if (value?.text("unitText")?.uppercase() == "MONTH") "mensual" else value?.text("unitText").orEmpty()
        val publication = jd?.text("datePosted").orEmpty().ifBlank { Regex("Fecha de publicaci[oó]n\\s*:?\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(raw)?.let { textDate(it.groupValues[1]) }.orEmpty() }
        val closing = jd?.text("validThrough").orEmpty().ifBlank { deadline(raw).orEmpty() }
        val n = RecommendationEngine.canon(desc)
        return RecommendationEngine.normalize(JSONObject().put("id", id(url)).put("fuente", source).put("url", url).put("titulo", title)
            .put("empresa", org.ifBlank { field("Organización") }.ifBlank { if ("Convocatoria" in title) title.substringBefore("Convocatoria").trim() else "" })
            .put("region", location?.optJSONObject("address")?.text("addressRegion").orEmpty())
            .put("pais", location?.optJSONObject("address")?.let { address -> address.optJSONObject("addressCountry")?.text("name") ?: address.text("addressCountry") }.orEmpty())
            .put("ubicacion", location?.optJSONObject("address")?.text("addressLocality").orEmpty().ifBlank { field("Lugar de (?:trabajo|prácticas|prestación del servicio)") })
            .put("vacantes", field("(?:Número de vacantes|Vacantes)").filter(Char::isDigit).toIntOrNull() ?: JSONObject.NULL)
            .put("descripcion", desc).put("publicado", confirmedDate(publication)).put("cierre", confirmedDate(closing))
            .put("detectado", now.toString()).put("revisado", now.toString()).put("salario", amount ?: JSONObject.NULL).put("moneda", currency).put("periodo", period)
            .put("job_type", jd?.text("employmentType").orEmpty().lowercase()).put("tipo", "empleo")
            .put("modalidad", when { jd?.text("jobLocationType") == "TELECOMMUTE" || Regex("(?:modalidad|trabajo|practicas)\\s+(?:de\\s+trabajo\\s+)?(?:remot[oa]|virtual)|\\bremote\\b").containsMatchIn(n) -> "remoto"; "hibrid" in n -> "hibrido"; "presencial" in n -> "presencial"; else -> "" })
            .put("cerrada", Regex("\\b(?:Concluido|Convocatoria finalizada)\\b", RegexOption.IGNORE_CASE).containsMatchIn(desc)))
    }
    fun linkedInCards(doc: Document, now: Instant = Instant.now()): List<JSONObject> = doc.select(".base-search-card, .job-search-card").mapNotNull { card ->
        val link = linkedInUrl(card.selectFirst("a.base-card__full-link, a[href*=/jobs/view/]")?.attr("abs:href").orEmpty()) ?: return@mapNotNull null
        val time = card.selectFirst("time")?.attr("datetime").orEmpty()
        RecommendationEngine.normalize(JSONObject().put("id", id(link)).put("fuente", "linkedin").put("url", link)
            .put("titulo", card.selectFirst(".base-search-card__title")?.text().orEmpty()).put("empresa", card.selectFirst(".base-search-card__subtitle")?.text().orEmpty())
            .put("ubicacion", card.selectFirst(".job-search-card__location")?.text().orEmpty()).put("publicado", confirmedDate(time)).put("cierre", JSONObject.NULL)
            .put("descripcion", "").put("tipo", "empleo").put("modalidad", "").put("salario", JSONObject.NULL).put("moneda", "").put("periodo", "")
            .put("detectado", now.toString()).put("revisado", now.toString()))
    }.filter { it.text("titulo").isNotBlank() }.distinctBy { it.text("id") }
    fun linkedInDetail(card: JSONObject, doc: Document): JSONObject {
        val jd = jobPosting(doc)
        val job = if (jd != null) detail(doc, card.text("url"), "linkedin", card.text("titulo")) else card.copyJson()
        for (key in listOf("titulo", "empresa", "ubicacion", "publicado", "detectado")) if (job.text(key).isBlank()) job.put(key, card.opt(key) ?: JSONObject.NULL)
        // The public card carries city, region and country; JSON-LD may expose only the city.
        if (card.text("ubicacion").isNotBlank()) job.put("ubicacion", card.text("ubicacion"))
        val description = doc.selectFirst(".show-more-less-html__markup, .description__text")?.let { lines(Jsoup.parse(it.html())) }.orEmpty()
        if (jd?.text("description").isNullOrBlank() && description.isNotBlank()) job.put("descripcion", description.take(20_000))
        val criteria = doc.select(".description__job-criteria-item").joinToString(" ") { it.text() }
        if (Regex("internship|pr[aá]cticas", RegexOption.IGNORE_CASE).containsMatchIn(criteria)) job.put("job_type", "internship")
        val n = RecommendationEngine.canon(job.text("descripcion"))
        if (job.text("modalidad").isBlank()) job.put("modalidad", when { Regex("(?:modalidad|trabajo)\\s+(?:de\\s+trabajo\\s+)?remot[oa]|\\bremote\\b").containsMatchIn(n) -> "remoto"; "hibrid" in n -> "hibrido"; "presencial" in n -> "presencial"; else -> "" })
        job.put("revisado", Instant.now().toString())
        return RecommendationEngine.normalize(job)
    }
}

class JobSources(
    private val budgetMillis: Long = 45_000,
    private val worker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2),
    private val readPage: suspend (String) -> Document = PublicPages()::read
) {
    private val portals = mapOf("practicas" to "https://www.practicas.pe/", "convocatorias" to "https://www.convocatoriasdetrabajo.com/")
    private val offer = Regex("/(?:oferta-|convocatoria-|oportunidad-laboral-)")
    private data class Link(val url: String, val title: String, val context: String)
    private fun links(doc: Document): List<Link> {
        val contexts = java.util.IdentityHashMap<org.jsoup.nodes.Element, String>()
        return doc.select("a[href]").take(6000).map { anchor ->
            val card = anchor.closest("article, .item-card, .convocatoria")
            val context = card?.let { contexts.getOrPut(it) { it.text().take(1500) } }.orEmpty()
            Link(anchor.attr("abs:href").substringBefore('#'), anchor.text(), context)
        }
    }
    private fun sameHost(a: String, b: String) = runCatching { URI(a).host == URI(b).host && URI(a).scheme == "https" }.getOrDefault(false)
    suspend fun search(source: String, query: String, location: String, type: String, limit: Int = 12, days: Int? = null, modality: String = "", country: String = "Peru", regional: Boolean = false): SourceResult = withContext(worker) {
        require(source in portals || source == "linkedin")
        val collected = java.util.Collections.synchronizedMap(linkedMapOf<String, JSONObject>())
        fun keep(job: JSONObject) { collected[job.text("id")] = job }
        try {
            val result = withTimeoutOrNull(budgetMillis) {
                if (source == "linkedin") linkedIn(query, location, type, limit.coerceIn(1, 30), ::keep, days, modality, country, regional) else portal(source, query, location, type, limit.coerceIn(1, 30), ::keep)
            }
            result ?: SourceResult(source, synchronized(collected) { collected.values.toList() }, listOf("${name(source)}: se alcanzó el tiempo de espera. Se conservaron ${collected.size} anuncios leídos; puedes volver a intentar."), complete = false)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { SourceResult(source, synchronized(collected) { collected.values.toList() }, listOf("${name(source)}: consulta incompleta. ${e.message ?: "Comprueba Internet y prueba más tarde."}"), complete = false) }
    }
    private suspend fun optional(url: String): Document? = try { readPage(url) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    private suspend fun portal(source: String, query: String, location: String, type: String, limit: Int, keep: (JSONObject) -> Unit): SourceResult {
        val base = portals.getValue(source); val homeLinks = links(readPage(base)); val observedLinks = homeLinks.toMutableList()
        val wanted = RecommendationEngine.tokens("$query $location")
        val queryTokens = RecommendationEngine.tokens(query); val locationTokens = RecommendationEngine.tokens(location)
        fun categoryScore(link: Link): Int {
            val tokens = RecommendationEngine.tokens("${link.title} ${link.url.replace('-', ' ')}")
            return queryTokens.intersect(tokens).size * 10 + locationTokens.intersect(tokens).size * 2
        }
        val categories = homeLinks.filter { link -> sameHost(base, link.url) && !offer.containsMatchIn(link.url) && wanted.isNotEmpty() && categoryScore(link) > 0 }
            .distinctBy { it.url }.sortedByDescending(::categoryScore).take(2)
        var failures = 0
        for (link in categories) optional(link.url)?.let { observedLinks.addAll(0, links(it)) } ?: run { failures++ }
        val matcher = RecommendationEngine.Matcher(query)
        fun score(link: Link): Double {
            val text = "${link.title} ${link.context} ${link.url.replace('-', ' ')}"
            val career = if (query.isBlank()) 0.0 else matcher.affinity(text) * 10
            val place = if (location.isBlank()) 0.0 else if (RecommendationEngine.contains(text, location)) 2.0 else 0.0
            val practice = if (type in listOf("practicas", "preprofesional", "profesional") && Regex("practic|intern", RegexOption.IGNORE_CASE).containsMatchIn(text)) 3.0 else 0.0
            return career + place + practice
        }
        val scores = mutableMapOf<String, Double>()
        fun ranked(links: List<Link>): List<Link> = links.map { it to scores.getOrPut(it.url) { score(it) } }.sortedByDescending { it.second }.map { it.first }
        val candidates = ranked(observedLinks.filter { sameHost(base, it.url) && offer.containsMatchIn(it.url) }.distinctBy { it.url })
        val direct = linkedMapOf<String, Link>(); var groups = 0
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            if ("/oferta-convocatoria-" in candidate.url || "/oportunidad-laboral-" in candidate.url) direct.putIfAbsent(candidate.url, candidate)
            else if (groups < 8) {
                groups++
                val page = optional(candidate.url)
                if (page == null) { failures++; continue }
                ranked(links(page).filter { sameHost(base, it.url) && Regex("/(?:oferta-|oportunidad-laboral-)").containsMatchIn(it.url) })
                    .forEach { direct.putIfAbsent(it.url, it) }
            }
            if (direct.size >= limit) break
        }
        val selected = ranked(direct.values.toList().ifEmpty { candidates }).take(limit)
        val jobs = mutableListOf<JSONObject>()
        // Two requests at a time, no unbounded crawl or background polling.
        for (batch in selected.chunked(2)) coroutineScope {
            batch.map { link -> async { optional(link.url)?.let { JobParser.detail(it, link.url, source, link.title).also(keep) } } }.awaitAll().forEach { if (it == null) failures++ else jobs.add(it) }
        }
        val notes = mutableListOf("${name(source)}: ${jobs.size} anuncios leídos; límite $limit. Se priorizan carreras, cargos y ubicación relacionados; la cobertura es parcial.")
        if (failures > 0) notes.add("${name(source)}: $failures páginas no pudieron leerse.")
        if (jobs.isEmpty()) notes.add("${name(source)}: no se encontraron detalles accesibles; esto no confirma que no existan vacantes.")
        return SourceResult(source, jobs, notes)
    }
    suspend fun refresh(job: JSONObject): SourceResult = withContext(worker) {
        try {
            withTimeout(budgetMillis) {
                val url = job.text("url")
                val source = job.text("fuente")
                if (source !in listOf("linkedin", "practicas", "convocatorias") || url.isBlank()) return@withTimeout SourceResult(source, emptyList(), listOf("${job.text("titulo")}: revisa las bases de la oferta añadida manualmente."), complete = false)
                val endpoint = if (source == "linkedin") {
                    val id = Regex("(?:-|/)([0-9]+)$").find(URI(url).path.trimEnd('/'))?.groupValues?.get(1)
                    if (id != null) "https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/$id" else url
                } else url
                val doc = optional(endpoint) ?: return@withTimeout SourceResult(source, emptyList(), listOf("${job.text("titulo")}: no se pudo revisar la fuente; se conservan los datos anteriores."), complete = false)
                val closed = Regex("no longer accepting applications|ya no acepta solicitudes|nao aceita mais candidaturas", RegexOption.IGNORE_CASE).containsMatchIn(doc.text())
                if (source == "linkedin" && JobParser.jobPosting(doc) == null && doc.selectFirst(".show-more-less-html__markup, .description__text") == null && !closed) return@withTimeout SourceResult(source, emptyList(), listOf("${job.text("titulo")}: LinkedIn no mostró detalles verificables; se conservan los datos."), complete = false)
                val fresh = if (source == "linkedin") JobParser.linkedInDetail(job, doc) else JobParser.detail(doc, url, source)
                if (fresh.text("descripcion").isBlank()) return@withTimeout SourceResult(source, emptyList(), listOf("${job.text("titulo")}: descripción no accesible; revisa las bases."), complete = false)
                if (JobParser.jobPosting(doc)?.text("validThrough").isNullOrBlank()) JobParser.deadline(fresh.text("descripcion"))?.let { fresh.put("cierre", it) }
                if (closed) fresh.put("cerrada", true)
                fresh.put("id", job.text("id"))
                SourceResult(source, listOf(fresh), listOf("${job.text("titulo")}: fuente revisada."))
            }
        } catch (e: TimeoutCancellationException) { SourceResult(job.text("fuente"), emptyList(), listOf("La revisión agotó su tiempo; se conservan las fechas conocidas."), complete = false) }
    }
    private suspend fun resolveGeography(country: String, region: String): String? {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        val query = region.ifBlank { CountryScope.canonicalName(country) }
        val types = if (region.isBlank()) "COUNTRY_REGION" else "ADMIN_DIVISION_1,ADMIN_DIVISION_2"
        val page = optional("https://www.linkedin.com/jobs-guest/api/typeaheadHits?query=${encode(query)}&typeaheadType=GEO&geoTypes=$types")
        val hits = runCatching { JSONArray(page?.body()?.text().orEmpty()) }.getOrNull()
        val candidate = hits?.let { values -> (0 until values.length()).mapNotNull { values.optJSONObject(it) }.firstOrNull {
            val name = it.text("displayName")
            it.text("id").matches(Regex("[0-9]{5,12}")) && CountryScope.matches(name, country) &&
                (if (region.isBlank()) CountryScope.canonicalName(name) == CountryScope.canonicalName(country) else RecommendationEngine.contains(name.substringBeforeLast(','), region))
        } }
        if (candidate != null) return candidate.text("id")
        if (region.isNotBlank()) return null
        // Compatibility fallback: confirm the geography actually displayed by the public search form.
        val search = optional("https://www.linkedin.com/jobs/search/?keywords=&location=${encode(country)}")
        val displayed = search?.selectFirst("input[name=location]")?.attr("value").orEmpty()
        return search?.selectFirst("input[name=geoId]")?.attr("value")?.takeIf { it.matches(Regex("[0-9]{5,12}")) && CountryScope.canonicalName(displayed) == CountryScope.canonicalName(country) }
    }
    private suspend fun linkedIn(query: String, location: String, type: String, limit: Int, keep: (JSONObject) -> Unit, days: Int?, modality: String, country: String, regional: Boolean): SourceResult {
        val target = query.ifBlank { "empleo" }
        val internship = type in listOf("practicas", "preprofesional", "profesional")
        val search = if (internship && CountryScope.canonicalName(country) in listOf("United States", "Canada")) "intern $target" else if (internship) "practicante $target" else target
        val place = searchPlace(location, country)
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        val regionalQuery = regional && location.isNotBlank()
        val geoId = if (!regionalQuery) CountryScope.knownGeoId(country) else null
        val resolved = geoId ?: resolveGeography(country, if (regionalQuery) location else "")
        if (resolved == null) return SourceResult("linkedin", emptyList(), listOf("LinkedIn no pudo confirmar ${if (regionalQuery) "la región '$location' de" else "el país"} '$country'. No se amplió la consulta a otra ubicación."), complete = false)
        val ageFilter = days?.takeIf { it in 1..365 }?.let { "&f_TPR=r${it * 86400L}" }.orEmpty()
        val workFilter = when (modality) { "presencial" -> "&f_WT=1"; "remoto" -> "&f_WT=2"; "hibrido" -> "&f_WT=3"; else -> "" }
        val base = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search?keywords=${encode(search)}&location=${encode(place)}&geoId=$resolved${if (internship) "&f_JT=I" else ""}$ageFilter$workFilter&start="
        val cards = linkedMapOf<String, JSONObject>(); val notes = mutableListOf<String>(); var outside = 0
        for (start in listOf(0, 25)) {
            if (cards.size >= limit) break
            val page = optional(base + start)
            if (page == null) { notes.add("LinkedIn no permitió una consulta pública o no hay conexión. Puedes buscar en LinkedIn y añadir la descripción manualmente."); break }
            val found = JobParser.linkedInCards(page).filter { card ->
                val accepted = country.isBlank() || CountryScope.confirmedCountry(card.text("ubicacion")) == null || CountryScope.matches(card.text("ubicacion"), country)
                if (!accepted) outside++
                accepted
            }
            found.forEach { if (cards.size < limit) cards[it.text("id")] = it.put("pais_busqueda", country).put("region_busqueda", if (regionalQuery) location else "").put("geo_busqueda", resolved).also(keep) }
            if (found.isEmpty()) break
        }
        val jobs = mutableListOf<JSONObject>(); var missing = 0
        for (batch in cards.values.toList().chunked(2)) coroutineScope {
            batch.map { card -> async {
                val numericId = Regex("-(\\d+)$").find(URI(card.text("url")).path.trimEnd('/'))?.groupValues?.get(1)
                    ?: Regex("/jobs/view/(\\d+)$").find(URI(card.text("url")).path)?.groupValues?.get(1)
                val url = if (numericId != null) "https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/$numericId" else card.text("url")
                val page = optional(url)
                (if (page == null) card else JobParser.linkedInDetail(card, page)).put("pais_busqueda", country).put("region_busqueda", if (regionalQuery) location else "").put("geo_busqueda", resolved).also(keep)
            } }.awaitAll().forEach { if (it.text("descripcion").isBlank()) missing++; jobs.add(it) }
        }
        if (outside > 0) notes.add("LinkedIn devolvió $outside anuncios de otro país; se descartaron.")
        if (missing > 0) notes.add("LinkedIn: $missing anuncios sin descripción accesible; sus requisitos quedan por confirmar.")
        if (jobs.isEmpty()) notes.add("LinkedIn no devolvió anuncios accesibles para $search en $place. Prueba un cargo o ubicación más amplio o abre la búsqueda en LinkedIn.")
        notes.add("LinkedIn: ${jobs.size} anuncios públicos leídos; límite $limit. Puede limitar o bloquear las consultas.")
        return SourceResult("linkedin", jobs, notes.distinct())
    }
    companion object { fun name(source: String) = when (source) { "practicas" -> "Prácticas.pe"; "convocatorias" -> "Convocatorias de Trabajo"; "linkedin" -> "LinkedIn"; else -> "Importadas" } }
}
