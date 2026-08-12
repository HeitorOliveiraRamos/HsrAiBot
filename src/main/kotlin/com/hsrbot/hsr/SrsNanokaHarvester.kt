package com.hsrbot.hsr

import com.hsrbot.config.BotProperties
import com.hsrbot.knowledge.NanokaIngestionSource
import com.hsrbot.knowledge.NanokaIngestionSource.Companion.children
import com.hsrbot.knowledge.NanokaIngestionSource.Companion.fill
import com.hsrbot.knowledge.NanokaIngestionSource.Companion.strip
import com.hsrbot.knowledge.StarRailStationIngestionSource
import com.hsrbot.knowledge.StarRailStationIngestionSource.Companion.hashPath
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hsrbot.knowledge.NanokaIngestionSource.Companion.maxLevelParams as nanMaxLevel
import com.hsrbot.knowledge.NanokaIngestionSource.Companion.minLevelParams as nanMinLevel
import com.hsrbot.knowledge.NanokaIngestionSource.Companion.paramList as nanParams
import com.hsrbot.knowledge.StarRailStationIngestionSource.Companion.maxLevelParams as srsMaxLevel
import com.hsrbot.knowledge.StarRailStationIngestionSource.Companion.minLevelParams as srsMinLevel
import com.hsrbot.knowledge.StarRailStationIngestionSource.Companion.paramList as srsParams
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Harvests the rich V17 schema ([PersonagemHsr] / [Reliquia] / [OrnamentoPlano] / [ConeDeLuz] /
 * [Build]) from the two structured-JSON sources, PT-first — the single upstream fetch behind both
 * the deterministic answers and the vector store:
 *
 *  - **nanoka** ([NanokaIngestionSource]) is the SPINE for characters and light cones — its
 *    numeric id index lists every id including betas srs hasn't published. Text is English.
 *  - **starrailstation** ([StarRailStationIngestionSource]) is the PT overlay (join key
 *    `rankKey` == the numeric id), plus the SOLE source of relic/ornament pieces (nanoka carries
 *    no per-piece lore).
 *
 * Every ability/eidolon/trace is split into a name/description pair, plus memosprite (Recordação),
 * euphoria (Euforia), relic/ornament pieces, light-cone lore and the recommended [Build]. The two
 * ingestion beans are injected only to reuse their version/deployment discovery and
 * hardened pure parsers (skill grouping, trace walk, [fill]/[strip], the srs path [hashPath]);
 * every extractor is a pure companion function, fixture-tested.
 *
 * The signature-cone link (a cone → the 5★ character it's designed for) is each character's #1
 * nanoka recommended cone, kept both-sided-5★ so generic cones don't get mislinked to 4★ units.
 */
@Component
class SrsNanokaHarvester(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
    private val nanoka: NanokaIngestionSource,
    private val srs: StarRailStationIngestionSource,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun harvest(): SrsNanokaData {
        val k = properties.knowledge
        val nanoVer = nanoka.resolveVersion() ?: run {
            log.error("srs_nanoka: nanoka version unresolved — aborting harvest.")
            return EMPTY
        }
        val nanoBase = "${k.nanokaCdnUrl.trimEnd('/')}/$nanoVer"
        val charIndex = getJson("$nanoBase/character.json") ?: run {
            log.error("srs_nanoka: nanoka character index unreachable — aborting harvest.")
            return EMPTY
        }

        val dep = srs.resolveDeployment()
        val srsByRankKey: Map<String, JsonNode> = dep?.let { d ->
            getSrs(d, "characters.json")?.path("entries")?.associateBy { it.path("rankKey").asText("") }
        }.orEmpty()
        if (dep == null || srsByRankKey.isEmpty()) {
            log.warn("srs_nanoka: starrailstation unavailable — English-only harvest (no PT, no relic/ornament pieces).")
        }

        // nanoka's item index: the bucket/rarity records that order a beta's cost grids, the icons
        // for those materials, and the id of the EXP book. English-only, so PT names come from
        // StarRailRes — which is keyed by the same game item id and is reachable when nanoka isn't.
        val nanAssetBase = k.nanokaAssetUrl.trimEnd('/')
        val itemIndex = getJson("$nanoBase/en/item.json")
        val itemRefs = refsDeItens(itemIndex, mapper)
        val nomesPtItens = getJson("${k.starRailResBase.trimEnd('/')}/pt/items.json")
            ?.let { StarRailResNames.nomes(it) }.orEmpty()

        // -------- characters + raw signature links (5★ char → its top nanoka cone) --------
        val personagens = mutableListOf<PersonagemHsr>()
        val rawSig = mutableMapOf<String, String>() // coneGameId -> characterGameId
        // charGameId -> nanoka detail, kept to build recommended builds after every name is known
        // (a build's team members are OTHER characters, resolved to display names in a second pass).
        val buildInputs = mutableListOf<Pair<String, JsonNode>>()
        var withPt = 0
        var expQtd: Long? = null
        for ((id, meta) in charIndex.fields()) {
            val srsEntry = srsByRankKey[id]
            val pageId = srsEntry?.path("pageId")?.asText("")?.ifBlank { null }
                ?: meta.path("icon").asText("").ifBlank { null }
            val srsDetail = if (dep != null && srsEntry != null && pageId != null) getSrs(dep, "characters/$pageId.json") else null
            if (srsEntry != null) withPt++
            val nanDetail = getJson("$nanoBase/en/character/$id.json")
            val p = buildPersonagem(id, meta, srsEntry, srsDetail, nanDetail, nanAssetBase)
            personagens += p
            // Constant across all 95 live characters (290, 4★ and 5★ alike) and the one cost cell
            // nanoka cannot produce, so the first srs character to yield it supplies every beta.
            if (expQtd == null) expQtd = srsDetail?.let { livrosExp(it.path("calculator")) }?.second
            nanDetail?.let { buildInputs += id to it }
            if (p.raridade == 5) {
                children(nanDetail?.path("lightcones") ?: mapper.nullNode()).firstOrNull()
                    ?.asText("")?.ifBlank { null }?.let { rawSig[it] = id }
            }
        }

        // -------- relic & ornament sets (srs only — PT pieces) --------
        val reliquias = mutableListOf<Reliquia>()
        val ornamentos = mutableListOf<OrnamentoPlano>()
        if (dep != null) {
            for (entry in children(getSrs(dep, "relics.json")?.path("entries") ?: mapper.nullNode())) {
                val pageId = entry.path("pageId").asText("").ifBlank { null } ?: continue
                if (strip(entry.path("name").asText("")).isBlank()) continue
                val detail = getSrs(dep, "relics/$pageId.json")
                when (entry.path("relicType").asInt(0)) {
                    1 -> reliquias += buildReliquia(entry, detail)
                    2 -> ornamentos += buildOrnamento(entry, detail)
                }
            }
        }

        // -------- light cones (nanoka spine, srs PT overlay) --------
        val srsConeIds: Set<String> = if (dep != null) {
            children(getSrs(dep, "searchItems.json")?.path("entries") ?: mapper.nullNode())
                .filter { it.path("type").asInt(-1) == 1 }
                .mapNotNull { it.path("url").asText("").substringAfterLast('/').ifBlank { null } }
                .toSet()
        } else emptySet()
        val coneIndex = getJson("$nanoBase/lightcone.json") ?: mapper.nullNode()
        val cones = mutableListOf<ConeDeLuz>()
        for ((id, meta) in coneIndex.fields()) {
            val cone = if (dep != null && id in srsConeIds) {
                getSrs(dep, "lightcones/$id.json")?.let { buildSrsCone(id, it) }
            } else null
            val resolved = cone ?: buildNanCone(id, meta, getJson("$nanoBase/en/lightcone/$id.json"), nanAssetBase)
            if (resolved.nome.isNotBlank()) cones += resolved
        }

        // Keep a signature link only when its cone is 5★ too (avoids mislinking shared cones).
        val coneRar = cones.associate { it.coneGameId to it.raridade }
        val signatureLinks = rawSig.filter { (coneId, _) -> coneRar[coneId] == 5 }

        // -------- upgrade materials — srs's dictionary, plus nanoka's for whatever a beta needs --------
        // Two disjoint id spaces in one table: srs keys by its own `pageId`, nanoka by the game item
        // id. nanoka goes first so an srs row always wins a (hypothetical) clash on re-insert.
        val materiaisNanoka = buildMateriaisNanoka(itemIndex, nomesPtItens, nanAssetBase)
        val materiaisSrs = if (dep != null) buildMateriais(getSrs(dep, "materials.json")) else emptyList()
        // Disjointness is a property of today's data, not a guarantee the sources make. Say so out
        // loud if it ever stops holding: the symptom would otherwise be one silently wrong icon.
        val colisoes = materiaisNanoka.map { it.materialId }.toSet() intersect materiaisSrs.map { it.materialId }.toSet()
        if (colisoes.isNotEmpty()) {
            log.warn("srs_nanoka: {} ids de material colidem entre nanoka e srs (srs vence): {}", colisoes.size, colisoes.take(10))
        }
        val materiais = materiaisNanoka + materiaisSrs

        // -------- nanoka costs for whoever srs left without any (the betas) --------
        val exp = nanLivrosExp(itemIndex, expQtd)
        val nanPorId = buildInputs.toMap()
        var comCustoNanoka = 0
        val comCustos = personagens.map { p ->
            if (p.custosMelhoria != null) p
            else nanCustos(nanPorId[p.characterId], itemRefs, exp)
                ?.also { comCustoNanoka++ }
                ?.let { p.copy(custosMelhoria = it) }
                ?: p
        }

        // -------- recommended builds (nanoka lists; team names resolved now every char is known) --------
        val displayNames = comCustos.associate { it.characterId to (it.nome ?: it.nomeEn ?: it.characterId) }
        val builds = buildInputs.mapNotNull { (cid, nd) -> buildBuild(cid, nd, displayNames) }

        log.info(
            "srs_nanoka: {} personagens ({} com PT, {} com custos da nanoka), {} relíquias, {} ornamentos, {} cones, {} assinaturas, {} builds, {} materiais",
            comCustos.size, withPt, comCustoNanoka, reliquias.size, ornamentos.size, cones.size, signatureLinks.size, builds.size, materiais.size,
        )
        return SrsNanokaData(comCustos, reliquias, ornamentos, cones, signatureLinks, builds, materiais)
    }

    // -------------------- io -------------------- //

    private fun getSrs(deployment: String, path: String): JsonNode? {
        val locized = "${properties.knowledge.srsLocale}/$path"
        val url = "${properties.knowledge.srsDataUrl.trimEnd('/')}/$deployment/${hashPath(locized)}"
        return getJson(url)
    }

    private fun getJson(url: String): JsonNode? = getText(url)?.let {
        try {
            mapper.readTree(it)
        } catch (e: Exception) {
            log.warn("srs_nanoka: bad JSON from {}: {}", url, e.message)
            null
        }
    }

    private fun getText(url: String): String? = try {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Mozilla/5.0 (compatible; HsrBot/1.0; +discord)")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() in 200..299) resp.body()
        else { log.warn("srs_nanoka: HTTP {} for {}", resp.statusCode(), url); null }
    } catch (e: Exception) {
        log.warn("srs_nanoka: fetch failed for {}: {}", url, e.message)
        null
    }

    internal companion object {
        val EMPTY = SrsNanokaData(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())

        private const val TRAILBLAZER = "Trailblazer"
        private val RARITY_DIGIT = Regex("""(\d)$""")

        /**
         * srs asset ids are the sha-256 of the file's own bytes, and we later paste them straight
         * into a CDN URL — so anything that isn't 64 lowercase hex is dropped here rather than
         * turned into a request. Fail-soft: a null icon renders as a placeholder, never an error.
         */
        private val ASSET_HASH = Regex("""^[0-9a-f]{64}$""")

        /** The asset hash at [field] of [node], or null when absent/blank/malformed. */
        internal fun assetHash(node: JsonNode?, field: String): String? =
            node?.path(field)?.asText("")?.takeIf { ASSET_HASH.matches(it) }

        /** HSR internal path codenames → readable EN (nanoka fallback for betas; srs gives PT). */
        private val PATH_EN = mapOf(
            "Warrior" to "Destruction", "Knight" to "Preservation", "Mage" to "Erudition",
            "Shaman" to "Harmony", "Warlock" to "Nihility", "Priest" to "Abundance",
            "Rogue" to "The Hunt", "Memory" to "Remembrance", "Elation" to "Elation",
        )

        /** nanoka combat types → readable EN. `Thunder` is the internal name for Lightning. */
        private val ELEMENT_EN = mapOf("Thunder" to "Lightning")

        /**
         * Realistic level cap per ability (srs PT tag / nanoka EN type_name). The source levelData
         * runs higher — Basic to 10, Skill/Ult to 15 — but those extra levels are only reachable via
         * eidolons and trace bonuses, so we fill at the level a player actually levels the ability to.
         * A type absent here (Técnica/Technique) has a single level and is filled at its max.
         */
        private val ABILITY_CAP_PT = mapOf(
            "ATQ Básico" to 6, "Perícia" to 10, "Perícia Suprema" to 10, "Talento" to 10, "Perícia da Euforia" to 10,
        )
        private val ABILITY_CAP_EN = mapOf(
            "Basic ATK" to 6, "Skill" to 10, "Ultimate" to 10, "Talent" to 10, "Elation Skill" to 10,
        )
        private const val MEMO_CAP = 6

        /** srs `levelData`: params of the highest `level` ≤ [cap] (or the max level when [cap] is null). */
        internal fun srsParamsCapped(levelData: JsonNode, cap: Int?): List<Double> {
            if (!levelData.isArray || levelData.isEmpty) return emptyList()
            if (cap == null) return srsMaxLevel(levelData)
            val entry = levelData.filter { it.path("level").asInt(0) <= cap }.maxByOrNull { it.path("level").asInt(0) }
                ?: return srsMaxLevel(levelData)
            return srsParams(entry.path("params"))
        }

        /** nanoka per-level `level` object: param_list of the highest key ≤ [cap] (or the max when null). */
        internal fun nanParamsCapped(levelNode: JsonNode, cap: Int?): List<Double> {
            if (!levelNode.isObject || levelNode.isEmpty) return emptyList()
            if (cap == null) return nanMaxLevel(levelNode)
            val key = levelNode.fieldNames().asSequence().mapNotNull { it.toIntOrNull() }.filter { it <= cap }.maxOrNull()
                ?: return nanMaxLevel(levelNode)
            return nanParams(levelNode.path(key.toString()).path("param_list"))
        }

        // ---------------- characters ---------------- //

        /**
         * Assembles one [PersonagemHsr] PT-first: each field takes the srs (Portuguese) value when
         * present, else the nanoka (English) one. Pure — fixture-testable without the network.
         *
         * Enhanced states (Firefly, Blade, Kafka, Silver Wolf…): srs flags them with `hasEnhanced`,
         * nanoka with a non-empty `enhanced` object. For those, the ability extractors pick the
         * ENHANCED variant of each skill, not the base. A NEW enhanced kit that nanoka has but srs
         * hasn't published yet ([nanokaKitWins]) is the ONE case a released character's kit is taken
         * from nanoka (English) over srs — otherwise srs (PT) stays primary.
         */
        internal fun buildPersonagem(
            id: String,
            nanMeta: JsonNode,
            srsEntry: JsonNode?,
            srsDetail: JsonNode?,
            nanDetail: JsonNode?,
            nanBase: String = "",
        ): PersonagemHsr {
            val srsEnhanced = srsDetail?.path("hasEnhanced")?.asBoolean(false) == true
            val nanEnhanced = (nanDetail?.path("enhanced")?.size() ?: 0) > 0
            val nanokaKitWins = nanEnhanced && !srsEnhanced

            val srsAbil = srsDetail?.let { srsAbilities(it, srsEnhanced) }.orEmpty()
            val nanAbil = nanDetail?.let { nanAbilities(it, nanEnhanced) }.orEmpty()
            // Icons track srs even when nanokaKitWins hands the TEXT to nanoka: nanoka publishes no
            // artwork, and srs's base icon for an ability stays correct when only its numbers changed.
            val skillIcons = srsSkillIcons(srsDetail, srsEnhanced)
            // Artwork fallback, per field rather than per character, exactly like the text above:
            // srs stays primary wherever it published something, and nanoka fills the holes. For a
            // beta srs has never heard of, every one of these is the only thing there is.
            val nanArte = if (nanBase.isBlank()) emptyMap() else nanCharAssets(id, nanMeta, nanBase)
            val nanIcones = if (nanBase.isBlank()) emptyMap() else nanSkillIcons(nanDetail, nanBase)
            val srsMemo = srsDetail?.let { srsMemosprite(it) }.orEmpty()
            val memoIcons = srsMemospriteIcons(srsDetail)
            val nanMemo = nanDetail?.let { nanMemosprite(it) }.orEmpty()
            val stories = srsStories(srsDetail).ifEmpty { nanStories(nanDetail) }
            // PT-first normally; nanoka-first only when its new enhanced kit overrides srs.
            fun kit(srsVal: NamedText?, nanVal: NamedText?): NamedText =
                if (nanokaKitWins) pick(nanVal, srsVal) else pick(srsVal, nanVal)
            return PersonagemHsr(
                characterId = id,
                nome = srsEntry?.path("name")?.asText("")?.let(::strip)?.ifBlank { null },
                nomeEn = nanMeta.path("en").asText("").ifBlank { null }?.replace("{NICKNAME}", TRAILBLAZER)?.let(::strip),
                elemento = srsEntry?.path("damageType")?.path("name")?.asText("")?.let(::strip)?.ifBlank { null }
                    ?: nanMeta.path("damageType").asText("").ifBlank { null }?.let { ELEMENT_EN[it] ?: it },
                caminho = srsEntry?.path("baseType")?.path("name")?.asText("")?.let(::strip)?.ifBlank { null }
                    ?: nanMeta.path("baseType").asText("").ifBlank { null }?.let { PATH_EN[it] ?: it },
                raridade = srsEntry?.path("rarity")?.asInt(0)?.takeIf { it > 0 }
                    ?: RARITY_DIGIT.find(nanMeta.path("rank").asText(""))?.value?.toIntOrNull(),
                faccao = srsDetail?.path("archive")?.path("camp")?.asText("")?.ifBlank { null }
                    ?: nanDetail?.path("chara_info")?.path("camp")?.asText("")?.ifBlank { null },
                descricao = srsDetail?.path("descHash")?.asText("")?.ifBlank { null }?.let { fill(it, emptyList()) }
                    ?: nanMeta.path("desc").asText("").ifBlank { null }?.let(::strip),
                atqBasico = kit(srsAbil["ATQ Básico"], nanAbil["Basic ATK"]),
                pericia = kit(srsAbil["Perícia"], nanAbil["Skill"]),
                periciaSuprema = kit(srsAbil["Perícia Suprema"], nanAbil["Ultimate"]),
                talento = kit(srsAbil["Talento"], nanAbil["Talent"]),
                tecnica = kit(srsAbil["Técnica"], nanAbil["Technique"]),
                periciaMemoespirito = kit(srsMemo["skill"], nanMemo["Memosprite Skill"]),
                talentoMemoespirito = kit(srsMemo["talent"], nanMemo["Memosprite Talent"]),
                periciaEuforia = kit(srsAbil["Perícia da Euforia"], nanAbil["Elation Skill"]),
                tracos = srsTraces(srsDetail, srsEnhanced).ifEmpty { nanTraces(nanDetail, nanEnhanced) },
                eidolons = srsEidolons(srsDetail, srsEnhanced).ifEmpty { nanEidolons(nanDetail, nanEnhanced) },
                detalhesPersonagem = stories.getOrNull(0)?.ifBlank { null },
                historias = stories.drop(1).map { it.ifBlank { null } },
                // Assets (srs only — nanoka is never consulted for artwork). The splash falls back
                // to the square 2048 art for the one character with no cut-out figure (Firefly).
                arteFigura = assetHash(srsDetail, "figPath"),
                arteCompleta = assetHash(srsDetail, "artPath") ?: nanArte["arteCompleta"],
                // The card's actual splash pair: a bust the artist already framed, over the same
                // illustration with the character removed. See V21.
                arteRetrato = assetHash(srsDetail, "splashIconPath") ?: nanArte["arteRetrato"],
                arteFundo = assetHash(srsDetail, "bgPath"),
                iconeMini = assetHash(srsDetail, "miniIconPath") ?: nanArte["iconeMini"],
                iconeElemento = assetHash(srsEntry?.path("damageType"), "iconPath") ?: nanArte["iconeElemento"],
                iconeCaminho = assetHash(srsEntry?.path("baseType"), "iconPath") ?: nanArte["iconeCaminho"],
                iconeAtqBasico = skillIcons["ATQ Básico"] ?: nanIcones["Basic ATK"],
                iconePericia = skillIcons["Perícia"] ?: nanIcones["Skill"],
                iconePericiaSuprema = skillIcons["Perícia Suprema"] ?: nanIcones["Ultimate"],
                iconeTalento = skillIcons["Talento"] ?: nanIcones["Talent"],
                iconePericiaEuforia = skillIcons["Perícia da Euforia"] ?: nanIcones["Elation Skill"],
                iconeTalentoMemoespirito = memoIcons["talent"] ?: nanIcones["Memosprite Talent"],
                iconePericiaMemoespirito = memoIcons["skill"] ?: nanIcones["Memosprite Skill"],
                // nanoka's costs are spliced in after the loop ([harvest]), once the EXP-book
                // quantity has been read off any character srs DID publish.
                custosMelhoria = srsCustos(srsDetail),
            )
        }

        // ---------------- upgrade materials (V30) ---------------- //

        /**
         * The `purposeId` buckets a character's upgrade can consume — the whole dictionary the
         * ascension card needs, and the only 142 of the index's 1045 entries kept:
         * 11 crédito · 1 livros de EXP · 2 drop de boss · 4 Rastros de Destino + boss semanal ·
         * 7 cálice · 3 rastro. See V30.
         */
        private val PROPOSITOS_PERSONAGEM = setOf(1, 2, 3, 4, 7, 11)

        /**
         * Draw order of each grid, as `purposeId` first and `rarity` ascending within it — derived
         * from the data rather than a hardcoded id list, so a new character's own materials land in
         * the right cells. A material whose bucket isn't listed is DROPPED rather than placed at
         * random; both orders resolve to a fixed 6 and 9 cells across all 95 characters.
         */
        private val ORDEM_PERSONAGEM = listOf(11, 1, 2, 7)
        private val ORDEM_RASTROS = listOf(11, 4, 7, 3)

        /** Every `{id, count}` of a `cost` array, accumulated into [into]. */
        private fun somarCustos(cost: JsonNode, into: MutableMap<Int, Long>) {
            for (c in cost) {
                val id = c.path("id").asInt(0)
                if (id != 0) into.merge(id, c.path("count").asLong(), Long::plus)
            }
        }

        /** A trace-tree node's own cost plus its children's. Enhanced-form duplicates are skipped. */
        private fun somarArvore(node: JsonNode, into: MutableMap<Int, Long>) {
            if (node.path("enhanceId").asInt(0) == 0) {
                for (campo in listOf("embedBuff", "embedBonusSkill")) {
                    val embed = node.path(campo)
                    somarCustos(embed.path("cost"), into)
                    embed.path("levelData").forEach { somarCustos(it.path("cost"), into) }
                }
            }
            node.path("children").forEach { somarArvore(it, into) }
        }

        /**
         * EXP books for levels 1→80 as `itemId to quantidade`, in the biggest book the game sells
         * (the card shows a single stack). `expCost` is keyed by level and INCLUDES the max level,
         * which is never paid — counting it yields 310 books where the guide shows 290. Book values
         * are the keys of `expItemConfig`; the remainder rounds up into one more big book.
         */
        private fun livrosExp(calculator: JsonNode): Pair<Int, Long>? {
            val expCost = calculator.path("expCost")
            val niveis = expCost.fieldNames().asSequence().mapNotNull { it.toIntOrNull() }.toList()
            val nivelMax = niveis.maxOrNull() ?: return null
            val total = niveis.filter { it < nivelMax }.sumOf { expCost.path(it.toString()).asLong() }
            val (valor, itemId) = calculator.path("expItemConfig").fields().asSequence()
                .mapNotNull { (v, cfg) -> v.toIntOrNull()?.let { it to cfg.path("itemId").asInt(0) } }
                .maxByOrNull { it.first } ?: return null
            if (valor <= 0 || itemId == 0 || total <= 0) return null
            return itemId to (total + valor - 1) / valor
        }

        /** Totals laid out in [ordem], reading each material's bucket/rarity from `itemReferences`. */
        private fun ordenarCustos(totais: Map<Int, Long>, refs: JsonNode, ordem: List<Int>): List<CustoMaterial> =
            totais.mapNotNull { (id, qtd) ->
                val ref = refs.path(id.toString())
                val pos = ordem.indexOf(ref.path("purposeId").asInt(-1))
                if (pos < 0) null else Triple(pos, ref.path("rarity").asInt(0), CustoMaterial(id, qtd))
            }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }

        /**
         * The ascension card's two grids for one character, or null when srs has no detail yet.
         *
         *  - **personagem**: the six promotion jumps (`levelData[].cost`, the last one empty) plus
         *    [livrosExp]. Leveling credits are NOT counted — the guide shows ascension credits only.
         *  - **rastros**: every ability level plus every node of the trace tree. Two traps that
         *    silently inflate the totals: only the FIRST id of each `skillGrouping` bucket counts
         *    (enhanced/alt forms are separate `skills` entries — Blade has two Basic ATKs), and the
         *    `servant` block of a Recordação unit MUST be added the same way, or Castorice closes at
         *    2.597M credits instead of 3M. Levels past the cap already carry an empty `cost`, so no
         *    level cap is needed here.
         */
        internal fun srsCustos(detail: JsonNode?): CustosMelhoria? {
            val d = detail ?: return null
            val refs = d.path("itemReferences")
            if (!refs.isObject || refs.isEmpty) return null

            val ascensao = HashMap<Int, Long>()
            d.path("levelData").forEach { somarCustos(it.path("cost"), ascensao) }
            livrosExp(d.path("calculator"))?.let { (id, qtd) -> ascensao[id] = qtd }

            val rastros = HashMap<Int, Long>()
            for (bloco in listOf(d, d.path("servant"))) {
                srsCanonicalBuckets(bloco, enhanced = false).forEach { bucket ->
                    bucket.firstOrNull()?.path("levelData")?.forEach { somarCustos(it.path("cost"), rastros) }
                }
                bloco.path("skillTreePoints").forEach { somarArvore(it, rastros) }
            }

            if (ascensao.isEmpty() || rastros.isEmpty()) return null
            return CustosMelhoria(
                ordenarCustos(ascensao, refs, ORDEM_PERSONAGEM),
                ordenarCustos(rastros, refs, ORDEM_RASTROS),
            )
        }

        // ---------------- nanoka upgrade costs (betas only) ---------------- //

        /**
         * The same two grids [srsCustos] builds, from nanoka instead — the only cost data that
         * exists for a character srs hasn't published. Verified against the srs numbers for the
         * released roster: identical quantities cell for cell (308.000 créditos, 65 do drop de
         * boss, 15/15/15 dos cálices, 3.000.000 nos rastros).
         *
         *  - **personagem**: `stats[].cost`, the six promotion jumps (the last entry's is empty),
         *    plus [exp] — nanoka carries no EXP-cost curve at all, so that one cell is passed in.
         *  - **rastros**: every `material_list` in the skill tree. Unlike srs there is no
         *    enhanced-form duplication to skip and no separate `servant` block: a Recordação unit's
         *    memosprite abilities are ordinary `point_type` 4 nodes of the same tree.
         *
         * Ordered by the same [ordenarCustos] rules, reading the bucket/rarity from nanoka's item
         * index ([refsDeItens]) instead of srs's `itemReferences`. Null when nanoka has no costs.
         */
        internal fun nanCustos(detail: JsonNode?, refs: JsonNode, exp: CustoMaterial?): CustosMelhoria? {
            val d = detail ?: return null

            val ascensao = HashMap<Int, Long>()
            d.path("stats").fields().forEach { (_, nivel) -> somarCustosNanoka(nivel.path("cost"), ascensao) }
            exp?.let { ascensao[it.id] = it.qtd }

            val rastros = HashMap<Int, Long>()
            d.path("skill_trees").fields().forEach { (_, niveis) ->
                niveis.fields().forEach { (_, no) -> somarCustosNanoka(no.path("material_list"), rastros) }
            }

            if (ascensao.isEmpty() || rastros.isEmpty()) return null
            return CustosMelhoria(
                ordenarCustos(ascensao, refs, ORDEM_PERSONAGEM),
                ordenarCustos(rastros, refs, ORDEM_RASTROS),
            )
        }

        /** nanoka's `{item_id, item_num}` cost shape, accumulated into [into] — srs uses `{id, count}`. */
        private fun somarCustosNanoka(cost: JsonNode, into: MutableMap<Int, Long>) {
            for (c in cost) {
                val id = c.path("item_id").asInt(0)
                if (id != 0) into.merge(id, c.path("item_num").asLong(), Long::plus)
            }
        }

        /** HSR's rarity enum as the number srs states directly, so both sources sort the same way. */
        private val RARIDADE_NANOKA = mapOf(
            "Normal" to 1, "NotNormal" to 2, "Rare" to 3, "VeryRare" to 4, "SuperRare" to 5,
        )

        /**
         * nanoka's item index reshaped into the `{purposeId, rarity}` records [ordenarCustos] reads,
         * so one ordering function serves both sources. nanoka's `purpose_type` uses the very same
         * bucket numbering as srs's `purposeId` (11 crédito, 1 EXP, 2 drop de boss, 4 rastros,
         * 7 cálice, 3 rastro) — only the rarity spelling differs ([RARIDADE_NANOKA]).
         */
        internal fun refsDeItens(index: JsonNode?, mapper: ObjectMapper): JsonNode {
            val out = mapper.createObjectNode()
            index?.fields()?.forEach { (id, item) ->
                val proposito = item.path("purpose_type").asInt(-1)
                if (proposito < 0) return@forEach
                out.putObject(id)
                    .put("purposeId", proposito)
                    .put("rarity", RARIDADE_NANOKA[item.path("rarity").asText("")] ?: 0)
            }
            return out
        }

        /**
         * The EXP-book cell, which nanoka alone cannot produce: it publishes no level-EXP curve, so
         * the quantity is lifted from the srs harvest of the released roster and the item id is
         * nanoka's own biggest EXP book (`purpose_type` 1, highest rarity — the Guia do Viajante).
         *
         * Safe to copy because the cell is not per-character: all 95 live characters store exactly
         * `290` of it, 4★ and 5★ alike. Null when either half is unavailable, which costs the beta
         * card one of its six cells rather than the whole grid.
         */
        internal fun nanLivrosExp(index: JsonNode?, qtd: Long?): CustoMaterial? {
            val n = qtd?.takeIf { it > 0 } ?: return null
            val id = index?.fields()?.asSequence().orEmpty()
                .filter { (_, item) -> item.path("purpose_type").asInt(-1) == 1 }
                .maxByOrNull { (_, item) -> RARIDADE_NANOKA[item.path("rarity").asText("")] ?: 0 }
                ?.key?.toIntOrNull() ?: return null
            return CustoMaterial(id, n)
        }

        /**
         * The `materiais` rows for everything nanoka's cost lists can point at. Names come from
         * StarRailRes ([nomesPt], PT — nanoka serves English only) and fall back to nanoka's own
         * English when that file lacks an id; icons are `itemfigures/<game id>`.
         *
         * These share the table with the srs rows rather than getting their own: the two id spaces
         * were checked and are disjoint (142 srs `pageId`s vs 3932 game item ids, zero overlap), so
         * the `material_id` primary key stays unambiguous. That is a property of the data and not a
         * guarantee — [harvest] warns if the two id sets ever start overlapping.
         */
        internal fun buildMateriaisNanoka(index: JsonNode?, nomesPt: Map<String, String>, base: String): List<Material> {
            val idx = index ?: return emptyList()
            return idx.fields().asSequence().mapNotNull { (id, item) ->
                val proposito = item.path("purpose_type").asInt(-1)
                if (proposito !in PROPOSITOS_PERSONAGEM) return@mapNotNull null
                val numerico = id.toIntOrNull() ?: return@mapNotNull null
                val nome = nomesPt[id] ?: strip(item.path("item_name").asText("")).ifBlank { null }
                    ?: return@mapNotNull null
                Material(
                    materialId = numerico,
                    nome = nome,
                    icone = "$base/itemfigures/$id.webp",
                    raridade = RARIDADE_NANOKA[item.path("rarity").asText("")],
                    propositoId = proposito,
                )
            }.toList()
        }

        /**
         * The `materiais` dictionary from the srs materials index — one fetch for all of them, since
         * the index already carries name/icon/rarity and no per-material detail is needed. Entries
         * outside [PROPOSITOS_PERSONAGEM] (consumables, gacha currency, relic XP…) are dropped, as
         * is anything without a usable id or name.
         */
        internal fun buildMateriais(index: JsonNode?): List<Material> {
            val entries = index?.path("entries") ?: return emptyList()
            if (!entries.isArray) return emptyList()
            return entries.mapNotNull { e ->
                val proposito = e.path("purposeId").asInt(-1)
                if (proposito !in PROPOSITOS_PERSONAGEM) return@mapNotNull null
                val id = e.path("pageId").asText("").toIntOrNull() ?: return@mapNotNull null
                val nome = strip(e.path("name").asText("")).ifBlank { null } ?: return@mapNotNull null
                Material(
                    materialId = id,
                    nome = nome,
                    icone = assetHash(e, "iconPath"),
                    raridade = e.path("rarity").asInt(0).takeIf { it > 0 },
                    propositoId = proposito,
                )
            }
        }

        /**
         * Ability icons keyed by the same PT `typeDescHash` tag [srsAbilities] groups on, so the
         * icon and the text of an ability can never disagree about which bucket they came from.
         * Only the bucket's FIRST skill contributes — the extra 「name」 blocks merged into the
         * description share the ability's single card icon. Técnica is extracted like the rest and
         * simply goes unused: the build card's trace-priority row doesn't show it.
         */
        internal fun srsSkillIcons(detail: JsonNode?, enhanced: Boolean): Map<String, String> {
            val d = detail ?: return emptyMap()
            return srsCanonicalBuckets(d, enhanced).mapNotNull { bucket ->
                val head = bucket.firstOrNull() ?: return@mapNotNull null
                val tag = strip(head.path("typeDescHash").asText("")).ifBlank { null } ?: return@mapNotNull null
                assetHash(head, "iconPath")?.let { tag to it }
            }.toMap()
        }

        // ---------------- nanoka artwork (betas only) ---------------- //

        /**
         * The character-level art nanoka publishes, keyed by the [PersonagemHsr] field it fills.
         * Everything is addressed by the numeric character id, and the element/path icons by the
         * game's INTERNAL codename lowercased — `thunder`, not the display "Lightning" [ELEMENT_EN]
         * produces, and `memory`, not "Remembrance". All 7 elements and 9 paths were verified to
         * resolve, so these are built off the raw index values rather than the readable ones.
         *
         * Only three of the five art columns have an equivalent: `avatardrawcard` is the square
         * 2048 illustration ([PersonagemHsr.arteCompleta]), `avatarshopicon` the 376x512 bust
         * ([PersonagemHsr.arteRetrato], what the card actually draws), `avatarroundicon` the mini
         * icon. nanoka publishes no cut-out figure and no scenery plate, so `arteFigura`/`arteFundo`
         * stay null on a beta — the card's own fallback chain already covers that.
         */
        internal fun nanCharAssets(id: String, meta: JsonNode, base: String): Map<String, String> = buildMap {
            put("arteCompleta", "$base/avatardrawcard/$id.webp")
            put("arteRetrato", "$base/avatarshopicon/$id.webp")
            put("iconeMini", "$base/avatarroundicon/$id.webp")
            meta.path("damageType").asText("").ifBlank { null }
                ?.let { put("iconeElemento", "$base/element/${it.lowercase()}.webp") }
            meta.path("baseType").asText("").ifBlank { null }
                ?.let { put("iconeCaminho", "$base/pathicon/${it.lowercase()}.webp") }
        }

        /**
         * The ability icons nanoka publishes, keyed by the EN `type_name` [nanAbilities] keys on —
         * so an icon and its text can never be resolved from different abilities, exactly the
         * guarantee [srsSkillIcons] gives on the srs side.
         *
         * nanoka does NOT hang the icon on the skill (`skills[].icon` is null for every character).
         * It lives on the skill-TREE node that levels that skill, so the join runs
         * `skill_trees[point][level].level_up_skill_id` → `skills` (or `memosprite.skills`) →
         * `type_name`. Only levelable nodes carry an ability icon: `point_type` 2 is the main kit
         * and 4 is the memosprite/Euforia block; 1 (minor stat traces) and 3 (the A2/A4/A6 majors)
         * are not per-ability and have no column to fill. The file is named `.png` in the JSON and
         * served as `.webp`, hence the extension swap.
         */
        internal fun nanSkillIcons(detail: JsonNode?, base: String): Map<String, String> {
            val d = detail ?: return emptyMap()
            val tipoPorId = buildMap {
                for (bloco in listOf(d.path("skills"), d.path("memosprite").path("skills"))) {
                    bloco.fields().forEach { (sid, sk) ->
                        sk.path("type_name").asText("").trim().ifBlank { null }?.let { put(sid, it) }
                    }
                }
            }
            val out = LinkedHashMap<String, String>()
            d.path("skill_trees").fields().forEach { (_, niveis) ->
                // Every level of a point levels the SAME skill, so any of them carries the same
                // icon; "1" is always present and is taken first only to keep the walk deterministic.
                val no = niveis.path("1").takeUnless { it.isMissingNode }
                    ?: niveis.elements().asSequence().firstOrNull() ?: return@forEach
                if (no.path("point_type").asInt(0) !in ICONE_POR_HABILIDADE) return@forEach
                val arquivo = no.path("icon").asText("").substringBeforeLast('.').ifBlank { null } ?: return@forEach
                for (sid in no.path("level_up_skill_id")) {
                    val tipo = tipoPorId[sid.asText("")] ?: continue
                    out.putIfAbsent(tipo, "$base/skillicons/$arquivo.webp")
                    break
                }
            }
            return out
        }

        /** `point_type`s whose tree node stands for one levelable ability: 2 = main kit, 4 = memoespírito/Euforia. */
        private val ICONE_POR_HABILIDADE = setOf(2, 4)

        /** PT-first pick: the srs pair unless it's fully blank, then nanoka, then EMPTY. */
        private fun pick(srs: NamedText?, nan: NamedText?): NamedText =
            srs?.takeUnless { it.isBlank } ?: nan?.takeUnless { it.isBlank } ?: NamedText.EMPTY

        /**
         * Merges one display group — a `skillGrouping` bucket, or all servant skills of one type —
         * into a single ability. The first block gives the name + main description; every extra
         * block is appended under its own 「name」 header, exactly the additional-ability cards HSR
         * shows inline on a skill (Rin's 「Estilo Livre Tohsaka」, Gilgamesh's 「…Permissão…」,
         * Cyrene/Aglaea's memosprite 「Ode…」 variants) — which the old one-id-per-bucket harvest
         * silently dropped. [blocks] are already filled+stripped, in display order.
         */
        private fun mergeBlocks(blocks: List<NamedText>): NamedText {
            val primary = blocks.firstOrNull() ?: return NamedText.EMPTY
            val desc = buildString {
                primary.descricao?.let { append(it) }
                for (b in blocks.drop(1)) {
                    val d = b.descricao?.takeIf { it.isNotBlank() } ?: continue
                    if (isNotEmpty()) append("\n\n")
                    b.nome?.takeIf { it.isNotBlank() }?.let { append("「").append(it).append("」\n") }
                    append(d)
                }
            }.ifBlank { null }
            return NamedText(primary.nome, desc)
        }

        /**
         * srs canonical skills as name/desc pairs, keyed by PT type tag ("Talento"…). Descriptions
         * are filled at the realistically achievable level ([ABILITY_CAP_PT]), not the eidolon/
         * trace-boosted max, and a bucket's extra ids are merged in as 「name」 blocks ([mergeBlocks]).
         * When [enhanced], the enhanced variant of each skill is used ([srsCanonicalBuckets]).
         */
        private fun srsAbilities(detail: JsonNode, enhanced: Boolean): Map<String, NamedText> =
            srsCanonicalBuckets(detail, enhanced).mapNotNull { bucket ->
                val tag = strip(bucket.firstOrNull()?.path("typeDescHash")?.asText("") ?: "")
                if (tag.isBlank()) return@mapNotNull null
                val cap = ABILITY_CAP_PT[tag]
                tag to mergeBlocks(bucket.map { sk ->
                    NamedText(
                        strip(sk.path("name").asText("")).ifBlank { null },
                        fill(sk.path("descHash").asText(""), srsParamsCapped(sk.path("levelData"), cap)).ifBlank { null },
                    )
                })
            }.toMap()

        /**
         * The canonical skills grouped by ability via `skillGrouping` — one bucket (list of skill
         * nodes, in display order) per ability. A base bucket keeps ALL its ids: the first is the
         * main skill, the rest are the inline 「name」 sub-ability blocks the game shows on the same
         * card (Rin's 「Estilo Livre Tohsaka」, Gilgamesh's 「…Permissão…」). An enhanced-state
         * character instead keeps its enhanced kit in a whole alternate detail under **`.enhanced`**
         * (its own `skills` + `skillGrouping`) — Kafka/Silver Wolf reuse the base ability NAMES there
         * with enhanced descriptions — so for [enhanced] we source from `.enhanced` and take only the
         * LAST id of each bucket (Firefly's `.enhanced` keeps base+enhanced pairs, enhanced last),
         * not the extra-block merge. Falls back to name-deduped skills (each its own 1-node bucket)
         * when `skillGrouping` is absent.
         */
        internal fun srsCanonicalBuckets(detail: JsonNode, enhanced: Boolean): List<List<JsonNode>> {
            val source = if (enhanced && detail.path("enhanced").path("skills").let { it.isArray && !it.isEmpty })
                detail.path("enhanced") else detail
            val skills = source.path("skills")
            val byId = skills.associateBy { it.path("id").asLong() }
            val grouping = source.path("skillGrouping")
            if (!grouping.isArray || grouping.isEmpty) return skills.distinctBy { it.path("name").asText("") }.map { listOf(it) }
            return grouping.map { group ->
                if (enhanced) listOfNotNull(byId[group.path(group.size() - 1).asLong()])
                else group.mapNotNull { byId[it.asLong()] }
            }
        }

        /**
         * nanoka abilities keyed by EN type_name, filled at [ABILITY_CAP_EN]. nanoka keeps the
         * enhanced kit under `.enhanced.<state>.skills` (state key "1"), NOT as duplicates in the
         * base `.skills` for every character (Kafka has none there). So we take the base kit
         * (first per type) and then, when [enhanced], overlay the enhanced skills on top — the last
         * enhanced entry per type winning, so Firefly's base+enhanced pair resolves to the enhanced.
         */
        private fun nanAbilities(detail: JsonNode, enhanced: Boolean): Map<String, NamedText> {
            val out = LinkedHashMap<String, NamedText>()
            fun add(sk: JsonNode, overwrite: Boolean) {
                val type = sk.path("type_name").asText("").trim()
                if (type.isBlank() || (!overwrite && out.containsKey(type))) return
                val raw = sk.path("desc").asText("").ifBlank { sk.path("simple_desc").asText("") }
                out[type] = NamedText(strip(sk.path("name").asText("")).ifBlank { null }, fill(raw, nanParamsCapped(sk.path("level"), ABILITY_CAP_EN[type])).ifBlank { null })
            }
            children(detail.path("skills")).forEach { add(it, overwrite = false) }
            if (enhanced) {
                children(detail.path("enhanced")).lastOrNull()?.path("skills")?.let { children(it) }?.forEach { add(it, overwrite = true) }
            }
            return out
        }

        /**
         * srs memosprite Skill/Talent (Recordação only) from `.servant.skills`, keyed "skill"/
         * "talent". Servant skills use their own field names (`typeDesc`, `skillDesc`) and are
         * grouped by `.servant.skillGrouping` (bucket order = display order), so — like the main
         * abilities — a bucket's extra ids are merged in as 「name」 blocks ([mergeBlocks]): Cyrene's
         * memosprite Skill carries 16 per-ally 「Ode…」 variants, Aglaea's Talent three. Falls back to
         * grouping-by-type (array order) when a servant has no skillGrouping. [] for non-Recordação.
         */
        private fun srsMemosprite(detail: JsonNode): Map<String, NamedText> =
            srsMemospriteBuckets(detail).mapValues { (_, bucket) ->
                mergeBlocks(bucket.map { sk ->
                    NamedText(
                        strip(sk.path("name").asText("")).ifBlank { null },
                        fill(sk.path("skillDesc").asText(""), srsParamsCapped(sk.path("levelData"), MEMO_CAP)).ifBlank { null },
                    )
                })
            }

        /**
         * The same two abilities' icons, keyed the same way. Servant nodes carry the asset under
         * **`icon`**, not the `iconPath` every other srs node uses, and only the bucket's first
         * skill contributes — the extra 「name」 blocks share the ability's single card icon, exactly
         * as [srsSkillIcons] treats the main kit.
         */
        internal fun srsMemospriteIcons(detail: JsonNode?): Map<String, String> =
            srsMemospriteBuckets(detail ?: return emptyMap())
                .mapNotNull { (key, bucket) -> assetHash(bucket.firstOrNull(), "icon")?.let { key to it } }
                .toMap()

        /** `.servant.skills` grouped into one bucket per memosprite ability, keyed "skill"/"talent". */
        private fun srsMemospriteBuckets(detail: JsonNode): Map<String, List<JsonNode>> {
            val servant = detail.path("servant")
            val skills = children(servant.path("skills"))
            if (skills.isEmpty()) return emptyMap()
            val byId = servant.path("skills").associateBy { it.path("id").asLong() }
            val grouping = servant.path("skillGrouping")
            val buckets: List<List<JsonNode>> =
                if (grouping.isArray && !grouping.isEmpty)
                    children(grouping).map { g -> children(g).mapNotNull { byId[it.asLong()] } }
                else skills.groupBy { strip(it.path("typeDesc").asText("")) }.values.toList()
            val out = LinkedHashMap<String, List<JsonNode>>()
            for (bucket in buckets) {
                val key = when (strip(bucket.firstOrNull()?.path("typeDesc")?.asText("") ?: "")) {
                    "Perícia do Memoespírito" -> "skill"
                    "Talento do Memoespírito" -> "talent"
                    else -> continue
                }
                if (out.containsKey(key)) continue
                out[key] = bucket
            }
            return out
        }

        /** nanoka memosprite skills keyed by type_name ("Memosprite Skill"/"Memosprite Talent"). */
        private fun nanMemosprite(detail: JsonNode): Map<String, NamedText> = buildMap {
            children(detail.path("memosprite").path("skills")).forEach { sk ->
                val type = sk.path("type_name").asText("").trim()
                if (type.isBlank() || containsKey(type)) return@forEach
                val raw = sk.path("desc").asText("").ifBlank { sk.path("simple_desc").asText("") }
                put(type, NamedText(strip(sk.path("name").asText("")).ifBlank { null }, fill(raw, nanParamsCapped(sk.path("level"), MEMO_CAP)).ifBlank { null }))
            }
        }

        /**
         * The 3 major traces (A2/A4/A6). An enhanced character's `skillTreePoints` carries BOTH the
         * base (`enhanceId == 0`) and the enhanced (`enhanceId > 0`) variant of each trace — same
         * names, different text — so for [enhanced] we keep the enhanced variant (falling back to
         * base when a character has no enhanced trace). `.enhanced.skillTreePoints` is empty, so the
         * enhanced traces live in the BASE tree, unlike the enhanced skills/eidolons.
         */
        private fun srsTraces(detail: JsonNode?, enhanced: Boolean): List<NamedText> {
            detail ?: return emptyList()
            return srsMajorTraces(detail, enhanced).map { pt ->
                val bonus = pt.path("embedBonusSkill")
                NamedText(
                    strip(bonus.path("name").asText("")).ifBlank { null },
                    fill(bonus.path("descHash").asText(""), srsMaxLevel(bonus.path("levelData"))).ifBlank { null },
                )
            }.filterNot { it.isBlank }
        }

        /** type==1 trace nodes (recursive walk); the enhanceId>0 variant for [enhanced], else base. */
        private fun srsMajorTraces(detail: JsonNode, enhanced: Boolean): List<JsonNode> {
            val nodes = mutableListOf<JsonNode>()
            fun walk(n: JsonNode) {
                // add(), not +=: JsonNode is Iterable<JsonNode>, so += concat-copies its children.
                if (n.path("type").asInt() == 1) nodes.add(n)
                n.path("children").forEach { walk(it) }
            }
            detail.path("skillTreePoints").forEach { walk(it) }
            val base = nodes.filter { it.path("enhanceId").asInt(0) == 0 }
            if (!enhanced) return base
            return nodes.filter { it.path("enhanceId").asInt(0) > 0 }.ifEmpty { base }
        }

        private fun nanTraces(detail: JsonNode?, enhanced: Boolean): List<NamedText> {
            detail ?: return emptyList()
            val enhTrees = if (enhanced) children(detail.path("enhanced")).lastOrNull()?.path("skill_trees") else null
            val trees = enhTrees?.takeIf { !it.isEmpty } ?: detail.path("skill_trees")
            return children(trees).mapNotNull { pt ->
                val node = pt.path("1")
                val raw = node.path("point_desc").asText("").trim()
                if (raw.isBlank()) return@mapNotNull null
                NamedText(strip(node.path("point_name").asText("")).ifBlank { null }, fill(raw, nanParams(node.path("param_list"))).ifBlank { null })
            }
        }

        /** Eidolons; the enhanced set (`.enhanced.ranks`, Kafka's differ from base) for [enhanced]. */
        private fun srsEidolons(detail: JsonNode?, enhanced: Boolean): List<NamedText> {
            detail ?: return emptyList()
            val enh = detail.path("enhanced").path("ranks")
            val ranks = if (enhanced && enh.isArray && !enh.isEmpty) enh else detail.path("ranks")
            return children(ranks).map { rk ->
                NamedText(strip(rk.path("name").asText("")).ifBlank { null }, fill(rk.path("descHash").asText(""), srsParams(rk.path("params"))).ifBlank { null })
            }
        }

        private fun nanEidolons(detail: JsonNode?, enhanced: Boolean): List<NamedText> {
            detail ?: return emptyList()
            val enhRanks = if (enhanced) children(detail.path("enhanced")).lastOrNull()?.path("ranks") else null
            val ranks = enhRanks?.takeIf { !it.isEmpty } ?: detail.path("ranks")
            return children(ranks).map { rk ->
                NamedText(strip(rk.path("name").asText("")).ifBlank { null }, fill(rk.path("desc").asText(""), nanParams(rk.path("param_list"))).ifBlank { null })
            }
        }

        /** srs stories as [detalhes, parte1..4] (detalhes "" if absent so the parts keep their slots). */
        private fun srsStories(detail: JsonNode?): List<String> {
            val items = detail?.let { children(it.path("storyItems")) }.orEmpty()
            if (items.isEmpty()) return emptyList()
            val details = items.firstOrNull { it.path("title").asText("").startsWith("Detalhes") }
            val parts = items.filter { it.path("title").asText("").contains("Parte") }
            return (listOf(details) + parts).map { strip(it?.path("text")?.asText("") ?: "") }
        }

        /** nanoka `chara_info.stories` object keyed "0".."4" → [detalhes, parte1..4]. */
        private fun nanStories(detail: JsonNode?): List<String> {
            val stories = detail?.path("chara_info")?.path("stories") ?: return emptyList()
            if (stories.isMissingNode || (0..4).all { stories.path(it.toString()).asText("").isBlank() }) return emptyList()
            return (0..4).map { strip(stories.path(it.toString()).asText("")) }
        }

        // ---------------- relics / ornaments (srs only) ---------------- //

        internal fun buildReliquia(entry: JsonNode, detail: JsonNode?): Reliquia {
            val bonuses = setBonuses(entry)
            val pieces = detail?.let { children(it.path("pieces")) }.orEmpty()
            return Reliquia(
                nome = strip(entry.path("name").asText("")),
                efeito2Pecas = bonuses[2],
                efeito4Pecas = bonuses[4],
                cabeca = piece(pieces, 0),
                maos = piece(pieces, 1),
                corpo = piece(pieces, 2),
                pes = piece(pieces, 3),
                gameId = entry.path("pageId").asText("").ifBlank { null },
                icone = assetHash(entry, "iconPath"),
                cabecaIcone = pieceIcon(pieces, 0),
                maosIcone = pieceIcon(pieces, 1),
                corpoIcone = pieceIcon(pieces, 2),
                pesIcone = pieceIcon(pieces, 3),
            )
        }

        internal fun buildOrnamento(entry: JsonNode, detail: JsonNode?): OrnamentoPlano {
            val bonuses = setBonuses(entry)
            val pieces = detail?.let { children(it.path("pieces")) }.orEmpty()
            return OrnamentoPlano(
                nome = strip(entry.path("name").asText("")),
                efeito2Pecas = bonuses[2],
                esfera = piece(pieces, 0),
                corda = piece(pieces, 1),
                gameId = entry.path("pageId").asText("").ifBlank { null },
                icone = assetHash(entry, "iconPath"),
                esferaIcone = pieceIcon(pieces, 0),
                cordaIcone = pieceIcon(pieces, 1),
            )
        }

        /** Set bonuses keyed by piece count (2/4), from the index entry's `skills` (desc + params). */
        private fun setBonuses(entry: JsonNode): Map<Int, String?> =
            children(entry.path("skills")).associate { sk ->
                sk.path("useNum").asInt() to fill(sk.path("desc").asText(""), srsParams(sk.path("params"))).ifBlank { null }
            }

        /** A relic-detail piece → name + lore (falling back to the shorter miniLore). */
        private fun piece(pieces: List<JsonNode>, i: Int): NamedText {
            val p = pieces.getOrNull(i) ?: return NamedText.EMPTY
            val descRaw = p.path("lore").asText("").ifBlank { p.path("miniLore").asText("") }
            return NamedText(strip(p.path("name").asText("")).ifBlank { null }, strip(descRaw).ifBlank { null })
        }

        /** The icon of the same piece [piece] reads at [i] — kept parallel so the order can't diverge. */
        private fun pieceIcon(pieces: List<JsonNode>, i: Int): String? = assetHash(pieces.getOrNull(i), "iconPath")

        // ---------------- light cones ---------------- //

        // Effect text is level-locked by rarity so it reads like what a normal player owns:
        // 5★ cones at superimpose 1 (the pull-and-forget baseline), 4★/3★ at superimpose 5
        // (cheap to max from the shop/battle pass). The `level`/`levelData` "level" IS superimpose.
        internal fun buildSrsCone(coneGameId: String, detail: JsonNode): ConeDeLuz {
            val skill = detail.path("skill")
            val rarity = detail.path("rarity").asInt(0).takeIf { it > 0 }
            val params = if (rarity == 5) srsMinLevel(skill.path("levelData")) else srsMaxLevel(skill.path("levelData"))
            return ConeDeLuz(
                coneGameId = coneGameId,
                nome = strip(detail.path("name").asText("")),
                caminho = strip(detail.path("baseType").path("name").asText("")).ifBlank { null },
                raridade = rarity,
                efeitoNome = strip(skill.path("name").asText("")).ifBlank { null },
                efeitoDescricao = fill(skill.path("descHash").asText(""), params).ifBlank { null },
                descricao = fill(detail.path("descHash").asText(""), emptyList()).ifBlank { null },
                // The portrait crop the build card shows, not the 128x128 square `iconPath`.
                icone = assetHash(detail, "mediumIconPath"),
            )
        }

        internal fun buildNanCone(coneGameId: String, meta: JsonNode, detail: JsonNode?, base: String): ConeDeLuz {
            val ref = detail?.path("refinements")
            val rarity = RARITY_DIGIT.find(meta.path("rank").asText(""))?.value?.toIntOrNull()
            return ConeDeLuz(
                coneGameId = coneGameId,
                nome = strip(meta.path("en").asText("")),
                caminho = meta.path("baseType").asText("").ifBlank { null }?.let { PATH_EN[it] ?: it },
                raridade = rarity,
                efeitoNome = ref?.path("name")?.asText("")?.let(::strip)?.ifBlank { null },
                efeitoDescricao = ref?.let {
                    val params = if (rarity == 5) nanMinLevel(it.path("level")) else nanMaxLevel(it.path("level"))
                    fill(it.path("desc").asText(""), params).ifBlank { null }
                },
                descricao = null,
                // nanoka's twin of srs `mediumIconPath` — the same portrait crop the build card draws.
                icone = "$base/lightconemediumicon/$coneGameId.webp",
            )
        }

        // ---------------- recommended builds (nanoka only) ---------------- //

        /**
         * One character's recommended [Build] from its nanoka detail JSON — the same lists
         * [com.hsrbot.knowledge.NanokaIngestionSource.buildDoc] renders: `set4_id_list` (cavern
         * relics) / `set2_id_list` (planar ornaments) / `lightcones` are shared game ids kept raw
         * for the FK join, each ordered best-first and capped to the table's 3 slots; `property_list`
         * mains and `sub_affix_property_list` substats are labelled via [BuildAnalyzer.statPt]; the
         * team is `teams[0].member_list` resolved to display names ([displayNames]), the character
         * itself first. Null when there's nothing to recommend (no relics and no cones). Pure.
         */
        internal fun buildBuild(charGameId: String, nanDetail: JsonNode, displayNames: Map<String, String>): Build? {
            val relics = nanDetail.path("relics")
            val four = idList(relics.path("set4_id_list")).take(3)
            val two = idList(relics.path("set2_id_list")).take(3)
            val cones = idList(nanDetail.path("lightcones")).take(3)
            if (four.isEmpty() && cones.isEmpty()) return null
            val mains = buildMap {
                relics.path("property_list").forEach { p ->
                    val slot = p.path("relic_type").asText("")
                    val stat = BuildAnalyzer.statPt(p.path("property_type").asText(""))
                    if (slot.isNotBlank() && stat.isNotBlank()) putIfAbsent(slot, stat)
                }
            }
            val subs = idList(relics.path("sub_affix_property_list"))
                .map { BuildAnalyzer.statPt(it) }.filter { it.isNotBlank() }
            val team = idList(nanDetail.path("teams").path(0).path("member_list")).mapNotNull { displayNames[it] }
            return Build(
                characterGameId = charGameId,
                reliquiaGameIds = four,
                ornamentoGameIds = two,
                coneGameIds = cones,
                mainStatCorpo = mains["BODY"],
                mainStatPes = mains["FOOT"],
                mainStatEsfera = mains["NECK"],
                mainStatCorda = mains["OBJECT"],
                substatusRecomendados = subs.takeIf { it.isNotEmpty() }?.joinToString(" > "),
                equipeRecomendada = (listOfNotNull(displayNames[charGameId]) + team)
                    .takeIf { it.isNotEmpty() }?.joinToString(", "),
            )
        }

        /** Non-blank string ids of a JSON array (nanoka ids are ints; `asText` renders them), in order. */
        private fun idList(node: JsonNode): List<String> =
            children(node).map { it.asText("") }.filter { it.isNotBlank() }
    }
}
