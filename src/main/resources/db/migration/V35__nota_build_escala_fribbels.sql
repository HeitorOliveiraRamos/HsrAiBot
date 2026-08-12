-- A nota do `/build` deixou de ser 0–10 e virou a PORCENTAGEM do fribbels/hsr-optimizer (0–100), a
-- mesma que a vitrine deles mostra como "Perfection". Duas consequências pro banco:
--
-- 1. `NUMERIC(4,2)` estoura em 100.00. Vira `NUMERIC(5,2)`.
-- 2. As linhas antigas estão na escala velha. Não dá pra converter multiplicando por 10: a fórmula
--    mudou junto (peso do fribbels em vez do StarRailScore, valor do substat em vez de contagem de
--    rolagens, main errado descontado), então um 7,3 antigo não é 73% hoje. Misturar as duas escalas
--    num ranking ordenado por nota poria toda build antiga no fim da lista, o que é pior do que não
--    ter linha nenhuma — então a tabela é esvaziada.
--
-- Repovoar: `mvn -q -DskipTests compile spring-boot:run \
--   -Dspring-boot.run.main-class=com.hsrbot.rank.RankBackfillStandaloneKt`
-- (ou simplesmente esperar: todo `/build` regrava a vitrine inteira de quem o rodou).
ALTER TABLE nota_build ALTER COLUMN nota TYPE NUMERIC(5, 2);

DELETE FROM nota_build;
