package pe.chambamatch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RecommendationEngineTest {
    private val now = Instant.parse("2026-10-05T17:00:00Z")
    private fun job(id: String = "a", title: String = "Practicante de sistemas", desc: String = "Estudiante de Ingeniería de Sistemas. Python y SQL.") = JSONObject()
        .put("id", id).put("fuente", "linkedin").put("titulo", title).put("descripcion", desc).put("tipo", "empleo").put("ubicacion", "San Isidro, Peru").put("publicado", JSONObject.NULL).put("cierre", JSONObject.NULL)
    private fun results(jobs: List<JSONObject> = listOf(job()), profile: JSONObject = JSONObject(), prefs: JSONObject = defaultFilters(), feedback: JSONObject = JSONObject()) = RecommendationEngine.results(jobs, profile, prefs, feedback, now)
    @Test fun homeRecommendsSimilarJobsWithoutEightyPercentCutoff() {
        val candidate = job(title = "Developer Python", desc = "Requisitos: Python obligatorio.")
        val profile = JSONObject().put("habilidades", "Python").put("cargos_objetivo", "Developer Python")
        assertEquals(1, results(listOf(candidate), profile).rows("recomendaciones").size)
        assertTrue(results(listOf(candidate), JSONObject(), defaultFilters().put("cargo", "Developer Python")).rows("recomendaciones").isEmpty())
        assertEquals(1, results(listOf(candidate), JSONObject().put("habilidades", "Python")).rows("recomendaciones").size)
    }
    @Test fun closestCareerRanksBeforeIncidentalSkillsEvenWithAnExperienceGap() {
        val profile = JSONObject().put("habilidades", "Python, SQL").put("cargos_objetivo", "Developer Python").put("meses_experiencia", "0")
        val close = job("a", "Developer Python", "Requisitos: Python obligatorio. Experiencia general de 2 años.")
        val loose = job("b", "Analista de datos", "Requisitos: SQL obligatorio.")
        val ranked = results(listOf(loose, close), profile).rows("recomendaciones")
        assertEquals(listOf("a", "b"), ranked.map { it.text("id") })
        assertTrue(ranked.first().optInt("puntaje") < ranked.last().optInt("puntaje"))
    }
    @Test fun similarOffersWithGapsRemainVisibleButUnrelatedAndExpiredDoNot() {
        val profile = JSONObject().put("habilidades", "Python").put("meses_experiencia", "0")
        val related = job("a", "Developer Python", "Requisitos: Python. Experiencia general de 2 años.")
        val unrelated = job("b", "Contador", "Requisitos: Contabilidad.")
        val expired = related.copyJson().put("id", "c").put("cierre", "2020-01-01")
        val list = results(listOf(related, unrelated, expired), profile).rows("recomendaciones")
        assertEquals(listOf("a"), list.map { it.text("id") })
        assertTrue(list.single().rows("requisitos").any { it.text("estado") == "brecha" })
    }
    @Test fun applicationDatesAreNotApplicantRequirementsAndChangedEvidenceGetsNewChecklistKey() {
        val candidate = job(desc = "Requisitos:\nPython obligatorio.\nPlazo para postular: 19 de octubre de 2026")
        val profile = JSONObject().put("habilidades", "Python")
        val checks = RecommendationEngine.requirements(candidate, profile)
        assertEquals(1, checks.size)
        val changed = RecommendationEngine.requirements(job(desc = "Requisitos: Python avanzado obligatorio."), profile)
        assertNotEquals(checks.single().text("clave"), changed.single().text("clave"))
    }
    @Test fun additionalApplicantSkillsDoNotLowerFitToActualRequirements() {
        val candidate = job(desc = "Requisitos: Python obligatorio.")
        val simple = results(listOf(candidate), JSONObject().put("habilidades", "Python")).rows("ofertas").single().getInt("puntaje")
        val broad = results(listOf(candidate), JSONObject().put("habilidades", "Python, SQL, Java, Excel, Docker")).rows("ofertas").single().getInt("puntaje")
        assertEquals(simple, broad)
    }
    @Test fun requiredMissingSkillIsNotHiddenByHighKeywordAffinity() {
        val candidate = job(desc = "Requisitos:\nDominio de Python y SQL.\nDocker obligatorio.\nAWS deseable.")
        val profile = JSONObject().put("habilidades", "Python, SQL").put("carrera", "Sistemas")
        val scored = results(listOf(candidate), profile, defaultFilters().put("ajustar_perfil", false)).rows("ofertas").single()
        assertTrue(scored.rows("requisitos").any { it.text("nombre") == "Habilidad: Docker" && it.text("estado") == "brecha" && it.optBoolean("obligatorio") })
        assertTrue(scored.rows("requisitos").any { it.text("nombre") == "Habilidad: AWS" && !it.optBoolean("obligatorio") })
        assertTrue(scored.optInt("puntaje") <= 45)
        assertTrue(results(listOf(candidate), profile).rows("ofertas").isEmpty())
    }
    @Test fun desirableSkillsDoNotExcludeAndDutiesAreNotRequirements() {
        val candidate = job(desc = "Requisitos:\nPython obligatorio.\nDocker deseable.\nFunciones:\nUsar AWS y Java.")
        val checks = RecommendationEngine.requirements(candidate, JSONObject().put("habilidades", "Python"))
        assertFalse(checks.any { it.text("nombre") == "Habilidad: AWS" })
        assertTrue(results(listOf(candidate), JSONObject().put("habilidades", "Python")).rows("ofertas").isNotEmpty())
    }
    @Test fun explicitAcademicConditionAndLanguageAreCompared() {
        val candidate = job(desc = "Requisitos:\nTitulado en Ingeniería de Sistemas.\nInglés avanzado requerido.")
        val checks = RecommendationEngine.requirements(candidate, JSONObject().put("situacion", "Estudiante").put("experiencia", "Inglés intermedio").put("carrera", "Sistemas"))
        assertTrue(checks.any { it.text("nombre") == "Formación y condición" && it.text("estado") == "brecha" })
        assertTrue(checks.any { it.text("nombre") == "Idioma: Inglés" && it.text("estado") == "brecha" })
    }
    @Test fun trackingIgnoresSearchFiltersAndKeepsExpiredLikedJobs() {
        val expired = job("a").put("cierre", "2020-01-01")
        val liked = results(listOf(expired), prefs = defaultFilters().put("ubicacion", "Puno"), feedback = JSONObject().put("a", "interesa"))
        assertTrue(liked.rows("ofertas").isEmpty())
        assertEquals("vencida", liked.rows("seguimiento").single().text("estado"))
    }
    @Test fun deadlineCanComeFromDescriptionButNotContractDuration() {
        assertEquals("2026-10-19", RecommendationEngine.normalize(job(desc = "Plazo para postular: 19 de octubre de 2026")).text("cierre"))
        assertEquals("", RecommendationEngine.normalize(job(desc = "Contrato de seis meses desde octubre de 2026")).text("cierre"))
    }
    @Test fun reclassifiesInternshipLevels() {
        assertEquals("preprofesional", RecommendationEngine.classify(job(title = "Practicante Pre Profesional TI")))
        assertEquals("profesional", RecommendationEngine.classify(job(title = "Practicante profesional TI")))
        assertEquals("practicas", RecommendationEngine.classify(job(desc = "Soporte técnico")))
    }
    @Test fun limaAcceptsDistrictsAndRelatedCareers() {
        val prefs = defaultFilters().put("cargo", "ingeniero de sistemas").put("ubicacion", "Lima").put("tipo", "preprofesional")
        assertEquals(1, results(prefs = prefs).rows("ofertas").size)
    }
    @Test fun punoDoesNotSilentlyIncludeLima() {
        val result = results(prefs = defaultFilters().put("ubicacion", "Puno"))
        assertEquals(0, result.rows("ofertas").size)
        assertEquals(1, result.getJSONObject("ocultas").getInt("ubicacion"))
        assertTrue("ubicacion" in result.rows("alternativas").single().strings("excluida_por"))
    }
    @Test fun unknownRequirementsAreNotClaimsOfCompliance() {
        val result = results(jobs = listOf(job(title = "Practicante preprofesional", desc = "Experiencia mínima de 2 años.")))
        assertTrue(result.rows("ofertas").single().rows("requisitos").all { it.text("estado") == "por confirmar" })
        assertTrue(result.rows("ofertas").single().isNull("puntaje"))
    }
    @Test fun declaredGapCapsScoreEvenWithPositiveFeedback() {
        val job = job(title = "Practicante preprofesional", desc = "Experiencia mínima de 2 años. Python")
        val profile = JSONObject().put("situacion", "Egresado").put("habilidades", "Python").put("meses_experiencia", "0")
        val candidate = results(listOf(job), profile, prefs = defaultFilters().put("ajustar_perfil", false), feedback = JSONObject().put("a", "interesa")).rows("ofertas").single()
        assertTrue(candidate.getInt("puntaje") <= 45)
        assertTrue(candidate.rows("requisitos").any { it.text("estado") == "brecha" })
    }
    @Test fun learningGeneralizesAndUndoRemovesItsInfluence() {
        val jobs = listOf(job("a"), job("b", "Practicante marketing", "Publicidad campañas ventas"), job("c"))
        val feedback = JSONObject().put("a", "interesa").put("b", "no_interesa")
        val learned = results(jobs, feedback = feedback).rows("ofertas").first { it.text("id") == "c" }
        assertTrue(learned.getDouble("aprendizaje") > 0)
        val reset = results(jobs).rows("ofertas").first { it.text("id") == "c" }
        assertEquals(0.0, reset.getDouble("aprendizaje"), 0.0); assertTrue(reset.isNull("puntaje"))
    }
    @Test fun incidentalAccountingSystemsDoNotMatchTechnology() {
        val result = results(jobs = listOf(job(title = "Practicante contabilidad", desc = "Usar sistemas contables y realizar pagos.")), prefs = defaultFilters().put("cargo", "ingeniero de sistemas"))
        assertEquals(0, result.rows("ofertas").size)
    }
    @Test fun incompleteProfileCannotReceivePerfectScore() {
        val result = results(profile = JSONObject().put("cargos_objetivo", "ingeniero de sistemas")).rows("ofertas").single()
        assertTrue(result.getInt("puntaje") <= 30)
    }
    @Test fun programmingLanguagesPreserveDistinctSymbols() {
        val result = results(listOf(job(desc = "JavaScript y C++")), JSONObject().put("habilidades", "Java, C++, C#")).rows("ofertas").single()
        assertEquals(listOf("C++"), result.strings("coincidencias"))
    }
    @Test fun publicationAgeAndDateOnlyDeadlineUseLima() {
        val candidate = job().put("publicado", "2026-10-03").put("cierre", "2026-10-05")
        val assessed = RecommendationEngine.evaluate(candidate, now)
        assertEquals(0, RecommendationEngine.calendarDaysRemaining(candidate, now))
        assertEquals(14, RecommendationEngine.calendarDaysRemaining(candidate.copyJson().put("cierre", "2026-10-19"), now))
        assertEquals("en plazo", assessed.text("estado"))
        assertEquals(2.5, assessed.getDouble("antiguedad"), 0.0001)
        assertEquals("vencida", RecommendationEngine.evaluate(candidate, Instant.parse("2026-10-06T05:00:00Z")).text("estado"))
    }
    @Test fun exactDeadlineAndInvalidDatesAreConservative() {
        assertEquals("vencida", RecommendationEngine.evaluate(job().put("cierre", "2026-10-05T11:00:00-05:00"), now).text("estado"))
        assertEquals("vigencia desconocida", RecommendationEngine.evaluate(job().put("cierre", "2026-99-99"), now).text("estado"))
        assertNull(RecommendationEngine.date("2026-02-30"))
    }
    @Test fun unknownDateTypeAndSalaryRespectExplicitPreferences() {
        val candidate = job(desc = "Soporte técnico")
        assertEquals(0, results(listOf(candidate), prefs = defaultFilters().put("sinfecha", false)).rows("ofertas").size)
        assertEquals(0, results(listOf(candidate), prefs = defaultFilters().put("tipo", "preprofesional").put("desconocidos", false)).rows("ofertas").size)
        assertEquals(0, results(listOf(candidate), prefs = defaultFilters().put("sueldo", "1000").put("desconocidos", false)).rows("ofertas").size)
    }
    @Test fun salaryFiltersOnlyComparableMonthlySoles() {
        val candidate = job().put("salario", 1200).put("moneda", "PEN").put("periodo", "mensual")
        assertEquals(1, results(listOf(candidate), prefs = defaultFilters().put("sueldo", "1000")).rows("ofertas").size)
        assertEquals(0, results(listOf(candidate), prefs = defaultFilters().put("sueldo", "1500")).rows("ofertas").size)
        assertEquals(0, results(listOf(candidate.put("moneda", "USD")), prefs = defaultFilters().put("sueldo", "1000").put("desconocidos", false)).rows("ofertas").size)
    }
    @Test fun specificExperienceCannotBeSatisfiedByGeneralMonths() {
        val job = RecommendationEngine.normalize(job(desc = "Experiencia específica de 2 años en el sector público."))
        val checks = RecommendationEngine.requirements(job, JSONObject().put("meses_experiencia", "60"))
        assertTrue(checks.all { it.text("estado") == "por confirmar" })
    }
    @Test fun decimalYearsConvertToMonths() {
        val job = RecommendationEngine.normalize(job(desc = "Experiencia general: 1.5 años."))
        val checks = RecommendationEngine.requirements(job, JSONObject().put("meses_experiencia", "12"))
        assertEquals("brecha", checks.single().text("estado"))
        assertTrue("18.0 meses" in checks.single().text("evidencia"))
    }
    @Test fun feedbackNeverRelaxesFiltersOrIncludesExpiredAlternatives() {
        val candidate = job().put("cierre", "2020-01-01")
        val result = results(listOf(candidate), feedback = JSONObject().put("a", "interesa"))
        assertTrue(result.rows("ofertas").isEmpty()); assertTrue(result.rows("alternativas").isEmpty())
        assertEquals(0, results(prefs = defaultFilters().put("fuentes", JSONArray())).rows("ofertas").size)
    }
    @Test fun recentOrderUsesPublishedDateNotDiscoveryDate() {
        val old = job("old").put("publicado", "2026-09-01").put("detectado", "2026-10-05")
        val newer = job("new").put("publicado", "2026-10-04")
        val unknown = job("unknown")
        assertEquals(listOf("new", "old", "unknown"), results(listOf(old, unknown, newer), prefs = defaultFilters().put("orden", "recientes")).rows("ofertas").map { it.text("id") })
    }
    @Test fun experienceInProfileTextIsComparedWithRequirement() {
        val checks = RecommendationEngine.requirements(job(desc = "Experiencia general: seis (6) meses."), JSONObject().put("experiencia", "Tengo un año de experiencia."))
        assertEquals("compatible", checks.single().text("estado"))
        assertTrue(checks.single().text("evidencia").contains("12.0 meses"))
        assertEquals("brecha", RecommendationEngine.requirements(job(desc = "Experiencia mínima de seis meses."), JSONObject().put("experiencia", "Sin experiencia")).single().text("estado"))
    }
    @Test fun jobRequirementNeverBecomesApplicantsExperience() {
        val check = RecommendationEngine.requirements(job(desc = "Experiencia general de 6 meses"), JSONObject()).single()
        assertEquals("por confirmar", check.text("estado"))
        assertTrue(check.text("evidencia").contains("Tu perfil"))
        assertNull(RecommendationEngine.experienceMonths("Tengo 6 meses de experiencia y 2 años de experiencia"))
        assertNull(RecommendationEngine.experienceMonths("2 años de experiencia en Java", true))
    }
    @Test fun fitFilterExcludesKnownGapsAndHonorsUnknowns() {
        val candidate = job(title = "Practicante preprofesional", desc = "Experiencia mínima de 6 meses.")
        val profile = JSONObject().put("situacion", "Estudiante").put("experiencia", "Tengo 3 meses de experiencia")
        assertTrue(results(listOf(candidate), profile).rows("ofertas").isEmpty())
        assertTrue(results(listOf(candidate), prefs = defaultFilters().put("requisitos_confirmados", true)).rows("ofertas").isEmpty())
        assertEquals(1, results(listOf(candidate), prefs = defaultFilters()).rows("ofertas").size)
    }
    @Test fun internationalScopeDoesNotKeepPeruvianPortalJobs() {
        val prefs = defaultFilters().put("ambito", "pais").put("pais", "España")
        val local = job().put("fuente", "convocatorias")
        val international = job("b").put("pais_busqueda", "España").put("ubicacion", "Madrid, Spain")
        assertEquals(listOf("b"), results(listOf(local, international), prefs = prefs).rows("ofertas").map { it.text("id") })
        assertEquals("Worldwide", searchPlace("", ""))
        assertEquals("Madrid, España", searchPlace("Madrid", "España"))
    }

}
