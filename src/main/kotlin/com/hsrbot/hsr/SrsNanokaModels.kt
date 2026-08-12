package com.hsrbot.hsr

/**
 * Domain rows for the richer SRS + nanoka schema (`personagem_hsr` / `reliquias` /
 * `ornamentos_planos` / `cones_de_luz`, migration V17) — the eventual replacement for the
 * V16 [HsrCharacter] cache. Unlike that one's joined `"Nome\ndescrição"` blobs, every ability,
 * eidolon and trace is a name/description pair ([NamedText]), and this carries relic/ornament
 * pieces, memosprite + euphoria abilities, and light-cone lore.
 *
 * Sourced PT-first from starrailstation, falling back to nanoka's English for betas srs hasn't
 * published — so every text field is nullable (a source may lack a given piece). Produced by
 * [SrsNanokaHarvester], stored by [SrsNanokaPopulator]. Kept separate from [HsrCharacter] so the
 * live bot's read paths stay untouched until they're switched over.
 */

/**
 * One `materiais` row (V30): an upgrade material the ascension card can show. [propositoId] is the
 * srs `purposeId` bucket — the same discriminator as `itemReferences[].type` — and [icone] is a
 * content hash, not a URL (V20 contract). [materialId] is the srs `pageId`, i.e. the game item id
 * a character's [CustoMaterial] points at.
 */
data class Material(
    val materialId: Int,
    val nome: String,
    val icone: String? = null,
    val raridade: Int? = null,
    val propositoId: Int,
)

/** One cell of an upgrade grid: how much of [id] the character needs. */
data class CustoMaterial(val id: Int, val qtd: Long)

/**
 * The two grids of the ascension guide card, each already in draw order, serialised whole into
 * `personagem_hsr.custos_melhoria` — so the field names ARE the JSON keys (see V30).
 * [personagem] holds 6 cells (crédito, livros de EXP, drop de boss, cálice 2/3/4) and [rastros] 9
 * (crédito, boss semanal, Rastros de Destino, cálice 2/3/4, rastro 2/3/4).
 */
data class CustosMelhoria(
    val personagem: List<CustoMaterial> = emptyList(),
    val rastros: List<CustoMaterial> = emptyList(),
)

/** A name + description pair, mapping one `_nome` / `_descricao` column pair. */
data class NamedText(val nome: String? = null, val descricao: String? = null) {
    val isBlank: Boolean get() = nome.isNullOrBlank() && descricao.isNullOrBlank()

    companion object {
        val EMPTY = NamedText()
    }
}

/**
 * One `personagem_hsr` row. [characterId] is the shared game id (nanoka index key == srs
 * `rankKey`, e.g. "1402"), stored in the `character_id` column; the table's own
 * `id_personagem_hsr` serial is assigned by the DB. [periciaMemoespirito]/[talentoMemoespirito]
 * are non-blank only for Recordação (Remembrance) units; [periciaEuforia] only for Euforia
 * (Elation) units. [tracos] holds A2/A4/A6 in order (≤3); [eidolons] holds E1..E6 in order (≤6);
 * [historias] holds partes 1..4 in order.
 *
 * The `icone*`/`arte*` fields are starrailstation asset content hashes, not URLs — resolved
 * against `bot.knowledge.srs-asset-url` when an image is actually needed. See V20/V21 for coverage:
 * [arteRetrato] is the card's splash (the only pre-framed one, 95/95), [arteFundo] the scenery
 * behind it, and [arteFigura] (94/95 — no Firefly) falls back to [arteCompleta].
 */
data class PersonagemHsr(
    val characterId: String,
    val nome: String? = null,
    val nomeEn: String? = null,
    val elemento: String? = null,
    val caminho: String? = null,
    val raridade: Int? = null,
    val faccao: String? = null,
    val descricao: String? = null,
    val atqBasico: NamedText = NamedText.EMPTY,
    val pericia: NamedText = NamedText.EMPTY,
    val periciaSuprema: NamedText = NamedText.EMPTY,
    val talento: NamedText = NamedText.EMPTY,
    val tecnica: NamedText = NamedText.EMPTY,
    val periciaMemoespirito: NamedText = NamedText.EMPTY,
    val talentoMemoespirito: NamedText = NamedText.EMPTY,
    val periciaEuforia: NamedText = NamedText.EMPTY,
    val tracos: List<NamedText> = emptyList(),
    val eidolons: List<NamedText> = emptyList(),
    val detalhesPersonagem: String? = null,
    val historias: List<String?> = emptyList(),
    val arteFigura: String? = null,
    val arteCompleta: String? = null,
    val arteRetrato: String? = null,
    val arteFundo: String? = null,
    val iconeMini: String? = null,
    val iconeElemento: String? = null,
    val iconeCaminho: String? = null,
    val iconeAtqBasico: String? = null,
    val iconePericia: String? = null,
    val iconePericiaSuprema: String? = null,
    val iconeTalento: String? = null,
    val iconePericiaEuforia: String? = null,
    val iconeTalentoMemoespirito: String? = null,
    val iconePericiaMemoespirito: String? = null,
    /** The ascension card's two grids; null for betas srs hasn't published (no cost data). */
    val custosMelhoria: CustosMelhoria? = null,
)

/**
 * One `reliquias` row: a Cavern set's 2/4-piece bonuses plus its four pieces (Cabeça…Pés).
 * [gameId] is transient (the shared game id, srs `pageId` == the nanoka set id a build's
 * `set4_id_list` references) — carried only in memory so [SrsNanokaPopulator] can resolve a
 * recommended build's relic FKs, never a column.
 */
data class Reliquia(
    val nome: String,
    val efeito2Pecas: String? = null,
    val efeito4Pecas: String? = null,
    val cabeca: NamedText = NamedText.EMPTY,
    val maos: NamedText = NamedText.EMPTY,
    val corpo: NamedText = NamedText.EMPTY,
    val pes: NamedText = NamedText.EMPTY,
    val gameId: String? = null,
    val icone: String? = null,
    /** Piece icons in the same cabeça→mãos→corpo→pés order as the pairs above. */
    val cabecaIcone: String? = null,
    val maosIcone: String? = null,
    val corpoIcone: String? = null,
    val pesIcone: String? = null,
)

/**
 * One `ornamentos_planos` row: a Planar set's 2-piece bonus plus its Sphere and Rope.
 * [gameId] mirrors [Reliquia.gameId] (srs `pageId` == the nanoka set id a build's
 * `set2_id_list` references) — transient, for the builds FK join only.
 */
data class OrnamentoPlano(
    val nome: String,
    val efeito2Pecas: String? = null,
    val esfera: NamedText = NamedText.EMPTY,
    val corda: NamedText = NamedText.EMPTY,
    val gameId: String? = null,
    val icone: String? = null,
    /** Piece icons in the same esfera→corda order as the pairs above. */
    val esferaIcone: String? = null,
    val cordaIcone: String? = null,
)

/**
 * One `builds` row — a character's recommended build, from nanoka's per-character recommendation
 * lists (the same `set4_id_list`/`set2_id_list`/`lightcones`/`property_list`/`teams` the build
 * docs are rendered from). Item references are shared game ids, ordered best-first and capped to
 * the table's 3 slots; [SrsNanokaPopulator] resolves them to FKs via the same PK maps as the
 * signature link. The stat/team fields are pre-rendered PT strings (free-TEXT columns).
 */
data class Build(
    val characterGameId: String,
    val reliquiaGameIds: List<String> = emptyList(),
    val ornamentoGameIds: List<String> = emptyList(),
    val coneGameIds: List<String> = emptyList(),
    val mainStatCorpo: String? = null,
    val mainStatPes: String? = null,
    val mainStatEsfera: String? = null,
    val mainStatCorda: String? = null,
    val substatusRecomendados: String? = null,
    val equipeRecomendada: String? = null,
)

/**
 * One `cones_de_luz` row. [coneGameId] is transient (the shared game cone id) — carried only in
 * memory to resolve the signature link, never a column. `id_personagem_hsr_atribuido` is set
 * afterward from the signature map, not here.
 */
data class ConeDeLuz(
    val coneGameId: String = "", // transient; "" when read back from the DB (no game-id column)
    val nome: String,
    val caminho: String? = null,
    val raridade: Int? = null,
    val efeitoNome: String? = null,
    val efeitoDescricao: String? = null,
    val descricao: String? = null,
    /** srs `mediumIconPath` (348x408 portrait crop) — null for the few cones only nanoka lists. */
    val icone: String? = null,
)

/**
 * A full harvest. [signatureLinks] maps a cone game id → the character game id it's the signature
 * cone for (each character's #1 recommended cone), resolved into
 * `cones_de_luz.id_personagem_hsr_atribuido` after both tables are written.
 */
data class SrsNanokaData(
    val personagens: List<PersonagemHsr>,
    val reliquias: List<Reliquia>,
    val ornamentos: List<OrnamentoPlano>,
    val cones: List<ConeDeLuz>,
    val signatureLinks: Map<String, String>,
    val builds: List<Build> = emptyList(),
    val materiais: List<Material> = emptyList(),
)
