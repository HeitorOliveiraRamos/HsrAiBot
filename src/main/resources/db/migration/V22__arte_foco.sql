-- Where the character actually is inside `arte_completa`, as "x,y,w,h" fractions of the image
-- (0..1, 3 decimals). This is the one piece of card data that is NOT harvested: three independent
-- automatic approaches were measured against the live art on 2026-08-04 and none reaches the
-- quality of the hand-made reference cards --
--
--   alpha bbox + top-fraction crop ......... 8/20  (halos, wings and flame columns sit ABOVE the head)
--   artPath - bgPath - fgPath differencing . 10/20 (fgPath is only 69/95; VFX fuse with the figure)
--   splashIconPath template match .......... fails (independent render, not a crop of the splash —
--                                                   NCC peaks at noise level and pins to min scale)
--
-- because "which of these equally-salient shapes is the person" is a semantic question, not a
-- geometric one. So the judgement is made ONCE per character by something that can see (a vision
-- pass, or a human with the grid overlay) and stored here; the renderer then just applies a
-- deterministic framing rule to this box. ~1-2 new characters per patch need one box each.
--
-- Deliberately absent from SrsNanokaPopulator.PERSONAGEM_COLS: the upsert's DO UPDATE SET never
-- names this column, so a re-populate cannot clobber curated values. NULL = fall back to the V1
-- bust layout, so an uncurated character still renders.
ALTER TABLE personagem_hsr
    ADD COLUMN IF NOT EXISTS arte_foco TEXT;

COMMENT ON COLUMN personagem_hsr.arte_foco IS
    'Caixa do personagem dentro de arte_completa: "x,y,w,h" em frações 0..1. Curada, não colhida.';
