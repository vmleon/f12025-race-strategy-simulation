# TFG Delta Review 01 — Changes ready to apply to the manuscript

**Generated:** 2026-04-17
**Review cycle:** partial delivery (8 April 2026), reviewed by Thiago Silva on 14 April 2026 (email + annotated docx).
**Manuscript under review:** `docs/TFG_Victor_Martin_Alvarez.pdf`
**Annotated source:** `~/Downloads/Entrega 1_TFG_Victor_Martin_Alvarez.docx` — DRM-locked; inline comments were transcribed manually by Victor on 2026-04-15.

This document collects every change that is **ready to apply** to the Word manuscript. Items that still need research, new figures, or prerequisite code are tracked in `todos/01-UNIR-DOCUMENTATION-IMPROVEMENTS.md` and will form the next delta cycle (see §"Deferred" at the bottom of this file).

---

## D2 — Portal screenshots

**Primary location:** §5.6 "Backend, portal web y cliente iOS" (currently prose-only, no images).
**Secondary location:** relevant screenshots may be reused inside Chapter 6 alongside the worked walkthrough once D1 is produced.
**Dev server:** `cd portal && npm start` (localhost:4200). The backend must be running (`cd backend && ./gradlew bootRun`) so the views that depend on live data populate.
**Caption style:** every figure gets a Spanish caption in the existing manuscript format — `Figura N. <descripción corta>. Fuente: Elaboración propia.` Use Word's cross-reference feature for figure numbers so they survive the renumbering required by C11.

### Figures to capture

| #   | View / state                                            | Route                                               | Frame                                                                                                                                                                                                                                                                                                                                    | Caption (Spanish)                                                                                                                                              |
| --- | ------------------------------------------------------- | --------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P1  | **Race view — live session**                            | `/race`                                             | Full browser window at ≥ 1440 × 900. Ensure the header shows an active track and lap counter, the positions table has the player's car visible with tyre compound/age, and the strategy widget on the left is populated (run an evaluation first). Crop away browser chrome.                                                             | _Figura N. Portal web, vista "Carrera en vivo" durante una sesión activa. Fuente: Elaboración propia._                                                         |
| P2  | **Strategy view — post-evaluation, rank 1 highlighted** | `/strategy`                                         | Full window. Trigger a strategy evaluation from the Race view first so the table is populated. Capture the full ranked table (Rank · Strategy · Stops · Expected Pos · 95 % CI · P(Podium) · P(Points) · P(DNF) · Expected Pts) with the rank-1 row highlighted green. Do **not** crop the column headers.                               | _Figura N. Portal web, vista "Estrategia" con la comparativa de estrategias candidatas y sus métricas agregadas Monte Carlo. Fuente: Elaboración propia._      |
| P3  | **Calibration view — fitted coefficients**              | `/calibration`                                      | Select a track that has been calibrated at least once (run `python -m calibration run <trackId>` beforehand if needed). Capture the readiness summary ("Overall: X fitted \| Y defaults") and the coefficient table with at least one row in each state (green = fitted high confidence, yellow = fitted low confidence, red = default). | _Figura N. Portal web, vista "Calibración" con el resumen de preparación y los coeficientes ajustados por knob, régimen y sector. Fuente: Elaboración propia._ |
| P4  | **Sessions — list view**                                | `/sessions`                                         | List with at least three sessions of different types (race, qualifying, practice). Include the driver-assignment column so the assign/unassign affordance is visible.                                                                                                                                                                    | _Figura N. Portal web, vista "Sesiones" con el histórico de sesiones importadas y la asignación de pilotos. Fuente: Elaboración propia._                       |
| P5  | **Session detail — participants + sector snapshots**    | `/sessions/:id` (click "Details" on a race session) | Must include both tables: the participants roster (car #, driver, team, AI flag) and at least 6–8 rows of the sector snapshots table so the per-sector granularity is visible.                                                                                                                                                           | _Figura N. Portal web, detalle de sesión: parrilla de participantes y muestras de telemetría por sector. Fuente: Elaboración propia._                          |
| P6  | **Drivers — list view**                                 | `/drivers`                                          | Roster with at least three drivers, the "Sessions" count column populated. Include the "Add driver" button affordance (form collapsed).                                                                                                                                                                                                  | _Figura N. Portal web, vista "Pilotos" con el listado de pilotos registrados y el número de sesiones asociadas a cada uno. Fuente: Elaboración propia._        |

### Notes

- **Monte Carlo plot:** the Strategy view currently renders the aggregated output as a table (mean position, 95 % CI, probabilities), not a histogram. The table in P2 **is** the Monte Carlo distribution output from the manuscript's standpoint. If a position-distribution histogram is later wanted as a separate figure, it needs to be generated offline from the simulator's `StrategyEvaluation` JSON — tracked as a deferred item.
- **Image format:** PNG, no JPEG artifacts. Keep the same DPI as existing manuscript figures so they print consistently. Zoom the browser to 100 % before capturing (`Cmd+0`).
- **Redaction:** before capturing, verify no real email addresses or personal driver names leak through the Drivers view; swap for placeholders if they do.

---

## D3 — Inline comments, ready-to-apply edits

Order follows the original numbering from Thiago's annotated docx. Each item is a mechanical or near-mechanical edit: Victor pastes the "Action" block into the Word document, runs any find-and-replace indicated, and checks the "Verify" notes.

### C1 — "Trabajo de Fin de Grado" (global)

**Anchor:** cover page, running headers/footers, resumen, English abstract, and every in-text reference to the work.
**Action:** global find-and-replace `Trabajo Fin de Grado` → `Trabajo de Fin de Grado` (and any lowercase variant `trabajo fin de grado` → `trabajo de fin de grado`).
**Verify:** (a) cover page; (b) running header on every page; (c) resumen opening sentence; (d) English abstract (if the English copy also omits "de"); (e) footer on the last page.

### C2 — Spanish keywords

**Anchor:** resumen, line `Palabras clave: Fórmula 1, telemetría UDP, simulación Monte Carlo, Oracle AI Database 26ai, modelos de lenguaje.`
**Action:** drop `telemetría UDP` and `simulación Monte Carlo`. Final line:
`Palabras clave: Fórmula 1, Oracle AI Database 26ai, modelos de lenguaje.`
**Verify:** English `Keywords:` line — remove the same two terms if they appear (`UDP telemetry`, `Monte Carlo simulation`).

### C3 — DRS footnote

**Anchor:** Chapter 1 (Introducción), first occurrence of "DRS" (paragraph starting _"…impide capturar fenómenos que ocurren a escala de sector como las zonas de DRS…"_).
**Action:** attach a footnote to the first "DRS":

> _DRS (Drag Reduction System): sistema de reducción de resistencia aerodinámica que abre una sección del alerón trasero en zonas delimitadas del circuito cuando un coche se sitúa a menos de un segundo del que le precede, con el objetivo de facilitar el adelantamiento (FIA, 2025)._

**Citation:** uses the existing `<a id="fia2025"></a>` entry in `design/10-REFERENCES.md` — no new reference needed.

### C4 — UDP footnote

**Anchor:** Chapter 1 (Introducción), first occurrence of "UDP" (paragraph starting _"…y emite de forma nativa por UDP toda la telemetría…"_).
**Action:**

1. Expand the first occurrence inline: `UDP` → `UDP (User Datagram Protocol)`.
2. Attach a footnote:
   > _Protocolo de transporte sin conexión definido en la RFC 768: entrega datagramas con mínima sobrecarga pero sin garantías de orden ni de entrega, lo que lo hace adecuado para flujos de telemetría de alta frecuencia donde la latencia importa más que la integridad absoluta (Postel, 1980)._
3. Add the reference below to `design/10-REFERENCES.md` under "Technical Documentation":
   > `<a id="rfc768"></a>Postel, J. (1980). _User datagram protocol_ (RFC 768). Internet Engineering Task Force. https://www.rfc-editor.org/rfc/rfc768`

### C5 — "Sim Racing" capitalisation

**Anchor:** Chapter 1, phrase _"ecosistema sim Racing"_.
**Action:** `sim Racing` → `Sim Racing`.
**Verify:** sweep the manuscript for `sim racing`, `sim Racing`, `Sim racing` and normalise all to `Sim Racing`.

### C6 — Figure 1 — reorder and introduce

**Anchor:** Chapter 1, paragraph _"A continuación, la disposición general de los seis componentes y los flujos principales de información entre ellos."_ followed by the architecture diagram (currently **Figura 1**).
**Action:**

1. Replace the existing lead-in paragraph at the figure's current position with a short forward reference:
   > _El sistema propuesto se articula en torno a seis componentes interconectados, como muestra la Figura 1._
2. After the figure and its caption, add a closing paragraph that summarises and references the figure explicitly:
   > _La Figura 1 sintetiza la disposición general de los seis componentes y los flujos principales de información entre ellos._
3. Coordination with C11: once C11 replaces Figura 1 with a higher-level block diagram, verify this forward reference still points to the right figure.

### C7 — "Estructura de la memoria"

**Anchor:** §1.x "Estructura de la memoria", sentence _"…organiza en seis capítulos adicionales…"_.
**Action:** `seis capítulos adicionales` → `siete capítulos adicionales`.
**Verify:** the walkthrough list below this sentence lists all seven chapters of the final manuscript TOC (1. Introducción · 2. Estado del arte · 3. Objetivos y metodología · 4. Arquitectura · 5. Implementación · 6. Evaluación y resultados · 7. Conclusiones y trabajo futuro — confirm against the final TOC before closing).

### C9 — Define "LLM" on first use

**Anchor:** Chapter 1 (Introducción), paragraph _"…madurez técnica de los modelos de lenguaje de gran tamaño que está modificando…"_.
**Action:** `modelos de lenguaje de gran tamaño` → `modelos de lenguaje de gran tamaño (LLM, por sus siglas en inglés *Large Language Model*)`.
**Verify:** this is the **first** occurrence in the manuscript. If an earlier mention exists, move the definition there and leave a plain `LLM` here.

### C10 — Intro sentence before the objectives list

**Anchor:** Chapter 3 (objetivos), just before _"Cada objetivo admite valoración independiente, y su consecución conjunta basta para considerar logrado el objetivo general. OE1. Diseñar e implementar…"_.
**Action:** insert a lead-in sentence immediately before the OE list:

> _Para alcanzar el objetivo general, el trabajo se descompone en los siguientes objetivos específicos:_

Keep the existing sentence about independent valuation either as the next sentence or fold it into the same paragraph after the OE list.

### C12 — Define R² at first use

**Anchor:** Chapter 3 (or the methodology subsection that first mentions it), sentence _"…el Capítulo 6: si el R² del modelo del jugador resulta significativamente…"_.
**Action:**

1. Add a footnote (or short inline definition) on the first occurrence of R²:
   > _Coeficiente de determinación (R²): proporción de la varianza de la variable dependiente explicada por el modelo, acotada en [0, 1]; un valor próximo a 1 indica buen ajuste y uno próximo a 0 un ajuste pobre. Formalmente, R² = 1 − SS_res / SS_tot, siendo SS_res la suma de cuadrados residual y SS_tot la suma de cuadrados total (James et al., 2013)._
2. Add the reference below to `design/10-REFERENCES.md` under "Books":
   > `<a id="james2013"></a>James, G., Witten, D., Hastie, T., & Tibshirani, R. (2013). _An introduction to statistical learning: With applications in R_. Springer. https://doi.org/10.1007/978-1-4614-7138-7`

### C13 — "Figuras" with capital F

**Anchor:** paragraph containing _"Las figuras [TT1.1]3, 4 y 5 ilustran…"_.
**Action:**

1. `Las figuras` → `Las Figuras`.
2. Remove the stray `[TT1.1]` marker.
3. Sweep the whole manuscript for `figura N` / `figuras N y M` patterns and normalise to capitalised `Figura` / `Figuras` when they reference a specific numbered figure. Leave lowercase when the word is used generically (e.g. _"en la figura anterior"_).

### C15 — Chapter 5 heading

**Anchor:** heading of Chapter 5, currently _"5. Conclusiones y trabajo futuro"_.
**Action:** rename to `5. Implementación` (or `5. Implementación del sistema` — Victor's choice; whichever matches the final TOC used in C7).
**Verify:** update (a) the TOC entry, (b) running headers for Chapter 5, (c) every cross-reference that currently says "el Capítulo 5 (Conclusiones…)".

### C17 — Justify IQR over normality-based outlier detection

**Anchor:** methodology paragraph _"…por piloto y por sector. Las filas cuyo tiempo queda fuera del rango [Q₁ − 1,5·IQR, Q₃ + 1,5·IQR] se marcan como atípicas y se excluyen. El IQR se prefiere a métodos basados en el supuesto de normalidad por su robustez frente a la presencia de los propios valores atípicos en la distribución que los define."_
**Action:** replace the closing one-sentence justification with the expanded paragraph below.

> _El IQR se prefiere a métodos basados en el supuesto de normalidad por tres razones. En primer lugar, los criterios que utilizan la media y la desviación típica muestrales (por ejemplo, el z-score con un umbral de ±3·σ) emplean estadísticos que se ven desplazados e inflados precisamente por los valores atípicos que pretenden detectar, lo que produce un "efecto de enmascaramiento" en el que los propios datos corruptos contaminan el criterio que los juzga (Tukey, 1977). En segundo lugar, los cuartiles Q₁ y Q₃ son estadísticos de orden robustos: hasta un 25 % de la distribución puede estar contaminado en cualquiera de las colas sin que Q₁ o Q₃ cambien de valor. En tercer lugar, la distribución empírica de los tiempos por sector no es gaussiana: presenta asimetría positiva (problemas mecánicos, tráfico y banderas amarillas empujan los tiempos hacia arriba más de lo que factores favorables los empujan hacia abajo) y con frecuencia es multimodal entre stints y compuestos, por lo que la hipótesis de normalidad que subyace al z-score no está justificada. El umbral de 1,5·IQR de Tukey es el criterio no paramétrico estándar recomendado por la literatura de análisis exploratorio de datos exactamente para estos casos._

**Citation:** uses the existing `<a id="tukey1977"></a>` entry in `design/10-REFERENCES.md` — no new reference needed.

---

## References file — summary of entries to add

Edits to `design/10-REFERENCES.md` resulting from this delta:

- **Technical Documentation section** — add: `Postel, J. (1980). User datagram protocol (RFC 768)…` (anchor id `rfc768`, used by C4).
- **Books section** — add: `James, G., Witten, D., Hastie, T., & Tibshirani, R. (2013). An introduction to statistical learning…` (anchor id `james2013`, used by C12).

Existing entries reused by this delta: `fia2025` (for C3), `tukey1977` (for C17).

---

## Observed gaps in the manuscript — out of scope for this delta

These holes in the partial-delivery PDF are first-milestone scope that will be closed in the final delivery task; they are listed here so Victor can confirm none are being confused with a review comment that requires action this cycle:

- Chapter 4 header reads _"Título del capítulo"_ (will be renamed to _"Arquitectura del sistema"_ or similar).
- Chapter 6 §6.1 – §6.5 all carry `[placeholder]`. Thiago's D1 walkthrough belongs here and is deferred — see the todo.
- Chapter 7 and its subsections are `[placeholder]`; C14's cross-reference target depends on Ch. 7 existing.
- Anexo A is `[placeholder]`.
- Stray `[placeholder]` paragraphs under chapter headings (Ch. 1, 2, 4, 5, 6) and at the end of §1.1.
- Repository URL on the cover page is `[placeholder]`.

---

## Deferred — feeds into the next delta cycle

Tracked in `todos/01-UNIR-DOCUMENTATION-IMPROVEMENTS.md`:

- **D1** — Worked simulation walkthrough in Chapter 6. Blocked on the LLM-over-catalogue layer (`todos/02-LLM-RADIO-MESSAGE-GENERATION.md`) and requires design of a concrete hypothetical race scenario.
- **C8** — reference supporting "varios millones de usuarios". Needs a credible source (Codemasters/EA press release, SteamDB, Newzoo) or a decision to soften the claim.
- **C11** — new higher-level architecture figure for Chapter 1, plus renumbering consequences. Requires Mermaid/drawio design work.
- **C14** — explicit cross-reference to "Capítulo 7 (Conclusiones y trabajo futuro), §7.2". Depends on Ch. 7 being written.
- **C16** — forward reference to the TxEventQ subsection (likely §5.3). Depends on the final TOC of the implementation chapter.
