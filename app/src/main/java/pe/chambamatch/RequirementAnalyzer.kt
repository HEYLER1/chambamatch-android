package pe.chambamatch

import org.json.JSONObject

/** Evidence is quoted from the vacancy; unknown profile information is never inferred. */
object RequirementAnalyzer {
    private val skills = listOf("Python", "SQL", "JavaScript", "TypeScript", "Java", "Kotlin", "C++", "C#", "Excel", "Power BI", "SAP", "Odoo", "React", "Angular", "Docker", "Git", "Linux", "AWS", "Azure", "Figma", "AutoCAD")
    private val mandatory = Regex("requisit|requirements|qualifications|buscamos|you hold|you have|conheciment|obligatori|indispens|must|minimum|required|necessari|exigido|conocimient|dominio|proficien|experiencia|experience|experiencia|habilidades|skills")
    private val optional = Regex("deseable|desejav|desirable|preferred|nice to have|valorable|diferencial|plus|opcional")
    fun clauses(text: String): List<String> = text.split(Regex("\\n+|(?<=[.;])\\s+(?=[A-ZÁÉÍÓÚ0-9])")).map(String::trim).filter { it.isNotBlank() }.take(150)
    private fun level(text: String): Int? = when {
        Regex("\\b(?:c2|c1|fluent|fluido|fluente|native|nativo|expert)\\b").containsMatchIn(text) -> 3
        Regex("\\b(?:b2|avanzado|advanced)\\b").containsMatchIn(text) -> 2
        Regex("\\b(?:b1|intermedio|intermediate)\\b").containsMatchIn(text) -> 1
        Regex("\\b(?:a1|a2|basico|basic)\\b").containsMatchIn(text) -> 0
        else -> null
    }
    fun extra(job: JSONObject, profile: JSONObject): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val description = job.text("descripcion")
        val declared = profile.text("habilidades") + " " + profile.text("experiencia")
        var section = false
        var desirableSection = false
        val found = mutableSetOf<String>()
        fun add(name: String, state: String, evidence: String, desirable: Boolean = false) {
            if (found.add(name)) result.add(JSONObject().put("nombre", name).put("clave", RecommendationEngine.canon(name) + ":" + JobParser.id(evidence.substringBefore("\n"))).put("estado", state).put("obligatorio", !desirable).put("evidencia", evidence.take(550)))
        }
        for (line in clauses(description)) {
            val n = RecommendationEngine.canon(line)
            if (Regex("^(?:plazo para postular|application deadline|closing date|vigente hasta|fecha de|remuneracion|subvencion|salario|sueldo|vacantes|como postular|how to apply|contacto)\\b").containsMatchIn(n)) { section = false; desirableSection = false; continue }
            if (n in listOf("deseable", "deseables", "preferred", "nice to have", "desirable")) { section = true; desirableSection = true }
            if (n.length < 90 && mandatory.containsMatchIn(n)) { section = true; desirableSection = optional.containsMatchIn(n) }
            if (Regex("^(funciones|responsibilities|beneficios|benefits|ofrecemos|sobre nosotros|about us|atividades)\\b").containsMatchIn(n)) { section = false; desirableSection = false }
            val isRequirement = section || mandatory.containsMatchIn(n) || optional.containsMatchIn(n)
            val desirable = desirableSection || optional.containsMatchIn(n)
            if (isRequirement && !Regex("no (?:se )?requiere|not required|nao requer").containsMatchIn(n)) {
                for (skill in skills) if (RecommendationEngine.contains(line, skill)) {
                    val level = Regex("basico|basic|intermedio|intermediate|avanzado|advanced|expert").find(n)?.value
                    val present = RecommendationEngine.contains(declared, skill) || skill == "Power BI" && RecommendationEngine.contains(declared, "PowerBI")
                    val profileSkills = profile.text("habilidades")
                    val state = when { present && level == null -> "compatible"; present -> "por confirmar"; profileSkills.isBlank() -> "por confirmar"; else -> "brecha" }
                    add("Habilidad: $skill", state, "Anuncio: $line\n" + if (present) { if (level == null) "Declarada en tu perfil." else "La mencionas; confirma el nivel exigido ($level)." } else "No consta en tus habilidades declaradas.", desirable)
                }
                for ((name, terms) in listOf("Inglés" to listOf("ingles", "english", "ingles"), "Portugués" to listOf("portugues", "portuguese"), "Español" to listOf("espanol", "spanish"))) if (terms.any { RecommendationEngine.contains(n, it) }) {
                    val languageLines = clauses(declared.replace(',', '\n')).filter { clause -> terms.any { RecommendationEngine.contains(RecommendationEngine.canon(clause), it) } }
                    val language = languageLines.isNotEmpty()
                    val requiredLevel = level(n)
                    val actualLevel = languageLines.mapNotNull { level(RecommendationEngine.canon(it)) }.maxOrNull()
                    val state = when { !language -> "por confirmar"; requiredLevel == null -> "compatible"; actualLevel == null -> "por confirmar"; actualLevel >= requiredLevel -> "compatible"; else -> "brecha" }
                    add("Idioma: $name", state, "Anuncio: $line\n" + if (language) "Idioma declarado en tu perfil; nivel del anuncio: ${requiredLevel ?: "sin indicar"}, nivel del perfil: ${actualLevel ?: "sin indicar"}. Revisa si exige certificado." else "Tu perfil no indica este idioma.", desirable)
                }
                if (Regex("autorizacion|permiso de trabajo|work authorization|authorized to work|right to work|residen|visa|colegiatur|habilitacion|licencia de conducir|driving licen|certificad").containsMatchIn(n)) add("Documentos y elegibilidad", "por confirmar", "Anuncio: $line\nConfirma esta condición o documento antes de postular.", desirable)
            }
            if ((isRequirement || Regex("^(?:egresad|bachiller|titulad|estudiant|bachelor|degree|graduate|student|graduacao|cursando)").containsMatchIn(n)) && Regex("(?:egresad|bachiller|titulad|estudiant|graduate|bachelor|degree|student|graduacao|cursando)").containsMatchIn(n) && !Regex("benefici|beca|scholarship").containsMatchIn(n)) {
                val situation = RecommendationEngine.canon(profile.text("situacion"))
                val allowed = listOf("estudiante", "egresado", "bachiller", "titulado").filter { when(it) {
                    "estudiante" -> Regex("estudiant|student|cursando").containsMatchIn(n)
                    "egresado" -> Regex("egresad|graduate").containsMatchIn(n)
                    "bachiller" -> Regex("bachiller|bachelor").containsMatchIn(n)
                    else -> Regex("titulad|degree|graduacao").containsMatchIn(n)
                } }
                val matches = situation in allowed || situation == "titulado" && allowed.any { it in listOf("egresado", "bachiller") } || situation == "bachiller" && "egresado" in allowed
                add("Formación y condición", if (situation.isBlank()) "por confirmar" else if (matches) "compatible" else "brecha", "Anuncio: $line\nTu condición: ${profile.text("situacion").ifBlank { "sin indicar" }}.", desirable)
                if (Regex("sistemas|informatica|software|computer science|contabilidad|accounting|administracion|administration|economia|economics|marketing|derecho|law|civil|industrial").containsMatchIn(n)) {
                    val career = profile.text("carrera")
                    val hit = career.isNotBlank() && RecommendationEngine.tokens(career).intersect(RecommendationEngine.tokens(line)).isNotEmpty()
                    add("Carrera", if (hit) "compatible" else "por confirmar", "Formación publicada: $line\n" + if (career.isBlank()) "Completa tu carrera en Perfil." else "Tu carrera: $career. Revisa equivalencias y carreras afines.", desirable)
                }
            }
            if (isRequirement && line.length > 12 && result.none { line in it.text("evidencia") } && !Regex("^(requisitos|requirements|qualifications)\\s*:?$").matches(n)) {
                add("Otro requisito: ${line.take(45)}", "por confirmar", "Anuncio: $line\nRevisa esta condición con las bases y tu perfil.", desirable)
            }
            if (Regex("experiencia especifica|specific experience|\\d+\\s+(?:anos|years|meses|months).*\\b(?:en|in)\\b").containsMatchIn(n)) add("Experiencia específica", "por confirmar", "Anuncio: $line\nLos meses totales del perfil no acreditan experiencia en esta función.", desirable)
        }
        return result
    }
}
