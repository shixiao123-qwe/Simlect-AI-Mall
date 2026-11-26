package com.simlect.constants;

import com.simlect.service.OutboxMessageService;
import com.simlect.entity.enums.MessageReliabilityLevelEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TransactionalMqSenderTest {
    @Test void outboxIsWrittenBeforeCommitAndDispatchRunsAfterCommit() {
        var outbox=mock(OutboxMessageService.class); var direct=mock(ReliableMessageSender.class);
        var sender=new TransactionalMqSender();
        ReflectionTestUtils.setField(sender,"outboxMessageService",outbox);
        ReflectionTestUtils.setField(sender,"reliableMessageSender",direct);
        when(outbox.savePending(anyString(),anyString(),any(),anyString(),any())).thenReturn(7L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            sender.sendAfterCommit("exchange","route","body","stable-key",MessageReliabilityLevelEnum.STANDARD);
            verify(outbox).savePending("exchange","route","body","stable-key",MessageReliabilityLevelEnum.STANDARD);
            verify(outbox,never()).tryDispatch(any());
            var callbacks=TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1,callbacks.size()); callbacks.get(0).afterCommit(); verify(outbox).tryDispatch(7L);
            verifyNoInteractions(direct);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void withoutTransactionExistingDirectPathIsPreserved() {
        var outbox=mock(OutboxMessageService.class);var direct=mock(ReliableMessageSender.class);
        var sender=new TransactionalMqSender();
        ReflectionTestUtils.setField(sender,"outboxMessageService",outbox);
        ReflectionTestUtils.setField(sender,"reliableMessageSender",direct);
        sender.sendAfterCommit("exchange","route","body","stable-key",null);
        verify(direct).sendMessage("exchange","route","body","stable-key",MessageReliabilityLevelEnum.STANDARD);
        verifyNoInteractions(outbox);
    }
}
