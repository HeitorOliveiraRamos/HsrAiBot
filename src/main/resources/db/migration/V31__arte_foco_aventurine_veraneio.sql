-- The one character V25 missed: Aventurine • Veraneio (A Euforia) was added by the nanoka beta
-- pipeline after V25's vision pass ran, so it fell back to the v1 bust layout like the other 92 did
-- before V25. All 96 other characters already carry a curated box; this is the last one.
--
-- Measured the same way as V25: a 0.01 fraction grid over the 2048x2048 arte_completa. This is a
-- reclining beach-chair pose (same family as Cipher, Hysilens, Jiaoqiu, Sampo, Yanqing — see V25's
-- notes), so `altura` runs from the top of his hair to his trailing sandal (0.31 -> 0.76 = 0.45) and
-- `largura` is kept well under it (0.24, ratio 0.53) rather than matching the literal left-to-right
-- span of the outstretched leg — width is only ever used for x + largura/2, the figure's centre.
--
-- Keyed on arte_completa like V25; this row stores the nanoka CDN URL directly rather than a sha256
-- (it's a beta-pipeline asset, see nanoka-beta-assets notes), which still works as an exact match.
UPDATE personagem_hsr
SET arte_foco_x       = 0.36,
    arte_foco_y       = 0.31,
    arte_foco_largura = 0.24,
    arte_foco_altura  = 0.45
WHERE arte_completa = 'https://static.nanoka.cc/assets/hsr/avatardrawcard/1513.webp';
