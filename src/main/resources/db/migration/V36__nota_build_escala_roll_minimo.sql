-- A relíquia de referência do `/build` deixou de ser "todo roll no substat certo E no valor máximo"
-- e virou "todo roll no substat certo, todos no valor MÍNIMO" (FribbelsScorer.MIN_ROLL). Como
-- mín/máx é 0.8 pra onze dos doze substats — e o décimo segundo, Velocidade, foi achatado pro mesmo
-- 0.8 de propósito, senão personagem de speed ganhava 3.5% de vantagem no ranking geral — a mudança
-- inteira é uma multiplicação: toda nota vale 1.25× o que valia.
--
-- Diferente da V35, aqui NÃO se esvazia a tabela. A fórmula é a mesma, só o divisor encolheu, então
-- as linhas existentes convertem sozinhas e o ranking continua de pé enquanto ninguém roda `/build`.
-- O `letra` é recalculado junto: ele é lido direto do banco pelo RankingService, e nota nova com
-- letra velha seria a única coisa pior do que não converter.
--
-- Precisão: a nota guardada é a média de seis porcentagens JÁ truncadas na décima, então converter
-- multiplicando erra até 0,01 na nota exibida contra um recálculo do zero. Cada linha se corrige
-- sozinha no próximo `/build` de quem a gerou; pra forçar tudo de uma vez:
--   mvn -q -DskipTests compile spring-boot:run \
--     -Dspring-boot.run.main-class=com.hsrbot.rank.RankBackfillStandaloneKt
--
-- NUMERIC(5,2) já aguenta: o teto simulado de uma build de whale é ~102 e o campo vai até 999,99.
UPDATE nota_build
SET nota  = nota * 1.25,
    letra = CASE
        WHEN nota * 1.25 <= 0   THEN '?'
        WHEN nota * 1.25 >= 100 THEN '@$%!'
        WHEN nota * 1.25 >= 90  THEN '???'
        WHEN nota * 1.25 >= 85  THEN 'SSS+'
        WHEN nota * 1.25 >= 80  THEN 'SSS'
        WHEN nota * 1.25 >= 75  THEN 'SS+'
        WHEN nota * 1.25 >= 70  THEN 'SS'
        WHEN nota * 1.25 >= 65  THEN 'S+'
        WHEN nota * 1.25 >= 60  THEN 'S'
        WHEN nota * 1.25 >= 55  THEN 'A+'
        WHEN nota * 1.25 >= 50  THEN 'A'
        WHEN nota * 1.25 >= 45  THEN 'B+'
        WHEN nota * 1.25 >= 40  THEN 'B'
        WHEN nota * 1.25 >= 35  THEN 'C+'
        WHEN nota * 1.25 >= 30  THEN 'C'
        WHEN nota * 1.25 >= 25  THEN 'D+'
        WHEN nota * 1.25 >= 20  THEN 'D'
        WHEN nota * 1.25 >= 15  THEN 'E+'
        WHEN nota * 1.25 >= 10  THEN 'E'
        WHEN nota * 1.25 >= 5   THEN 'F+'
        ELSE 'F'
    END;
