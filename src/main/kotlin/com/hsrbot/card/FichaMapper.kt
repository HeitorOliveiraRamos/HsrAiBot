package com.hsrbot.card

import org.slf4j.LoggerFactory

/**
 * Turns a `personagem_hsr` row into the part of a [Ficha] that is the same whoever asked for the
 * card: identity, art and the trace icons. What differs — curated `builds` data vs. a guide the
 * user wrote — is copied on top by the caller.
 */
private val log = LoggerFactory.getLogger("FichaMapper")

/**
 * Trace label → the column holding its icon, in the order a guide offers them.
 *
 * The last three are path-gated: only A Euforia units have a euphoria skill and only A Recordação
 * units have a memosprite, so those columns are null everywhere else. That null IS the gate — the
 * `/guia` form builds its trace options from the columns this character actually has, so nothing
 * can be chosen that [rastro] would then drop.
 */
internal val RASTRO_ICONE = mapOf(
    "Talento" to "icone_talento",
    "Perícia Suprema" to "icone_pericia_suprema",
    "Perícia" to "icone_pericia",
    "Ataque Básico" to "icone_atq_basico",
    "Perícia da Euforia" to "icone_pericia_euforia",
    "Talento do Memoespírito" to "icone_talento_memoespirito",
    "Perícia do Memoespírito" to "icone_pericia_memoespirito",
)

internal fun fichaBase(p: Map<String, Any?>, nomeFallback: String, v2: Boolean) = Ficha(
    nome = str(p["nome"]) ?: nomeFallback,
    faccao = str(p["faccao"]),
    elemento = str(p["elemento"]),
    caminho = str(p["caminho"]),
    raridade = (p["raridade"] as? Number)?.toInt() ?: 5,
    arte = Arte(
        completa = str(p["arte_completa"]),
        retrato = str(p["arte_retrato"]),
        figura = str(p["arte_figura"]),
        foco = foco(p),
        v2 = v2,
    ),
)

/** The icon for one trace, or null when the character has no such ability (euphoria, memosprite). */
internal fun rastro(p: Map<String, Any?>, rotulo: String): Rastro? =
    RASTRO_ICONE[rotulo]?.let { col -> str(p[col])?.let { Rastro(rotulo, it) } }

/**
 * The four `arte_foco_*` columns as a box. Ranges are enforced by the V23 CHECKs, so the only case
 * left here is a partially filled row — treated as absent, which drops v2 back to the v1 layout.
 */
internal fun foco(p: Map<String, Any?>): Foco? {
    val v = listOf("arte_foco_x", "arte_foco_y", "arte_foco_largura", "arte_foco_altura")
        .map { (p[it] as? Number)?.toDouble() }
    if (v.any { it == null }) {
        if (v.any { it != null }) log.warn("arte_foco_* incompleto, ignorando: {}", v)
        return null
    }
    return Foco(v[0]!!, v[1]!!, v[2]!!, v[3]!!)
}
