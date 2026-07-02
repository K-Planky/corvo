-- V5: drop the unused users.email column.
--
-- Email was collected at registration (V2) but never used functionally: login is by username,
-- the JWT carries userId/username (not email), and there is no email-sending, password-reset,
-- verification, or notification path. It was unused PII, so it is removed. Postgres drops the
-- dependent uq_users_email constraint automatically along with the column.
ALTER TABLE users DROP COLUMN email;
