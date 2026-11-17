-- Schema for GitHub Look.
-- Portable DDL: runs on MySQL 8 (InnoDB) and on H2 in MySQL compatibility mode,
-- so the local demo database and production use the exact same migration.

CREATE TABLE monitored_repo (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    owner          VARCHAR(120) NOT NULL,
    name           VARCHAR(160) NOT NULL,
    full_name      VARCHAR(280) NOT NULL,
    default_branch VARCHAR(160),
    webhook_secret VARCHAR(200),
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_monitored_repo_full_name ON monitored_repo (full_name);

CREATE TABLE workflow_run (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    external_run_id BIGINT       NOT NULL,
    repo_full_name  VARCHAR(280) NOT NULL,
    workflow_name   VARCHAR(200),
    run_number      INT,
    branch          VARCHAR(200),
    event_name      VARCHAR(60),
    status          VARCHAR(40)  NOT NULL,
    conclusion      VARCHAR(40),
    actor           VARCHAR(120),
    commit_sha      VARCHAR(64),
    commit_message  VARCHAR(1000),
    commit_author   VARCHAR(160),
    html_url        VARCHAR(500),
    started_at      TIMESTAMP    NULL,
    completed_at    TIMESTAMP    NULL,
    duration_ms     BIGINT,
    delivery_id     VARCHAR(120),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_workflow_run_repo_external ON workflow_run (repo_full_name, external_run_id);
CREATE INDEX idx_workflow_run_started_at ON workflow_run (started_at);
CREATE INDEX idx_workflow_run_status ON workflow_run (status);
CREATE INDEX idx_workflow_run_branch ON workflow_run (branch);
CREATE INDEX idx_workflow_run_repo ON workflow_run (repo_full_name);

CREATE TABLE run_job (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    run_pk          BIGINT       NOT NULL,
    external_job_id BIGINT,
    name            VARCHAR(200),
    status          VARCHAR(40),
    conclusion      VARCHAR(40),
    started_at      TIMESTAMP    NULL,
    completed_at    TIMESTAMP    NULL,
    duration_ms     BIGINT,
    runner_name     VARCHAR(160),
    html_url        VARCHAR(500),
    PRIMARY KEY (id)
);

CREATE INDEX idx_run_job_run_pk ON run_job (run_pk);

CREATE TABLE run_step (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    job_pk       BIGINT       NOT NULL,
    step_number  INT,
    name         VARCHAR(300),
    status       VARCHAR(40),
    conclusion   VARCHAR(40),
    started_at   TIMESTAMP    NULL,
    completed_at TIMESTAMP    NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_run_step_job_pk ON run_step (job_pk);

CREATE TABLE webhook_delivery (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    delivery_id   VARCHAR(120) NOT NULL,
    event_name    VARCHAR(80),
    status        VARCHAR(30)  NOT NULL,
    repo_full_name VARCHAR(280),
    received_at   TIMESTAMP    NOT NULL,
    processed_at  TIMESTAMP    NULL,
    error_message VARCHAR(1000),
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_webhook_delivery_id ON webhook_delivery (delivery_id);
CREATE INDEX idx_webhook_delivery_received_at ON webhook_delivery (received_at);

CREATE TABLE app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(80)  NOT NULL,
    display_name  VARCHAR(120),
    password_hash VARCHAR(200) NOT NULL,
    role          VARCHAR(40)  NOT NULL DEFAULT 'ADMIN',
    created_at    TIMESTAMP    NOT NULL,
    last_login_at TIMESTAMP    NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_app_user_username ON app_user (username);

CREATE TABLE notification_setting (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    channel         VARCHAR(40)  NOT NULL,
    webhook_url     VARCHAR(500),
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    notify_on       VARCHAR(120) NOT NULL DEFAULT 'failure',
    updated_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_notification_channel ON notification_setting (channel);
