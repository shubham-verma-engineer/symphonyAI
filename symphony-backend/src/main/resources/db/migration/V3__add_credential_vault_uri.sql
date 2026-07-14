-- Add credential_vault_uri column to agents table
ALTER TABLE agents ADD COLUMN credential_vault_uri VARCHAR(255);
