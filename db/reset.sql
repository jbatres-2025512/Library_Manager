-- DEVELOPMENT ONLY: wipes the whole schema. Then restart the app to recreate and reseed.
-- Usage: psql -U library_user -d library -f db/reset.sql
DROP TABLE IF EXISTS loans CASCADE;
DROP TABLE IF EXISTS books CASCADE;
DROP TABLE IF EXISTS users CASCADE;