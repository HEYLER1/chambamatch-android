package pe.chambamatch

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")
fun JSONObject.rows(key: String): List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
fun JSONObject.copyJson() = JSONObject(toString())
fun JSONObject.number(key: String): Double? = text(key).toDoubleOrNull()?.takeIf { it.isFinite() }
fun defaultFilters() = JSONObject().put("pais", "Peru").put("ajustar_perfil", true).put("fuentes", JSONArray(listOf("practicas", "convocatorias", "linkedin", "manual"))).put("sinfecha", true).put("desconocidos", true).put("vencidas", false).put("orden", "compatibilidad")

fun linkedInUrl(link: String): String? = runCatching {
    val uri = java.net.URI(link)
    if (uri.scheme == "https" && (uri.host in setOf("www.linkedin.com", "linkedin.com") || uri.host?.matches(Regex("[a-z]{2}\\.linkedin\\.com")) == true) && uri.userInfo == null && uri.port in listOf(-1, 443) && uri.path.startsWith("/jobs/view/")) "https://www.linkedin.com${uri.rawPath.trimEnd('/')}" else null
}.getOrNull()

/** Country is a search scope, never proof of residence or permission to work. */
fun searchCountry(filters: JSONObject): String = if (filters.text("ambito") == "global") "" else CountryScope.canonicalName(if (filters.text("ambito") in listOf("", "peru") && CountryScope.isKnownCountry(filters.text("ubicacion"))) filters.text("ubicacion") else filters.text("pais").ifBlank { "Peru" })
fun searchPlace(location: String, country: String): String = when {
    country.isBlank() -> location.ifBlank { "Worldwide" }
    location.isBlank() -> country
    RecommendationEngine.contains(location, country) -> location
    else -> "$location, $country"
}

object CountryScope {
    private val countryAliases = java.util.Locale.getISOCountries().associateWith { code ->
        val locale = java.util.Locale.Builder().setRegion(code).build()
        setOf(locale.getDisplayCountry(java.util.Locale.ENGLISH), locale.getDisplayCountry(java.util.Locale.forLanguageTag("es")), code).map(RecommendationEngine::canon).toSet()
    }
    private val countryCodes = java.util.concurrent.ConcurrentHashMap(countryAliases.flatMap { (code, names) -> names.map { it to code } }.toMap() + mapOf("usa" to "US", "ee uu" to "US", "eeuu" to "US", "u s a" to "US", "estados unidos de america" to "US", "canda" to "CA", "uk" to "GB"))
    fun registerCountries(countries: List<WorldCountry>) { countries.forEach { country -> countryCodes[RecommendationEngine.canon(country.name)] = country.code; countryCodes[RecommendationEngine.canon(country.label)] = country.code } }
    fun isKnownCountry(country: String): Boolean = countryCodes.containsKey(RecommendationEngine.canon(country)) && (country.trim().length != 2 || country.trim() == country.trim().uppercase(java.util.Locale.ROOT))
    fun canonicalName(country: String): String = countryCodes[RecommendationEngine.canon(country)]?.let { java.util.Locale.Builder().setRegion(it).build().getDisplayCountry(java.util.Locale.ENGLISH) } ?: country.trim()
    private fun aliases(country: String): Set<String> {
        val name = RecommendationEngine.canon(country)
        return countryAliases[countryCodes[name]].orEmpty() + name
    }
    fun confirmedCountry(location: String): String? {
        val last = RecommendationEngine.canon(location.substringAfterLast(',').replace(Regex("\\([^)]*\\)"), "").trim())
        return countryCodes[last]?.let { java.util.Locale.Builder().setRegion(it).build().getDisplayCountry(java.util.Locale.ENGLISH) }
    }
    fun matches(location: String, country: String): Boolean = country.isBlank() || aliases(country).any { RecommendationEngine.contains(location, it) }
    fun knownGeoId(country: String): String? = when {
        country.isBlank() -> "92000000"
        matches("Peru", country) -> "102927786"
        matches("Spain", country) -> "105646813"
        matches("Chile", country) -> "104621616"
        matches("United States", country) -> "103644278"
        matches("Canada", country) -> "101174742"
        matches("Brazil", country) -> "106057199"
        else -> null
    }
}
