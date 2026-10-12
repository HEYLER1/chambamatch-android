package pe.chambamatch

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.*
import java.util.Locale
import kotlin.math.*

/** Ranking explicable y regresión logística local, sin servicios externos. */
object RecommendationEngine {
    val lima: ZoneId = ZoneId.of("America/Lima")
    private val stop = "de del la el los las en y o un una para con por al the and of to in se que es su como ingenieria profesional requisitos convocatoria trabajo".split(' ').toSet()
    private val areas = listOf(
        listOf("sistemas", "informatica", "software", "programacion", "desarrollador", "developer", "tecnologia", "technology", "soporte tecnico", "ti", "it", "computacion", "sap", "odoo"),
        listOf("datos", "data", "analista de datos", "business intelligence", "power bi", "sql", "analytics"),
        listOf("administracion", "administrativo", "gestion empresarial"), listOf("contabilidad", "contable", "contador", "cuentas por pagar")
    )
    private val aliases = mapOf("excel" to listOf("excel", "hojas de calculo"), "power bi" to listOf("power bi", "powerbi"), "javascript" to listOf("javascript", "js"), "atencion al cliente" to listOf("atencion al cliente", "servicio al cliente"))
    private val districts = listOf("san isidro", "miraflores", "surquillo", "surco", "la molina", "lurin", "san borja", "santa anita", "ate", "barranco", "chorrillos", "lima metropolitan")
    private val marks = Regex("\\p{M}+")
    private val punctuation = Regex("[^a-z0-9]+")
    private val engineering = Regex("\\bingenier(?:o|a|os|as|ia)\\b")
    private val education = Regex("(?:formaci[oó]n\\s+acad[eé]mica|carreras?\\s+(?:de\\s+)?|estudiantes?\\s+(?:universitarios?\\s+)?de)\\s*:?\\s*([^\\n.]{1,250})", RegexOption.IGNORE_CASE)
    fun canon(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .replace(marks, "").replace(punctuation, " ").trim().replace(engineering, "ingenieria")
    private fun normalizedTokens(value: String) = value.split(' ').filter { it.length > 2 && it !in stop }.toSet()
    fun tokens(value: String): Set<String> = normalizedTokens(canon(value))
    private fun hasNormalized(text: String, term: String) = term.isNotBlank() && " $term " in " $text "
    fun contains(text: String, term: String): Boolean {
        if ('+' in term || '#' in term) return Regex("(?<!\\w)${Regex.escape(term.lowercase(Locale.ROOT))}(?!\\w)").containsMatchIn(text.lowercase(Locale.ROOT))
        return hasNormalized(canon(text), canon(term))
    }
    // Compute query features once, then normalize each candidate only once.
    class Matcher(query: String) {
        private val normalized = canon(query)
        private val words = normalizedTokens(normalized)
        private val groups = areas.filter { group -> group.any { hasNormalized(normalized, it) } }
        fun affinity(text: String): Double {
            if (words.isEmpty()) return 0.0
            val n = canon(text)
            val literal = words.intersect(normalizedTokens(n)).size.toDouble() / words.size
            val related = groups.any { group -> group.any { hasNormalized(n, it) } }
            return max(literal, if (related) 0.6 else 0.0)
        }
        fun relevance(job: JSONObject): Double {
            val academic = education.findAll(job.text("descripcion")).maxOfOrNull { affinity(it.groupValues[1]) } ?: 0.0
            return max(affinity(job.text("titulo")), academic * 0.8)
        }
    }
    fun affinity(query: String, text: String): Double = Matcher(query).affinity(text)
    fun relevance(query: String, job: JSONObject): Double = Matcher(query).relevance(job)
    fun classify(job: JSONObject): String {
        val title = canon(job.text("titulo")); val desc = canon(job.text("descripcion"))
        if (Regex("pre\\s*profesional").containsMatchIn(title)) return "preprofesional"
        if (Regex("practicante\\s+profesional|practicas\\s+profesionales").containsMatchIn(title)) return "profesional"
        if (Regex("practic|\\bintern\\b|\\binternship\\b").containsMatchIn(title) || job.text("job_type") == "internship" || job.text("fuente") == "practicas") {
            val student = Regex("\\bestudiantes?\\b").containsMatchIn(desc); val graduate = Regex("\\begresad[oa]s?\\b").containsMatchIn(desc)
            if (Regex("pre\\s*profesional").containsMatchIn(desc) || student && !graduate) return "preprofesional"
            if (Regex("practicas?\\s+profesionales").containsMatchIn(desc) || graduate && !student) return "profesional"
            return "practicas"
        }
        return job.text("tipo").takeIf { it in listOf("preprofesional", "profesional", "practicas") } ?: "empleo"
    }
    fun normalize(raw: JSONObject): JSONObject = raw.copyJson().apply {
        if (text("fuente") in listOf("practicas", "convocatorias")) {
            var desc = text("descripcion")
            Regex("\\b(?:Requisitos|RESUMEN DE LA CONVOCATORIA)\\b", RegexOption.IGNORE_CASE).find(desc)?.let { desc = desc.substring(it.range.first) }
            Regex("Recomendaciones para postular|Te sugerimos|Te recomendamos|Únete a nuestros grupos", RegexOption.IGNORE_CASE).find(desc)?.let { desc = desc.substring(0, it.range.first) }
            put("descripcion", desc)
        }
        put("tipo", classify(this))
        if (date(text("cierre"), true) == null) JobParser.deadline(text("descripcion"))?.let { put("cierre", it); put("cierre_evidencia", "Extraído de una fecha de postulación explícita en la descripción.") }
        if (optBoolean("is_remote")) put("modalidad", "remoto")
    }
    fun date(value: String, endOfDay: Boolean = false): Instant? {
        if (value.isBlank()) return null
        return runCatching {
            if (value.length == 10) {
                val day = LocalDate.parse(value)
                if (endOfDay) day.atTime(LocalTime.MAX).atZone(lima).toInstant() else day.atStartOfDay(lima).toInstant()
            } else runCatching { OffsetDateTime.parse(value).toInstant() }.getOrElse { LocalDateTime.parse(value).atZone(lima).toInstant() }
        }.getOrNull()
    }
    fun calendarDaysRemaining(job: JSONObject, now: Instant = Instant.now()): Int? = date(job.text("cierre"), true)?.let {
        java.time.temporal.ChronoUnit.DAYS.between(now.atZone(lima).toLocalDate(), it.atZone(lima).toLocalDate()).toInt()
    }
    fun evaluate(job: JSONObject, now: Instant): JSONObject = job.copyJson().apply {
        val deadline = date(text("cierre"), true)
        val publication = date(text("publicado"))
        put("estado", when { deadline != null && deadline < now -> "vencida"; optBoolean("cerrada") -> "cerrada"; deadline != null -> "en plazo"; else -> "vigencia desconocida" })
        put("antiguedad", publication?.let { max(0.0, Duration.between(it, now).toMillis() / 86_400_000.0) } ?: JSONObject.NULL)
        put("dias_restantes", deadline?.let { Duration.between(now, it).toMillis() / 86_400_000.0 } ?: JSONObject.NULL)
    }
    fun experienceMonths(text: String, requirement: Boolean = false): Double? {
        var plain = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD).replace(marks, "")
        val words = mapOf("un" to "1", "uno" to "1", "una" to "1", "dos" to "2", "tres" to "3", "cuatro" to "4", "cinco" to "5", "seis" to "6", "siete" to "7", "ocho" to "8", "nueve" to "9", "diez" to "10", "doce" to "12")
        words.forEach { (word, number) -> plain = plain.replace(Regex("\\b$word\\b"), number) }
        if (Regex("sin experiencia|no (?:tengo|cuento con) experiencia|no (?:se )?requiere experiencia|no experience required").containsMatchIn(plain)) return 0.0
        val number = "(\\d+(?:[.,]\\d+)?)\\s*(?:\\(\\d+\\)\\s*)?(anos?|mes(?:es)?|years?|months?)"
        val forward = Regex("(?:experiencia|experience)\\s*(?:general\\s*)?(?:(?:minima|minimum|de|of|:|al menos|at least)\\s*)*$number")
        val reverse = Regex("$number\\s*(?:(?:de|of)\\s+)?(?:experiencia|experience)")
        val matches = (forward.findAll(plain) + reverse.findAll(plain)).filter { match -> !Regex("^\\s+(?:en|in)\\s+|^\\s+(?:especifica|specific)\\b").containsMatchIn(plain.substring(match.range.last + 1)) }.toList()
        val durations = matches.map { it.groupValues[1].replace(',', '.').toDouble() * if (it.groupValues[2].startsWith("ano") || it.groupValues[2].startsWith("year")) 12 else 1 }.distinct()
        if (requirement && Regex("experiencia especifica|specific experience").containsMatchIn(plain) && matches.isEmpty()) return null
        return durations.singleOrNull()?.takeIf { it in 0.0..1200.0 }
    }
    fun requirements(job: JSONObject, profile: JSONObject): List<JSONObject> {
        val checks = mutableListOf<JSONObject>()
        fun add(name: String, state: String, evidence: String) { checks.add(JSONObject().put("nombre", name).put("estado", state).put("evidencia", evidence)) }
        val situation = canon(profile.text("situacion")); val desc = job.text("descripcion"); val n = canon(desc)
        if (job.text("tipo") == "preprofesional") add("Situación académica", if (situation.isEmpty()) "por confirmar" else if (situation == "estudiante") "compatible" else "brecha", "Prácticas preprofesionales; revisa si las bases admiten tu condición.")
        if (job.text("tipo") == "profesional") add("Situación académica", if (situation.isEmpty()) "por confirmar" else if (situation == "estudiante") "brecha" else "compatible", "Prácticas profesionales; revisa condición y plazo desde el egreso.")
        val plain = Normalizer.normalize(desc.lowercase(Locale.ROOT), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        val general = experienceMonths(plain, requirement = true)
        val actual = profile.number("meses_experiencia") ?: experienceMonths(profile.text("experiencia"), requirement = false)
        if (general != null) {
            add("Experiencia general", if (actual == null) "por confirmar" else if (actual >= general) "compatible" else "brecha",
                "Requisito del anuncio: $general meses. " + if (actual == null) "Tu perfil no indica una duración de experiencia; escribe tus meses o descríbela en Perfil." else "Tu experiencia declarada: $actual meses.")
        } else if ("experiencia" in n || "experience" in n) {
            add("Experiencia", "por confirmar", "El anuncio menciona experiencia específica o una duración no reconocida. Tus meses totales no prueban experiencia en esa función.")
        }
        Regex("(?:a\\s+partir\\s+del?\\s+|desde\\s+el\\s+|minimo\\s+)(\\d{1,2})(?:vo|to|mo|do|ro)?\\s+ciclo").find(n)?.let { m ->
            val actual = profile.number("ciclo")
            add("Ciclo académico", if (actual == null) "por confirmar" else if (actual >= m.groupValues[1].toDouble()) "compatible" else "brecha", "Se menciona un mínimo de ciclo ${m.groupValues[1]}.")
        }
        Regex("(?:formaci[oó]n\\s+acad[eé]mica|carreras?\\s+(?:de\\s+)?|estudiantes?\\s+de)\\s*:?\\s*([^\\n]{1,400})", RegexOption.IGNORE_CASE).find(desc)?.let { m ->
            if (profile.text("carrera").isNotBlank()) add("Carrera", if (affinity(profile.text("carrera"), m.groupValues[1]) >= 0.8) "compatible" else "por confirmar", "Formación publicada: ${m.groupValues[1].take(250)}")
        }
        val extra = RequirementAnalyzer.extra(job, profile).filterNot { it.text("nombre").startsWith("Otro requisito:") && (general != null && (it.text("evidencia").contains("experiencia", true) || it.text("evidencia").contains("experience", true)) || it.text("evidencia").contains("ciclo", true) && checks.any { c -> c.text("nombre") == "Ciclo académico" }) }
        if (extra.any { it.text("nombre") == "Formación y condición" }) checks.removeAll { it.text("nombre") == "Situación académica" }
        if (extra.any { it.text("nombre") == "Carrera" }) checks.removeAll { it.text("nombre") == "Carrera" }
        return (checks + extra).map { it.apply { if (!has("obligatorio")) put("obligatorio", true); if (!has("clave")) put("clave", canon(text("nombre"))) } }
    }
    fun vector(job: JSONObject): Map<String, Double> {
        val parts = tokens(job.text("titulo")).toList().let { it + it + it } + tokens(job.text("descripcion").take(6000)) + listOf("tipo:${job.text("tipo")}", "modalidad:${job.text("modalidad")}")
        val counts = parts.groupingBy { it }.eachCount()
        val norm = sqrt(counts.values.sumOf { it.toDouble().pow(2) }).takeIf { it > 0 } ?: 1.0
        return counts.mapValues { it.value / norm }
    }
    data class Model(val weights: Map<String, Double>, val count: Int)
    fun train(jobs: List<JSONObject>, feedback: JSONObject): Model {
        val byId = jobs.associateBy { it.text("id") }
        val samples = feedback.keys().asSequence().sorted().mapNotNull { id ->
            val value = feedback.text(id)
            if (byId[id] == null || value !in listOf("interesa", "no_interesa")) null else vector(normalize(byId.getValue(id))) to if (value == "interesa") 1.0 else 0.0
        }.toList()
        val weights = mutableMapOf<String, Double>()
        repeat(25) { samples.forEach { (x, y) ->
            val z = x.entries.sumOf { (k, v) -> (weights[k] ?: 0.0) * v }.coerceIn(-20.0, 20.0)
            val error = y - 1 / (1 + exp(-z))
            x.forEach { (k, v) -> weights[k] = (weights[k] ?: 0.0) * 0.998 + 0.25 * error * v }
        } }
        return Model(weights, samples.size)
    }
    fun recommend(job: JSONObject, profile: JSONObject, model: Model): JSONObject = job.copyJson().apply {
        val skills = profile.text("habilidades").split(',').map(String::trim).filter(String::isNotEmpty)
        val matched = skills.filter { skill -> (aliases[canon(skill)] ?: listOf(skill)).any { contains(text("descripcion") + " " + text("titulo"), it) } }
        val reasons = mutableListOf<String>(); var base = 0.0; var knownBase = false
        val checks = requirements(this, profile)
        val requiredSkills = checks.filter { it.text("nombre").startsWith("Habilidad:") && it.optBoolean("obligatorio", true) }
        if (skills.isNotEmpty()) { base += (if (requiredSkills.isNotEmpty()) requiredSkills.count { it.text("estado") == "compatible" }.toDouble() / requiredSkills.size else matched.size.toDouble() / skills.size) * 0.45; knownBase = true; if (matched.isNotEmpty()) reasons.add("Habilidades mencionadas: ${matched.joinToString()}") }
        val query = listOf("carrera", "cargos_objetivo", "experiencia").joinToString(" ") { profile.text(it) }
        if (query.isNotBlank()) { val related = relevance(query, this); base += related * 0.3; knownBase = true; if (related > 0) reasons.add("Afinidad con tus cargos, carrera o experiencia declarados.") }
        val known = checks.filter { it.text("estado") != "por confirmar" && it.optBoolean("obligatorio", true) }
        if (known.isNotEmpty()) { base += known.count { it.text("estado") == "compatible" }.toDouble() / known.size * 0.25; knownBase = true }
        val signal = vector(this).entries.sumOf { (k, v) -> (model.weights[k] ?: 0.0) * v }
        val adjust = 15 * tanh(signal) * min(model.count / 5.0, 1.0)
        if (abs(adjust) > 0.5) reasons.add("Tus valoraciones guardadas en el celular ajustan su posición.")
        var score: Int? = if (knownBase || model.count > 0) ((if (knownBase) base * 100 else 50.0) + adjust).coerceIn(0.0, 100.0).roundToInt() else null
        if (checks.any { it.text("estado") == "brecha" && it.optBoolean("obligatorio", true) }) { score = score?.coerceAtMost(45); reasons.add("Hay un requisito declarado que no coincide con tu perfil.") }
        val pending = checks.count { it.text("estado") == "por confirmar" && it.optBoolean("obligatorio", true) }
        if (pending > 0) { score = score?.coerceAtMost(75); reasons.add("$pending requisitos obligatorios necesitan confirmación.") }
        if (checks.isEmpty()) { score = score?.coerceAtMost(60); reasons.add("No se reconocieron requisitos explícitos; revisa las bases.") }
        if (text("descripcion").isBlank()) { score = score?.coerceAtMost(30); reasons.add("Descripción no accesible: requisitos sin evaluar.") }
        put("evaluacion", if (text("descripcion").isBlank()) "Sin descripción" else if (checks.isEmpty()) "Sin requisitos reconocidos" else "$pending por confirmar")
        put("puntaje", score ?: JSONObject.NULL); put("coincidencias", JSONArray(matched)); put("razones", JSONArray(reasons)); put("requisitos", JSONArray(checks)); put("aprendizaje", adjust)
        put("aviso", "Compatibilidad orientativa. Revisa los requisitos completos y las bases antes de postular.")
    }
    fun exclusions(job: JSONObject, prefs: JSONObject): List<String> {
        val reasons = mutableListOf<String>(); val unknown = prefs.optBoolean("desconocidos", true)
        val sources = if (prefs.has("fuentes")) prefs.strings("fuentes") else defaultFilters().strings("fuentes")
        if (prefs.optBoolean("solo_guardadas") && !job.optBoolean("guardada")) reasons.add("no guardada")
        if (job.text("fuente") !in sources) reasons.add("fuente")
        if (!prefs.optBoolean("vencidas") && job.text("estado") in listOf("vencida", "cerrada")) reasons.add("vigencia")
        val age = job.number("antiguedad")
        if (age == null && !prefs.optBoolean("sinfecha", true)) reasons.add("fecha desconocida")
        if (age != null && prefs.number("dias")?.let { age > it } == true) reasons.add("antigüedad")
        for (field in listOf("modalidad", "ubicacion")) {
            val wanted = prefs.text(field); val value = job.text(field)
            if (wanted.isBlank() || field == "ubicacion" && CountryScope.isKnownCountry(wanted)) continue
            if (value.isBlank()) { if (!unknown) reasons.add("$field desconocida") }
            else if (field == "ubicacion" && job.text("fuente") == "linkedin" && job.text("region_busqueda") == wanted && job.text("geo_busqueda").isNotBlank()) { /* The provider resolved the selected administrative region, including localized names. */ }
            else if (!(if (field == "ubicacion") (if (CountryScope.isKnownCountry(wanted)) CountryScope.matches(value, wanted) || CountryScope.matches(job.text("pais"), wanted) else contains(value, wanted)) || canon(wanted) == "lima" && districts.any { contains(value, it) } else value == wanted)) reasons.add(field)
        }
        val country = searchCountry(prefs)
        val countryText = job.text("pais").ifBlank { job.text("ubicacion") }
        if (country.isNotBlank() && job.text("fuente") in listOf("practicas", "convocatorias") && canon(country) != "peru") reasons.add("país")
        if (country.isNotBlank() && job.text("fuente") == "linkedin" && CountryScope.confirmedCountry(countryText) != null && !CountryScope.matches(countryText, country)) reasons.add("país")
        if (prefs.optBoolean("ajustar_perfil", true)) {
            if (job.rows("requisitos").any { it.text("estado") == "brecha" && it.optBoolean("obligatorio", true) }) reasons.add("requisitos no cumplidos")
            if (prefs.optBoolean("requisitos_confirmados") && job.rows("requisitos").any { it.text("estado") == "por confirmar" && it.optBoolean("obligatorio", true) }) reasons.add("requisitos por confirmar")
        }
        val type = prefs.text("tipo"); val actual = job.text("tipo")
        if (type.isNotBlank()) {
            if (actual == "practicas" && type in listOf("preprofesional", "profesional", "practicas")) { if (!unknown && type != "practicas") reasons.add("tipo por confirmar") }
            else if (!(actual == type || type == "practicas" && actual in listOf("preprofesional", "profesional"))) reasons.add("tipo")
        }
        prefs.number("sueldo")?.let { salary ->
            val valid = job.text("moneda") == "PEN" && job.text("periodo") in listOf("mensual", "monthly") && job.number("salario") != null
            if (!valid && !unknown) reasons.add("sueldo desconocido") else if (valid && job.number("salario")!! < salary) reasons.add("sueldo")
        }
        if (prefs.text("cargo").isNotBlank() && relevance(prefs.text("cargo"), job) < 0.3) reasons.add("cargo")
        return reasons
    }
    fun results(jobs: List<JSONObject>, profile: JSONObject, prefs: JSONObject, feedback: JSONObject, now: Instant = Instant.now()): JSONObject {
        val model = train(jobs, feedback); val visible = mutableListOf<JSONObject>(); val excluded = mutableListOf<JSONObject>()
        val counts = mutableMapOf<String, Int>(); val sources = JSONObject(); val tracking = mutableListOf<JSONObject>(); val recommendations = mutableListOf<JSONObject>()
        val effective = profile.copyJson().apply { if (text("cargos_objetivo").isBlank()) put("cargos_objetivo", prefs.text("cargo")) }
        val homePrefs = prefs.copyJson().apply { remove("cargo"); put("ajustar_perfil", false); put("requisitos_confirmados", false); put("vencidas", false) }
        val goals = listOf("carrera", "cargos_objetivo").joinToString(" ") { profile.text(it) }
        val goalMatcher = Matcher(goals)
        val profileSkills = profile.text("habilidades").split(',').count { it.isNotBlank() }
        for (raw in jobs.distinctBy { it.text("id") }) {
            val job = recommend(evaluate(normalize(raw), now), effective, model).put("valoracion", feedback.text(raw.text("id")))
            val home = recommend(evaluate(normalize(raw), now), profile, model).put("valoracion", feedback.text(raw.text("id")))
            val goalMatch = goalMatcher.relevance(home)
            val skillMatch = if (profileSkills == 0) 0.0 else home.strings("coincidencias").size.toDouble() / profileSkills
            val similarity = (if (goals.isBlank()) skillMatch else goalMatch * .7 + skillMatch * .3) + (home.number("aprendizaje") ?: 0.0) / 1000
            home.put("semejanza", similarity.coerceIn(0.0, 1.0))
            val similar = goalMatch > 0 || home.strings("coincidencias").isNotEmpty()
            if (similar && exclusions(home, homePrefs).isEmpty()) recommendations.add(home)
            val reasons = exclusions(job, prefs); job.put("excluida_por", JSONArray(reasons))
            if (job.optBoolean("guardada") || job.text("valoracion") == "interesa" || job.text("postulacion").isNotBlank()) tracking.add(job)
            val source = job.text("fuente"); val count = sources.optJSONObject(source) ?: JSONObject().put("guardadas", 0).put("visibles", 0).also { sources.put(source, it) }
            count.put("guardadas", count.optInt("guardadas") + 1)
            if (reasons.isEmpty()) { visible.add(job); count.put("visibles", count.optInt("visibles") + 1) }
            else { excluded.add(job); reasons.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        }
        fun rank(job: JSONObject) = when (prefs.text("orden")) { "recientes" -> -(job.number("antiguedad") ?: 1e9); "cierre" -> -(job.number("dias_restantes") ?: 1e9); else -> job.number("puntaje") ?: -1.0 }
        return JSONObject().put("ofertas", JSONArray(visible.sortedByDescending(::rank))).put("alternativas", JSONArray(excluded.filter { "fuente" !in it.strings("excluida_por") && "vigencia" !in it.strings("excluida_por") }.sortedByDescending(::rank).take(8)))
            .put("recomendaciones", JSONArray(recommendations.sortedWith(compareByDescending<JSONObject> { it.number("semejanza") ?: 0.0 }.thenByDescending { it.number("puntaje") ?: -1.0 })))
            .put("seguimiento", JSONArray(tracking.sortedWith(compareBy<JSONObject> { it.text("estado") in listOf("vencida", "cerrada") }.thenBy { date(it.text("cierre"), true) ?: Instant.MAX })))
            .put("total", jobs.size).put("ocultas", JSONObject(counts as Map<*, *>)).put("fuentes", sources).put("valoraciones", model.count)
    }
}
