package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.services.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mailqueue", "spring.jpa.show-sql=false",
    "security.abuse.enabled=false", "account.mail.queue.poll-initial-delay-ms=3600000"})
@Import(RefreshSessionIntegrationTests.TimeConfig.class)
class AccountMailQueueIntegrationTests {
    @Autowired AccountMailQueue queue;
    @MockitoSpyBean AccountMailWorker worker;
    @MockitoSpyBean AccountMailGateway gateway;
    @Autowired UserService users;
    @Autowired UserRepository repository;
    @Autowired com.raphael.beachcleanup.repositories.TrashCollectionRepository collections;
    @Autowired com.raphael.beachcleanup.repositories.CleanupEventRepository events;
    @Autowired AccountActionService actions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RefreshSessionIntegrationTests.MutableClock clock;
    @Autowired ThreadPoolTaskExecutor accountMailExecutor;

    @BeforeEach void setup() {
        doNothing().when(worker).wakeAfterCommit(any());
        jdbc.update("delete from tb_account_mail_queue");
        collections.deleteAll(); repository.deleteAll(); events.deleteAll(); gateway.clearSimulated();
        clock.value.set(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }
    private User signup() { return users.register(new User(null, "Fila", "queue@example.com", "Uma praia mais limpa!", 0.0)); }
    private String status() { return jdbc.queryForObject("select status from tb_account_mail_queue", String.class); }
    private int attempts() { return jdbc.queryForObject("select attempts from tb_account_mail_queue", Integer.class); }

    @Test void signupAndMailAreAtomicAndRollbackNeverSends() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { signup(); tx.setRollbackOnly(); });
        assertFalse(repository.existsByEmail("queue@example.com"));
        assertEquals(0, jdbc.queryForObject("select count(*) from tb_account_mail_queue", Integer.class));
        worker.drain(); assertTrue(gateway.simulatedMessages().isEmpty());
        signup(); assertEquals("READY", status()); assertEquals(0, attempts());
        worker.drain(); assertEquals("SENT", status()); assertEquals(1, attempts());
        assertEquals(1, gateway.simulatedMessages().size());
        assertNull(jdbc.queryForObject("select claim_token from tb_account_mail_queue", String.class));
        var columns = jdbc.queryForList("select * from tb_account_mail_queue").getFirst();
        assertFalse(columns.toString().contains("#verify="));
        assertFalse(columns.toString().contains("Uma praia"));
    }

    @Test void transientFailuresBackOffAndEventuallySendWithoutPersistingExceptionDetails() {
        doThrow(new org.springframework.mail.MailSendException("secret SMTP response queue@example.com"))
            .doThrow(new org.springframework.mail.MailSendException("secret"))
            .doCallRealMethod().when(gateway).send(any());
        signup(); worker.drain(); assertEquals("READY", status()); assertEquals(1, attempts());
        assertEquals("DELIVERY_FAILED", jdbc.queryForObject("select last_error from tb_account_mail_queue", String.class));
        clock.advance(29); worker.drain(); assertEquals(1, attempts());
        clock.advance(1); worker.drain(); assertEquals(2, attempts());
        clock.advance(59); worker.drain(); assertEquals(2, attempts());
        clock.advance(1); worker.drain(); assertEquals("SENT", status()); assertEquals(3, attempts());
        assertEquals(1, gateway.simulatedMessages().size());
    }

    @Test void permanentFailureStopsAtConfiguredLimitAndIsRetainedForInspection() {
        doThrow(new org.springframework.mail.MailSendException("unavailable")).when(gateway).send(any());
        signup();
        for (int i = 0; i < 6; i++) { worker.drain(); clock.advance(600); }
        assertEquals("DEAD", status()); assertEquals(6, attempts());
        worker.drain(); assertEquals(6, attempts()); assertTrue(gateway.simulatedMessages().isEmpty());
        clock.advance(7 * 86400); queue.prune();
        assertEquals(0, jdbc.queryForObject("select count(*) from tb_account_mail_queue", Integer.class));
    }

    @Test void concurrentClaimsAndExpiredLeasesCannotBeAcknowledgedByAnOldWorker() throws Exception {
        signup();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<AccountMailQueue.Claim> call = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return queue.claim(); };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            var first = a.get(10, TimeUnit.SECONDS); var second = b.get(10, TimeUnit.SECONDS);
            assertTrue((first == null) != (second == null));
            var old = first == null ? second : first;
            clock.advance(120); var recovered = queue.claim(); assertNotNull(recovered);
            assertEquals(old.id(), recovered.id()); assertEquals(2, recovered.attempts());
            assertFalse(queue.complete(old, true)); assertTrue(queue.complete(recovered, true));
        }
    }

    @Test void processDeathDuringFinalAttemptBecomesDeadAfterLeaseExpiration() {
        signup();
        for (int i = 0; i < 6; i++) { assertNotNull(queue.claim()); clock.advance(120); }
        assertNull(queue.claim()); assertEquals("DEAD", status()); assertEquals(6, attempts());
    }

    @Test void expiredAndSupersededRequestsNeverGenerateFreshLinks() {
        var owner = signup(); clock.advance(86400); worker.drain();
        assertEquals("CANCELLED", status()); assertTrue(gateway.simulatedMessages().isEmpty());
        jdbc.update("delete from tb_account_mail_queue");
        actions.request(owner.getEmail(), AccountActionService.VERIFY);
        owner = repository.findById(owner.getId()).orElseThrow(); owner.setCredentialVersion(1L); owner = repository.saveAndFlush(owner);
        worker.drain(); assertEquals("CANCELLED", status()); assertTrue(gateway.simulatedMessages().isEmpty());
        jdbc.update("delete from tb_account_mail_queue");
        actions.request(owner.getEmail(), AccountActionService.RESET);
        owner.setEmailVerified(true); repository.saveAndFlush(owner);
        clock.advance(900); worker.drain(); assertEquals("CANCELLED", status());
    }

    @Test void unknownAddressCannotSendToAnAccountCreatedAfterTheRequestAndDeletionIsSafe() {
        actions.request("queue@example.com", AccountActionService.VERIFY);
        var owner = users.insert(new User(null, "Fila", "queue@example.com", "Uma praia mais limpa!", 0.0));
        worker.drain(); assertEquals("CANCELLED", status()); assertTrue(gateway.simulatedMessages().isEmpty());
        jdbc.update("delete from tb_account_mail_queue");
        actions.request(owner.getEmail(), AccountActionService.VERIFY); repository.deleteById(owner.getId());
        worker.drain(); assertEquals("CANCELLED", status());
    }

    @Test void saturatedExecutorDoesNotLoseACommittedSignupAndPollingResumesDelivery() throws Exception {
        var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        accountMailExecutor.execute(() -> {
            started.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        accountMailExecutor.execute(() -> {}); // Fill the one-slot wake-up queue.
        try {
            doCallRealMethod().when(worker).wakeAfterCommit(any());
            assertDoesNotThrow(this::signup); assertEquals("READY", status());
        } finally { release.countDown(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (accountMailExecutor.getActiveCount() != 0 || accountMailExecutor.getQueueSize() != 0) {
            assertTrue(System.nanoTime() < deadline); Thread.sleep(10);
        }
        worker.wake();
        while (!"SENT".equals(status())) { assertTrue(System.nanoTime() < deadline); Thread.sleep(10); }
        assertEquals(1, gateway.simulatedMessages().size());
    }

    @Test void realSmtpConnectionRefusesMissingStartTlsAndQueuesARetryWithoutSendingTheToken() throws Exception {
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                var pool = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var smtp = pool.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                    var output = new java.io.PrintWriter(socket.getOutputStream(), true, java.nio.charset.StandardCharsets.US_ASCII);
                    output.print("220 localhost test SMTP\r\n"); output.flush();
                    String command;
                    while ((command = input.readLine()) != null) {
                        commands.add(command);
                        output.print(command.startsWith("EHLO") ? "250 localhost\r\n" : "221 bye\r\n"); output.flush();
                        if (!command.startsWith("EHLO")) break;
                    }
                }
                return true;
            });
            var sender = new org.springframework.mail.javamail.JavaMailSenderImpl();
            sender.setHost(server.getInetAddress().getHostAddress()); sender.setPort(server.getLocalPort());
            var properties = sender.getJavaMailProperties();
            properties.setProperty("mail.smtp.starttls.enable", "true");
            properties.setProperty("mail.smtp.starttls.required", "true");
            properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            properties.setProperty("mail.smtp.connectiontimeout", "3000");
            properties.setProperty("mail.smtp.timeout", "3000");
            properties.setProperty("mail.smtp.writetimeout", "3000");
            var env = new org.springframework.mock.env.MockEnvironment(); env.setActiveProfiles("test");
            env.withProperty("account.mail.mode", "smtp").withProperty("account.mail.from", "sender@example.com")
                .withProperty("spring.mail.host", "localhost")
                .withProperty("spring.mail.properties.mail.smtp.starttls.required", "true")
                .withProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", "true");
            var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            beans.registerSingleton("sender", sender);
            var transport = new AccountMailGateway(new com.raphael.beachcleanup.config.AccountPolicy(env),
                beans.getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class));
            doAnswer(call -> { transport.send(call.getArgument(0)); return null; }).when(gateway).send(any());
            signup(); worker.drain();
            assertTrue(smtp.get(10, TimeUnit.SECONDS));
            assertEquals("READY", status()); assertEquals(1, attempts());
            assertTrue(commands.stream().anyMatch(command -> command.startsWith("EHLO")));
            assertTrue(commands.stream().noneMatch(command -> command.startsWith("MAIL") || command.equals("DATA") || command.contains("#verify=")));
        }
    }
}
