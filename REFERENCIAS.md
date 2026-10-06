> Investigación del proyecto original [peru-job-finder](https://github.com/HEYLER1/peru-job-finder). Las referencias a archivos Python describen esa versión anterior; este repositorio contiene únicamente la aplicación Android en Kotlin.

# Referencias para la app de empleo

Investigación inicial: 2 de octubre de 2026. Se revisó documentación pública; no se instalaron ni probaron estos proyectos.

## Repositorios seleccionados

| Repositorio | Qué aporta | Idea para nuestra app |
| --- | --- | --- |
| [JobSync](https://github.com/Gsync/jobsync) | Gestión de postulaciones, importación de CV, comparación con ofertas y descubrimiento mediante APIs de bolsas de empresas. Licencia MIT. | Panel de ofertas recomendadas, perfil editable y seguimiento de postulaciones. |
| [JobSpy](https://github.com/speedyapply/JobSpy) | Biblioteca Python que reúne ofertas de LinkedIn, Indeed y otras fuentes. Licencia MIT. | Adaptadores de fuentes que conviertan las ofertas a nuestro modelo `Oferta`. |
| [Resume Matcher](https://github.com/srbhr/Resume-Matcher) | CV maestro, análisis de coincidencias y adaptación del CV a una oferta; modelos locales o remotos. Licencia Apache-2.0. | Perfil único y explicación de coincidencias; adaptación del CV como etapa posterior. |
| [AI Resume Matcher](https://github.com/Jismeet/AI-Resume-Matcher) | Ranking híbrido con habilidades, similitud textual y semántica, experiencia y educación. | Evaluación por dimensiones y lista de habilidades presentes y faltantes. |

JobSpy documenta soporte para Perú en Indeed y búsquedas globales por ubicación en LinkedIn. También documenta bloqueos y límites de solicitudes; el soporte declarado no confirma que una búsqueda funcione hoy. Antes de integrarlo, probar consultas pequeñas y conservar importación manual como alternativa.

## Propuesta de primera versión

### Fuentes prioritarias y antigüedad

Por solicitud del usuario, buscar en estas tres fuentes:

- [Convocatorias de Trabajo](https://www.convocatoriasdetrabajo.com/).
- [Prácticas.pe](https://www.practicas.pe/).
- LinkedIn, evaluando JobSpy como conector.

Crear un adaptador independiente para cada fuente y unificar los resultados. Revisar el detalle de cada anuncio y sus bases cuando sean necesarias para conocer los requisitos completos. En prácticas, comparar carrera, ciclo, condición de estudiante/egresado y tiempo desde el egreso.

Registrar por separado fecha de publicación, precisión de esa fecha (exacta/aproximada/desconocida), fecha de primera detección, fecha de última revisión, inicio y cierre de postulaciones (incluida hora si existe), estado declarado por la fuente y enlace original. Calcular antigüedad y tiempo restante usando America/Lima para fuentes peruanas. No presentar la fecha de primera detección como fecha de publicación ni usar la fecha del buscador como prueba de antigüedad.

Mostrar filtros de últimas 24 horas, 3, 7, 14 y 30 días, además de anuncios sin fecha conocida. Mostrar estados: próxima apertura, en plazo, vencida, cerrada por la fuente o vigencia desconocida. Si hay discrepancias, señalarla y revisar el cronograma original. Ocultar vencidas por defecto, conservándolas para consulta. Separar compatibilidad, antigüedad y urgencia; permitir ordenar por cada una.

En resultados indexados de Prácticas.pe se observó una etiqueta «Vigente» junto a un plazo ya pasado al 2 de octubre de 2026. Esto puede deberse a una copia desactualizada: la app debe consultar la página y calcular el plazo, sin depender únicamente de la etiqueta o del buscador. Ejemplo: https://www.practicas.pe/oferta-convocatoria-011-practicante-centro-estudios-constitucionales-tribunal-constitucional-lima-69538.html

1. Cargar un CV o completar un perfil: experiencia por cargo, habilidades, estudios, idiomas y preferencias.
2. Revisar y corregir los datos extraídos antes de utilizarlos.
3. Importar ofertas pegando su descripción o desde una fuente conectada.
4. Comparar cada requisito con evidencia del perfil y marcar: cumple, no cumple o falta información.
5. Separar requisitos obligatorios de deseables. Una coincidencia textual alta no debe ocultar un requisito obligatorio incumplido.
6. Ordenar las ofertas compatibles y permitir filtrar por ubicación, modalidad, salario, idioma y experiencia.
7. Mostrar motivos, requisitos faltantes, enlace original y estado de postulación.

La puntuación debe expresar compatibilidad estimada, no probabilidad de contratación. No inventar habilidades ni considerar cumplido un requisito sin evidencia. Los requisitos ausentes en la oferta se distinguen de los datos desconocidos del candidato.

## Dirección recomendada

Tomar JobSync como referencia del flujo, JobSpy como posible conector y AI Resume Matcher como referencia conceptual del ranking. Construir sobre los módulos Python existentes en `jobbot/modelo.py` y `jobbot/geo.py`, después de revisar su comportamiento. Empezar con comparación de perfil y ofertas importadas; conectar búsquedas reales después.

Pendiente para personalizar: CV o experiencia y habilidades, cargos objetivo, países o ciudades, modalidad e idiomas. Antes de reutilizar código, revisar la licencia del archivo y versión concretos.


## Mejora del 5 de octubre de 2026

Diagnóstico: había ofertas de LinkedIn guardadas clasificadas como empleo aunque fueran prácticas; las palabras del cargo se combinaban como condiciones obligatorias; el listado inicial de los portales peruanos cubría muy pocas convocatorias. Un cero visible no distinguía falta de resultados de exclusión por filtros.

Fuentes técnicas revisadas:

- [JobSpy, documentación y código](https://github.com/speedyapply/JobSpy): `fetch_description` reemplaza el parámetro antiguo `linkedin_fetch_description`; la consulta usa cargo y ubicación y puede sufrir límites de la fuente. Se verificó además la firma de la versión instalada.
- [ConFit: Improving Resume-Job Matching using Data Augmentation and Contrastive Learning](https://arxiv.org/abs/2401.16349): investigación específica de ranking CV–empleo que motiva distinguir el problema de relevancia del simple conteo de palabras. No se implementó ni se entrenó ConFit en esta app.
- [A Contextual-Bandit Approach to Personalized News Article Recommendation](https://arxiv.org/abs/1003.0146): estudia adaptación al contenido y al feedback. Su aplicación a preferencias de empleo es una decisión de diseño de este proyecto; no se trasladan sus resultados ni se implementa LinUCB.

Implementación elegida: reglas explícitas para perfil y requisitos, afinidad por palabras y grupos de áreas, y una regresión logística local entrenada únicamente con valoraciones del usuario. El componente aprendido modifica moderadamente el orden y no supera una brecha detectada. No hay modelo semántico preentrenado, dataset de contratación ni precisión validada.

Las valoraciones se guardan en el mismo archivo local ignorado por Git. Deshacer una valoración reconstruye el modelo sin esa etiqueta. El entrenamiento utiliza características del empleo, no atributos personales sensibles del candidato.

## Android autónomo — 5 de octubre de 2026

La implementación en `android/` traslada los filtros, la evaluación de fechas y requisitos y la regresión logística local a Kotlin. Usa almacenamiento privado con `AtomicFile` y lectura HTML con [jsoup](https://jsoup.org/download). No necesita el servidor Python. Se verificó la persistencia y el uso sin servidor con pruebas instrumentadas; las tres fuentes también se consultaron directamente desde Android. El lector de LinkedIn reconoce sus enlaces peruanos `pe.linkedin.com` y normaliza el destino del anuncio, sin utilizar credenciales ni mecanismos para eludir bloqueos.

### Búsquedas internacionales y tarjetas Android (octubre de 2026)

- [Filtros de búsqueda de LinkedIn](https://www.linkedin.com/help/linkedin/answer/a507441/filter-and-sort-job-search-results?lang=en): ubicación por ciudad o país y modalidad como parámetros separados. Se elimina el sufijo Perú impuesto por el lector anterior; los portales peruanos mantienen su alcance local.
- [Empleos remotos en LinkedIn](https://www.linkedin.com/help/linkedin/answer/a508610): la modalidad remota no demuestra que el postulante pueda trabajar desde cualquier país. La app conserva esa incertidumbre.
- [Cards en Jetpack Compose](https://developer.android.com/develop/ui/compose/components/card): tarjetas con agrupación de información y acciones. La referencia visual proporcionada por el usuario guía los colores, la insignia de cierre, contrato, vacantes y compartir; no se fabrican logos ni vacantes.

La prueba internacional detectó dos fallos adicionales: el listado público necesitaba el identificador geográfico observado en la búsqueda, y los enlaces `es.linkedin.com` eran rechazados por el lector anterior. Se normalizan enlaces regionales de LinkedIn al dominio principal y se descartan países confirmados distintos del solicitado. Los países sin identificador conocido se resuelven a partir de los campos visibles de la búsqueda pública; si no se confirma la ubicación, se informa y no se sustituye por otro país.

Identificadores públicos contrastados en 2.3.0: [Chile, oferta enlazada por Universidad de Chile](https://diqbm.uchile.cl/plataforma-laboral/ofertas-laborales/16---global-talent) (`104621616`), [Canadá, convocatoria de Transdev publicada por McGill](https://www.mcgill.ca/continuingstudies/files/continuingstudies/job_description_-_senior_advisor_of_corporate_communication_-_transdev_ca_-_english.pdf) (`101174742`) y [Estados Unidos, recursos de Boston University](https://www.bu.edu/archaeology/job-resources/) (`103644278`). Los identificadores se validan también con consultas reales instrumentadas desde Android.

### Catálogo mundial y regiones — 6 de octubre de 2026

[Countries States Cities Database](https://github.com/dr5hn/countries-states-cities-database) aporta los 250 países y territorios y 5.317 subdivisiones del selector. Se combinaron `json/countries.json` (códigos y traducciones españolas) y `json/countries+states.json` (regiones). La selección derivada se distribuye con [licencia ODbL 1.0](https://github.com/dr5hn/countries-states-cities-database/blob/master/LICENSE), atribución y fecha en los assets Android.

El lector usa el [selector geográfico público de LinkedIn](https://www.linkedin.com/jobs-guest/api/typeaheadHits?query=Germany&typeaheadType=GEO&geoTypes=COUNTRY_REGION) para resolver países, y `ADMIN_DIVISION_1,ADMIN_DIVISION_2` para estados y provincias. La respuesta pública de São Paulo confirma `105871508` como estado de Brasil, distinto de su ciudad; Alemania confirma `101282230`. Si no se resuelve la región, se informa sin sustituirla por una búsqueda nacional. Estos endpoints observados pueden cambiar y no constituyen una API con disponibilidad garantizada.
