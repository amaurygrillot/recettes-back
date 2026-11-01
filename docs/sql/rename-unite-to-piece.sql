-- Run by hand ONCE on any database where the unit was created as "unité" (ddl-auto=update only adds).
-- Safe to run whether or not the app has already been restarted on this version: if the seeder has
-- already inserted its own empty "pièce", that row is dropped first (only when no recipe line uses it),
-- so the "unité" row, and every recipe line pointing at it, is the one that gets renamed.
ALTER TABLE units ADD COLUMN IF NOT EXISTS hidden boolean NOT NULL DEFAULT false;

DELETE FROM units
WHERE normalized_name = 'piece'
  AND id NOT IN (SELECT unit_id FROM recipe_ingredients WHERE unit_id IS NOT NULL)
  AND EXISTS (SELECT 1 FROM units WHERE normalized_name = 'unite');

UPDATE units
SET name = 'pièce',
    normalized_name = 'piece',
    abbreviation = NULL,
    hidden = TRUE
WHERE normalized_name = 'unite';
