-- Create Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";

-- Users Table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(150) UNIQUE NOT NULL,
    role_flag VARCHAR(20) NOT NULL CHECK (role_flag IN ('ADMIN', 'CREATOR', 'USER')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Workspaces Table
CREATE TABLE workspaces (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL,
    billing_tier VARCHAR(50) DEFAULT 'FREE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Workspace Membership Table (Joint RBAC/Multi-tenancy mapping)
CREATE TABLE workspace_members (
    workspace_id UUID REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    joined_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workspace_id, user_id)
);

-- Agents Table
CREATE TABLE agents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    version VARCHAR(20) NOT NULL,
    description TEXT NOT NULL,
    description_embedding vector(1536), -- Dimension matches text-embedding-3-small
    endpoint_url VARCHAR(255) NOT NULL,
    health_check_url VARCHAR(255),
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'UNHEALTHY', 'DEPRECATED')),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    registered_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    concurrency_version INTEGER DEFAULT 0 NOT NULL,
    CONSTRAINT unique_workspace_agent_name UNIQUE (workspace_id, name)
);

-- Agent Parameters Table
CREATE TABLE agent_parameters (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    param_name VARCHAR(100) NOT NULL,
    param_type VARCHAR(20) NOT NULL CHECK (param_type IN ('string', 'number', 'boolean', 'array')),
    param_description TEXT,
    is_required BOOLEAN DEFAULT FALSE,
    CONSTRAINT unique_agent_param UNIQUE (agent_id, param_name)
);

-- Telemetry Logs
CREATE TABLE telemetry_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    agent_id UUID REFERENCES agents(id) ON DELETE SET NULL,
    query_text VARCHAR(500) NOT NULL,
    routing_decision VARCHAR(20) NOT NULL CHECK (routing_decision IN ('routed', 'chained', 'no_match')),
    execution_latency_ms INTEGER,
    token_cost DECIMAL(10, 6) DEFAULT 0.000000,
    http_status_code INTEGER,
    executed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Failed Queries
CREATE TABLE failed_queries (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    raw_query VARCHAR(500) NOT NULL,
    is_clustered BOOLEAN DEFAULT FALSE,
    logged_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Innovation Gaps
CREATE TABLE innovation_gaps (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    title VARCHAR(150) NOT NULL,
    summary TEXT,
    count_frequency INTEGER DEFAULT 1,
    detected_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indices for Fast Lookups
CREATE INDEX idx_agents_embedding ON agents USING hnsw (description_embedding vector_cosine_ops);
CREATE INDEX idx_telemetry_workspace ON telemetry_logs (workspace_id);
CREATE INDEX idx_failed_queries_unclustered ON failed_queries (workspace_id) WHERE is_clustered = FALSE;
