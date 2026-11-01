-- Run by hand ONCE on any database where the unit was created as "unité" (ddl-auto=update only adds).
-- Run it BEFORE the first start of this version: the seeder inserts "pièce" only when it is missing,
-- so starting first would create a second "pièce" next to the "unité" row.
ALTER TABLE units ADD COLUMN IF NOT EXISTS hidden boolean NOT NULL DEFAULT false;

UPDATE units
SET name = 'pièce',
    normalized_name = 'piece',
    abbreviation = NULL,
    hidden = TRUE
WHERE normalized_name = 'unite';
