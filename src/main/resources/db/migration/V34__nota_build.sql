-- A nota que o `/build` já calcula, guardada — é a matéria-prima do `/rank`.
--
-- Uma linha por (usuário, personagem) e NÃO por servidor: a nota é um fato sobre a build da pessoa,
-- não sobre onde ela rodou o comando. O ranking de um servidor sai filtrando estas linhas por quem
-- está lá dentro (JDA `retrieveMembersByIds`), o que mantém a nota igual em todo servidor onde a
-- pessoa aparece e dispensa uma segunda tabela de presença.
--
-- A chave é o `usuario_id`, não o `uid`: trocar de conta no `/uid` é trocar a vitrine, e a linha
-- acompanha a pessoa. O upsert SOBRESCREVE mesmo com nota pior — isto é a build atual da vitrine,
-- não recorde histórico, então desequipar relíquia tem que descer no ranking.
--
-- `character_id` é o mesmo id de `personagem_hsr` (INTEGER), a única chave que distingue as cinco
-- Desbravadoras — nome não é identidade aqui, pelo mesmo motivo documentado na V28.
CREATE TABLE nota_build (
    usuario_id    TEXT NOT NULL,        -- discord user id
    character_id  INTEGER NOT NULL,     -- personagem_hsr.character_id
    uid           VARCHAR(16) NOT NULL, -- a conta de onde a vitrine veio
    apelido       TEXT,                 -- nick no jogo; é o nome mostrado no ranking global
    nota          NUMERIC(4,2) NOT NULL,
    -- A letra de BuildAnalyzer.rank. Chamada `letra` e não `rank` porque `rank` é nome de função de
    -- janela no Postgres e viveria entre aspas em toda query.
    letra         TEXT NOT NULL,
    nivel         INTEGER NOT NULL DEFAULT 0,
    eidolon       INTEGER NOT NULL DEFAULT 0,
    atualizado_em TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (usuario_id, character_id)
);

-- As duas leituras do /rank: o ranking de uma personagem, e o `DISTINCT ON (character_id)` que tira
-- a melhor build de cada uma.
CREATE INDEX nota_build_ranking_idx ON nota_build (character_id, nota DESC);
