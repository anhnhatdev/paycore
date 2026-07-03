CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_type VARCHAR(30) NOT NULL,
    period_start TIMESTAMP NOT NULL,
    period_end TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    total_checked INT,
    total_discrepancies INT,
    started_at TIMESTAMP NOT NULL DEFAULT now(),
    completed_at TIMESTAMP
);

CREATE TABLE discrepancies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_run_id UUID NOT NULL REFERENCES reconciliation_runs(id),
    discrepancy_type VARCHAR(50) NOT NULL,
    severity VARCHAR(10) NOT NULL,
    entity_reference VARCHAR(255) NOT NULL,
    expected_value JSONB,
    actual_value JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolved_by VARCHAR(255),
    resolution_note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    resolved_at TIMESTAMP
);

CREATE INDEX idx_discrepancies_status ON discrepancies(status, severity);
CREATE INDEX idx_discrepancies_run ON discrepancies(reconciliation_run_id);
CREATE INDEX idx_discrepancies_dedup ON discrepancies(discrepancy_type, entity_reference, status);

CREATE TABLE settlement_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(20) NOT NULL,
    report_date DATE NOT NULL,
    raw_file_reference TEXT NOT NULL,
    row_count INT,
    downloaded_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (provider, report_date)
);
