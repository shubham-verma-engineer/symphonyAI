# Implementation Plan: Database Column-Level Encryption

| Field | Value |
|---|---|
| **Plan #** | 0003 |
| **Status** | Done |
| **Branch** | `main` |
| **Author** | Antigravity + Shubham |
| **Created** | 2026-07-14 |
| **Last updated** | 2026-07-14 |
| **Related design doc** | `docs/SYSTEM_DESIGN.md` |

## 1. Understanding

The goal is to encrypt sensitive enterprise query texts stored in the PostgreSQL database to secure data at rest.
- Target columns:
  - `telemetry_logs.query_text`
  - `failed_queries.raw_query`
- Cryptographic algorithm: **AES-256-GCM** authenticated encryption with unique initialization vectors (IVs) for each row.
- Transparent management: Use JPA `AttributeConverter` at the ORM layer so that query data is encrypted transparently on INSERT/UPDATE, and decrypted transparently on SELECT, without affecting existing service-layer code.
- Size safety: Base64-encoded GCM ciphertexts can exceed original VARCHAR column constraints. Change target columns to `TEXT` type to eliminate any potential overflow risks.

## 2. Approach

- **Schema Migration**: Add a Flyway migration `V4__increase_query_text_columns_length.sql` to alter column types of `query_text` and `raw_query` to `TEXT` in the respective tables.
- **AesGcmEncryptor Converter**: Create an `AttributeConverter<String, String>` class `AesGcmEncryptor` executing AES-256-GCM encryption and decryption. Include static fallback initialization in case of instantiation outside the Spring lifecycle.
- **Entity Annotations**: Annotate fields `queryText` in `TelemetryLog.java` and `rawQuery` in `FailedQuery.java` with `@Convert(converter = AesGcmEncryptor.class)`.

## 3. Phases

### Phase 1 — Database Columns Expansion · Status: Done
- **Does**: Apply migration to widen target query text columns to `TEXT`.
- **Verify**: Inspect updated schema inside postgres test database.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/resources/db/migration/V4__increase_query_text_columns_length.sql` | Migration widening database columns to TEXT (new) |

### Phase 2 — Cryptographic Attribute Converter · Status: Done
- **Does**: Implement `AesGcmEncryptor` and annotate entity properties.
- **Verify**: Confirm compilation.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/config/AesGcmEncryptor.java` | Create transparent GCM encryption JPA converter (new) |
  | `symphony-backend/src/main/java/com/symphony/entity/TelemetryLog.java` | Apply `@Convert` to queryText (modified) |
  | `symphony-backend/src/main/java/com/symphony/entity/FailedQuery.java` | Apply `@Convert` to rawQuery (modified) |

## 4. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Performance overhead from cryptographic operations | GCM uses hardware-accelerated AES instruction sets (AES-NI) on modern CPUs. The latency overhead is sub-millisecond, which is negligible compared to database I/O and network latency. |
| Existing query records fail decryption | For a clean local database setup in development/tests, database resets occur. In production, a migration script would migrate existing plaintext rows to encrypted formats before activating the converter. |

## 5. Out of scope / deferred

- Key rotation mechanism and Key Management Service (KMS) integration (e.g. AWS KMS / GCP KMS). Deferred to local properties configuration.

## 6. Verification

- Write integration tests inside `DatabaseEncryptionIntegrationTest.java` that insert a log, select it, assert the entity properties are readable, and query the raw database using `JdbcTemplate` to assert that the raw string in PostgreSQL is indeed encrypted Base64 and not plaintext.

## 7. Rollback

- Revert entity converters and restore original column types.

## 8. Decision Log

| Date | Change | Why |
|---|---|---|
| 2026-07-14 | Initial draft | Proposed plan for Database Column-Level Encryption |

## 9. Commits / PR

- Commit: `feat(db): implement database column-level GCM encryption via JPA converter`
