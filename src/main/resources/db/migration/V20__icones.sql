-- Asset references for the build-card image generator (no generative AI: a baked PNG frame plus
-- these icons composited with Java2D). starrailstation serves every game asset from a
-- content-addressed CDN, so what we store is the 64-hex content hash, NOT the bytes and NOT a URL:
--
--     https://cdn.starrailstation.com/assets/<hash>.webp        (bot.knowledge.srs-asset-url)
--
-- Hashes are stable (they change only when the artwork itself changes), the format is webp — which
-- the bot already decodes via the imageio-webp reader registered in VisionService — and everything
-- here rides the fetch the harvester ALREADY performs, so this costs zero extra HTTP requests.
-- Storing bytes in Postgres was rejected: it bloats dumps and buys nothing over a disk cache.
--
-- Every column is nullable and every consumer must fail soft (draw a placeholder, never throw):
-- a hash can 404 after a patch until the next populate run, and coverage is not 100% everywhere
-- (see the per-column notes). Verified against the live V4.3 dataset on 2026-08-03.

-- ---------------------------------------------------------------- personagem_hsr
-- Coverage measured over all 95 characters. The card needs: the cut-out figure for the splash on
-- the left, the round mini icon for the SINERGIAS row (which shows OTHER characters), the element
-- badge, and the four ability icons of the RASTROS priority row.
ALTER TABLE personagem_hsr
    ADD COLUMN IF NOT EXISTS arte_figura            TEXT,  -- figPath, ~1560x1652 transparent cut-out; 94/95 (Vaga-lume/Firefly has none)
    ADD COLUMN IF NOT EXISTS arte_completa          TEXT,  -- artPath, 2048x2048; 95/95 — the fallback that keeps Firefly's card from rendering empty
    ADD COLUMN IF NOT EXISTS icone_mini             TEXT,  -- miniIconPath, 128x128 round avatar; 95/95 — the SINERGIAS circles
    ADD COLUMN IF NOT EXISTS icone_elemento         TEXT,  -- damageType.iconPath, 256x256; 95/95
    ADD COLUMN IF NOT EXISTS icone_caminho          TEXT,  -- baseType.iconPath, 64x64; 95/95 (small — upscale or use sparingly)
    -- RASTROS row, 128x128 each, keyed off the same PT typeDescHash tag the kit extraction groups on.
    -- Técnica is deliberately absent: the card's trace-priority row never shows it.
    ADD COLUMN IF NOT EXISTS icone_atq_basico       TEXT,
    ADD COLUMN IF NOT EXISTS icone_pericia          TEXT,
    ADD COLUMN IF NOT EXISTS icone_pericia_suprema  TEXT,
    ADD COLUMN IF NOT EXISTS icone_talento          TEXT,
    ADD COLUMN IF NOT EXISTS icone_pericia_euforia  TEXT;  -- Euforia units only (Evanescia et al.); null elsewhere

-- ---------------------------------------------------------------- reliquias
-- 32/32 sets carry a set icon, and all 32 expose exactly 4 pieces with 128/128 piece icons.
-- Piece order is the established cabeça→mãos→corpo→pés convention the _nome/_descricao columns
-- already rely on; the icons reuse it rather than introducing a second ordering.
ALTER TABLE reliquias
    ADD COLUMN IF NOT EXISTS icone         TEXT,  -- set icon, 256x256
    ADD COLUMN IF NOT EXISTS cabeca_icone  TEXT,
    ADD COLUMN IF NOT EXISTS maos_icone    TEXT,
    ADD COLUMN IF NOT EXISTS corpo_icone   TEXT,
    ADD COLUMN IF NOT EXISTS pes_icone     TEXT;

-- ---------------------------------------------------------------- ornamentos_planos
-- 28/28 sets, all with exactly 2 pieces and 56/56 piece icons. Same esfera→corda order as the
-- existing _nome/_descricao columns.
ALTER TABLE ornamentos_planos
    ADD COLUMN IF NOT EXISTS icone        TEXT,  -- set icon, 256x256
    ADD COLUMN IF NOT EXISTS esfera_icone TEXT,
    ADD COLUMN IF NOT EXISTS corda_icone  TEXT;

-- ---------------------------------------------------------------- cones_de_luz
-- mediumIconPath (348x408) is the portrait-card crop the build card shows, not the 128x128 square
-- iconPath. 165/165 starrailstation cones have one; nanoka lists 169, so the ~4 cones that exist
-- only in nanoka stay NULL — they are obscure/unreleased and never appear on a build card.
ALTER TABLE cones_de_luz
    ADD COLUMN IF NOT EXISTS icone TEXT;
