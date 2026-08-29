ALTER TABLE user_accounts ADD COLUMN password_hash VARCHAR(100);

ALTER TABLE user_accounts
    ADD CONSTRAINT uk_user_accounts_email UNIQUE (email);
