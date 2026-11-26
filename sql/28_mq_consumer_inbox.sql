-- T03: apply in EACH Inbox consumer's local DB after the T02 Outbox migration.
-- Current consumers use simlect_order, simlect_coupon, simlect_user and simlect_search.
-- Stop/drain old consumers before deploying the new implementation. Redis flags are NOT imported as DONE.
CREATE TABLE IF NOT EXISTS mq_consumer_inbox (
    consumer_name varchar(128) COLLATE utf8mb4_bin NOT NULL,
    event_id varchar(256) COLLATE utf8mb4_bin NOT NULL,
    event_type varchar(128) NOT NULL,
    status varchar(16) NOT NULL COMMENT 'PROCESSING/DONE/FAILED',
    failure_count int NOT NULL DEFAULT 0,
    error_message varchar(512) NULL,
    dlq_enqueued tinyint NOT NULL DEFAULT 0 COMMENT 'Failure handed off atomically to local Outbox',
    created_time datetime(3) NOT NULL,
    completed_time datetime(3) NULL,
    PRIMARY KEY (consumer_name,event_id),
    KEY idx_inbox_failed(status,dlq_enqueued,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Local transaction Inbox; retain DONE for replay safety';
