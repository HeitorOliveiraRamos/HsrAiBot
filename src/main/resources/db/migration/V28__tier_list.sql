-- Community tier lists written through `/tierlist`: 20 cells (4 papéis × 5 tiers) of characters,
-- one row per list.
--
-- `grade` is JSONB and not the twenty `dp_tier_s`, `ds_tier_a`, … TEXT columns the shape suggests,
-- for two reasons that both cost data rather than tidiness:
--
--   1. It holds `personagem_hsr.character_id`, never names. A NAME IS NOT AN IDENTITY here — five
--      Desbravadoras and two 7 de Março share theirs, so a stored list would have to carry the
--      disambiguated display name ("Desbravadora (Recordação)"), and that string is COMPUTED: it
--      only grows its suffix once a second character shares the base name. The day a "Herta" variant
--      ships, every stored "Herta" stops matching the roster and the diff against the previous list
--      silently reports the whole column as new. The id never moves. The display name is produced at
--      render time by `HsrCharacterService.displayName`, which already emits exactly the
--      "Nome (Caminho)" form.
--   2. The wizard writes on EVERY click. One JSONB field is one UPDATE; twenty columns is twenty,
--      and a fifth papel or a sixth tier would be a migration plus an edit in every read site.
--
-- Shape: {"dp": [[ids do S], [ids do A], [B], [C], [D]], "ds": …, "sup": …, "def": …}. A character
-- may appear in more than one papel — plenty of them genuinely carry two roles — but only once
-- within a papel, which the wizard enforces by leaving the other tiers' picks out of the options.
--
-- One row covers a list's whole life:
--   publicado_em IS NULL ..... draft, still being filled in by the wizard
--   publicado_em IS NOT NULL . posted to a channel, and from then on the seed for the author's next
--                              list of the same mode
--
-- No hash column, unlike `guia`: two authors answering a 97-character opinion poll identically is
-- not a thing that happens, so there is nothing to deduplicate and no rendered PNG worth caching
-- under a shared name.
CREATE TABLE tier_list (
    id_tier_list  SERIAL PRIMARY KEY,
    chave         TEXT NOT NULL UNIQUE,   -- short id carried in componentIds, as in guia
    autor_id      TEXT NOT NULL,          -- discord user id; kept even for an anonymous list, since
                                          -- it is what finds the author's previous one to seed from
    modo          TEXT NOT NULL,          -- moc | pf | as — the endgame the list is about
    versao        TEXT,                   -- the patch it was written for, drawn as a pill ("v4.3")
    -- Hides the author's name on the image. The server's own line always stays.
    anonimo       BOOLEAN NOT NULL DEFAULT false,
    grade         JSONB NOT NULL DEFAULT '{}'::jsonb,
    publicado_em  TIMESTAMP,
    criado_em     TIMESTAMP NOT NULL DEFAULT now(),
    atualizado_em TIMESTAMP NOT NULL DEFAULT now()
);

-- One open draft per author per mode, so re-running `/tierlist` resumes instead of forking and a
-- double-clicked command cannot open two wizards over the same list.
CREATE UNIQUE INDEX tier_list_rascunho_uk ON tier_list (autor_id, modo) WHERE publicado_em IS NULL;

-- The seed lookup: the author's most recent published list for this mode.
CREATE INDEX tier_list_anterior_idx ON tier_list (autor_id, modo, publicado_em DESC)
    WHERE publicado_em IS NOT NULL;
