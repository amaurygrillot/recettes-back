-- Renames categories_relation.superior_category_id to parent_category_id.
--
-- WHY THIS EXISTS
-- There is no migration tool here (ddl-auto=update, no Flyway), and Hibernate's update mode never
-- renames anything: it sees a column it does not know about and a column it expects, and it adds
-- the second one. That add is `parent_category_id VARCHAR(255) NOT NULL`, which PostgreSQL refuses
-- on a table that already has rows, so the column is never created at all and every query against
-- categories_relation fails with "column cre1_0.parent_category_id does not exist".
--
-- A rename is used rather than add-copy-drop because it carries the column's type, its NOT NULL and
-- its foreign key across untouched, and leaves nothing behind to clean up afterwards. The foreign
-- key and its index keep names mentioning "superior"; constraint names are invisible to the
-- application, and the one index whose name this project does choose is renamed below.
--
-- WHEN TO RUN IT
-- Once, against any database that has a categories_relation table created before this rename. A
-- fresh database does not need it: Hibernate creates the table with the new name from the entity.
-- It is safe to run twice, and safe to run on a database that has never had the table.
--
-- HOW TO RUN IT
--   psql -U <user> -d <database> -f docs/sql/rename-superior-category-to-parent.sql
-- or paste it into DataGrip against the local database. The app must be stopped, or restarted
-- afterwards.
--
-- See docs/category-hierarchy.md for what the column means and why the name changed.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.categories_relation') IS NULL THEN
        RAISE NOTICE 'No categories_relation table: nothing to rename, Hibernate will create it.';

    ELSIF EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'public'
                    AND table_name = 'categories_relation'
                    AND column_name = 'parent_category_id') THEN
        RAISE NOTICE 'parent_category_id already exists: nothing to do.';

    ELSIF EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'public'
                    AND table_name = 'categories_relation'
                    AND column_name = 'superior_category_id') THEN
        ALTER TABLE categories_relation RENAME COLUMN superior_category_id TO parent_category_id;
        RAISE NOTICE 'Renamed superior_category_id to parent_category_id.';

    ELSE
        -- Neither column. The table exists but holds neither name, which this script must not guess
        -- its way out of: loudly wrong beats quietly wrong on someone's only copy of the data.
        RAISE EXCEPTION 'categories_relation has neither superior_category_id nor parent_category_id; '
                        'inspect the table by hand before running this again.';
    END IF;
END $$;

-- The index this project names itself. Dropping and recreating it would also work; renaming keeps
-- the index built rather than rebuilding it.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_categories_relation_superior') THEN
        ALTER INDEX idx_categories_relation_superior RENAME TO idx_categories_relation_parent;
        RAISE NOTICE 'Renamed the index to idx_categories_relation_parent.';
    END IF;
END $$;

COMMIT;

-- Verification: one row per category, every parent_category_id populated, roots pointing at
-- themselves. The second count must be zero and the third must equal the number of main categories.
SELECT count(*)                                              AS edges,
       count(*) FILTER (WHERE parent_category_id IS NULL)    AS unpopulated,
       count(*) FILTER (WHERE parent_category_id = category_id) AS roots
FROM categories_relation;
