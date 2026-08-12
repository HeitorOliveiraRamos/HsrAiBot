-- Build guides written by the builds team through `/guia`, as opposed to the harvested `builds`
-- row (which is one curated recommendation per character and is overwritten by every populate run).
--
-- `spec` is the form itself — the author's CHOICES: set names, superimposition ranks, piece splits,
-- status targets. It is JSONB and not a set of columns because the shape is three repeating
-- structures (cone lines with ranks, relic lines that may hold two sets each, N status targets):
-- normalising that is four child tables that only the card renderer ever reads back. Icons and
-- canonical names are NOT stored — they are resolved against personagem_hsr/reliquias/
-- ornamentos_planos/cones_de_luz at render time, so a guide keeps working after an asset changes.
--
-- One row covers a guide's whole life:
--   hash IS NULL ......... draft, still being filled in by the wizard
--   hash IS NOT NULL ..... finished; the hash is sha256 over the canonical spec JSON
--   publicado_em ......... posted to a channel
--
-- The hash is what makes "same answers, same card" free: a finished guide with a matching hash is
-- reused instead of re-rendered, and the rendered PNG is cached on disk under that name. Bytes stay
-- out of Postgres for the same reason V20 keeps asset bytes out — a card re-renders deterministically
-- from the spec, so the cache is disposable and the dumps stay small.
CREATE TABLE guia (
    id_guia           SERIAL PRIMARY KEY,
    chave             TEXT NOT NULL UNIQUE,   -- short id carried in componentIds, as in resposta_paginada
    hash              TEXT,
    -- Denormalised for "guias do Blade" lookups. The spec carries the character's game id too, so a
    -- guide survives personagem_hsr being dropped and repopulated (V17/V18 did exactly that).
    id_personagem_hsr INTEGER REFERENCES personagem_hsr(id_personagem_hsr) ON DELETE SET NULL,
    autor_id          TEXT NOT NULL,          -- discord user id
    spec              JSONB NOT NULL,
    publicado_em      TIMESTAMP,
    criado_em         TIMESTAMP NOT NULL DEFAULT now(),
    atualizado_em     TIMESTAMP NOT NULL DEFAULT now()
);

-- Identical answers are the same guide no matter who typed them — that IS the reuse rule.
CREATE UNIQUE INDEX guia_hash_uk ON guia (hash) WHERE hash IS NOT NULL;

-- One draft per author per character, so re-running `/guia` resumes instead of forking, and a
-- double-clicked command cannot open two wizards over the same guide.
CREATE UNIQUE INDEX guia_rascunho_uk ON guia (autor_id, id_personagem_hsr) WHERE hash IS NULL;

CREATE INDEX guia_personagem_idx ON guia (id_personagem_hsr);
