GRANT CREATE SESSION TO pdbadmin;
GRANT CREATE TABLE TO pdbadmin;
GRANT CREATE SEQUENCE TO pdbadmin;
GRANT CREATE VIEW TO pdbadmin;
GRANT UNLIMITED TABLESPACE TO pdbadmin;
GRANT EXECUTE ON DBMS_AQ TO pdbadmin;
GRANT EXECUTE ON DBMS_AQADM TO pdbadmin;
GRANT AQ_ADMINISTRATOR_ROLE TO pdbadmin;

-- Dev DB hardening (Fix B from the GP postmortem): the default profile locks
-- the account after 10 failed logins. During cold-start, multiple services
-- racing to populate UCP/oracledb pools while the PDB is still mounting can
-- burn through 10 failures in seconds and lock the account, killing strategy
-- and simulation for the rest of the session. Set it to UNLIMITED on dev DBs.
ALTER PROFILE default LIMIT FAILED_LOGIN_ATTEMPTS UNLIMITED;
