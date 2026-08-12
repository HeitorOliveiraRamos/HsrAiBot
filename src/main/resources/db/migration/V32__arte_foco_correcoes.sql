-- Re-review of all 96 curated boxes from V25 (the user asked for a fresh pass and to be shown the
-- ones judged wrong before overwriting). Checked every character against its arte_completa with the
-- box drawn on top; the overwhelming majority hold up. Two boxes clip the character's own head —
-- the box top sits below the hairline instead of above it, cutting off ears/hair on render:
--
-- Tribbie: three copies of her appear in this splash (past/present/future), the box had drifted onto
-- the smallest of the trio's neighbourhood rather than framing the central, largest figure fully —
-- 0.40 was below her hair; feet also re-measured (0.72 ran past her boot onto the moon prop below).
-- Yukong: the box top (0.28) landed at chin height, well below her cat ears (~0.245); x re-centred
-- on her torso instead of the smaller foreground companion figure beside her.
UPDATE personagem_hsr
SET arte_foco_x = 0.33, arte_foco_y = 0.38, arte_foco_largura = 0.18, arte_foco_altura = 0.28
WHERE arte_completa = 'fabb8257367002189e1d16b45653fc53ba0cabee930938fd7abb2f831c0fac1c'; -- Tribbie (A Harmonia)

UPDATE personagem_hsr
SET arte_foco_x = 0.39, arte_foco_y = 0.25, arte_foco_largura = 0.22, arte_foco_altura = 0.48
WHERE arte_completa = '7bdc95123d027b9f0c231c07babf07534584e124a2294b69f0d96407cae6043c'; -- Yukong (A Harmonia)
