package pe.chambamatch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

data class WorldCountry(val code: String, val name: String, val label: String, val regions: List<String>) {
    val flag: String get() = code.uppercase(Locale.ROOT).map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("")
}
object WorldLocations {
    fun parse(json: String): List<WorldCountry> {
        val rows = JSONArray(json)
        val collator = Collator.getInstance(Locale.forLanguageTag("es"))
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            WorldCountry(it.text("code"), it.text("name"), it.text("label"), it.strings("regions").distinct().sortedWith { a,b -> collator.compare(a,b) })
        } }.sortedWith { a,b -> collator.compare(a.label,b.label) }
    }
    suspend fun load(context: Context): List<WorldCountry> = withContext(Dispatchers.IO) {
        context.assets.open("world-regions.json").bufferedReader().use { parse(it.readText()) }.also(CountryScope::registerCountries)
    }
    fun selected(countries: List<WorldCountry>, filters: JSONObject): WorldCountry? = countries.firstOrNull {
        it.name == searchCountry(filters) || CountryScope.canonicalName(it.name) == searchCountry(filters)
    }
    fun select(filters: JSONObject, country: WorldCountry?): JSONObject = filters.copyJson().apply {
        put("pais", country?.name.orEmpty()); put("ambito", if (country == null) "global" else if (country.code == "PE") "peru" else "pais")
        put("ubicacion", ""); put("region_seleccionada", true)
        put("fuentes", JSONArray(if (country?.code == "PE") listOf("practicas", "convocatorias", "linkedin", "manual") else listOf("linkedin", "manual")))
        if (country?.code != "PE") put("sueldo", "")
    }
    fun migrate(countries: List<WorldCountry>, filters: JSONObject): JSONObject = filters.copyJson().apply {
        val country = selected(countries, this)
        if (text("ambito") != "global" && country == null) { put("pais", "Peru"); put("ambito", "peru") }
        val current = selected(countries, this)
        if (text("ubicacion") !in current?.regions.orEmpty()) put("ubicacion", "")
        put("region_seleccionada", true)
    }
}
