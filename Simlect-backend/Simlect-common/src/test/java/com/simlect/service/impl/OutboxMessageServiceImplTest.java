package com.simlect.service.impl;

import com.simlect.constants.ReliableMessageSender;
import com.simlect.entity.po.LocalMessageOutbox;
import com.simlect.mappers.LocalMessageOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.amqp.AmqpException;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Models DB atomic UPDATE semantics with a controllable clock; not a MySQL integration test. */
class OutboxMessageServiceImplTest {
    LocalMessageOutboxMapper mapper;
    ReliableMessageSender sender;
    OutboxMessageServiceImpl service;
    LocalMessageOutbox row;
    long now;

    @BeforeEach void setup() {
        mapper = mock(LocalMessageOutboxMapper.class);
        sender = mock(ReliableMessageSender.class);
        service = worker();
        now = 100_000;
        row = new LocalMessageOutbox();
        row.setId(1L); row.setIdempotencyKey("order-timeout:o1");
        row.setExchangeName("exchange"); row.setRoutingKey("route"); row.setPayloadJson("{\"orderId\":\"o1\"}");
        row.setStatus(0); row.setRetryCount(0); row.setCreateTime(new Date(0));
        when(mapper.claimLease(anyLong(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> claim(call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5)));
        when(mapper.selectById(1L)).thenAnswer(call -> snapshot());
        when(mapper.ownsLease(anyLong(), anyString())).thenAnswer(call -> owns(call.getArgument(1)) ? 1 : 0);
        when(mapper.completeLease(anyLong(), anyString(), anyBoolean(), nullable(String.class), anyInt(), anyInt()))
                .thenAnswer(call -> complete(call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5)));
        when(mapper.selectReadyBatch(anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> ready(call.getArgument(1)) ? List.of(snapshot()) : List.of());
        when(mapper.expireExhausted(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            synchronized (this) {
                if (row.getStatus() == 1 && row.getRetryCount() >= (Integer) call.getArgument(1) && row.getLeaseUntil().getTime() <= now) {
                    row.setStatus(3); row.setLeaseOwner(null); row.setLeaseUntil(null); row.setNextAttemptTime(null);
                    row.setErrorMessage("Final lease expired; delivery outcome unknown; manual review required");
                    return 1;
                }
                return 0;
            }
        });
    }

    OutboxMessageServiceImpl worker() {
        var worker = new OutboxMessageServiceImpl();
        ReflectionTestUtils.setField(worker, "localMessageOutboxMapper", mapper);
        ReflectionTestUtils.setField(worker, "reliableMessageSender", sender);
        return worker;
    }
    synchronized LocalMessageOutbox snapshot() {
        var copy = new LocalMessageOutbox(); BeanUtils.copyProperties(row, copy); return copy;
    }
    synchronized boolean ready(int max) {
        return row.getRetryCount() < max && (row.getNextAttemptTime() == null || row.getNextAttemptTime().getTime() <= now)
                && (row.getStatus() == 0 || row.getStatus() == 3 || (row.getStatus() == 1 && row.getLeaseUntil().getTime() <= now));
    }
    synchronized int claim(String owner, int seconds, int max, int base, int cap) {
        if (!ready(max)) return 0;
        row.setStatus(1); row.setLeaseOwner(owner); row.setLeaseUntil(new Date(now + seconds * 1000L));
        row.setRetryCount(row.getRetryCount() + 1);
        row.setNextAttemptTime(new Date(row.getLeaseUntil().getTime() + delay(base, cap)));
        return 1;
    }
    long delay(int base, int cap) { return Math.min(cap, base * (1L << Math.min(row.getRetryCount() - 1, 30))) * 1000L; }
    synchronized boolean owns(String owner) {
        return row.getStatus() == 1 && Objects.equals(owner, row.getLeaseOwner()) && row.getLeaseUntil().getTime() > now;
    }
    synchronized int complete(String owner, boolean sent, String error, int base, int cap) {
        if (!owns(owner)) return 0;
        row.setStatus(sent ? 2 : 3); row.setErrorMessage(error);
        row.setSentTime(sent ? new Date(now) : null);
        row.setNextAttemptTime(sent ? null : new Date(now + delay(base, cap)));
        row.setLeaseOwner(null); row.setLeaseUntil(null); return 1;
    }
    void advanceToRetry() { now = row.getNextAttemptTime().getTime(); }
    static class ProcessExit extends Error { }

    @Test void confirmedPublishBecomesSent() throws Exception {
        service.tryDispatch(1L);
        assertEquals(2, row.getStatus()); assertEquals(1, row.getRetryCount());
        assertNull(row.getLeaseOwner()); assertNull(row.getNextAttemptTime()); assertNotNull(row.getSentTime());
        verify(sender).sendOutboxWithConfirm(eq("exchange"), eq("route"), any(), eq("order-timeout:o1"), anyString());
    }
    @Test void nullIdDoesNotTouchDatabase() {
        service.tryDispatch(null); verify(mapper, never()).claimLease(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    }
    @Test void sentMessageIsNotSentAgain() throws Exception {
        service.tryDispatch(1L); service.tryDispatch(1L);
        verify(sender, times(1)).sendOutboxWithConfirm(anyString(), anyString(), any(), anyString(), anyString());
    }
    @Test void processExitAfterClaimBeforeReadingOrSendingIsRecoverable() throws Exception {
        var crash = new AtomicBoolean(true);
        when(mapper.selectById(1L)).thenAnswer(call -> { if (crash.getAndSet(false)) throw new ProcessExit(); return snapshot(); });
        assertThrows(ProcessExit.class, () -> service.tryDispatch(1L));
        assertEquals(1, row.getStatus()); verifyNoInteractions(sender);
        worker().tryDispatch(1L); verifyNoInteractions(sender);
        advanceToRetry(); assertEquals(1, worker().dispatchPendingBatch(30, 10)); assertEquals(2, row.getStatus());
    }
    @Test void processExitDuringSendIsRecoverable() throws Exception {
        doThrow(new ProcessExit()).doNothing().when(sender).sendOutboxWithConfirm(anyString(), anyString(), any(), anyString(), anyString());
        assertThrows(ProcessExit.class, () -> service.tryDispatch(1L));
        String firstOwner = row.getLeaseOwner(); advanceToRetry(); worker().tryDispatch(1L);
        assertEquals(2, row.getStatus()); assertEquals(2, row.getRetryCount());
        verify(mapper).completeLease(eq(1L), argThat(owner -> !owner.equals(firstOwner)), eq(true), isNull(), anyInt(), anyInt());
    }
    @Test void confirmedThenProcessExitBeforeSentWriteRepublishesSameBusinessKey() throws Exception {
        var crash = new AtomicBoolean(true);
        when(mapper.completeLease(anyLong(), anyString(), eq(true), isNull(), anyInt(), anyInt())).thenAnswer(call -> {
            if (crash.getAndSet(false)) throw new ProcessExit();
            return complete(call.getArgument(1), true, null, call.getArgument(4), call.getArgument(5));
        });
        assertThrows(ProcessExit.class, () -> service.tryDispatch(1L));
        advanceToRetry(); worker().tryDispatch(1L); assertEquals(2, row.getStatus());
        var attempts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sender, times(2)).sendOutboxWithConfirm(anyString(), anyString(), any(), eq("order-timeout:o1"), attempts.capture());
        assertNotEquals(attempts.getAllValues().get(0), attempts.getAllValues().get(1));
    }
    @Test void databaseFailureAfterConfirmRemainsUnknownUntilLeaseRecovery() {
        when(mapper.completeLease(anyLong(), anyString(), eq(true), isNull(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(() -> service.tryDispatch(1L));
        assertEquals(1, row.getStatus()); assertNotNull(row.getLeaseUntil()); assertNull(row.getSentTime());
    }
    @Test void brokerFailureBacksOffAndThenRetries() throws Exception {
        doThrow(new AmqpException("broker down")).doNothing().when(sender)
                .sendOutboxWithConfirm(anyString(), anyString(), any(), anyString(), anyString());
        service.tryDispatch(1L); assertEquals(3, row.getStatus()); assertEquals("broker down", row.getErrorMessage());
        assertEquals(now + 2000, row.getNextAttemptTime().getTime());
        service.tryDispatch(1L); assertEquals(1, row.getRetryCount());
        advanceToRetry(); service.tryDispatch(1L); assertEquals(2, row.getStatus());
    }
    @Test void expiredOwnerCannotSaveSuccessOrFailureAfterAnotherClaim() throws Exception {
        doAnswer(call -> {
            String old = call.getArgument(4); advanceToRetry();
            assertEquals(1, mapper.claimLease(1L, "another-instance:claim", 60, 10, 2, 300));
            assertEquals(0, mapper.completeLease(1L, old, false, "late error", 2, 300));
            return null;
        }).when(sender).sendOutboxWithConfirm(anyString(), anyString(), any(), anyString(), anyString());
        service.tryDispatch(1L); assertEquals(1, row.getStatus()); assertEquals("another-instance:claim", row.getLeaseOwner());
    }
    @Test void twoInstancesCannotHoldOneEffectiveLease() throws Exception {
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2); var other = worker();
        try {
            Future<?> one = pool.submit(() -> { await(start); service.tryDispatch(1L); });
            Future<?> two = pool.submit(() -> { await(start); other.tryDispatch(1L); });
            start.countDown(); one.get(5, TimeUnit.SECONDS); two.get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(2, row.getStatus()); assertEquals(1, row.getRetryCount());
        verify(sender, times(1)).sendOutboxWithConfirm(anyString(), anyString(), any(), anyString(), anyString());
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    @Test void exhaustedCrashedAttemptIsRetainedForManualReview() throws Exception {
        ReflectionTestUtils.setField(service, "maxAttempts", 1);
        doThrow(new ProcessExit()).when(sender).sendOutboxWithConfirm(anyString(), anyString(), any(), anyString(), anyString());
        assertThrows(ProcessExit.class, () -> service.tryDispatch(1L));
        advanceToRetry(); assertEquals(0, service.dispatchPendingBatch(30, 1));
        assertEquals(3, row.getStatus()); assertNull(row.getLeaseOwner()); assertTrue(row.getErrorMessage().contains("unknown"));
        service.tryDispatch(1L); assertEquals(1, row.getRetryCount());
    }
    @Test void failureBackoffHasUpperBound() throws Exception {
        ReflectionTestUtils.setField(service, "backoffMaxSeconds", 4);
        doThrow(new AmqpException("timeout")).when(sender).sendOutboxWithConfirm(anyString(), anyString(), any(), anyString(), anyString());
        for (int i = 0; i < 5; i++) { service.tryDispatch(1L); assertTrue(row.getNextAttemptTime().getTime() <= now + 4000); advanceToRetry(); }
        assertEquals(5, row.getRetryCount());
    }
    @Test void invalidConfigurationFailsFast() {
        ReflectionTestUtils.setField(service, "leaseSeconds", 0);
        assertThrows(IllegalArgumentException.class, service::validateSettings);
    }
    @Test void duplicateSaveReturnsExistingIdWithoutReplacingPayload() {
        when(mapper.selectByIdempotencyKey("order-timeout:o1")).thenReturn(snapshot());
        assertEquals(1L, service.savePending("exchange", "route", "different payload", "order-timeout:o1", null));
        verify(mapper, never()).insert(any());
    }
}
