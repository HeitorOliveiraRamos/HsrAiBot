package com.hsrbot.hsr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the pure extraction of [SrsNanokaHarvester] against real srs + nanoka fixtures:
 * split abilities (base kit), memosprite (Recordação), euphoria (Euforia), relic/ornament pieces,
 * light-cone lore, and the signature-cone wiring.
 */
class SrsNanokaHarvesterTest {

    private val mapper = ObjectMapper()
    private fun load(name: String): JsonNode =
        mapper.readTree(javaClass.getResourceAsStream("/hsr/$name") ?: error("missing fixture $name"))

    // -------------------- characters -------------------- //

    @Test
    fun `base kit comes PT-first, split into nome and descricao`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1401",
            nanMeta = load("nanoka_index_1401.json"),
            srsEntry = load("srs_entry_theherta.json"),
            srsDetail = load("srs_detail_theherta.json"),
            nanDetail = load("nanoka_detail_1401.json"),
        )
        assertEquals("1401", p.characterId)
        assertEquals("A Herta", p.nome)
        assertEquals("The Herta", p.nomeEn)
        assertEquals("Gelo", p.elemento)
        assertEquals(5, p.raridade)
        assertEquals("Estação Espacial Herta", p.faccao)
        assertTrue(p.caminho!!.contains("Erudição"), "caminho: ${p.caminho}")
        // Abilities split: name in _nome, multiplier text in _descricao.
        assertEquals("Você Compreendeu?", p.atqBasico.nome)
        assertTrue(p.atqBasico.descricao!!.isNotBlank())
        assertTrue(p.periciaSuprema.nome!!.contains("Mágica Acontece"), "ult nome: ${p.periciaSuprema.nome}")
        assertEquals(6, p.eidolons.size)
        assertEquals(3, p.tracos.size)
        // Erudition unit → no memosprite, no euphoria.
        assertNull(p.periciaMemoespirito.nome)
        assertNull(p.talentoMemoespirito.nome)
        assertNull(p.periciaEuforia.nome)
    }

    @Test
    fun `Recordacao unit fills memosprite skill and talent, PT`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1402",
            nanMeta = load("nanoka_index_1402.json"),
            srsEntry = load("srs_entry_aglaea.json"),
            srsDetail = load("srs_detail_aglaea.json"),
            nanDetail = load("nanoka_detail_1402.json"),
        )
        assertEquals("Aglaea", p.nome)
        assertTrue(p.caminho!!.contains("Recordação"), "caminho: ${p.caminho}")
        assertEquals("Amphoreus", p.faccao)
        assertEquals("Armadilha de Espinhos", p.periciaMemoespirito.nome)
        assertTrue(p.periciaMemoespirito.descricao!!.isNotBlank())
        assertEquals("Um Corpo Feito de Lágrimas", p.talentoMemoespirito.nome)
        // Not an Elation unit.
        assertNull(p.periciaEuforia.nome)
    }

    @Test
    fun `Euforia unit fills the euphoria skill and has no memosprite`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1501",
            nanMeta = load("nanoka_index_1501.json"),
            srsEntry = load("srs_entry_sparxie.json"),
            srsDetail = load("srs_detail_sparxie.json"),
            nanDetail = load("nanoka_detail_1501.json"),
        )
        assertEquals("Sparxie", p.nome)
        assertTrue(p.caminho!!.contains("Euforia"), "caminho: ${p.caminho}")
        assertEquals("Explosão de Sinal: O Grande Bis!", p.periciaEuforia.nome)
        assertTrue(p.periciaEuforia.descricao!!.isNotBlank())
        assertNull(p.periciaMemoespirito.nome)
        assertNull(p.talentoMemoespirito.nome)
    }

    // -------------------- relics / ornaments -------------------- //

    @Test
    fun `cavern set carries 2 and 4pc bonuses and all four pieces`() {
        val r = SrsNanokaHarvester.buildReliquia(
            entry = load("srs_relics_entry_101.json"),
            detail = load("srs_relics_detail_101.json"),
        )
        assertEquals("Transeunte da Nuvem Errante", r.nome)
        assertTrue(r.efeito2Pecas!!.contains("10%"), "2pc: ${r.efeito2Pecas}") // #1[i]% of 0.1 → 10%
        assertTrue(r.efeito4Pecas!!.contains("Ponto de Perícia"), "4pc: ${r.efeito4Pecas}")
        assertEquals("Prendedor de Cabelo Rejuvenescido do Transeunte", r.cabeca.nome)
        assertTrue(r.cabeca.descricao!!.isNotBlank())
        assertTrue(r.pes.nome!!.contains("Botas"), "pés: ${r.pes.nome}")
    }

    @Test
    fun `planar set carries the 2pc bonus, sphere and rope`() {
        val o = SrsNanokaHarvester.buildOrnamento(
            entry = load("srs_relics_entry_301.json"),
            detail = load("srs_relics_detail_301.json"),
        )
        assertEquals("Estação de Vedação Espacial", o.nome)
        assertTrue(o.efeito2Pecas!!.isNotBlank())
        assertEquals("Estação Espacial de Herta", o.esfera.nome)
        assertEquals("Jornada Nômade de Herta", o.corda.nome)
    }

    // -------------------- light cones + signature -------------------- //

    @Test
    fun `srs cone carries effect and lore, and wires its signature owner`() {
        val cone = SrsNanokaHarvester.buildSrsCone("23036", load("srs_cone_23036.json"))
        assertEquals("Tempo Tecido em Ouro", cone.nome)
        assertEquals(5, cone.raridade)
        assertTrue(cone.caminho!!.contains("Recordação"), "caminho: ${cone.caminho}")
        assertEquals("Estabelecimento", cone.efeitoNome)
        // 5★ → superimpose 1: VEL base +12 (S1), NOT +20 (S5).
        assertTrue(cone.efeitoDescricao!!.contains("12"), "S1 effect: ${cone.efeitoDescricao}")
        assertFalse(cone.efeitoDescricao!!.contains("20"), "should not be S5: ${cone.efeitoDescricao}")
        assertTrue(cone.descricao!!.isNotBlank(), "descricao (lore) should be set")

        // Signature join: Aglaea's #1 recommended cone is this 5★ cone, so the harvest links them.
        val topCone = load("nanoka_detail_1402.json").path("lightcones").first().asText()
        assertEquals(cone.coneGameId, topCone)
    }

    @Test
    fun `4-star cone uses superimpose 5, not superimpose 1`() {
        val json = mapper.readTree(
            """{"name":"C","rarity":4,"baseType":{"name":"Destruição"},
               "skill":{"name":"E","descHash":"Aumenta o Dano em #1[i]%.",
                        "levelData":[{"level":1,"params":[0.24]},{"level":5,"params":[0.48]}]}}""",
        )
        val cone = SrsNanokaHarvester.buildSrsCone("21001", json)
        assertEquals(4, cone.raridade)
        assertTrue(cone.efeitoDescricao!!.contains("48"), "S5 effect: ${cone.efeitoDescricao}") // 0.48 → 48%
        assertFalse(cone.efeitoDescricao!!.contains("24"), "should not be S1: ${cone.efeitoDescricao}")
    }

    // -------------------- recommended builds -------------------- //

    @Test
    fun `build extracts recommended relics, ornaments, cones, main stats, substats and team`() {
        val names = mapOf("1402" to "Aglaea", "1415" to "Trailblazer", "1313" to "Sunday", "1409" to "Hyacine")
        val b = SrsNanokaHarvester.buildBuild("1402", load("nanoka_detail_1402.json"), names)!!
        assertEquals("1402", b.characterGameId)
        // Shared game ids kept raw, best-first, capped to 3.
        assertEquals(listOf("123", "102", "109"), b.reliquiaGameIds)
        assertEquals(listOf("318", "302", "301"), b.ornamentoGameIds)
        assertEquals(listOf("23036", "21051", "21052"), b.coneGameIds)
        // property_list slots -> PT stat labels.
        assertEquals("Chance Crít.", b.mainStatCorpo)     // BODY / CriticalChanceBase
        assertEquals("Velocidade", b.mainStatPes)          // FOOT / SpeedDelta
        assertEquals("Dano de Raio", b.mainStatEsfera)     // NECK / ThunderAddedRatio
        assertEquals("Regen. de Energia", b.mainStatCorda) // OBJECT / SPRatioBase
        assertTrue(b.substatusRecomendados!!.startsWith("Chance Crít. > Dano Crít."), "subs: ${b.substatusRecomendados}")
        // Team resolved to display names, the character itself first.
        assertEquals("Aglaea, Trailblazer, Sunday, Hyacine", b.equipeRecomendada)
    }

    @Test
    fun `build is null when there are no recommended relics or cones`() {
        assertNull(SrsNanokaHarvester.buildBuild("9999", mapper.createObjectNode(), emptyMap()))
    }

    // -------------------- enhanced states + level caps -------------------- //

    @Test
    fun `enhanced-state character extracts the enhanced kit, PT from srs`() {
        // Firefly (id 1310, srs pageId "sam") has hasEnhanced=true → the enhanced (SAM-form) skills.
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1310",
            nanMeta = load("nanoka_index_1310.json"),
            srsEntry = load("srs_entry_sam.json"),
            srsDetail = load("srs_detail_sam.json"),
            nanDetail = load("nanoka_detail_1310.json"),
        )
        assertEquals("Vaga-lume", p.nome)
        assertEquals("Firefly", p.nomeEn)
        // Enhanced Basic/Skill, not the base "Ordem: ..." forms.
        assertEquals("Vaga-lume Tipo-IV: Dizimação Pirogênica", p.atqBasico.nome)
        assertEquals("Vaga-lume Tipo-IV: Sobrecarga da Estrela da Morte", p.pericia.nome)
    }

    @Test
    fun `enhanced kit that only changes descriptions is sourced from the enhanced object (Kafka)`() {
        // Kafka's base skillGrouping is single-id (same ability names) — the enhanced descriptions
        // live ONLY under .enhanced, so the fix must source skills from there.
        val kafka = load("srs_detail_kafka.json")
        fun skillId(buckets: List<List<JsonNode>>, tag: String) =
            buckets.map { it.first() }.first { it.path("typeDescHash").asText() == tag }.path("id").asLong()
        assertEquals(25635354L, skillId(SrsNanokaHarvester.srsCanonicalBuckets(kafka, enhanced = true), "Perícia"))
        assertEquals(874958L, skillId(SrsNanokaHarvester.srsCanonicalBuckets(kafka, enhanced = false), "Perícia"))
        // End-to-end: the enhanced Skill description lands in pericia_descricao.
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1005",
            nanMeta = load("nanoka_index_1005.json"),
            srsEntry = load("srs_entry_kafka.json"),
            srsDetail = kafka,
            nanDetail = load("nanoka_detail_1005.json"),
        )
        assertEquals("Luar Carinhoso", p.pericia.nome)
        assertTrue(p.pericia.descricao!!.isNotBlank())
    }

    @Test
    fun `enhanced state also updates eidolons and traces (Kafka)`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1005",
            nanMeta = load("nanoka_index_1005.json"),
            srsEntry = load("srs_entry_kafka.json"),
            srsDetail = load("srs_detail_kafka.json"),
            nanDetail = load("nanoka_detail_1005.json"),
        )
        // Eidolon 1 "Da Capo": enhanced text ("Ao usar um ataque…"), not base ("Quando o Talento…").
        val e1 = p.eidolons[0].descricao!!
        assertTrue(e1.contains("Ao usar um ataque"), "E1 should be enhanced: $e1")
        assertTrue(!e1.contains("Quando o Talento ativa"), "E1 must not be the base text")
        // A2 trace (enhanceId=1 variant): enhanced text, not the base "Ao usar a Perícia Suprema…".
        val a2 = p.tracos[0].descricao!!
        assertTrue(a2.contains("Taxa de Acerto de Efeito do aliado"), "A2 should be enhanced: $a2")
        assertEquals(3, p.tracos.size)
    }

    @Test
    fun `a new enhanced kit in nanoka wins when srs has not published it yet`() {
        // srsDetail=null models srs lacking the (enhanced) kit — the one case nanoka overrides srs.
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1310",
            nanMeta = load("nanoka_index_1310.json"),
            srsEntry = null,
            srsDetail = null,
            nanDetail = load("nanoka_detail_1310.json"),
        )
        assertEquals("Fyrefly Type-IV: Pyrogenic Decimation", p.atqBasico.nome)
    }

    @Test
    fun `extra ids in a skillGrouping bucket are merged in as bracketed sub-ability blocks`() {
        // A base (non-enhanced) ability card that spans two skills — the shape behind the missing
        // 「Estilo Livre Tohsaka」 (Rin), 「…Permissão…」 (Gilgamesh) text. Both must land in descricao.
        val srsDetail = mapper.readTree(
            """
            {
              "skills": [
                {"id": 1, "typeDescHash": "Talento", "name": "Taumaturgia de Gemas",
                 "descHash": "Base talento ganha <nobr>#1[i]</nobr> ponto.",
                 "levelData": [{"level": 10, "params": [20]}]},
                {"id": 2, "typeDescHash": "Talento", "name": "Estilo Livre Tohsaka",
                 "descHash": "Ataque Extra causando <nobr>#1[i]%</nobr> do ATQ.",
                 "levelData": [{"level": 10, "params": [3.0]}]}
              ],
              "skillGrouping": [[1, 2]]
            }
            """.trimIndent(),
        )
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "9001", nanMeta = mapper.createObjectNode(), srsEntry = null, srsDetail = srsDetail, nanDetail = null,
        )
        assertEquals("Taumaturgia de Gemas", p.talento.nome)
        val d = p.talento.descricao!!
        assertTrue(d.startsWith("Base talento ganha 20 ponto."), "primary block first: $d")
        assertTrue(d.contains("「Estilo Livre Tohsaka」"), "extra block header missing: $d")
        assertTrue(d.contains("Ataque Extra causando 300% do ATQ."), "extra block body missing: $d")
    }

    @Test
    fun `ability params are filled at the capped level, not the eidolon-boosted max`() {
        // The Herta Basic ATK (id 14751013): lvl6 params start 1.0, the boosted lvl10 start 1.4.
        val basic = load("srs_detail_theherta.json").path("skills").first { it.path("id").asLong() == 14751013L }.path("levelData")
        val expected6 = basic.first { it.path("level").asInt() == 6 }.path("params").map { it.asDouble() }
        assertEquals(expected6, SrsNanokaHarvester.srsParamsCapped(basic, 6))
        assertTrue(SrsNanokaHarvester.srsParamsCapped(basic, 6) != SrsNanokaHarvester.srsParamsCapped(basic, null),
            "cap-6 must differ from the max-level params")

        // nanoka Firefly enhanced Basic (131008): lvl6 differs from the lvl10 max too.
        val nanBasicLevel = load("nanoka_detail_1310.json").path("skills").path("131008").path("level")
        val exp6 = nanBasicLevel.path("6").path("param_list").map { it.asDouble() }
        assertEquals(exp6, SrsNanokaHarvester.nanParamsCapped(nanBasicLevel, 6))
        assertTrue(SrsNanokaHarvester.nanParamsCapped(nanBasicLevel, 6) != SrsNanokaHarvester.nanParamsCapped(nanBasicLevel, null))
    }

    // -------------------- assets (V20 icon columns) -------------------- //

    @Test
    fun `character assets are extracted from srs, skill icons keyed by the same PT tag as the text`() {
        val detail = load("srs_detail_kafka.json")
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1005",
            nanMeta = load("nanoka_index_1005.json"),
            srsEntry = load("srs_entry_kafka.json"),
            srsDetail = detail,
            nanDetail = load("nanoka_detail_1005.json"),
        )
        assertEquals(detail.path("figPath").asText(), p.arteFigura)
        assertEquals(detail.path("artPath").asText(), p.arteCompleta)
        assertEquals(detail.path("miniIconPath").asText(), p.iconeMini)
        assertEquals(load("srs_entry_kafka.json").path("damageType").path("iconPath").asText(), p.iconeElemento)
        assertEquals(load("srs_entry_kafka.json").path("baseType").path("iconPath").asText(), p.iconeCaminho)

        // The four icons of the card's RASTROS row must come from the same buckets as the kit text:
        // Kafka is an enhanced unit, so both are sourced from `.enhanced`.
        val icons = SrsNanokaHarvester.srsSkillIcons(detail, enhanced = true)
        assertEquals(icons["ATQ Básico"], p.iconeAtqBasico)
        assertEquals(icons["Perícia"], p.iconePericia)
        assertEquals(icons["Perícia Suprema"], p.iconePericiaSuprema)
        assertEquals(icons["Talento"], p.iconeTalento)
        listOf(p.iconeAtqBasico, p.iconePericia, p.iconePericiaSuprema, p.iconeTalento).forEach {
            assertTrue(it != null && it.length == 64, "expected a 64-hex asset hash, got $it")
        }
        // Not a Euforia unit → no euphoria icon, exactly as the kit text has no euphoria ability.
        assertNull(p.iconePericiaEuforia)
    }

    @Test
    fun `Firefly has no cut-out figure, so the card falls back to the full art`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            id = "1310",
            nanMeta = load("nanoka_index_1310.json"),
            srsEntry = load("srs_entry_sam.json"),
            srsDetail = load("srs_detail_sam.json"),
            nanDetail = load("nanoka_detail_1310.json"),
        )
        assertNull(p.arteFigura, "Vaga-lume's figPath is blank upstream — must not become a bogus URL")
        assertTrue(p.arteCompleta != null && p.iconeMini != null, "the fallback art and mini icon must still be there")
    }

    @Test
    fun `relic and ornament piece icons follow the same slot order as the piece names`() {
        val entry = load("srs_relics_entry_101.json")
        val detail = load("srs_relics_detail_101.json")
        val r = SrsNanokaHarvester.buildReliquia(entry, detail)
        assertEquals(entry.path("iconPath").asText(), r.icone)
        val pieces = detail.path("pieces")
        assertEquals(pieces.path("1").path("iconPath").asText(), r.cabecaIcone)
        assertEquals(pieces.path("2").path("iconPath").asText(), r.maosIcone)
        assertEquals(pieces.path("3").path("iconPath").asText(), r.corpoIcone)
        assertEquals(pieces.path("4").path("iconPath").asText(), r.pesIcone)

        val oEntry = load("srs_relics_entry_301.json")
        val oDetail = load("srs_relics_detail_301.json")
        val o = SrsNanokaHarvester.buildOrnamento(oEntry, oDetail)
        assertEquals(oEntry.path("iconPath").asText(), o.icone)
        // Ornament pieces are keyed by their GAME slot (5 = Esfera Planar, 6 = Corda de Ligação),
        // not 1/2 like cavern relics — the extraction relies on document order, so pin the real keys.
        assertEquals(oDetail.path("pieces").path("5").path("iconPath").asText(), o.esferaIcone)
        assertEquals(oDetail.path("pieces").path("6").path("iconPath").asText(), o.cordaIcone)
    }

    @Test
    fun `cone stores the portrait crop, and the nanoka-only fallback stores none`() {
        val detail = load("srs_cone_23036.json")
        val cone = SrsNanokaHarvester.buildSrsCone("23036", detail)
        assertEquals(detail.path("mediumIconPath").asText(), cone.icone)
        assertFalse(cone.icone == detail.path("iconPath").asText(), "must be the 348x408 crop, not the square icon")

        // Beta cones srs doesn't list get their portrait from nanoka's lightconemediumicon CDN.
        val nan = SrsNanokaHarvester.buildNanCone("99999", load("nanoka_index_1005.json"), null, "https://static.nanoka.cc/assets/hsr")
        assertEquals("https://static.nanoka.cc/assets/hsr/lightconemediumicon/99999.webp", nan.icone)
    }

    // -------------------- custos de melhoria (V30) -------------------- //

    /**
     * The canonical 5★ totals, cell for cell, in the order the ascension card draws them —
     * personagem: crédito, livros de EXP, drop de boss, cálice 2/3/4; rastros: crédito, boss
     * semanal, Rastros de Destino, cálice 2/3/4, rastro 2/3/4. Every live 5★ produces exactly these,
     * and they match the guide team's hand-made reference art.
     */
    private val PERSONAGEM_5 = listOf(308_000L, 290L, 65L, 15L, 15L, 15L)
    private val RASTROS_5 = listOf(3_000_000L, 12L, 8L, 41L, 56L, 58L, 18L, 69L, 139L)

    @Test
    fun `upgrade costs fill both grids with the canonical 5-star totals, in draw order`() {
        val c = SrsNanokaHarvester.srsCustos(load("srs_detail_theherta.json"))!!
        assertEquals(PERSONAGEM_5, c.personagem.map { it.qtd })
        assertEquals(RASTROS_5, c.rastros.map { it.qtd })

        // The three calyx materials are the SAME items in both grids (15/15/15 there, 41/56/58
        // here), so their ids landing in the same slots is what proves the rarity ordering holds
        // across the two — without pinning any id the next patch could renumber.
        assertEquals(c.personagem.takeLast(3).map { it.id }, c.rastros.drop(3).take(3).map { it.id })
    }

    @Test
    fun `EXP books exclude the max level, which is never paid for`() {
        val calc = load("srs_detail_theherta.json").path("calculator").path("expCost")
        val tudo = calc.fieldNames().asSequence().sumOf { calc.path(it).asLong() }
        // Counting level 80 as well is the mistake that turns 290 books into 311.
        assertEquals(311L, (tudo + 19_999) / 20_000, "fixture no longer matches the 1..80 exp curve")
        assertEquals(290L, SrsNanokaHarvester.srsCustos(load("srs_detail_theherta.json"))!!.personagem[1].qtd)
    }

    @Test
    fun `a memosprite unit counts its servant block too`() {
        // Aglaea's memosprite skills carry their own trace costs. Skip the `servant` block and the
        // credits close at ~2.6M instead of 3M — the failure is silent, hence this test.
        val c = SrsNanokaHarvester.srsCustos(load("srs_detail_aglaea.json"))!!
        assertTrue(load("srs_detail_aglaea.json").path("servant").path("skills").size() > 0, "fixture has no servant")
        assertEquals(RASTROS_5, c.rastros.map { it.qtd })
    }

    @Test
    fun `an enhanced-state unit does not pay for its alternate kit twice`() {
        // Kafka's enhanced skills are extra `skills` entries inside the same skillGrouping buckets;
        // summing every entry instead of the bucket's first doubles most of the grid.
        val c = SrsNanokaHarvester.srsCustos(load("srs_detail_kafka.json"))!!
        assertEquals(RASTROS_5, c.rastros.map { it.qtd })
        assertEquals(PERSONAGEM_5, c.personagem.map { it.qtd })
    }

    @Test
    fun `no srs detail means no costs, rather than an empty grid`() {
        assertNull(SrsNanokaHarvester.srsCustos(null))
        assertNull(SrsNanokaHarvester.srsCustos(mapper.readTree("""{"itemReferences":{}}""")))
    }

    @Test
    fun `the material index keeps only what a character can consume`() {
        val index = mapper.readTree(
            """
            {"entries":[
              {"pageId":"29328","name":"Crédito","rarity":3,"purposeId":11,"iconPath":"${"a".repeat(64)}"},
              {"pageId":"125435","name":"Rastros de Destino","rarity":5,"purposeId":4,"iconPath":"${"b".repeat(64)}"},
              {"pageId":"29325","name":"Jade Estelar","rarity":5,"purposeId":12,"iconPath":"${"c".repeat(64)}"},
              {"pageId":"1","name":"Relíquia","rarity":2,"purposeId":19,"iconPath":"${"d".repeat(64)}"},
              {"pageId":"nao-numerico","name":"X","rarity":4,"purposeId":2,"iconPath":"${"e".repeat(64)}"},
              {"pageId":"7","name":"","rarity":4,"purposeId":2,"iconPath":"${"f".repeat(64)}"}
            ]}
            """.trimIndent(),
        )
        val m = SrsNanokaHarvester.buildMateriais(index)
        // Only the two usable buckets survive; the gacha currency, the relic XP, the non-numeric id
        // and the nameless entry are all dropped.
        assertEquals(listOf(29328, 125435), m.map { it.materialId })
        assertEquals("Crédito", m[0].nome)
        assertEquals("a".repeat(64), m[0].icone)
        assertEquals(3, m[0].raridade)
        assertEquals(11, m[0].propositoId)
        assertEquals(emptyList(), SrsNanokaHarvester.buildMateriais(null))
    }

    // -------------------- nanoka-only characters (betas srs hasn't published) -------------------- //

    private val NAN = "https://static.nanoka.cc/assets/hsr"

    /**
     * Robin • Summeretto (1512) and Aventurine • Waveflair (1513) are the reason this path exists:
     * srs lists 95 characters and neither is among them, so every field here is nanoka's or nothing.
     */
    @Test
    fun `a beta character takes its whole kit of icons and art from nanoka`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            "1512", load("nanoka_index_1512.json"), null, null, load("nanoka_detail_1512.json"), NAN,
        )
        assertEquals("Robin • Summeretto", p.nomeEn)
        // The internal codename lowercased, NOT the readable name: thunder/memory, not lightning/remembrance.
        assertEquals("$NAN/element/wind.webp", p.iconeElemento)
        assertEquals("$NAN/pathicon/memory.webp", p.iconeCaminho)
        assertEquals("$NAN/avatardrawcard/1512.webp", p.arteCompleta)
        assertEquals("$NAN/avatarshopicon/1512.webp", p.arteRetrato)
        assertEquals("$NAN/avatarroundicon/1512.webp", p.iconeMini)
        // The four kit icons plus both memosprite ones — a Recordação unit, so the servant pair is set.
        assertEquals("$NAN/skillicons/SkillIcon_1512_Normal.webp", p.iconeAtqBasico)
        assertEquals("$NAN/skillicons/SkillIcon_1512_BP.webp", p.iconePericia)
        assertEquals("$NAN/skillicons/SkillIcon_1512_Ultra.webp", p.iconePericiaSuprema)
        assertEquals("$NAN/skillicons/SkillIcon_1512_Passive.webp", p.iconeTalento)
        assertEquals("$NAN/skillicons/SkillIcon_11512_Servant.webp", p.iconePericiaMemoespirito)
        assertEquals("$NAN/skillicons/SkillIcon_11512_ServantPassive.webp", p.iconeTalentoMemoespirito)
        // Not a Euforia unit, and nanoka publishes no cut-out figure or scenery plate for anyone.
        assertNull(p.iconePericiaEuforia)
        assertNull(p.arteFigura)
        assertNull(p.arteFundo)
    }

    /** The other `point_type == 4` shape: an Euforia ability instead of a memosprite. */
    @Test
    fun `a beta Euforia unit gets its euphoria icon and no memosprite one`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            "1513", load("nanoka_index_1513.json"), null, null, load("nanoka_detail_1513.json"), NAN,
        )
        assertEquals("Aventurine • Waveflair", p.nomeEn)
        assertEquals("$NAN/element/quantum.webp", p.iconeElemento)
        assertEquals("$NAN/pathicon/elation.webp", p.iconeCaminho)
        assertEquals("$NAN/skillicons/SkillIcon_1513_Elation.webp", p.iconePericiaEuforia)
        assertNull(p.iconePericiaMemoespirito)
        assertNull(p.iconeTalentoMemoespirito)
    }

    /** With no nanoka base configured nothing is invented — the old srs-only behaviour, unchanged. */
    @Test
    fun `no nanoka base means no nanoka art`() {
        val p = SrsNanokaHarvester.buildPersonagem(
            "1512", load("nanoka_index_1512.json"), null, null, load("nanoka_detail_1512.json"),
        )
        listOf(p.arteCompleta, p.arteRetrato, p.iconeMini, p.iconeElemento, p.iconeCaminho, p.iconeAtqBasico)
            .forEach { assertNull(it) }
    }

    /**
     * The cost grids, cell for cell against the values srs reports for the released roster:
     * 6 cells of ascension (crédito, livros de EXP, drop de boss, cálice 2/3/4) and 9 of traces.
     * The quantities are the cross-check — 308.000 and 3.000.000 are what every 5★ costs.
     */
    @Test
    fun `a beta's upgrade costs come out of nanoka in the card's draw order`() {
        val refs = SrsNanokaHarvester.refsDeItens(load("nanoka_item_index.json"), mapper)
        val exp = SrsNanokaHarvester.nanLivrosExp(load("nanoka_item_index.json"), 290)
        assertEquals(213, exp?.id, "the biggest EXP book (Guia do Viajante), not the small ones")

        val c = SrsNanokaHarvester.nanCustos(load("nanoka_detail_1512.json"), refs, exp)!!
        assertEquals(
            listOf(2 to 308_000L, 213 to 290L, 110435 to 65L, 114001 to 15L, 114002 to 15L, 114003 to 15L),
            c.personagem.map { it.id to it.qtd },
        )
        assertEquals(9, c.rastros.size)
        assertEquals(2 to 3_000_000L, c.rastros[0].let { it.id to it.qtd })
        // Cálices and rastros are laid out in ascending rarity inside their own bucket.
        assertEquals(listOf(114001, 114002, 114003), c.rastros.map { it.id }.filter { it in 114001..114003 })
        assertEquals(listOf(110251, 110252, 110253), c.rastros.map { it.id }.filter { it in 110251..110253 })

        // Without the srs-sourced EXP quantity the grid loses that one cell, not the whole card.
        val semExp = SrsNanokaHarvester.nanCustos(load("nanoka_detail_1512.json"), refs, null)!!
        assertEquals(5, semExp.personagem.size)
        assertNull(SrsNanokaHarvester.nanLivrosExp(load("nanoka_item_index.json"), null))
        assertNull(SrsNanokaHarvester.nanCustos(null, refs, exp))
    }

    /** nanoka's material rows: PT names when StarRailRes has the id, its own English when it doesn't. */
    @Test
    fun `nanoka materials carry game item ids, itemfigures icons and PT names when available`() {
        val m = SrsNanokaHarvester.buildMateriaisNanoka(
            load("nanoka_item_index.json"), mapOf("2" to "Crédito"), NAN,
        ).associateBy { it.materialId }

        assertEquals("Crédito", m[2]?.nome)
        assertEquals("$NAN/itemfigures/2.webp", m[2]?.icone)
        assertEquals(11, m[2]?.propositoId)
        // No PT name for this one, so nanoka's English stands in rather than the row being dropped.
        assertEquals("Dream Collection Component", m[114001]?.nome)
        assertEquals(2, m[114001]?.raridade, "NotNormal is rarity 2, the same number srs states")
        assertEquals(7, m[114001]?.propositoId)
        assertEquals(emptyList(), SrsNanokaHarvester.buildMateriaisNanoka(null, emptyMap(), NAN))
    }

    @Test
    fun `asset hashes that are absent, blank or malformed never become a URL`() {
        val node = mapper.readTree(
            """{"good":"${"a".repeat(64)}","blank":"","short":"abc123","upper":"${"A".repeat(64)}","url":"http://x/y.webp"}""",
        )
        assertEquals("a".repeat(64), SrsNanokaHarvester.assetHash(node, "good"))
        listOf("blank", "short", "upper", "url", "missing").forEach {
            assertNull(SrsNanokaHarvester.assetHash(node, it), "field '$it' must not yield a hash")
        }
        assertNull(SrsNanokaHarvester.assetHash(null, "good"))
    }
}
