-- Art an author uploaded for their own guide, keyed by the sha256 of the stored bytes.
--
-- This deliberately breaks V24's "bytes stay out of Postgres" rule, and the reason the rule existed
-- is exactly why: V20 assets and rendered cards are kept out because they REGENERATE — an asset
-- re-downloads from the CDN, a card re-renders from its spec, so both are disposable caches. An
-- uploaded image regenerates from nothing. Discord's own attachment URLs are signed and expire in
-- hours, so storing the link instead would mean every guide with custom art quietly losing it. This
-- is the only copy, so it lives where the rest of the wizard's state lives.
--
-- Dump size is the cost, and it is paid down two ways: `GuiaArte` caps the long side at 1280px
-- before storing (the card scales the illustration to ~82% of a 1080px canvas, so anything larger
-- is thrown away at render time anyway), and the primary key is the CONTENT hash — two authors who
-- upload the same wallpaper share one row, the same way two identical guides share one `guia` row.
--
-- `guia.spec` stores only the hash. Bytes in the spec would land inside the guide's own sha256 and
-- inside every JSONB read of the wizard's state.
CREATE TABLE guia_arte (
    hash      TEXT PRIMARY KEY,
    bytes     BYTEA NOT NULL,
    criado_em TIMESTAMP NOT NULL DEFAULT now()
);
