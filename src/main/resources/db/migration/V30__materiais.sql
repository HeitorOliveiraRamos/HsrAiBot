-- Character upgrade costs for the ascension guide card ("MELHORIA DE PERSONAGEM" / "MELHORIA DE
-- RASTROS"), in two halves: this dictionary of materials, and one JSONB column per character
-- holding the ordered (id, quantidade) pairs the card draws.
--
-- WHY A DICTIONARY: the same ~13 materials repeat across all 95 characters, and 3 of them (crédito,
-- Guia do Viajante, Rastros de Destino) are shared by every single card. Names and icons live here
-- and are resolved at render time — the same rule V24 states for guia.spec and V20 for asset
-- hashes, so a patch that re-skins an icon fixes every card at once.
--
-- SOURCE: one fetch of srs `pt/materials.json` (the /pt/materials index), filtered to the six
-- `purposeId` buckets a character can consume. The index already carries name/icon/rarity, so no
-- per-material detail request is needed — 142 rows out of 1045 for one HTTP call.
--
--   11 = Moeda Comum ................................ crédito
--    1 = Material de EXP de Personagem ............... livros de EXP
--    2 = Material de Ascensão de Personagem .......... drop de boss (Sombra Estagnada)
--    4 = Materiais de Rastros ........................ Rastros de Destino + boss semanal
--    7 = Rastros + Ascensão de Personagem ............ cálice, raridades 2/3/4
--    3 = Rastros + Ascensão de Cone de Luz ........... rastro, raridades 2/3/4
--
-- No SERIAL here, unlike the other V17 tables: nothing FKs to a material (the JSONB below
-- references materials by their game id), so the game id IS the key.
CREATE TABLE IF NOT EXISTS materiais (
    material_id   INTEGER PRIMARY KEY,   -- srs `pageId` == the `itemReferences` key in a character detail
    nome          TEXT NOT NULL,         -- PT
    icone         TEXT,                  -- iconPath: 64-hex content hash, NOT a URL (V20 contract)
    raridade      SMALLINT,              -- 2/3/4/5 — what splits the three tiers of a calyx/trace line
    proposito_id  SMALLINT NOT NULL      -- srs `purposeId`, the filter's own predicate
);

CREATE INDEX IF NOT EXISTS materiais_proposito_idx ON materiais (proposito_id);

-- The per-character half: the card's two grids, already in draw order.
--
--   {"personagem": [{"id": 29328, "qtd": 308000}, …6 itens],
--    "rastros":    [{"id": 29328, "qtd": 3000000}, …9 itens]}
--
-- JSONB and not 15 columns for the reason V28 gives for `tier_list.grade`: it is one repeating
-- (id, quantidade) shape that only the card renderer ever reads back, and it is written whole by
-- the populate run. Quantities are stored raw (308000, not "308K") — formatting is the renderer's.
--
-- Derived from the character detail the harvester ALREADY fetches (`levelData[].cost`,
-- `calculator.expCost`, `skills[].levelData[].cost`, `skillTreePoints`, plus the `servant` block on
-- Recordação units), so this costs zero extra requests. Nullable: a beta character srs has not
-- published yet has no cost data, and the renderer must fail soft.
--
-- The 95 live characters collapse into 4 signatures; `playerboy4`/`playergirl4` are a genuine
-- fourth, not a bug. Verified cell-for-cell against the reference guide art on 2026-08-05.
ALTER TABLE personagem_hsr ADD COLUMN IF NOT EXISTS custos_melhoria JSONB;
