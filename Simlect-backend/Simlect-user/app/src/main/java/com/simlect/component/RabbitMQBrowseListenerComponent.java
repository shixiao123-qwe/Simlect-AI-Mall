package com.simlect.component;

import com.simlect.constants.RabbitMQConfig;
import com.simlect.api.dto.BrowseHistoryMessageDTO;
import com.simlect.biz.UserBrowseHistoryService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class RabbitMQBrowseListenerComponent {

    @Resource
    private UserBrowseHistoryService userBrowseHistoryService;
    @Resource
    private MqListenerHelper mqListenerHelper;

    @RabbitListener(queues = RabbitMQConfig.BROWSE_RECORD_QUEUE, ackMode = "MANUAL")
    public void handleBrowseRecord(BrowseHistoryMessageDTO message, Channel channel, Message mqMessage) throws IOException {
        mqListenerHelper.consumeInbox(RabbitMQConfig.BROWSE_RECORD_QUEUE,"BROWSE_HISTORY",mqMessage,channel,message,() -> {
            if (message==null || message.getUserId()==null || message.getProductId()==null)
                throw new MqConsumerIdempotencyHelper.InvalidEventException("足迹消息参数缺失");
            userBrowseHistoryService.recordBrowse(message.getUserId(),message.getProductId());
        });
    }
}
