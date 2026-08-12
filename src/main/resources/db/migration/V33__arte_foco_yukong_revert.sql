-- User reviewed V32's two fixes and kept Tribbie but wants Yukong back the way it was — the original
-- V25 vision-pass box reads better on the rendered card than my re-measurement, even though it sits
-- lower than her literal hairline. Restoring the exact V25 values.
UPDATE personagem_hsr
SET arte_foco_x = 0.32, arte_foco_y = 0.28, arte_foco_largura = 0.24, arte_foco_altura = 0.51
WHERE arte_completa = '7bdc95123d027b9f0c231c07babf07534584e124a2294b69f0d96407cae6043c'; -- Yukong (A Harmonia)
