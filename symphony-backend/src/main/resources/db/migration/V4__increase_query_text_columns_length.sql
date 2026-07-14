-- Widen query text columns to TEXT to avoid overflow with base64 encoded GCM ciphertexts
ALTER TABLE telemetry_logs ALTER COLUMN query_text TYPE TEXT;
ALTER TABLE failed_queries ALTER COLUMN raw_query TYPE TEXT;
