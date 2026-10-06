package pe.chambamatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prueba optativa de red: no guarda resultados ni cambia perfiles. */
@RunWith(AndroidJUnit4::class)
class LiveSourcesTest {
    @Test fun readsBrazilRegionAndDynamicGermany() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSources") == "true")
        for ((country, region) in listOf("Brazil" to "", "Brazil" to "São Paulo", "Germany" to "", "Spain" to "Barcelona")) {
            val result = JobSources().search("linkedin", "software engineer", region, "empleo", 4, country = country, regional = region.isNotBlank())
            println("LIVE WORLD $country / $region: ${result.jobs.size}; ${result.notes.joinToString()}")
            assertTrue("No se recuperaron anuncios de $country / $region", result.jobs.isNotEmpty())
            assertTrue(result.jobs.all { CountryScope.confirmedCountry(it.text("ubicacion")) == null || CountryScope.matches(it.text("ubicacion"), country) })
            if (region.isNotBlank()) assertTrue(result.jobs.all { it.text("geo_busqueda") == (if (region == "São Paulo") "105871508" else "107025191") && it.text("region_busqueda") == region })
            val filters = defaultFilters().put("ambito", "pais").put("pais", country).put("ubicacion", region).put("region_seleccionada", true).put("ajustar_perfil", false)
            assertTrue(RecommendationEngine.results(result.jobs, org.json.JSONObject(), filters, org.json.JSONObject()).rows("ofertas").isNotEmpty())
        }
    }
    @Test fun readPublicSourcesDirectlyFromAndroid() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSources") == "true")
        val sources = JobSources()
        var accessiblePortals = 0
        for (source in listOf("practicas", "convocatorias", "linkedin")) {
            val result = sources.search(source, "sistemas", "Lima", "practicas", 4)
            println("LIVE $source: ${result.jobs.size} anuncios, ${result.jobs.count { it.text("descripcion").isNotBlank() }} con descripción. ${result.notes.joinToString()}")
            result.jobs.forEach { println("LIVE TITLE ${it.text("titulo")} | ${it.text("url")}") }
            assertTrue(result.notes.isNotEmpty())
            assertTrue(result.jobs.all { it.text("url").startsWith("https://") && it.text("titulo").isNotBlank() })
            if (source != "linkedin" && result.jobs.isNotEmpty()) accessiblePortals++
        }
        assertTrue("Ningún portal peruano fue accesible: revisar conexión y cambios de HTML.", accessiblePortals > 0)
    }
    @Test fun readsInternationalRemoteListings() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSources") == "true")
        val result = JobSources().search("linkedin", "software engineer", "", "empleo", 4, modality = "remoto", country = "España")
        println("LIVE INTERNATIONAL: ${result.jobs.size} anuncios; ${result.notes.joinToString()}")
        result.jobs.forEach { println("LIVE INTERNATIONAL TITLE ${it.text("titulo")} | ${it.text("ubicacion")} | ${it.text("pais")} | ${it.text("modalidad")}") }
        assertTrue("La consulta de España no devolvió anuncios verificables", result.jobs.isNotEmpty())
        assertTrue(result.jobs.all { it.text("pais_busqueda") == "España" })
        assertTrue(result.jobs.all { it.text("pais").isBlank() || CountryScope.matches(it.text("pais"), "España") })
        assertTrue(result.jobs.all { CountryScope.confirmedCountry(it.text("ubicacion")) == null || CountryScope.matches(it.text("ubicacion"), "España") })
        assertTrue(result.notes.isNotEmpty())
    }

    @Test fun readsChileUnitedStatesAndCanada() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSources") == "true")
        for (country in listOf("Chile", "United States", "Canada")) {
            val result = JobSources().search("linkedin", "software engineer", "", "empleo", 4, country = country)
            println("LIVE COUNTRY $country: ${result.jobs.size}; ${result.notes.joinToString()}")
            result.jobs.forEach { println("LIVE COUNTRY TITLE $country: ${it.text("titulo")} | ${it.text("ubicacion")}") }
            assertTrue("No se recuperaron anuncios de $country", result.jobs.isNotEmpty())
            assertTrue(result.jobs.all { CountryScope.confirmedCountry(it.text("ubicacion")) == null || CountryScope.matches(it.text("ubicacion"), country) })
            val filters = defaultFilters().put("ambito", "pais").put("pais", country).put("cargo", "software engineer").put("ajustar_perfil", false)
            assertTrue("El filtro local ocultó todos los anuncios de $country", RecommendationEngine.results(result.jobs, org.json.JSONObject(), filters, org.json.JSONObject()).rows("ofertas").isNotEmpty())
        }
    }

}
