-- The build card's splash used `arte_figura` (a flat standing render) over a colour wash derived
-- from it. Both replacements come out of the SAME character detail JSON the harvester already
-- fetches, so like V20 they cost zero extra HTTP requests:
--
--   splashIconPath  the artist's own head-and-shoulders crop with a transparent background. The
--                   subject is framed UPSTREAM, which is the whole point: cropping the character
--                   out of `arte_completa` ourselves needs subject detection (the composition puts
--                   halos, wings and flame columns above the head, so no bbox/centroid rule holds).
--   bgPath          that same splash illustration with the character removed — the scenery layer,
--                   which replaces the flat derived-colour wash behind the bust.
--
-- Same contract as V20: 64-hex content hashes, not bytes and not URLs; nullable; every consumer
-- fails soft. Coverage measured over all 95 srs characters on 2026-08-04 (live V4.3).
ALTER TABLE personagem_hsr
    ADD COLUMN IF NOT EXISTS arte_retrato TEXT,  -- splashIconPath, 376x512 bust; 95/95 — better than arte_figura's 94/95
    ADD COLUMN IF NOT EXISTS arte_fundo   TEXT;  -- bgPath, ~1024x768 scenery; 94/95 (Vaga-lume/Firefly has none)
