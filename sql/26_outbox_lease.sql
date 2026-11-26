-- T02 schema upgrade for MySQL 8. Run with the application connection selected to EACH owning database.
-- Required databases: simlect_order, simlect_admin, simlect_user, simlect_product, simlect_search, simlect_coupon.
-- Run existing initialization scripts first. Stop/drain ALL old publishers before migration; old binaries
-- cannot fence lease results. Apply this script in all owning DBs, deploy new binaries, then resume workers.
-- This script is repeatable and does not change message payloads/statuses or reset retry counters.
-- DDL commits implicitly in MySQL; take a backup and schedule normal maintenance before applying.
DROP PROCEDURE IF EXISTS migrate_t02_outbox_lease;
DELIMITER $$
CREATE PROCEDURE migrate_t02_outbox_lease()
BEGIN
    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select an Outbox owning database first';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='local_message_outbox') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Initialize local_message_outbox before this migration';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='local_message_outbox' AND column_name='lease_owner') THEN
        ALTER TABLE local_message_outbox ADD COLUMN lease_owner varchar(96) NULL COMMENT 'Unique instance:claim fencing token';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='local_message_outbox' AND column_name='lease_until') THEN
        ALTER TABLE local_message_outbox ADD COLUMN lease_until datetime(3) NULL COMMENT 'DB-time lease expiration';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='local_message_outbox' AND column_name='next_attempt_time') THEN
        ALTER TABLE local_message_outbox ADD COLUMN next_attempt_time datetime(3) NULL COMMENT 'Earliest retry time';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='local_message_outbox' AND index_name='idx_outbox_ready') THEN
        ALTER TABLE local_message_outbox ADD INDEX idx_outbox_ready(status, next_attempt_time, id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='local_message_outbox' AND index_name='idx_outbox_lease') THEN
        ALTER TABLE local_message_outbox ADD INDEX idx_outbox_lease(status, lease_until, id);
    END IF;
END$$
DELIMITER ;
CALL migrate_t02_outbox_lease();
DROP PROCEDURE migrate_t02_outbox_lease;
