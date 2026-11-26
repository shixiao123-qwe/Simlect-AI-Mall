-- Manual Outbox review/requeue. Select ONE owning database explicitly. No cross-database operation.
-- Default NULL ID is intentionally a no-op. Set it to the reviewed row ID before using this runbook.
-- Keep the business idempotency key, exchange and payload unchanged. Verify consumer idempotency first.
-- Check actual delivery/business outcome before resetting an exhausted/unknown record.
SET @outbox_review_id = NULL;
SET @outbox_max_attempts = 10; -- Must match that service's mq.outbox.dispatch-max-retries.
START TRANSACTION;
SELECT id, idempotency_key, exchange_name, routing_key, status, retry_count,
       error_message, lease_owner, lease_until, next_attempt_time
FROM local_message_outbox WHERE id = @outbox_review_id FOR UPDATE;
-- Only terminal FAILED rows can be requeued. Active leases, SENT and recoverable SENDING are untouched.
UPDATE local_message_outbox
SET status=0, retry_count=0, next_attempt_time=NULL, update_time=now(3)
WHERE id=@outbox_review_id AND status=3 AND retry_count>=@outbox_max_attempts
  AND lease_owner IS NULL AND lease_until IS NULL;
SELECT ROW_COUNT() AS manually_requeued_rows;
COMMIT;
-- Keep the query result, operator, reason and timestamp in your operations audit trail.
