-- The two memosprite ability icons, for the RASTROS row of a build card.
--
-- V20 gave the card the four base ability icons plus euphoria, because that was every ability the
-- trace-priority row could show. The `/guia` form now lets an author of a Recordação character put
-- "Talento do Memoespírito" / "Perícia do Memoespírito" in that row, and a trace with no icon is
-- silently dropped by `FichaMapper.rastro` — so the option cannot exist before the column does.
--
-- Same contract as every other V20 asset column: the 64-hex content hash, never the bytes and never
-- a URL, and nullable because only the Recordação units have a memosprite at all. srs serves these
-- under `.servant.skills[].icon` (the servant nodes use `icon`, not the `iconPath` the main skills
-- use); they are populated by the next SrsNanokaPopulator run.
ALTER TABLE personagem_hsr
    ADD COLUMN IF NOT EXISTS icone_talento_memoespirito TEXT,
    ADD COLUMN IF NOT EXISTS icone_pericia_memoespirito TEXT;
