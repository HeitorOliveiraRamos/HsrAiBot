package com.hsrbot.card

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Turns a `personagem_hsr` row plus the `materiais` icon dictionary into an [Ascensao] — the
 * [FichaMapper] of the ascension card, shared by [AscensaoService] and
 * [AscensaoPreviewStandalone] so the row→model rules live in one place.
 *
 * Icons are looked up here rather than stored on the character, the same rule V24 states for a
 * guide's relic names: a patch that re-skins a material fixes every card at once.
 */
private val log = LoggerFactory.getLogger("AscensaoMapper")

private val mapper = ObjectMapper()

/**
 * The V30 JSONB, as much of it as the card needs. `@JsonIgnoreProperties` so a field added to
 * [CustosMelhoria] later doesn't break every stored row on the way back in.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class CustosJson(
    val personagem: List<Item> = emptyList(),
    val rastros: List<Item> = emptyList(),
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Item(val id: Int = 0, val qtd: Long = 0)
}

/**
 * The card for one character, or null when the row carries no `custos_melhoria` — the betas srs
 * hasn't published yet. Null rather than an empty grid: a card with two blank panels looks like a
 * rendering bug, and the caller has a better answer than showing one.
 */
internal fun ascensao(p: Map<String, Any?>, icones: Map<Int, String?>): Ascensao? {
    val custos = custos(p["custos_melhoria"]) ?: return null
    if (custos.personagem.isEmpty() && custos.rastros.isEmpty()) return null
    fun grade(itens: List<CustosJson.Item>) = itens.map { Custo(it.qtd, icones[it.id]) }
    return Ascensao(
        nome = str(p["nome"]) ?: str(p["nome_en"]).orEmpty(),
        elemento = str(p["elemento"]),
        caminho = str(p["caminho"]),
        raridade = (p["raridade"] as? Number)?.toInt() ?: 5,
        // Same art the build card's v2 layout uses — the whole illustration, placed by the curated
        // `arte_foco_*` box. A character without a box falls back to the bust, exactly like a guide.
        arte = Arte(
            completa = str(p["arte_completa"]),
            retrato = str(p["arte_retrato"]),
            figura = str(p["arte_figura"]),
            foco = foco(p),
            v2 = true,
        ),
        personagem = grade(custos.personagem),
        rastros = grade(custos.rastros),
    )
}

/**
 * `toString()`, never `as? String`: PgJDBC hands a jsonb column back as a `PGobject`, so the cast
 * silently yields null — and a card that then falls back to anything renders plausibly with no
 * icons and no error. Same read [com.hsrbot.guia.GuiaService] and
 * [com.hsrbot.tier.TierListService] do.
 */
private fun custos(valor: Any?): CustosJson? = valor?.let {
    runCatching { mapper.readValue(it.toString(), CustosJson::class.java) }.getOrElse { e ->
        log.warn("custos_melhoria ilegível, tratando como ausente: {}", e.message)
        null
    }
}
