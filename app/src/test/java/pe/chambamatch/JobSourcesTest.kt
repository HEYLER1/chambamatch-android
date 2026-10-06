package pe.chambamatch

import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JobSourcesTest {
    private val detailUrl = "https://www.practicas.pe/oferta-convocatoria-practicante-123.html"
    @Test fun agendaRefreshReadsUpdatedDescriptionAndDeadlineAndKeepsBlockedOffers() = runBlocking {
        val old = JSONObject().put("id", "old").put("fuente", "linkedin").put("url", "https://www.linkedin.com/jobs/view/12345").put("titulo", "Developer").put("descripcion", "Python").put("cierre", "2026-10-10")
        val updated = JobSources { url -> Jsoup.parse("<div class='show-more-less-html__markup'><p>Requisitos: SQL obligatorio.</p><p>Plazo para postular: 19 de octubre de 2026</p></div>", url) }.refresh(old).jobs.single()
        assertEquals("old", updated.text("id"))
        assertTrue("SQL" in updated.text("descripcion"))
        assertEquals("2026-10-19", updated.text("cierre"))
        val blocked = JobSources { error("HTTP 429") }.refresh(old)
        assertTrue(blocked.jobs.isEmpty())
        assertFalse(blocked.complete)
        assertEquals("2026-10-10", old.text("cierre"))
    }
    @Test fun resolvesStateInsteadOfCountryAndDoesNotBroadenInvalidRegion() = runBlocking {
        val visited = java.util.concurrent.CopyOnWriteArrayList<String>()
        val adapter = JobSources { url ->
            visited.add(url)
            val body = if ("typeaheadHits" in url) """[{"id":"105871508","displayName":"São Paulo, Brazil"}]""" else ""
            Jsoup.parse(body, url)
        }
        adapter.search("linkedin", "software", "São Paulo", "empleo", 1, country = "Brazil", regional = true)
        assertTrue(visited.first().contains("geoTypes=ADMIN_DIVISION_1"))
        assertTrue(visited.any { "geoId=105871508" in it })
        assertFalse(visited.any { "geoId=106057199" in it })
        visited.clear()
        val missing = JobSources { url -> visited.add(url); Jsoup.parse("[]", url) }
            .search("linkedin", "software", "Unknown", "empleo", 1, country = "Brazil", regional = true)
        assertFalse(missing.complete)
        assertTrue(missing.jobs.isEmpty())
        assertEquals(1, visited.size)
    }
    @Test fun resolvesCountryOutsideKnownIds() = runBlocking {
        val visited = java.util.concurrent.CopyOnWriteArrayList<String>()
        JobSources { url ->
            visited.add(url)
            Jsoup.parse(if ("typeaheadHits" in url) """[{"id":"101282230","displayName":"Germany"}]""" else "", url)
        }.search("linkedin", "software", "", "empleo", 1, country = "Germany")
        assertTrue(visited.first().contains("geoTypes=COUNTRY_REGION"))
        assertTrue(visited.any { "geoId=101282230" in it })
    }
    @Test fun parsesSpanishDatesAndDeadlineTime() {
        assertEquals("2026-09-15", JobParser.textDate("15 de setiembre de 2026"))
        assertEquals("2026-10-05", JobParser.textDate("05/10/26"))
        assertNull(JobParser.textDate("31 de febrero de 2026"))
        assertEquals("2026-10-05T17:30-05:00", JobParser.deadline("Plazo para postular: 5 de octubre de 2026 hasta las 5:30 p.m. COMO POSTULAR"))
    }
    @Test fun portalKeepsUnknownPublicationSeparateFromDiscovery() {
        val doc = Jsoup.parse("""<h1>Entidad Convocatoria practicante preprofesional de sistemas</h1><p>Requisitos</p><p>Organización: Entidad</p><p>Lugar de prácticas: Puno</p><p>Subvención económica: S/. 1,200.00</p><p>Estudiante de Sistemas. Python</p><p>Plazo para postular: 5 de octubre de 2026</p><p>Te recomendamos: Contador Excel</p>""", detailUrl)
        val job = JobParser.detail(doc, detailUrl, "practicas")
        assertTrue(job.isNull("publicado")); assertFalse(job.isNull("detectado"))
        assertEquals("2026-10-05", job.text("cierre")); assertEquals("Puno", job.text("ubicacion"))
        assertEquals(1200.0, job.getDouble("salario"), 0.0); assertEquals("PEN", job.text("moneda"))
        assertEquals("preprofesional", job.text("tipo")); assertFalse("Contador Excel" in job.text("descripcion"))
    }
    @Test fun jobPostingNestedGraphExtractsConfirmedFields() {
        val doc = Jsoup.parse("""<script type="application/ld+json">{"@graph":[{"@type":"JobPosting","title":"Desarrollador","description":"<p>Python y SQL</p>","datePosted":"2026-10-01","validThrough":"2026-10-08","hiringOrganization":{"name":"Equipo"},"jobLocation":[{"address":{"addressLocality":"Lima"}}],"baseSalary":{"currency":"PEN","value":{"minValue":2000,"unitText":"MONTH"}}}]}</script>""", detailUrl)
        val job = JobParser.detail(doc, detailUrl, "convocatorias")
        assertEquals("Desarrollador", job.text("titulo")); assertEquals("Equipo", job.text("empresa")); assertEquals("Lima", job.text("ubicacion")); assertEquals("2026-10-01", job.text("publicado"))
        assertEquals(2000.0, job.getDouble("salario"), 0.0); assertEquals("mensual", job.text("periodo"))
    }
    @Test fun linkedInCardsDeduplicateAndDoNotInventDescriptionOrAge() {
        val card = """<div class="base-search-card"><a class="base-card__full-link" href="https://www.linkedin.com/jobs/view/practicante-ti-12345?tracking=1"></a><h3 class="base-search-card__title">Practicante TI</h3><h4 class="base-search-card__subtitle">Equipo</h4><span class="job-search-card__location">Lima</span><time datetime="2026-10-04">ayer</time></div>"""
        val jobs = JobParser.linkedInCards(Jsoup.parse(card + card, "https://www.linkedin.com/"))
        assertEquals(1, jobs.size); assertEquals("practicas", jobs.single().text("tipo")); assertEquals("2026-10-04", jobs.single().text("publicado")); assertEquals("", jobs.single().text("descripcion"))
        assertTrue(JobParser.linkedInCards(Jsoup.parse(card.replace("www.linkedin.com/jobs/view", "evil.example/jobs/view"))).isEmpty())
    }
    @Test fun peruLinkedInLinksAreAcceptedAndCanonicalized() {
        val html = """<div class="base-search-card"><a class="base-card__full-link" href="https://pe.linkedin.com/jobs/view/practicante-tecnolog%C3%ADa-12345?tracking=1"></a><h3 class="base-search-card__title">Practicante de tecnología</h3></div>"""
        val job = JobParser.linkedInCards(Jsoup.parse(html, "https://www.linkedin.com/")).single()
        assertEquals("https://www.linkedin.com/jobs/view/practicante-tecnolog%C3%ADa-12345", job.text("url"))
        assertEquals(JobParser.id(job.text("url")), job.text("id"))
        assertEquals("https://www.linkedin.com/jobs/view/12345", linkedInUrl("https://es.linkedin.com/jobs/view/12345"))
        assertEquals("https://www.linkedin.com/jobs/view/12345", linkedInUrl("https://uk.linkedin.com/jobs/view/12345"))
        assertNull(linkedInUrl("https://linkedin.com.evil.example/jobs/view/12345"))
    }
    @Test fun linkedInDetailExtractsDescriptionAndLevel() {
        val card = JSONObject().put("id", "x").put("fuente", "linkedin").put("titulo", "Practicante TI").put("url", "https://www.linkedin.com/jobs/view/12345").put("empresa", "Equipo").put("descripcion", "").put("ubicacion", "Lima")
        val doc = Jsoup.parse("""<div class="show-more-less-html__markup"><p>Estudiante de sistemas. Python y SQL.</p></div><li class="description__job-criteria-item">Employment type Internship</li>""")
        val job = JobParser.linkedInDetail(card, doc)
        assertEquals("Lima", job.text("ubicacion")); assertEquals("preprofesional", job.text("tipo")); assertTrue("Python" in job.text("descripcion")); assertEquals("Equipo", job.text("empresa"))
    }
    @Test fun groupedPortalDiscoveryFollowsObservedLinksAndIsBounded() = runBlocking {
        val visited = java.util.concurrent.CopyOnWriteArrayList<String>()
        val adapter = JobSources { url ->
            visited.add(url)
            val html = when (url) {
                "https://www.practicas.pe/" -> "<a href='/convocatoria-entidad.html'>Entidad</a>"
                "https://www.practicas.pe/convocatoria-entidad.html" -> "<a href='$detailUrl'>Practicante</a><a href='https://evil.example/oferta-trampa'>No seguir</a>"
                detailUrl -> "<h1>Practicante de sistemas</h1><p>Requisitos</p><p>Estudiante. Python</p>"
                else -> error("URL no prevista: $url")
            }
            Jsoup.parse(html, url)
        }
        val result = adapter.search("practicas", "", "", "", 1)
        assertEquals(1, result.jobs.size); assertEquals(3, visited.size); assertFalse(visited.any { "evil" in it })
    }
    @Test fun careerSectionsOutrankBroadLocationSections() = runBlocking {
        val visited = java.util.concurrent.CopyOnWriteArrayList<String>()
        val adapter = JobSources { url ->
            visited.add(url)
            val html = if (url == "https://www.practicas.pe/") """<a href="/lima.html">Lima</a><a href="/otros-lima.html">Lima</a><a href="/sistemas.html">Ingeniería de sistemas</a>""" else """<a href="$detailUrl">Practicante Sistemas</a><h1>Practicante de sistemas</h1>"""
            Jsoup.parse(html, url)
        }
        adapter.search("practicas", "sistemas", "Lima", "practicas", 1)
        assertTrue("https://www.practicas.pe/sistemas.html" in visited)
        assertFalse("https://www.practicas.pe/otros-lima.html" in visited)
    }
    @Test fun sourceFailuresAreVisibleAndNeverFakeJobs() = runBlocking {
        val adapter = JobSources { error("HTTP 429") }
        val result = adapter.search("linkedin", "sistemas", "Puno", "practicas")
        assertTrue(result.jobs.isEmpty()); assertTrue(result.notes.any { "no permitió" in it }); assertTrue(result.notes.any { "0 anuncios" in it })
    }
    @Test fun linkedInLimitsResultsAndPreservesCardsWhenDetailBlocked() = runBlocking {
        val visited = java.util.concurrent.CopyOnWriteArrayList<String>()
        val adapter = JobSources { url ->
            visited.add(url)
            if ("seeMoreJobPostings" !in url) error("HTTP 429")
            Jsoup.parse((1..3).joinToString("") { "<div class='base-search-card'><a class='base-card__full-link' href='https://www.linkedin.com/jobs/view/practicante-$it'></a><h3 class='base-search-card__title'>Practicante TI</h3></div>" }, url)
        }
        val result = adapter.search("linkedin", "ingeniero de sistemas", "Lima", "preprofesional", 2)
        assertEquals(2, result.jobs.size); assertTrue(result.notes.any { "sin descripción" in it }); assertEquals(3, visited.size)
        assertTrue(visited.first().contains("f_JT=I")); assertTrue(visited.first().contains("location=Lima%2C+Peru"))
    }    @Test fun timeoutRetainsAlreadyReadDetails() = runBlocking {
        val adapter = JobSources(budgetMillis = 250) { url ->
            if (url.endsWith("slow.html")) delay(5_000)
            val html = if (url == "https://www.practicas.pe/") """<a href="/oferta-convocatoria-fast.html">Fast</a><a href="/oferta-convocatoria-slow.html">Slow</a>""" else "<h1>Oferta rápida</h1><p>Requisitos Python</p>"
            Jsoup.parse(html, url)
        }
        val result = adapter.search("practicas", "", "", "", 2)
        assertFalse(result.complete); assertEquals(1, result.jobs.size)
        assertTrue(result.notes.any { "tiempo de espera" in it })
    }
    @Test fun userCancellationPropagatesWithoutSuccessResult() = runBlocking {
        var released = false
        val adapter = JobSources { try { delay(5_000); Jsoup.parse("") } finally { released = true } }
        val job = async { adapter.search("practicas", "", "", "") }
        delay(50); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertTrue(released)
    }
    @Test fun linkedinUsesAgeAndModalityBeforeDownloadingDetails() = runBlocking {
        val urls = mutableListOf<String>()
        val adapter = JobSources { url -> urls.add(url); Jsoup.parse("", url) }
        adapter.search("linkedin", "sistemas", "Lima", "practicas", 3, days = 7, modality = "remoto")
        assertTrue(urls.single().contains("f_TPR=r604800")); assertTrue(urls.single().contains("f_WT=2"))
    }

    @Test fun internationalLinkedinQueryDoesNotAppendPeru() = runBlocking {
        val urls = mutableListOf<String>()
        val adapter = JobSources { url -> urls.add(url); Jsoup.parse("", url) }
        adapter.search("linkedin", "software engineer", "Madrid", "empleo", 3, modality = "remoto", country = "España")
        assertTrue(urls.single().contains("location=Madrid%2C+Espa%C3%B1a"))
        assertFalse(urls.single().contains("Peru")); assertTrue(urls.single().contains("geoId=105646813"))
        assertTrue(urls.single().contains("keywords=software+engineer"))
        urls.clear()
        adapter.search("linkedin", "developer", "", "empleo", 3, modality = "remoto", country = "")
        assertTrue(urls.single().contains("location=Worldwide")); assertTrue(urls.single().contains("geoId=92000000")); assertTrue(urls.single().contains("f_WT=2"))
    }

    @Test fun resolvesObservedCountryIdAndRejectsOtherCountries() = runBlocking {
        val urls = mutableListOf<String>()
        val adapter = JobSources { url ->
            urls.add(url)
            val html = if ("/jobs/search/" in url) "<input name='location' value='France'><input name='geoId' value='105015875'>"
            else if ("seeMoreJobPostings" in url) """<div class='base-search-card'><a class='base-card__full-link' href='https://fr.linkedin.com/jobs/view/developer-12345'></a><h3 class='base-search-card__title'>Developer</h3><span class='job-search-card__location'>Paris, France</span></div><div class='base-search-card'><a class='base-card__full-link' href='https://www.linkedin.com/jobs/view/developer-67890'></a><h3 class='base-search-card__title'>Other country</h3><span class='job-search-card__location'>Seattle, United States</span></div>"""
            else "<div class='show-more-less-html__markup'>Remote developer</div>"
            Jsoup.parse(html, url)
        }
        val result = adapter.search("linkedin", "developer", "", "empleo", 1, country = "Francia")
        assertEquals(1, result.jobs.size)
        assertEquals("Paris, France", result.jobs.single().text("ubicacion"))
        assertTrue(urls.any { "geoId=105015875" in it })
        assertTrue(result.notes.any { "otro país" in it })
    }

    @Test fun chileUsAndCanadaAliasesUseVerifiedGeographies() = runBlocking {
        for ((name, expected) in listOf("Chile" to "104621616", "EEUU" to "103644278", "USA" to "103644278", "Canadá" to "101174742", "canda" to "101174742")) {
            val urls = mutableListOf<String>()
            val adapter = JobSources { url -> urls.add(url); Jsoup.parse("", url) }
            val country = searchCountry(defaultFilters().put("ambito", "pais").put("pais", name))
            adapter.search("linkedin", "developer", "", "empleo", 1, country = country)
            assertEquals(1, urls.size); assertTrue(urls.single().contains("geoId=$expected"))
            assertFalse(urls.single().contains("location=Peru"))
        }
        assertFalse(CountryScope.isKnownCountry("Ch")); assertFalse(CountryScope.isKnownCountry("Ca"))
        assertEquals("Chile", searchCountry(defaultFilters().put("ubicacion", "Chile")))
        assertEquals("United States", searchCountry(defaultFilters().put("ubicacion", "EEUU")))
    }

}
