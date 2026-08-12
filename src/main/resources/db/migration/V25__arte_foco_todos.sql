-- V23 gave the focus box four numeric columns and /guia let an author type their own, but only 5 of
-- the 95 illustrated characters had a box — so ~92 fell back to the v1 bust layout unless someone
-- filled the "Enquadramento" modal by hand. This is the missing DEFAULT for everyone else.
--
-- Measured by a vision pass over every `arte_completa` (all 2048x2048) with a 0.05 fraction grid
-- drawn on top, following the column comments in V23: `y` is the top of the head with halos, wings
-- and flame columns excluded, `altura` runs from there to the feet, and `x + largura/2` is the
-- figure's centre — the only thing largura is ever used for
-- (CardRenderer.drawScene: cardX = 0.25W + (u − x − w/2)·imgW·s, s ∝ 1/altura).
--
-- Wide and reclining poses (Cipher, Cisne Negro, Hysilens, Jiaoqiu, Sampo, Yanqing, the collages…)
-- carry a taller `altura` than the figure literally measures. Scale comes from height alone, so a
-- horizontal figure framed at its true height renders far wider than the 534px left column; the
-- boxes below keep width/altura at roughly 0.6 or less, which is where all five hand-curated boxes
-- already sat.
--
-- Keyed on arte_completa, never on nome: five characters share a name across paths (Desbravador and
-- Desbravadora ×5 each, 7 de Março ×2) while every hash is unique. It also self-invalidates — if a
-- re-populate ever swaps the artwork, the hash stops matching and no box is applied to art it was
-- not measured against. The columns stay out of SrsNanokaPopulator.PERSONAGEM_COLS.
--
-- Blade, Robin, Argenti, Sparkle and Evanescia repeat their existing V22/V23 values: the vision pass
-- agreed with them and they were already validated against rendered cards.
UPDATE personagem_hsr p
SET arte_foco_x       = v.x,
    arte_foco_y       = v.y,
    arte_foco_largura = v.w,
    arte_foco_altura  = v.h
FROM (VALUES
    ('28b2aaa11b70452e11a9e87966ef323259ca1256417567b9b08e38c6844f1930', 0.33, 0.26, 0.18, 0.44), -- 7 de Março (A Caça)
    ('1ca48661ecfb96771c6ed0c1d32df75071074efac504d40dd24c2c0508fedf24', 0.40, 0.27, 0.20, 0.56), -- 7 de Março (A Preservação)
    ('e7f957929809bae42e39f9d1c5dcc0e1e731ac7cf0c1127a35a7795d21a57c91', 0.40, 0.26, 0.16, 0.53), -- A Dália (A Inexistência)
    ('d924db37f724bc88e8a532c8aef4a82c80348681233b2c5ebae4105188aa8d61', 0.42, 0.38, 0.16, 0.34), -- A Herta (A Erudição)
    ('0483ee4a7b355cb9e4e252b56c90b7dd141cea99ed84a737e3e1ea9632097ef1', 0.43, 0.19, 0.14, 0.56), -- Acheron (A Inexistência)
    ('82999c40fa963a92e81c2d0c78ce2668a1f4f8ef6ce907769a046f0508f6a626', 0.54, 0.15, 0.14, 0.33), -- Aglaea (A Recordação)
    ('a7c54308cb5a5d20b2cba337487c86a7aa705430887d615ec3b153661d8cbaff', 0.50, 0.29, 0.16, 0.59), -- Anaxa (A Erudição)
    ('6abf1c186bfb334db58b5681cb47c793b12d98ae4b9755449d685a1abd118e31', 0.44, 0.30, 0.18, 0.50), -- Archer (A Caça)
    ('b679799542c72dad3bf8de2f56b35b978fc7e06ff662adb3ac5d7dd95413f0eb', 0.27, 0.28, 0.17, 0.48), -- Argenti (A Erudição)
    ('a1e3f58197ac02ef27ace436a92c27e3be7db0fd1e082b01a85d08970f47c731', 0.50, 0.34, 0.20, 0.41), -- Arlan (A Destruição)
    ('bd9f46645ea6f28e6a227794ee46ef635b6ef67e527182f81134e84a750d11e5', 0.41, 0.29, 0.14, 0.47), -- Ashveil (A Caça)
    ('528688c2c72d652c3525aec0afe0bbf1d0b7dff4a306013cde352daf89b1d300', 0.39, 0.30, 0.16, 0.49), -- Asta (A Harmonia)
    ('a9ca1f28e733306a6a1ae613eca15976eadd8214ccb7a5a25d205ccea89fa574', 0.42, 0.28, 0.16, 0.44), -- Aventurine (A Preservação)
    ('b836159effaa7e25b6cd88f6557b27e75993aa1c4ee9860a0b937e7c57b16f9e', 0.42, 0.28, 0.16, 0.38), -- Bailu (A Abundância)
    ('14cbedb479675daf4dcfad3578a7801e4df5be5438e0bdfa931d6912e3fe4e3a', 0.33, 0.06, 0.29, 0.62), -- Blade (A Destruição)
    ('5a0f995137492e6d18efcf21242d6e88fab87a6e7accf85b298f06936eee5e23', 0.38, 0.31, 0.18, 0.59), -- Boothill (A Caça)
    ('063b528d8ce0b6089729501c6694a6ec678277f491fb6f8a9b052e38f3f4afc4', 0.40, 0.44, 0.12, 0.41), -- Bronya (A Harmonia)
    ('7dda65214d1d900469b62f8d88bd878b25bad0e9891a31b2230c3aa54da98aee', 0.38, 0.30, 0.14, 0.42), -- Castorice (A Recordação)
    ('5e497b311eb300ac1131d674c6f6a2c24b223b5165c7441cf05a6d14a896db53', 0.44, 0.26, 0.15, 0.47), -- Cerydra (A Harmonia)
    ('9a3af1b12d79cf786cc9950a39ded9472337f06a7442047fdef49124bf02dbe7', 0.47, 0.33, 0.23, 0.38), -- Cipher (A Inexistência)
    ('ad9e0e0b0779d32351cac4672a3fa69d3da74dd99baa121ae8480418546aecd1', 0.35, 0.28, 0.24, 0.50), -- Cisne Negro (A Inexistência)
    ('761ff8e0af84f71c96a11338cc7b77a022bf56e1d020a12d77a3afc10d6925c7', 0.32, 0.28, 0.20, 0.40), -- Clara (A Destruição)
    ('3e63f7009c216bfa7bf3488de4ef5ab0892dcb580af7b93bdf04755d4a50b15b', 0.46, 0.52, 0.11, 0.31), -- Cyrene (A Recordação)
    ('90d62f466efd9b2ae08748cfeb1297edc8dd128f82742953624abdbcdd204b28', 0.33, 0.37, 0.25, 0.42), -- Dan Heng (A Caça)
    ('31cd73ec99b24e839261510c315a785b00f9657a14b793b2d363ef6f996ff6fb', 0.36, 0.27, 0.19, 0.53), -- Dan Heng - Embebidor Lunae (A Destruição)
    ('8938c0208d32e5a3915c60ab571d90669110e0fd0d04893942e39f7384ed89a0', 0.37, 0.30, 0.18, 0.48), -- Dan Heng - Permansor Terrae (A Preservação)
    ('afaff1b644621a1e9c9ef8dd3793352a6f08df3a58d8684a369b5392ea51adc5', 0.44, 0.32, 0.23, 0.53), -- Desbravador (A Destruição)
    ('4ca8a9c9ef99fdece08f9ac6eaeee5c56bcc98167f2dcc7fe4226a2299906c4c', 0.40, 0.28, 0.22, 0.50), -- Desbravador (A Euforia)
    ('eaf7eacf04939ae31fdc7a273db5066ae126f136d3590dbd182013bebea4aea4', 0.43, 0.35, 0.17, 0.42), -- Desbravador (A Harmonia)
    ('495d39b4306b9a04f6fe938524c5f96b525b54ca59e3987172f7117dbe01e3a9', 0.40, 0.40, 0.18, 0.32), -- Desbravador (A Preservação)
    ('02d6a2bb9b8421d50adf2322190642cd7a7fcd0d200efdf9a46ad6a0584babbb', 0.35, 0.28, 0.26, 0.52), -- Desbravador (A Recordação)
    ('62de3a1b831de9f6fb0262c6495af969312945587f2c5d580368f5ad81ee2d90', 0.41, 0.30, 0.20, 0.55), -- Desbravadora (A Destruição)
    ('89cc37fa77e44925531057ade4791448d0b27c371ea61da47c50eba6d3b15cb0', 0.38, 0.30, 0.24, 0.49), -- Desbravadora (A Euforia)
    ('eb87473f3f1e92df1c1915d30e6edfce68df5f2972ccaea3a21c27a3868d35ab', 0.43, 0.35, 0.16, 0.44), -- Desbravadora (A Harmonia)
    ('4c3a1ce379b382042883e2e703e284e2bfe5b32f8beea6af7205198e55baee8e', 0.40, 0.40, 0.18, 0.32), -- Desbravadora (A Preservação)
    ('116e8d8dc8b6d7a37368d580de8da00740a72f0c0b015ec72cdfe339c41b9eff', 0.37, 0.27, 0.22, 0.51), -- Desbravadora (A Recordação)
    ('2250a4ccda35563a63d7317c5510a822ebae50b98c5c821663339460ffd5b599', 0.36, 0.20, 0.24, 0.53), -- Dr. Ratio (A Caça)
    ('290a1924ed66556f2caa796851abd1a5b5a8ad3ba1f6e1d3f48135bc1c9f6845', 0.40, 0.28, 0.22, 0.46), -- Evanescia (A Euforia)
    ('a4b1d5058d188e1c9d195cc4999284c70f53b036fb2610ec33b391d032eb0f6f', 0.45, 0.36, 0.17, 0.36), -- Feixiao (A Caça)
    ('c6024a27be9343b9253fa8f451ce5aee85f7c9fda568b52c7d04638834571a97', 0.39, 0.28, 0.15, 0.44), -- Fu Xuan (A Preservação)
    ('c70adc0dbb094c92c1a261b0095ee9d9c3e4c19bfa0a8ef806707429ce5e21c6', 0.29, 0.42, 0.22, 0.40), -- Fugue (A Inexistência)
    ('16e8d78dca009c164d204f0c8848ce8e8563a842e34f6505eed5590e779b93f4', 0.40, 0.18, 0.24, 0.64), -- Gallagher (A Abundância)
    ('75e0b74011008c47fc380347a23ceb5ba7b9779157b2cf67c9a11e4bc59b3fc9', 0.53, 0.39, 0.19, 0.51), -- Gepard (A Preservação)
    ('64d6cb731a20dbccefcd95b790721080cf14d72306227f43caf6072109b2e6af', 0.45, 0.26, 0.22, 0.49), -- Gilgamesh (A Destruição)
    ('83fd5b7aa227402d6d6633e29d9ce55c6954ff8a478a13792fbbe50293dbcee9', 0.35, 0.28, 0.22, 0.55), -- Guinaifen (A Inexistência)
    ('25ccf4f5729e2ce00b58358539feceb60ea00f171d3292afe783780af4445db7', 0.36, 0.27, 0.24, 0.45), -- Hanya (A Harmonia)
    ('6a50283db8622837107bd59b3d8bdc3ca6a547c0a987926bc179072a391ceced', 0.35, 0.19, 0.22, 0.45), -- Herta (A Erudição)
    ('0c0468db7ae2d0dac5b66b80e1bf7ddb2d78a72f438bbfbd027285a561a3a500', 0.41, 0.44, 0.18, 0.50), -- Himeko (A Erudição)
    ('67769d671237dd817876b1b8026ba3bddcb9f5e4055bb2a56bc612fe777bc2fd', 0.36, 0.31, 0.18, 0.37), -- Himeko - Nova (A Erudição)
    ('b3c1a8013413a833b2d87da9bb612e2300a1647c682de20f11895948f6fe3d68', 0.38, 0.41, 0.20, 0.34), -- Hook (A Destruição)
    ('7eed60de26df08b9dc194632709eab078557a874f3da538a77c0ed3f29b7e55b', 0.40, 0.29, 0.15, 0.44), -- Huohuo (A Abundância)
    ('f4e9b6d4041e74b9dec856074752ec99aa7ad33eef40e96dd8da2d1b1493f08d', 0.44, 0.35, 0.28, 0.46), -- Hyacine (A Recordação)
    ('7b32e2922c94484f5c920dfe164938dad570be51982c67f4c9ac67d5e2384f60', 0.33, 0.30, 0.24, 0.55), -- Hysilens (A Inexistência)
    ('5b7a51e375c542619a0933487d08260b7e6f6882547fcf874b008b15cd75c956', 0.42, 0.25, 0.16, 0.45), -- Jade (A Erudição)
    ('6dc12c89cb61489b2dfa899610a18d243ebc5e564e625b8192c17c8341485c8e', 0.30, 0.27, 0.24, 0.52), -- Jiaoqiu (A Inexistência)
    ('6940887b8301e800d0cba12dc37bdbe24cce8c18f55d91230d882451d156dcc9', 0.40, 0.38, 0.15, 0.32), -- Jing Yuan (A Erudição)
    ('3eaf4781f5e8d88fbdffeee50ee515777b7f1e618a78a23cd94f840960bfc732', 0.41, 0.16, 0.18, 0.59), -- Jingliu (A Destruição)
    ('8d220180b0f645b609ca2805fc09a561d4548b03db1ddceb6e61d40c8a05dc0a', 0.41, 0.26, 0.16, 0.36), -- Kafka (A Inexistência)
    ('70dddfde42f901ed1c7530f5a092474a627a6e56ec1d91632bcb6dc0712af536', 0.42, 0.33, 0.26, 0.44), -- Lingsha (A Abundância)
    ('c62c0cc06f179c73fde2e8af09492efe28a4ee923a09e57aebaa1f177dc40448', 0.35, 0.30, 0.28, 0.46), -- Loba Prateada (A Inexistência)
    ('0642d24133b729ec1cfdfd9b889a677f5e446bfe417d4299a75b9c8ea0b98b42', 0.38, 0.33, 0.28, 0.48), -- Loba Prateada Nv. 999 (A Euforia)
    ('6a574364b0f283fb44af0d2bb642ae090e16f424fbcce79ff8f7bf1cced78629', 0.36, 0.22, 0.22, 0.58), -- Luka (A Inexistência)
    ('33a4a2ae03f2a97b1f7bb7928f9019a3be65d7e854ce8f99cd443bf149157d28', 0.38, 0.28, 0.24, 0.40), -- Luocha (A Abundância)
    ('83a5b9cea1d9831c7fb4adc4e5a3fc32661eb0f25f9fbb01a2ca8fe5f8e0d642', 0.49, 0.33, 0.22, 0.43), -- Lynx (A Abundância)
    ('e7994b1ab611358ce651e5d89ce9c4019ab8bb17d93ab1a6754c760fc049a8f2', 0.45, 0.34, 0.22, 0.39), -- Misha (A Destruição)
    ('6fa00eea4c0b305ddc224bdd85b9373f5d81f421c4e45974612a8985e6899afb', 0.38, 0.31, 0.24, 0.47), -- Mortenax Blade (A Inexistência)
    ('616dbf7b3fa16d162ff9092b31e0ed03ef603d20e8ec78cb5f199e6456db1971', 0.41, 0.36, 0.18, 0.43), -- Moze (A Caça)
    ('7d1c2afaad21e9530d0cdae4a45e5dac16fa3f0d3090b4db462ee34aa800937f', 0.37, 0.45, 0.22, 0.38), -- Mydei (A Destruição)
    ('c142d4919c0eeaa20b395732bc3ed9e56edc3ba417a2ab93c7138ab7174ff341', 0.36, 0.20, 0.22, 0.67), -- Natasha (A Abundância)
    ('07c7f055edcda59cc705498ecd073244d7f101f601b7c27857015a7b3e64ef5f', 0.41, 0.28, 0.20, 0.51), -- Noite Eterna (A Recordação)
    ('7d50aab0abb5bb06bb873bb0b2b12d3ced2ff7cdc4d6ad9083a1d37c71dde42d', 0.32, 0.35, 0.22, 0.47), -- Pela (A Inexistência)
    ('25de23aa3dec541dccd5cb3dfcad38dffe1100e22db4ef03675077ff6efb8323', 0.35, 0.26, 0.22, 0.58), -- Phainon (A Destruição)
    ('9813c429ff15c7b8c276bef0c923735b5b63f96928fca1b20fa9f838d2590d4b', 0.36, 0.28, 0.24, 0.52), -- Qingque (A Erudição)
    ('cdc633b598a172e6cba24f9e1d1d9e3e6bef4d6d6f991c261a58663fefd80d03', 0.51, 0.44, 0.20, 0.48), -- Rappa (A Erudição)
    ('6e7a0870263cf71ce18395a4c2e6453e02166519f419609617a986e387e326ea', 0.39, 0.29, 0.22, 0.51), -- Rin Tohsaka (A Erudição)
    ('1ad8479754cc41c0f741cfcc700110ec6c7cd71e617bd6bc87d297a874fa91e7', 0.38, 0.20, 0.21, 0.37), -- Robin (A Harmonia)
    ('307107546cc37b8148af3d688c798bf2f79c10a0e008bfe5527e990de4695601', 0.41, 0.30, 0.20, 0.58), -- Ruan Mei (A Harmonia)
    ('af28a47ed471235d0b27dd55c7599c812978351e9c37e5081774d6c29d8c20b7', 0.33, 0.25, 0.22, 0.54), -- Saber (A Destruição)
    ('0f39715ceb17de0fd93654a29341121cc3d216b563f69dec1afc9bb40f4417e6', 0.34, 0.24, 0.22, 0.56), -- Sampo (A Inexistência)
    ('2ae51c54a5688c2d71d45c181ec5a607f505c2632f65b8fd3f870332adf40a98', 0.32, 0.52, 0.18, 0.30), -- Seele (A Caça)
    ('5ecd17c5531571ef9efbfaf03d0ad4c048190e1fac74058c718379ae36111b90', 0.42, 0.38, 0.20, 0.42), -- Serval (A Erudição)
    ('838aa611c7ded47583facd1b1a97bddf12f8c3a320c03aefaed2ec6dc312d9b5', 0.40, 0.28, 0.22, 0.46), -- Sparkle (A Harmonia)
    ('8fdd921744f23e50271010c750b54133b5cca965bb75f39afb886388e517175b', 0.35, 0.25, 0.26, 0.50), -- Sparxie (A Euforia)
    ('ceb6f2437d9053aa12c50fb4c463112a7759e5a44e566b742df6db5bfebb5824', 0.41, 0.29, 0.18, 0.51), -- Sunday (A Harmonia)
    ('1ddf50af5349ba8e5c82035276b3a552fe267ba904a1fccbb0b8999d3ac65ce2', 0.41, 0.31, 0.26, 0.49), -- Sushang (A Caça)
    ('170273e9d07979d2bb93045a20c651d69c074f69c5fbf95e9e15f5310569e5a9', 0.40, 0.21, 0.18, 0.55), -- Tingyun (A Harmonia)
    ('ba6cffaaa2db5d3486a5e05eea704cb071274fee69f50a6f4f6a8fe35c9c5991', 0.40, 0.22, 0.30, 0.54), -- Topaz e Dinheirinho (A Caça)
    ('fabb8257367002189e1d16b45653fc53ba0cabee930938fd7abb2f831c0fac1c', 0.36, 0.40, 0.18, 0.32), -- Tribbie (A Harmonia)
    ('bf120e422edf74aef5e2436bfdade901e996fb05b630caef371813231eaba263', 0.38, 0.44, 0.20, 0.36), -- Vaga-lume (A Destruição)
    ('c2a1224f1bb50d307cc13aa1f71a4e492a77692a74eeaa15ca00293f9427e582', 0.35, 0.23, 0.22, 0.52), -- Welt (A Inexistência)
    ('ca7a2efae23cb8a274eed89814ade8698b5f2e178eb46fa7635873beadbad55f', 0.40, 0.19, 0.18, 0.53), -- Xueyi (A Destruição)
    ('317961b63542038ce975f248d184821e323c20ad39b37f9098aa9ea9d5fe399c', 0.35, 0.26, 0.24, 0.60), -- Yanqing (A Caça)
    ('dfb73012737ee8823b1619956c70972443dffaf525a003d23cf9a35f6415a137', 0.32, 0.36, 0.24, 0.46), -- Yao Guang (A Euforia)
    ('7bdc95123d027b9f0c231c07babf07534584e124a2294b69f0d96407cae6043c', 0.32, 0.28, 0.24, 0.51), -- Yukong (A Harmonia)
    ('01bed836d202a3bdb0ba4f8666f5faedf528d558044239fb164bb4cbe10b7f5b', 0.41, 0.42, 0.20, 0.38)   -- Yunli (A Destruição)
) AS v(hash, x, y, w, h)
WHERE p.arte_completa = v.hash;
