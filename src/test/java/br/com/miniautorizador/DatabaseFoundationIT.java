package br.com.miniautorizador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mysql.cj.jdbc.JdbcConnection;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseFoundationIT {

    private static final String CARD_NUMBER = "0000000000000001";
    private static final String ROUTE = "POST /transacoes";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:5.7.44")
            .withDatabaseName("miniautorizador_test")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.MySQLLegacyDialect");
    }

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM idempotencia");
        jdbcTemplate.update("DELETE FROM cartao");
    }

    @Test
    void iniciaContextoEConectaComMySql57() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT VERSION()")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).startsWith("5.7");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class)).isEqualTo(2);
    }

    @Test
    void prazoDeRedeERestauradoAoDevolverConexaoAoPool() throws SQLException {
        JdbcConnection fisica;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            fisica = connection.unwrap(JdbcConnection.class);
            br.com.miniautorizador.shared.adapter.persistence.PrazoEsperaMySql.aplicar(statement,
                    br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaIdempotente.iniciar());
            assertThat(connection.getNetworkTimeout()).isBetween(1, 5000);
            try (ResultSet resultado = statement.executeQuery()) {
                assertThat(resultado.next()).isTrue();
                assertThat(resultado.getInt(1)).isEqualTo(1);
            }
        }
        assertThat(fisica.getNetworkTimeout()).isZero();
    }

    @Test
    void migracoesValidamESaoReaplicaveisSemAlterarOEsquema() {
        flyway.validate();

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name IN ('cartao', 'idempotencia')",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void chavesPrimariasImpedemCartoesEReservasDuplicados() {
        insertCard("500.00");

        assertThatThrownBy(() -> insertCard("400.00"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        String key = UUID.randomUUID().toString();
        insertIdempotency(ROUTE, key);

        assertThatThrownBy(() -> insertIdempotency(ROUTE, key))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        insertIdempotency("POST /outra-rota", key);
    }

    @Test
    void bloqueioDeLinhaCoordenaDuasConexoesIndependentes() throws Exception {
        insertCard("10.00");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch lockAttempted = new CountDownLatch(1);
        AtomicLong secondConnectionId = new AtomicLong();

        try (Connection firstConnection = dataSource.getConnection()) {
            firstConnection.setAutoCommit(false);
            long firstConnectionId = connectionId(firstConnection);
            lockCard(firstConnection);
            try (PreparedStatement statement = firstConnection.prepareStatement(
                    "UPDATE cartao SET saldo = 0.00 WHERE numero_cartao = ?")) {
                statement.setString(1, CARD_NUMBER);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }

            Future<BigDecimal> secondRead = executor.submit(() -> {
                try (Connection secondConnection = dataSource.getConnection()) {
                    secondConnection.setAutoCommit(false);
                    secondConnectionId.set(connectionId(secondConnection));
                    lockAttempted.countDown();
                    BigDecimal balance = lockCard(secondConnection);
                    secondConnection.commit();
                    return balance;
                }
            });

            assertThat(lockAttempted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> secondRead.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            firstConnection.commit();

            assertThat(secondRead.get(5, TimeUnit.SECONDS)).isEqualByComparingTo("0.00");
            assertThat(secondConnectionId.get()).isNotEqualTo(firstConnectionId);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rollbackReverteSaldoEResultadoTecnicoNaMesmaTransacao() {
        insertCard("10.00");
        String key = UUID.randomUUID().toString();
        insertIdempotency(ROUTE, key);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE cartao SET saldo = saldo - 10.00 WHERE numero_cartao = ?", CARD_NUMBER);
            jdbcTemplate.update("UPDATE idempotencia SET estado = 'CONCLUIDA', status_http = 201, "
                    + "corpo_resposta = 'OK', tipo_midia = 'text/plain', "
                    + "concluida_em = UTC_TIMESTAMP(6), expira_em = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 24 HOUR) "
                    + "WHERE rota = ? AND chave = ?", ROUTE, key);
            throw new IllegalStateException("Falha técnica simulada antes do commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT saldo FROM cartao WHERE numero_cartao = ?", BigDecimal.class, CARD_NUMBER))
                .isEqualByComparingTo("10.00");
        assertThat(jdbcTemplate.queryForMap("SELECT estado, status_http FROM idempotencia "
                + "WHERE rota = ? AND chave = ?", ROUTE, key))
                .containsEntry("estado", "PENDENTE")
                .containsEntry("status_http", null);
    }

    private void insertCard(String balance) {
        jdbcTemplate.update("INSERT INTO cartao (numero_cartao, senha_hash, saldo) VALUES (?, ?, ?)",
                CARD_NUMBER, "hash-de-teste", new BigDecimal(balance));
    }

    private void insertIdempotency(String route, String key) {
        jdbcTemplate.update("INSERT INTO idempotencia "
                + "(rota, chave, geracao, impressao_digital, estado) VALUES (?, ?, 1, ?, 'PENDENTE')",
                route, key, new byte[32]);
    }

    private long connectionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(
                "SELECT CONNECTION_ID()")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private BigDecimal lockCard(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT saldo FROM cartao WHERE numero_cartao = ? FOR UPDATE")) {
            statement.setString(1, CARD_NUMBER);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getBigDecimal(1);
            }
        }
    }
}
