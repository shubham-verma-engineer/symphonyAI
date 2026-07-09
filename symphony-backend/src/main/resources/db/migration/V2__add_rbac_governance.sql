-- Add Governance RBAC columns to agents table
ALTER TABLE agents ADD COLUMN requires_rbac BOOLEAN DEFAULT FALSE;
ALTER TABLE agents ADD COLUMN authorized_roles VARCHAR(255);
