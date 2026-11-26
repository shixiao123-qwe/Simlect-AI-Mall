package com.simlect.inbox;

import com.rabbitmq.client.Channel;
import com.simlect.component.*;
import com.simlect.constants.*;
import com.simlect.mappers.*;
import com.simlect.service.OutboxMessageService;
import com.simlect.service.impl.OutboxMessageServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** All database/broker addresses come from fresh containers, never application configuration. */
@Testcontainers
@EnabledIfEnvironmentVariable(named="SIMLECT_INBOX_IT",matches="true")
class InboxConsistencyIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.3"))
            .withDatabaseName("inbox_test").withUsername("inbox_test").withPassword("inbox_test_only");
    @Container static final RabbitMQContainer RABBIT=new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));
    static JdbcTemplate jdbc;
    static CachingConnectionFactory connection;
    static ReliableMessageSender publisher;
    static MqConsumerIdempotencyHelper inbox;
    static MqListenerHelper listener;
    static class ProcessExit extends Error { }
    @BeforeAll static void initialize() throws Exception {
        var dataSource=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(dataSource);
        Path root=Path.of("").toAbsolutePath();while(root!=null && !Files.isRegularFile(root.resolve("sql/28_mq_consumer_inbox.sql")))root=root.getParent();
        assertNotNull(root);Path sql=root.resolve("sql");
        String outboxDdl=Files.readString(sql.resolve("12_order_outbox.sql"));int start=outboxDdl.indexOf("create table if not exists local_message_outbox");
        jdbc.execute(outboxDdl.substring(start,outboxDdl.indexOf(';',start)));
        String migration=Files.readString(sql.resolve("26_outbox_lease.sql"));
        jdbc.execute(migration.substring(migration.indexOf("CREATE PROCEDURE"),migration.indexOf("END$$")+3));
        jdbc.execute("CALL migrate_t02_outbox_lease()");jdbc.execute("DROP PROCEDURE migrate_t02_outbox_lease");
        String inboxDdl=Files.readString(sql.resolve("28_mq_consumer_inbox.sql"));
        jdbc.execute(inboxDdl.substring(inboxDdl.indexOf("CREATE TABLE")));jdbc.execute(inboxDdl.substring(inboxDdl.indexOf("CREATE TABLE")));
        jdbc.execute("CREATE TABLE inbox_test_counter(event_id varchar(256) PRIMARY KEY,effects int NOT NULL DEFAULT 0) ENGINE=InnoDB");
        var manager=new DataSourceTransactionManager(dataSource);
        var config=new org.apache.ibatis.session.Configuration(new Environment("inbox-test",new SpringManagedTransactionFactory(),dataSource));
        for(String name:List.of("MqInboxMapper","LocalMessageOutboxMapper")) {
            String resource="com/simlect/mappers/"+name+".xml";
            try(var reader=Resources.getResourceAsReader(resource)){new XMLMapperBuilder(reader,config,resource,config.getSqlFragments()).parse();}
        }
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        connection=new CachingConnectionFactory(RABBIT.getHost(),RABBIT.getAmqpPort());
        connection.setUsername(RABBIT.getAdminUsername());connection.setPassword(RABBIT.getAdminPassword());
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);connection.setPublisherReturns(true);
        var rabbit=new RabbitTemplate(connection);rabbit.setMessageConverter(new Jackson2JsonMessageConverter());
        var confirm=new MqPublisherConfirmHelper();ReflectionTestUtils.setField(confirm,"rabbitTemplate",rabbit);
        ReflectionTestUtils.setField(confirm,"defaultConfirmTimeoutMs",3000L);confirm.attachCallbacks();
        publisher=new ReliableMessageSender();ReflectionTestUtils.setField(publisher,"mqPublisherConfirmHelper",confirm);
        var outbox=new OutboxMessageServiceImpl();ReflectionTestUtils.setField(outbox,"localMessageOutboxMapper",session.getMapper(LocalMessageOutboxMapper.class));
        ReflectionTestUtils.setField(outbox,"reliableMessageSender",publisher);
        var proxy=new ProxyFactory(outbox);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        var sender=new TransactionalMqSender();ReflectionTestUtils.setField(sender,"outboxMessageService",(OutboxMessageService)proxy.getProxy());
        inbox=new MqConsumerIdempotencyHelper();ReflectionTestUtils.setField(inbox,"inboxMapper",session.getMapper(MqInboxMapper.class));
        ReflectionTestUtils.setField(inbox,"transactionManager",manager);ReflectionTestUtils.setField(inbox,"transactionalMqSender",sender);
        listener=new MqListenerHelper();ReflectionTestUtils.setField(listener,"mqConsumerIdempotencyHelper",inbox);
        var admin=new RabbitAdmin(connection);
        admin.declareExchange(new DirectExchange(RabbitMQConfig.INBOX_FAILURE_EXCHANGE,true,false));
        admin.declareQueue(new org.springframework.amqp.core.Queue(RabbitMQConfig.INBOX_FAILURE_QUEUE,true));
        admin.declareBinding(new Binding(RabbitMQConfig.INBOX_FAILURE_QUEUE,Binding.DestinationType.QUEUE,
                RabbitMQConfig.INBOX_FAILURE_EXCHANGE,RabbitMQConfig.INBOX_FAILURE_KEY,null));
    }
    @AfterAll static void cleanup() { if(connection!=null)connection.destroy(); }
    String id() {String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO inbox_test_counter(event_id) VALUES(?)",id);return id;}
    String queue() throws Exception {String name="inbox-test-"+UUID.randomUUID();try(Channel channel=channel()){channel.queueDeclare(name,true,false,false,null);}return name;}
    Channel channel() {return connection.createConnection().createChannel(false);}
    void publish(String queue,String id) throws Exception {publisher.sendOutboxWithConfirm("",queue,Map.of("event",id),id,UUID.randomUUID().toString());}
    Message receive(Channel channel,String queue) throws Exception {
        var delivery=channel.basicGet(queue,false);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(delivery==null && System.nanoTime()<deadline){Thread.sleep(20);delivery=channel.basicGet(queue,false);}
        assertNotNull(delivery,"Expected actual broker redelivery");
        var properties=new MessageProperties();properties.setMessageId(delivery.getProps().getMessageId());
        properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());properties.setRedelivered(delivery.getEnvelope().isRedeliver());
        return new Message(delivery.getBody(),properties);
    }
    void effect(String id) {jdbc.update("UPDATE inbox_test_counter SET effects=effects+1 WHERE event_id=?",id);}
    int effects(String id) {return jdbc.queryForObject("SELECT effects FROM inbox_test_counter WHERE event_id=?",Integer.class,id);}
    String status(String queue,String id) {return jdbc.queryForObject("SELECT status FROM mq_consumer_inbox WHERE consumer_name=? AND event_id=?",String.class,queue,id);}
    @Test void interruptedBeforeConsumptionIsRedeliveredAndNotCompleted() throws Exception {
        String id=id(),queue=queue();publish(queue,id);Channel first=channel();receive(first,queue);first.abort();
        try(Channel retry=channel()) {Message redelivered=receive(retry,queue);assertTrue(Boolean.TRUE.equals(redelivered.getMessageProperties().isRedelivered()));
            listener.consumeInbox(queue,"COUNTER",redelivered,retry,null,()->effect(id));}
        assertEquals(1,effects(id));assertEquals("DONE",status(queue,id));
    }
    @Test void midTransactionExitRollsBackBusinessAndInboxThenRedeliveryWorks() throws Exception {
        String id=id(),queue=queue();publish(queue,id);Channel first=channel();Message message=receive(first,queue);
        assertThrows(ProcessExit.class,()->listener.consumeInbox(queue,"COUNTER",message,first,null,()->{effect(id);throw new ProcessExit();}));
        assertEquals(0,effects(id));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mq_consumer_inbox WHERE consumer_name=? AND event_id=?",Integer.class,queue,id));
        first.abort();try(Channel retry=channel()){listener.consumeInbox(queue,"COUNTER",receive(retry,queue),retry,null,()->effect(id));}
        assertEquals(1,effects(id));assertEquals("DONE",status(queue,id));
    }
    @Test void committedBusinessWithFailedAckIsNotRepeatedOnActualRedelivery() throws Exception {
        String id=id(),queue=queue();publish(queue,id);Channel first=channel();Message message=receive(first,queue);
        Channel broken=mock(Channel.class,org.mockito.AdditionalAnswers.delegatesTo(first));
        doThrow(new IOException("simulated ACK loss")).when(broken).basicAck(anyLong(),eq(false));
        listener.consumeInbox(queue,"COUNTER",message,broken,null,()->effect(id));
        assertEquals("DONE",status(queue,id));assertEquals(1,effects(id));
        try(Channel retry=channel()){Message again=receive(retry,queue);assertTrue(Boolean.TRUE.equals(again.getMessageProperties().isRedelivered()));
            listener.consumeInbox(queue,"COUNTER",again,retry,null,()->effect(id));assertNull(retry.basicGet(queue,true));}
        assertEquals(1,effects(id));
    }
    @Test void duplicateBrokerMessagesOnlyExecuteBusinessOnce() throws Exception {
        String id=id(),queue=queue();publish(queue,id);publish(queue,id);
        try(Channel channel=channel()){for(int i=0;i<2;i++)listener.consumeInbox(queue,"COUNTER",receive(channel,queue),channel,null,()->effect(id));}
        assertEquals(1,effects(id));
    }
    @Test void simultaneousConsumersCompeteInMysqlAndOnlyOneExecutes() throws Exception {
        String id=id(),queue=queue();publish(queue,id);publish(queue,id);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try(Channel one=channel();Channel two=channel()) {
            Message first=receive(one,queue),second=receive(two,queue);
            Future<?> a=pool.submit(()->{await(start);listener.consumeInbox(queue,"COUNTER",first,one,null,()->effect(id));});
            Future<?> b=pool.submit(()->{await(start);listener.consumeInbox(queue,"COUNTER",second,two,null,()->effect(id));});
            start.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(1,effects(id));assertEquals("DONE",status(queue,id));
    }
    static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("timeout");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
    @Test void businessRedisOutageCannotCreateFalseDone() throws Exception {
        String id=id(),queue=queue();publish(queue,id);
        try(Channel channel=channel()) {
            listener.consumeInbox(queue,"COUNTER",receive(channel,queue),channel,null,()->{effect(id);throw new org.springframework.data.redis.RedisConnectionFailureException("Redis unavailable");});
            assertEquals(0,effects(id));assertEquals("FAILED",status(queue,id));
            listener.consumeInbox(queue,"COUNTER",receive(channel,queue),channel,null,()->effect(id));
        }
        assertEquals(1,effects(id));assertEquals("DONE",status(queue,id));
    }
    @Test void committedProcessingRecordIsRequeuedNeverAckedOrRun() throws Exception {
        String id=id(),queue=queue();publish(queue,id);
        jdbc.update("INSERT INTO mq_consumer_inbox(consumer_name,event_id,event_type,status,created_time) VALUES(?,?,'COUNTER','PROCESSING',now(3))",queue,id);
        try(Channel channel=channel()){listener.consumeInbox(queue,"COUNTER",receive(channel,queue),channel,null,()->effect(id));
            Message retry=receive(channel,queue);assertTrue(Boolean.TRUE.equals(retry.getMessageProperties().isRedelivered()));channel.basicReject(retry.getMessageProperties().getDeliveryTag(),false);}
        assertEquals(0,effects(id));assertEquals("PROCESSING",status(queue,id));
    }
    @Test void existingFailedRecordCanRetryAndExistingDoneIsNotDowngraded() throws Exception {
        String id=id(),queue=queue();publish(queue,id);publish(queue,id);
        jdbc.update("INSERT INTO mq_consumer_inbox(consumer_name,event_id,event_type,status,failure_count,created_time) VALUES(?,?,'COUNTER','FAILED',1,now(3))",queue,id);
        try(Channel channel=channel()){listener.consumeInbox(queue,"COUNTER",receive(channel,queue),channel,null,()->effect(id));
            listener.consumeInbox(queue,"COUNTER",receive(channel,queue),channel,null,()->effect(id));}
        assertEquals(1,effects(id));assertEquals("DONE",status(queue,id));
    }
    @Test void exhaustedRetriesRollbackBusinessAndReachRealDlqViaAtomicOutbox() throws Exception {
        String id=id(),queue=queue();publish(queue,id);
        try(Channel channel=channel()) {
            for(int i=0;i<4;i++)listener.consumeInbox(queue,"COUNTER",receive(channel,queue),channel,null,()->{effect(id);throw new IllegalStateException("permanent business failure");});
            assertNull(channel.basicGet(queue,true));
            var letter=channel.basicGet(RabbitMQConfig.INBOX_FAILURE_QUEUE,true);assertNotNull(letter);
            String body=new String(letter.getBody(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains(id));assertTrue(body.contains("permanent business failure"));
        }
        assertEquals(0,effects(id));assertEquals("FAILED",status(queue,id));
        assertEquals(4,jdbc.queryForObject("SELECT failure_count FROM mq_consumer_inbox WHERE consumer_name=? AND event_id=?",Integer.class,queue,id));
        assertEquals(1,jdbc.queryForObject("SELECT dlq_enqueued FROM mq_consumer_inbox WHERE consumer_name=? AND event_id=?",Integer.class,queue,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM local_message_outbox WHERE idempotency_key LIKE 'inbox-dlq:%' AND status=2",Integer.class));
    }
    @Test void differentLogicalConsumersDoNotShareCompletion() {
        String id=id();MessageProperties props=new MessageProperties();props.setMessageId(id);var message=new Message(new byte[]{1},props);
        inbox.executeBatch("consumer-a","COUNTER",List.of(message),indices->effect(id));
        inbox.executeBatch("consumer-b","COUNTER",List.of(message),indices->effect(id));
        assertEquals(2,effects(id));
    }
}
