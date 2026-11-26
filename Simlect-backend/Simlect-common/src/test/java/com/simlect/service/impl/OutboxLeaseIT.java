package com.simlect.service.impl;

import com.simlect.component.MqPublisherConfirmHelper;
import com.simlect.constants.ReliableMessageSender;
import com.simlect.entity.po.LocalMessageOutbox;
import com.simlect.mappers.LocalMessageOutboxMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Opt-in. Creates isolated disposable containers; never reads application database/broker settings. */
@Testcontainers
@EnabledIfEnvironmentVariable(named="SIMLECT_OUTBOX_IT", matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxLeaseIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.3"))
            .withDatabaseName("outbox_test").withUsername("outbox_test").withPassword("outbox_test_only");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));
    static JdbcTemplate jdbc;
    static LocalMessageOutboxMapper mapper;
    static CachingConnectionFactory connection;
    static RabbitTemplate rabbit;
    static ReliableMessageSender sender;
    static Path sqlRoot;

    @BeforeAll static void initialize() throws Exception {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Path location = Path.of("").toAbsolutePath();
        while (location != null && !Files.isRegularFile(location.resolve("sql/26_outbox_lease.sql"))) location = location.getParent();
        assertNotNull(location); sqlRoot = location.resolve("sql");
        String original = Files.readString(sqlRoot.resolve("12_order_outbox.sql"));
        int start = original.indexOf("create table if not exists local_message_outbox");
        jdbc.execute(original.substring(start, original.indexOf(';', start)));
        migrate(); migrate();
        var configuration = new org.apache.ibatis.session.Configuration(new Environment("outbox-test", new SpringManagedTransactionFactory(), dataSource));
        String resource = "com/simlect/mappers/LocalMessageOutboxMapper.xml";
        try (var reader = Resources.getResourceAsReader(resource)) {
            new XMLMapperBuilder(reader, configuration, resource, configuration.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(LocalMessageOutboxMapper.class);
        connection = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connection.setUsername(RABBIT.getAdminUsername()); connection.setPassword(RABBIT.getAdminPassword());
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true); connection.setConnectionTimeout(2000);
        rabbit = new RabbitTemplate(connection); rabbit.setMessageConverter(new Jackson2JsonMessageConverter());
        var admin = new RabbitAdmin(connection);
        admin.declareExchange(new DirectExchange("outbox-test-exchange", true, false));
        var confirm = new MqPublisherConfirmHelper();
        ReflectionTestUtils.setField(confirm, "rabbitTemplate", rabbit);
        ReflectionTestUtils.setField(confirm, "defaultConfirmTimeoutMs", 2000L);
        confirm.attachCallbacks();
        sender = new ReliableMessageSender(); ReflectionTestUtils.setField(sender, "mqPublisherConfirmHelper", confirm);
    }
    static void migrate() throws Exception {
        String migration = Files.readString(sqlRoot.resolve("26_outbox_lease.sql"));
        jdbc.execute("DROP PROCEDURE IF EXISTS migrate_t02_outbox_lease");
        jdbc.execute(migration.substring(migration.indexOf("CREATE PROCEDURE"), migration.indexOf("END$$") + 3));
        jdbc.execute("CALL migrate_t02_outbox_lease()");
        jdbc.execute("DROP PROCEDURE migrate_t02_outbox_lease");
    }
    @AfterAll static void closeConnection() { if (connection != null) connection.destroy(); }
    LocalMessageOutbox row() {
        var row = new LocalMessageOutbox(); row.setIdempotencyKey(UUID.randomUUID().toString());
        row.setExchangeName("outbox-test-exchange"); row.setRoutingKey(row.getIdempotencyKey());
        row.setPayloadJson("{\"orderId\":\"isolated-test\"}"); row.setReliabilityLevel("STANDARD");
        row.setStatus(0); row.setRetryCount(0); row.setCreateTime(new Date(System.currentTimeMillis()-10000)); row.setUpdateTime(row.getCreateTime());
        mapper.insert(row); return row;
    }
    OutboxMessageServiceImpl service(LocalMessageOutboxMapper source) {
        var service = new OutboxMessageServiceImpl(); ReflectionTestUtils.setField(service,"localMessageOutboxMapper",source);
        ReflectionTestUtils.setField(service,"reliableMessageSender",sender); return service;
    }
    String queue(LocalMessageOutbox row) {
        String queue = "outbox-test-" + row.getId();
        var admin = new RabbitAdmin(connection); admin.declareQueue(new org.springframework.amqp.core.Queue(queue, true));
        admin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, row.getExchangeName(), row.getRoutingKey(), null));
        return queue;
    }
    void expire(Long id) {
        jdbc.update("UPDATE local_message_outbox SET lease_until=timestampadd(SECOND,-1,now(3)),next_attempt_time=timestampadd(SECOND,-1,now(3)) WHERE id=?", id);
    }
    @Test @Order(1) void migrationAndLegacySendingRecovery() {
        var row = row();
        jdbc.update("UPDATE local_message_outbox SET status=1,update_time=timestampadd(SECOND,-120,now()) WHERE id=?",row.getId());
        assertEquals(1, mapper.claimLease(row.getId(),"new-owner",60,10,2,300));
        assertEquals(1, mapper.selectById(row.getId()).getRetryCount());
    }
    @Test @Order(2) void actualMysqlAtomicClaimAndFencedCompletion() throws Exception {
        var row = row(); var pool = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Integer>>();
        try {
            for (int i=0;i<4;i++) { String owner="instance-"+i; results.add(pool.submit(()->{start.await();return mapper.claimLease(row.getId(),owner,60,10,2,300);})); }
            start.countDown(); int wins=0; for(var result:results) wins+=result.get(10,TimeUnit.SECONDS); assertEquals(1,wins);
        } finally {pool.shutdownNow();}
        String previous=mapper.selectById(row.getId()).getLeaseOwner(); expire(row.getId());
        assertEquals(1,mapper.claimLease(row.getId(),"recovered-owner",60,10,2,300));
        assertEquals(0,mapper.completeLease(row.getId(),previous,true,null,2,300));
        assertEquals(0,mapper.completeLease(row.getId(),previous,false,"late",2,300));
        assertEquals(1,mapper.completeLease(row.getId(),"recovered-owner",true,null,2,300));
    }
    @Test @Order(3) void actualConfirmThenInterruptedSentWriteRecoversWithStableMessageId() throws Exception {
        var row=row(); String queue=queue(row);
        var interrupted=spy(mapper);
        doThrow(new OutboxMessageServiceImplTest.ProcessExit()).when(interrupted)
                .completeLease(eq(row.getId()),anyString(),eq(true),isNull(),anyInt(),anyInt());
        assertThrows(OutboxMessageServiceImplTest.ProcessExit.class,()->service(interrupted).tryDispatch(row.getId()));
        assertEquals(1,mapper.selectById(row.getId()).getStatus());
        expire(row.getId()); service(mapper).tryDispatch(row.getId());
        assertEquals(2,mapper.selectById(row.getId()).getStatus());
        Message one=rabbit.receive(queue,3000); Message two=rabbit.receive(queue,3000);
        assertNotNull(one);assertNotNull(two);
        assertEquals(row.getIdempotencyKey(),one.getMessageProperties().getMessageId());
        assertEquals(one.getMessageProperties().getMessageId(),two.getMessageProperties().getMessageId());
        String firstAttempt = one.getMessageProperties().getHeader(MqPublisherConfirmHelper.HEADER_PUBLISH_ATTEMPT);
        String secondAttempt = two.getMessageProperties().getHeader(MqPublisherConfirmHelper.HEADER_PUBLISH_ATTEMPT);
        assertNotEquals(firstAttempt, secondAttempt);
    }
    @Test @Order(4) void unroutableMessageIsFailedAndBackedOff() {
        var row=row(); service(mapper).tryDispatch(row.getId());
        var failed=mapper.selectById(row.getId()); assertEquals(3,failed.getStatus()); assertNotNull(failed.getNextAttemptTime());
        service(mapper).tryDispatch(row.getId()); assertEquals(1,mapper.selectById(row.getId()).getRetryCount());
    }
    @Test @Order(5) void finalCrashedAttemptIsVisibleAndManualScriptCannotTouchLiveLease() throws Exception {
        var row=row(); assertEquals(1,mapper.claimLease(row.getId(),"final-owner",60,1,2,300));
        assertEquals(0,mapper.expireExhausted(30,1,60)); expire(row.getId());
        assertEquals(1,mapper.expireExhausted(30,1,60)); assertEquals(3,mapper.selectById(row.getId()).getStatus());
        String script=Files.readString(sqlRoot.resolve("27_outbox_manual_retry.sql"));
        int start=script.indexOf("UPDATE local_message_outbox"); String update=script.substring(start,script.indexOf(';',start));
        assertEquals(1, manualRetry(row.getId(), update)); assertEquals(0,mapper.selectById(row.getId()).getStatus());
        assertEquals(1,mapper.claimLease(row.getId(),"live-owner",60,1,2,300));
        assertEquals(0,manualRetry(row.getId(), update)); assertEquals("live-owner",mapper.selectById(row.getId()).getLeaseOwner());
    }
    int manualRetry(Long id, String update) {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) sqlConnection -> {
            try (var statement = sqlConnection.createStatement()) {
                statement.execute("SET @outbox_review_id=" + id);
                statement.execute("SET @outbox_max_attempts=1");
                return statement.executeUpdate(update);
            }
        });
    }
    @Test @Order(99) void stoppedIsolatedBrokerLeavesRetryableFailure() {
        var row=row(); RABBIT.stop();
        service(mapper).tryDispatch(row.getId());
        assertEquals(3,mapper.selectById(row.getId()).getStatus()); assertNotNull(mapper.selectById(row.getId()).getErrorMessage());
    }
}
