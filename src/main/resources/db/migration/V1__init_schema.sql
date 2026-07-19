-- V1__init_schema.sql
-- Artel Orchestration Server Initial Database Schema

CREATE TABLE IF NOT EXISTS sdk_session_log (
    id BIGSERIAL PRIMARY KEY,
    sdk_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);