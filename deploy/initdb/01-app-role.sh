#!/bin/sh
# Template: /srv/apps/othello/initdb/01-app-role.sh   (mounted at /docker-entrypoint-initdb.d)
#
# Creates the least-privilege role the application connects as.
#
# Why two roles at all: the postgres image makes POSTGRES_USER a cluster SUPERUSER, and a
# superuser can COPY ... TO PROGRAM (command execution in the database container) and read files
# with pg_read_file. Handing that credential to the internet-facing process means any future SQL
# injection escalates straight past the data. The app role below can do neither.
#
# Why POSTGRES_USER stays a superuser rather than being demoted: the nightly backup runs
# `pg_dumpall -U $POSTGRES_USER`, and pg_dumpall reads pg_authid to dump roles, which requires
# superuser. Demoting it produces a failing or degraded backup that you discover at restore time.
# So the admin role keeps its privileges and simply stops being the one the app uses.
#
# This runs ONCE, on first initialization of the data directory. For a database that already
# exists, see "Retrofitting an existing app" in DEPLOY.md.
set -eu

: "${DB_APP_USER:?DB_APP_USER must be set for the initdb role script}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD must be set for the initdb role script}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
	CREATE ROLE "$DB_APP_USER" LOGIN PASSWORD '$DB_APP_PASSWORD'
	    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

	-- CREATE so the app can build its own schema at startup and own what it builds; an owner
	-- needs no further grants on its own tables. USAGE is implicit on public in PG15+, stated
	-- here so the intent survives a future REVOKE ... FROM PUBLIC.
	GRANT USAGE, CREATE ON SCHEMA public TO "$DB_APP_USER";
EOSQL

echo "created least-privilege app role $DB_APP_USER"
