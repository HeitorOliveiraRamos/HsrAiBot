package com.hsrbot.rank

import com.hsrbot.card.Arte
import com.hsrbot.card.LinhaRank
import com.hsrbot.card.RankRenderer
import com.hsrbot.card.Ranking
import com.hsrbot.card.fichaBase
import com.hsrbot.card.str
import com.hsrbot.hsr.BuildAnalyzer
import com.hsrbot.hsr.HsrCharacterService
import com.hsrbot.hsr.ShowcaseProfile
import net.dv8tion.jda.api.entities.Guild
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.max

/**
 * O lado de escrita e de leitura do `/rank`: guarda as notas que o `/build` já calculou e monta a
 * página que o [RankRenderer] desenha.
 *
 * A escrita é oportunista — roda dentro do `/build`, sobre a vitrine que ele acabou de buscar, e é
 * **fail-open** como o [com.hsrbot.knowledge.AnswerCache]: banco fora do ar atrasa o ranking, nunca
 * derruba a avaliação que o membro pediu.
 *
 * A leitura é o contrário: se ela falha, não há o que mostrar, então o erro sobe e o comando
 * responde a mensagem de erro. Em especial a resolução de membros — um ranking "do servidor" que
 * silenciosamente virasse global estaria mostrando gente de outro servidor.
 */
@Service
class RankingService(
    private val jdbc: JdbcTemplate,
    private val personagens: HsrCharacterService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // -------------------- escrita -------------------- //

    /**
     * Pontua e guarda a vitrine INTEIRA, não só a personagem que o `/build` pediu.
     *
     * Sai de graça: os dados já foram buscados, [BuildAnalyzer.analyze] é CPU pura, e um `/build`
     * passa a encher oito linhas em vez de uma — que é a diferença entre um ranking que existe e um
     * que fica vazio esperando as pessoas pedirem personagem por personagem.
     *
     * Quem o fribbels ainda não pontua é pulado: nota sem régua não significa nada, e é a mesma
     * recusa que o `/build` dá na cara do membro.
     */
    fun registrar(usuarioId: String, uid: String, profile: ShowcaseProfile) {
        runCatching {
            profile.characters.forEach { c ->
                val id = c.id.toIntOrNull() ?: return@forEach
                val r = BuildAnalyzer.analyze(c, personagens.fribbelsMeta(c.id)) ?: return@forEach
                // Nota CRUA, sem o teto de 10 que o card desenha: é ela que ordena o ranking, e duas
                // builds que passaram da relíquia de referência precisam continuar distinguíveis.
                jdbc.update(
                    UPSERT, usuarioId, id, uid, profile.nickname,
                    r.totalScore, r.totalRank, c.level, c.eidolon,
                )
            }
        }.onFailure { log.warn("não deu pra guardar as notas de {}: {}", usuarioId, it.message) }
    }

    // -------------------- leitura -------------------- //

    /**
     * Uma página do ranking, ou null quando não há nenhuma nota que caiba nele.
     *
     * [characterId] null é o ranking geral: TODAS as builds pontuadas, de todo mundo, ranqueadas
     * entre si — a mesma personagem pode ocupar várias posições, uma por dono.
     * [guild] null é o escopo global; com guild, as linhas saem filtradas por quem está no servidor
     * e os nomes passam a ser os do Discord.
     *
     * [pagina] pode vir de qualquer lugar (um botão antigo, uma lista que encolheu) e é dobrada de
     * volta pra dentro da faixa, igual o [com.hsrbot.discord.util.PageButtonListener] faz.
     */
    fun pagina(characterId: String?, guild: Guild?, pagina: Int): Ranking? {
        val alvo = characterId?.takeIf { it.toIntOrNull() != null }
        if (characterId != null && alvo == null) return null
        val brutas = if (alvo == null) jdbc.queryForList(GERAL)
        else jdbc.queryForList(POR_PERSONAGEM, alvo.toInt())
        val nomes = guild?.let { membros(it, brutas.mapNotNull { l -> str(l["usuario_id"]) }.toSet()) }
        val linhas = brutas
            .filter { nomes == null || str(it["usuario_id"]) in nomes }
            .mapIndexed { i, l ->
                Linha(
                    characterId = (l["character_id"] as Number).toInt(),
                    posicao = i + 1,
                    jogador = nomes?.get(str(l["usuario_id"])) ?: str(l["apelido"]) ?: "Anônimo",
                    nota = (l["nota"] as Number).toDouble(),
                    letra = str(l["letra"]).orEmpty(),
                    nivel = (l["nivel"] as Number).toInt(),
                    eidolon = (l["eidolon"] as Number).toInt(),
                )
            }
        if (linhas.isEmpty()) return null

        val (indice, fatia) = fatia(linhas, pagina)
        // O geral mostra a personagem em cada linha; o ranking de uma personagem, não — ali seria a
        // mesma cara dez vezes, e ela já é a coluna da esquerda inteira.
        val geral = alvo == null
        val icones = if (geral) icones(fatia.map { it.characterId }) else emptyMap()
        // O holofote é do topo DESTA página: assim a página 2 também tem uma capa, em vez de repetir
        // a #1 geral três vezes seguidas.
        val destaque = alvo ?: fatia.first().characterId.toString()

        return identidade(destaque).copy(
            titulo = if (alvo == null) "MELHORES BUILDS" else personagens.displayName(alvo),
            escopo = guild?.let { "SERVIDOR" } ?: "GLOBAL",
            servidorIcone = guild?.iconUrl,
            servidorNome = guild?.name,
            linhas = fatia.map { l ->
                LinhaRank(
                    posicao = l.posicao,
                    jogador = l.jogador,
                    nota = l.nota,
                    letra = l.letra,
                    personagem = if (geral) personagens.displayName(l.characterId.toString()) else null,
                    icone = icones[l.characterId],
                    nivel = l.nivel,
                    eidolon = l.eidolon,
                )
            },
            pagina = indice,
            paginas = paginas(linhas.size),
            total = linhas.size,
        )
    }

    // -------------------- resolução -------------------- //

    /**
     * A coluna da esquerda: splash, elemento, caminho e estrelas de quem está em destaque.
     *
     * Como no [com.hsrbot.card.AvaliacaoService], o banco é consultado só por arte — uma personagem
     * que `personagem_hsr` ainda não conhece rende um card sem ilustração, nunca um card faltando.
     */
    private fun identidade(characterId: String): Ranking {
        val nome = personagens.displayName(characterId)
        val p = characterId.toIntOrNull()?.let {
            runCatching {
                jdbc.queryForList("SELECT * FROM personagem_hsr WHERE character_id = ? LIMIT 1", it).firstOrNull()
            }.onFailure { e -> log.warn("arte de {} indisponível: {}", characterId, e.message) }.getOrNull()
        }
        val base = p?.let { fichaBase(it, nome, v2 = true) }
        return Ranking(
            nome = base?.nome ?: nome,
            elemento = base?.elemento,
            caminho = base?.caminho,
            raridade = base?.raridade ?: 5,
            arte = base?.arte ?: Arte(),
        )
    }

    private fun icones(ids: List<Int>): Map<Int, String?> {
        if (ids.isEmpty()) return emptyMap()
        return runCatching {
            jdbc.queryForList(
                "SELECT character_id, icone_mini FROM personagem_hsr WHERE character_id IN " +
                    "(${ids.joinToString(",") { "?" }})",
                *ids.toTypedArray(),
            ).associate { (it["character_id"] as Number).toInt() to str(it["icone_mini"]) }
        }.onFailure { log.warn("ícones mini indisponíveis: {}", it.message) }.getOrDefault(emptyMap())
    }

    /**
     * Quem, desses ids, está neste servidor — e com que nome aparece nele.
     *
     * As notas são globais de propósito (uma linha por pessoa+personagem, não por servidor), então é
     * aqui que o ranking do servidor ganha o recorte. `retrieveMembersByIds` é uma requisição de
     * gateway de verdade, em blocos de 100, e exige a intent `GUILD_MEMBERS` — ligada no
     * [com.hsrbot.discord.JdaConfig]. Bloquear no `get()` é seguro porque isto sempre roda no
     * executor, nunca na thread do gateway.
     *
     * O `Member` que volta também dá o apelido do servidor de graça, que é o nome pelo qual as
     * pessoas se conhecem ali — o nick do jogo fica pro ranking global.
     */
    private fun membros(guild: Guild, ids: Set<String>): Map<String, String> {
        val snowflakes = ids.mapNotNull { it.toLongOrNull() }
        if (snowflakes.isEmpty()) return emptyMap()
        return snowflakes.chunked(BLOCO_MEMBROS)
            .flatMap { bloco -> guild.retrieveMembersByIds(bloco).setTimeout(ESPERA_MEMBROS).get() }
            .associate { it.id to it.effectiveName }
    }

    /** Uma linha do ranking antes de virar desenho — ainda com o id, que o card não usa. */
    private data class Linha(
        val characterId: Int,
        val posicao: Int,
        val jogador: String,
        val nota: Double,
        val letra: String,
        val nivel: Int,
        val eidolon: Int,
    )

    companion object {

        /**
         * ponytail: teto explícito. Com a base atual todo mundo cabe folgado; num bot muito maior, um
         * membro na 301ª posição global sumiria do ranking do servidor dele. O conserto é uma tabela
         * de presença (usuário × servidor) escrita a cada interação, e aí o filtro vira SQL.
         */
        private const val TETO = 300

        /**
         * A build ATUAL da vitrine, não o recorde: uma nota pior sobrescreve a melhor de propósito,
         * senão desequipar uma relíquia deixaria a pessoa no pódio com uma build que não existe mais.
         */
        private const val UPSERT =
            "INSERT INTO nota_build (usuario_id, character_id, uid, apelido, nota, letra, nivel, eidolon) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (usuario_id, character_id) DO UPDATE SET " +
                "uid = EXCLUDED.uid, apelido = EXCLUDED.apelido, nota = EXCLUDED.nota, " +
                "letra = EXCLUDED.letra, nivel = EXCLUDED.nivel, eidolon = EXCLUDED.eidolon, " +
                "atualizado_em = now()"

        private const val COLUNAS = "usuario_id, character_id, apelido, nota, letra, nivel, eidolon"

        private const val POR_PERSONAGEM =
            "SELECT $COLUNAS FROM nota_build WHERE character_id = ? ORDER BY nota DESC LIMIT $TETO"

        /**
         * Toda build de todo mundo, ranqueadas entre si — sem `DISTINCT ON (character_id)`.
         *
         * O ranking geral é de JOGADORES: se as três melhores builds do bot são da mesma personagem,
         * o pódio é ela três vezes, com três donos diferentes. Deduplicar por personagem escondia
         * duas dessas três pessoas.
         *
         * Ordena pela nota CRUA, que é o motivo de o teto de 10 viver só no card: duas builds que
         * passaram da relíquia de referência desenham `10,00` as duas e mesmo assim saem na ordem
         * certa aqui. Guardar a nota cortada obrigaria a uma coluna de desempate; não guardar, não.
         *
         * ponytail: sem índice em `nota DESC`. Com algumas centenas de linhas o sort é grátis; se a
         * tabela crescer pra dezenas de milhares, uma migração com `CREATE INDEX ... (nota DESC)`.
         */
        private const val GERAL =
            "SELECT $COLUNAS FROM nota_build ORDER BY nota DESC LIMIT $TETO"

        private const val BLOCO_MEMBROS = 100

        private val ESPERA_MEMBROS: Duration = Duration.ofSeconds(15)

        internal fun paginas(total: Int): Int =
            max(1, ceil(total.toDouble() / RankRenderer.POR_PAGINA).toInt())

        /**
         * A página pedida, dobrada pra dentro da faixa, e as linhas dela. `floorMod` dá a volta nas
         * pontas (◀ na primeira vai pra última) e conserta um índice velho de um botão de quando a
         * lista era maior.
         */
        internal fun <T> fatia(linhas: List<T>, pagina: Int): Pair<Int, List<T>> {
            val indice = Math.floorMod(pagina, paginas(linhas.size))
            val de = indice * RankRenderer.POR_PAGINA
            return indice to linhas.subList(de, minOf(de + RankRenderer.POR_PAGINA, linhas.size))
        }
    }
}
