-- V22 packed the character's focus box into one TEXT column ("x,y,w,h"), which made every value a
-- parse away from being wrong: a swapped separator, a comma decimal or a fifth number only surfaced
-- at render time, and the renderer's reaction to a malformed box is to silently drop the card to
-- the v1 bust layout — a wrong card, not an error. Four NUMERIC(4,3) columns let Postgres reject
-- nonsense on the way in, which is where curated-by-hand data has to be checked.
--
-- The box is measured against `arte_completa` (2048x2048), in fractions of the image, and it frames
-- the PERSON — not the composition. That distinction is the entire reason this data is hand-curated
-- (see V22's measurements): halos, wings and flame columns are as salient as the figure to every
-- automatic method, and they sit above the head.
--
-- Still deliberately absent from SrsNanokaPopulator.PERSONAGEM_COLS, so a re-populate cannot
-- clobber curated values. All four NULL = fall back to the v1 bust layout; a partially filled row
-- is treated the same way (the renderer needs all four or none).
ALTER TABLE personagem_hsr
    ADD COLUMN IF NOT EXISTS arte_foco_x       NUMERIC(4,3) CHECK (arte_foco_x BETWEEN 0 AND 1),
    ADD COLUMN IF NOT EXISTS arte_foco_y       NUMERIC(4,3) CHECK (arte_foco_y BETWEEN 0 AND 1),
    ADD COLUMN IF NOT EXISTS arte_foco_largura NUMERIC(4,3) CHECK (arte_foco_largura > 0 AND arte_foco_largura <= 1),
    ADD COLUMN IF NOT EXISTS arte_foco_altura  NUMERIC(4,3) CHECK (arte_foco_altura  > 0 AND arte_foco_altura  <= 1);

COMMENT ON COLUMN personagem_hsr.arte_foco_x IS
    'Borda ESQUERDA da caixa do personagem em arte_completa, como fração 0..1 da largura da imagem.';
COMMENT ON COLUMN personagem_hsr.arte_foco_y IS
    'Borda SUPERIOR: topo da cabeça/cabelo, EXCLUINDO halo, asas, chamas e outros efeitos acima dela. Fração 0..1 da altura.';
COMMENT ON COLUMN personagem_hsr.arte_foco_largura IS
    'Largura da caixa (ponto mais largo do corpo, normalmente ombro a ombro) como fração 0..1 da largura da imagem.';
COMMENT ON COLUMN personagem_hsr.arte_foco_altura IS
    'Altura da caixa (topo da cabeça até os pés ou até onde o corpo ainda é visível) como fração 0..1 da altura da imagem.';

-- Carry the five curated boxes over. split_part on a NULL/empty column yields '' — hence the guard.
UPDATE personagem_hsr
SET arte_foco_x       = split_part(arte_foco, ',', 1)::numeric,
    arte_foco_y       = split_part(arte_foco, ',', 2)::numeric,
    arte_foco_largura = split_part(arte_foco, ',', 3)::numeric,
    arte_foco_altura  = split_part(arte_foco, ',', 4)::numeric
WHERE arte_foco ~ '^\s*[0-9.]+\s*,\s*[0-9.]+\s*,\s*[0-9.]+\s*,\s*[0-9.]+\s*$';

ALTER TABLE personagem_hsr DROP COLUMN IF EXISTS arte_foco;
