package br.com.miniautorizador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.miniautorizador.cartao.application.port.in.ConsultarSaldoUseCase;
import br.com.miniautorizador.cartao.application.port.out.CartaoPort;
import br.com.miniautorizador.cartao.application.port.out.SenhaCartaoPort;
import br.com.miniautorizador.transacao.application.port.in.AutorizarTransacaoUseCase;
import br.com.miniautorizador.idempotencia.application.AutorizarTransacaoIdempotenteUseCase;
import br.com.miniautorizador.idempotencia.application.IdempotenciaPort;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaIdempotente;
import br.com.miniautorizador.idempotencia.application.ResultadoOperacaoIdempotente;
import br.com.miniautorizador.transacao.domain.MotivoRecusa;
import br.com.miniautorizador.transacao.domain.ResultadoAutorizacao;
import jakarta.servlet.Filter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.json.JsonMapper;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class MiniAutorizadorHttpIT {

    private static final String CARTAO = "0000000000000001";
    private static final String SENHA = "0042";
    private static final String CREDENCIAL = "Basic " + Base64.getEncoder()
            .encodeToString("username:password".getBytes(StandardCharsets.UTF_8));

@SuppressWarnings("resource")
@Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:5.7.44")
            .withDatabaseName("miniautorizador_http_test")
            .withUsername("test")
            .withPassword("test");

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SenhaCartaoPort senhaCartaoPort;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoSpyBean
    private CartaoPort cartaoPort;

    @MockitoSpyBean
    private AutorizarTransacaoUseCase autorizarTransacaoUseCase;

    @MockitoSpyBean
    private IdempotenciaPort idempotenciaPort;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.MySQLLegacyDialect");
    }

    @BeforeEach
    void limparBanco() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
        jdbcTemplate.update("DELETE FROM idempotencia");
        jdbcTemplate.update("DELETE FROM cartao");
    }

    @Test
    void criaConsultaEPreservaCartaoEmNovoContexto() throws Exception {
        mockMvc.perform(autenticada(post("/cartoes")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + CARTAO + "\",\"senha\":\"" + SENHA + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.numeroCartao").value(CARTAO))
                .andExpect(jsonPath("$.senha").value(SENHA));

        mockMvc.perform(autenticada(get("/cartoes/{numeroCartao}", CARTAO)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("500.00"));
        mockMvc.perform(autenticada(get("/cartoes/{numeroCartao}", "0000000000000002")))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        String senhaHash = jdbcTemplate.queryForObject(
                "SELECT senha_hash FROM cartao WHERE numero_cartao = ?", String.class, CARTAO);
        assertThat(senhaCartaoPort.corresponde(SENHA, senhaHash)).isTrue();
        assertThat(senhaHash).doesNotContain(SENHA);

        try (ConfigurableApplicationContext contexto = novoContexto()) {
            ConsultarSaldoUseCase consulta = contexto.getBean(ConsultarSaldoUseCase.class);
            assertThat(consulta.executar(CARTAO)).contains(new BigDecimal("500.00"));
        }
    }

    @Test
    void duplicidadeRetornaSenhaRecebidaESemSubstituirCredencialExistente() throws Exception {
        criarCartao(CARTAO, SENHA);

        mockMvc.perform(autenticada(post("/cartoes")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + CARTAO + "\",\"senha\":\"9876\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.numeroCartao").value(CARTAO))
                .andExpect(jsonPath("$.senha").value("9876"));

        String senhaHash = jdbcTemplate.queryForObject(
                "SELECT senha_hash FROM cartao WHERE numero_cartao = ?", String.class, CARTAO);
        assertThat(senhaCartaoPort.corresponde(SENHA, senhaHash)).isTrue();
        assertThat(senhaCartaoPort.corresponde("9876", senhaHash)).isFalse();
        assertThat(saldoAtual()).isEqualByComparingTo("500.00");
    }

    @Test
    void concorrenciaDeCriacaoProduzUmaInclusaoEUmaDuplicidade() throws Exception {
        CountDownLatch prontas = new CountDownLatch(2);
        CountDownLatch iniciar = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(() -> criarConcorrente("1234", prontas, iniciar));
            var segunda = executor.submit(() -> criarConcorrente("5678", prontas, iniciar));
            assertThat(prontas.await(5, TimeUnit.SECONDS)).isTrue();
            iniciar.countDown();

            assertThat(List.of(primeira.get(10, TimeUnit.SECONDS), segunda.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 422);
            String senhaHash = jdbcTemplate.queryForObject(
                    "SELECT senha_hash FROM cartao WHERE numero_cartao = ?", String.class, CARTAO);
            assertThat(List.of("1234", "5678").stream()
                    .map(senha -> senhaCartaoPort.corresponde(senha, senhaHash))
                    .filter(Boolean::booleanValue)
                    .count()).isEqualTo(1);
        }
    }

    @Test
    void autorizaDebitosExatosEAplicaOrdemDasRecusasSemAlterarSaldo() throws Exception {
        criarCartao(CARTAO, SENHA);
        jdbcTemplate.update("UPDATE cartao SET saldo = 10.00 WHERE numero_cartao = ?", CARTAO);

        transacao(CARTAO, SENHA, "6.25").andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("OK"));
        transacao(CARTAO, SENHA, "3.75").andExpect(status().isCreated()).andExpect(content().string("OK"));
        transacao(CARTAO, SENHA, "0.01").andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(MotivoRecusa.SALDO_INSUFICIENTE.name()));
        transacao(CARTAO, "9999", "11.00").andExpect(status().isUnprocessableContent())
                .andExpect(content().string(MotivoRecusa.SENHA_INVALIDA.name()));
        transacao(CARTAO, SENHA, "999999999999999999999999999999999.99")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(MotivoRecusa.SALDO_INSUFICIENTE.name()));
        transacao("0000000000000002", SENHA, "11.00").andExpect(status().isUnprocessableContent())
                .andExpect(content().string(MotivoRecusa.CARTAO_INEXISTENTE.name()));

        assertThat(saldoAtual()).isEqualByComparingTo("0.00");
    }

    @Test
    void aceitaValoresJsonInteirosEComDuasCasas() throws Exception {
        criarCartao(CARTAO, SENHA);
        jdbcTemplate.update("UPDATE cartao SET saldo = 20.00 WHERE numero_cartao = ?", CARTAO);

        transacao(CARTAO, SENHA, "10").andExpect(status().isCreated()).andExpect(content().string("OK"));
        transacao(CARTAO, SENHA, "10.00").andExpect(status().isCreated()).andExpect(content().string("OK"));

        assertThat(saldoAtual()).isEqualByComparingTo("0.00");
    }

    @Test
    void chaveIdempotenteReproduzAprovacaoEEquivalenciaNumericaSemNovoDebito() throws Exception {
        criarCartao(CARTAO, SENHA);
        jdbcTemplate.update("UPDATE cartao SET saldo = 20.00 WHERE numero_cartao = ?", CARTAO);
        String chave = java.util.UUID.randomUUID().toString();

        transacaoComChave(CARTAO, SENHA, "10", chave).andExpect(status().isCreated())
                .andExpect(content().string("OK"));
        transacaoComChave(CARTAO, SENHA, "10.00", chave).andExpect(status().isCreated())
                .andExpect(content().string("OK"));

        assertThat(saldoAtual()).isEqualByComparingTo("10.00");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotencia WHERE chave = ?", Integer.class,
                chave)).isEqualTo(1);
    }

    @Test
    void chaveReutilizadaComConteudoDivergenteNaoDebitaNovamente() throws Exception {
        criarCartao(CARTAO, SENHA);
        jdbcTemplate.update("UPDATE cartao SET saldo = 20.00 WHERE numero_cartao = ?", CARTAO);
        String chave = java.util.UUID.randomUUID().toString();
        transacaoComChave(CARTAO, SENHA, "10.00", chave).andExpect(status().isCreated());

        transacaoComChave(CARTAO, SENHA, "5.00", chave).andExpect(status().isConflict())
                .andExpect(content().string("CHAVE_IDEMPOTENCIA_REUTILIZADA"));

        assertThat(saldoAtual()).isEqualByComparingTo("10.00");
    }

    @Test
    void chaveVaziaOuUuidInvalidoNaoCriaReservaNemDebita() throws Exception {
        criarCartao(CARTAO, SENHA);
        for (String chave : List.of("", "uuid-invalido")) {
            transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("REQUISICAO_INVALIDA"));
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotencia", Integer.class)).isZero();
        assertThat(saldoAtual()).isEqualByComparingTo("500.00");
    }

    @Test
    void resultadoDeRecusaEReproduzidoMesmoDepoisDeCriarOCartao() throws Exception {
        String chave = java.util.UUID.randomUUID().toString();
        transacaoComChave(CARTAO, SENHA, "10.00", chave).andExpect(status().isUnprocessableContent())
                .andExpect(content().string("CARTAO_INEXISTENTE"));
        criarCartao(CARTAO, SENHA);

        transacaoComChave(CARTAO, SENHA, "10.00", chave).andExpect(status().isUnprocessableContent())
                .andExpect(content().string("CARTAO_INEXISTENTE"));

        assertThat(saldoAtual()).isEqualByComparingTo("500.00");
    }

    @Test
    void novaReservaAposExpiracaoAceitaNovoConteudo() throws Exception {
        criarCartao(CARTAO, SENHA);
        jdbcTemplate.update("UPDATE cartao SET saldo = 20.00 WHERE numero_cartao = ?", CARTAO);
        String chave = java.util.UUID.randomUUID().toString();
        transacaoComChave(CARTAO, SENHA, "10.00", chave).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE idempotencia SET expira_em = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE rota = 'POST /transacoes' AND chave = ?", chave);

        transacaoComChave(CARTAO, SENHA, "5.00", chave).andExpect(status().isCreated());

        assertThat(saldoAtual()).isEqualByComparingTo("5.00");
        assertThat(jdbcTemplate.queryForObject("SELECT geracao FROM idempotencia WHERE chave = ?", Long.class,
                chave)).isEqualTo(2L);
    }

    @Test
    void resultadoConcluidoFicaRetidoPor24Horas() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();

        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("SELECT TIMESTAMPDIFF(SECOND, concluida_em, expira_em) "
                + "FROM idempotencia WHERE chave = ?", Integer.class, chave)).isEqualTo(86400);
    }

    @Test
    void conteudoDivergenteDuranteNovaReservaAposExpiracaoRetornaConflitoImediato() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        transacaoComChave(CARTAO, SENHA, "2.00", chave).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE idempotencia SET expira_em = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE rota = 'POST /transacoes' AND chave = ?", chave);
        CountDownLatch autorizacaoIniciada = new CountDownLatch(1);
        CountDownLatch liberarAutorizacao = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocacao -> {
            autorizacaoIniciada.countDown();
            liberarAutorizacao.await(8, TimeUnit.SECONDS);
            return invocacao.callRealMethod();
        }).when(autorizarTransacaoUseCase).executar(org.mockito.ArgumentMatchers.eq(CARTAO),
                org.mockito.ArgumentMatchers.eq(SENHA), org.mockito.ArgumentMatchers.eq(new BigDecimal("1.00")),
                org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var novaReserva = executor.submit(() -> transacaoComChave(CARTAO, SENHA, "1.00", chave)
                    .andReturn().getResponse().getStatus());
            assertThat(autorizacaoIniciada.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbcTemplate.queryForMap("SELECT estado, geracao FROM idempotencia WHERE chave = ?", chave))
                    .containsEntry("estado", "PENDENTE");
            assertThat(jdbcTemplate.queryForObject("SELECT geracao FROM idempotencia WHERE chave = ?", Long.class,
                    chave)).isEqualTo(2L);

            long inicio = System.nanoTime();
            transacaoComChave(CARTAO, SENHA, "3.00", chave).andExpect(status().isConflict())
                    .andExpect(content().string("CHAVE_IDEMPOTENCIA_REUTILIZADA"));
            long duracaoMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
            assertThat(duracaoMillis).isLessThan(1000L);

            liberarAutorizacao.countDown();
            assertThat(novaReserva.get(5, TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertThat(saldoAtual()).isEqualByComparingTo("497.00");
        assertThat(jdbcTemplate.queryForObject("SELECT geracao FROM idempotencia WHERE chave = ?", Long.class,
                chave)).isEqualTo(2L);
    }

    @Test
    void revalidacaoSobBloqueioUsaGeracaoVigenteEReabreResultadoExpirado() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE idempotencia SET expira_em = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE chave = ?", chave);
        transacaoComChave(CARTAO, SENHA, "2.00", chave).andExpect(status().isCreated());
        byte[] digital = jdbcTemplate.queryForObject(
                "SELECT impressao_digital FROM idempotencia WHERE chave = ?", byte[].class, chave);

        ResultadoOperacaoIdempotente resultadoAtual = idempotenciaPort.executar(chave, 1L, digital,
                PrazoConcorrenciaIdempotente.iniciar(), () -> {
                    throw new AssertionError("A geração vigente já está concluída e não deve ser reexecutada.");
                }).orElseThrow();
        assertThat(resultadoAtual.status()).isEqualTo(201);
        assertThat(saldoAtual()).isEqualByComparingTo("497.00");

        jdbcTemplate.update("UPDATE idempotencia SET expira_em = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE chave = ?", chave);
        assertThat(idempotenciaPort.executar(chave, 2L, digital,
                PrazoConcorrenciaIdempotente.iniciar(),
                () -> autorizarTransacaoUseCase.executar(CARTAO, SENHA, new BigDecimal("2.00")))).isEmpty();
        IdempotenciaPort.Reserva reservaAtualizada = idempotenciaPort.reservar(chave, digital,
                PrazoConcorrenciaIdempotente.iniciar());
        ResultadoOperacaoIdempotente novaGeracao = idempotenciaPort.executar(chave, reservaAtualizada.geracao(), digital,
                PrazoConcorrenciaIdempotente.iniciar(),
                () -> autorizarTransacaoUseCase.executar(CARTAO, SENHA, new BigDecimal("2.00"))).orElseThrow();

        assertThat(novaGeracao.status()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject("SELECT geracao FROM idempotencia WHERE chave = ?", Long.class,
                chave)).isEqualTo(3L);
        assertThat(saldoAtual()).isEqualByComparingTo("495.00");
    }

    @Test
    void mesmaChaveConcorrenteExecutaDebitoUmaUnicaVez() throws Exception {
        criarCartao(CARTAO, SENHA);
        jdbcTemplate.update("UPDATE cartao SET saldo = 10.00 WHERE numero_cartao = ?", CARTAO);
        String chave = java.util.UUID.randomUUID().toString();
        CountDownLatch prontas = new CountDownLatch(2);
        CountDownLatch iniciar = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(() -> transacaoConcorrente(chave, prontas, iniciar));
            var segunda = executor.submit(() -> transacaoConcorrente(chave, prontas, iniciar));
            assertThat(prontas.await(5, TimeUnit.SECONDS)).isTrue();
            iniciar.countDown();
            assertThat(List.of(primeira.get(10, TimeUnit.SECONDS), segunda.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 201);
        }
        assertThat(saldoAtual()).isEqualByComparingTo("0.00");
    }

    @Test
    void mesmaOperacaoIdempotenteConcorrenteEmContextosIndependentesDebitaUmaVez() throws Exception {
        criarCartao(CARTAO, SENHA);
        jdbcTemplate.update("UPDATE cartao SET saldo = 10.00 WHERE numero_cartao = ?", CARTAO);
        String chave = java.util.UUID.randomUUID().toString();
        try (ConfigurableApplicationContext primeiro = novoContexto();
                ConfigurableApplicationContext segundo = novoContexto();
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            long primeiraConexao = connectionId(primeiro.getBean(DataSource.class));
            long segundaConexao = connectionId(segundo.getBean(DataSource.class));
            CountDownLatch prontas = new CountDownLatch(2);
            CountDownLatch iniciar = new CountDownLatch(1);
            var primeira = executor.submit(() -> autorizarIdempotenteConcorrente(primeiro, chave, prontas, iniciar));
            var segunda = executor.submit(() -> autorizarIdempotenteConcorrente(segundo, chave, prontas, iniciar));
            assertThat(prontas.await(5, TimeUnit.SECONDS)).isTrue();
            iniciar.countDown();

            assertThat(List.of(primeira.get(10, TimeUnit.SECONDS), segunda.get(10, TimeUnit.SECONDS)))
                    .containsExactly(201, 201);
            assertThat(primeiraConexao).isNotEqualTo(segundaConexao);
            assertThat(saldoAtual()).isEqualByComparingTo("0.00");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotencia WHERE chave = ?",
                    Integer.class, chave)).isEqualTo(1);
        }
    }

    @Test
    void prazoUnicoIncluiBloqueioDaReservaESegueComTempoRestanteNoBloqueioDoCartao() throws Exception {
        validarBloqueiosSucessivos(Optional.empty(), false);
    }

    @Test
    void concluiComDebitoUnicoAposEsperaNaReservaENoCartaoDentroDoPrazo() throws Exception {
        validarBloqueiosSucessivos(Optional.of(2100L), false);
    }

    @Test
    void disputaDaPublicacaoDeReservaExpiradaTambemPreservaTempoUtilParaOCartao() throws Exception {
        validarBloqueiosSucessivos(Optional.of(2100L), true);
    }

    private void validarBloqueiosSucessivos(Optional<Long> liberarCartaoAposMillis, boolean expirarReserva)
            throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new IllegalStateException("falha antes da autorização"))
                .when(autorizarTransacaoUseCase).executar(org.mockito.ArgumentMatchers.eq(CARTAO),
                        org.mockito.ArgumentMatchers.eq(SENHA),
                        org.mockito.ArgumentMatchers.eq(new BigDecimal("1.00")),
                        org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));
        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().is5xxServerError());
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM idempotencia WHERE chave = ?", String.class, chave))
                .isEqualTo("PENDENTE");
        org.mockito.Mockito.reset(autorizarTransacaoUseCase);
        Optional.of(expirarReserva).filter(Boolean::booleanValue).ifPresent(ignorado -> jdbcTemplate.update(
                "UPDATE idempotencia SET estado = 'CONCLUIDA', status_http = 201, corpo_resposta = 'OK', "
                        + "tipo_midia = 'text/plain', concluida_em = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 25 HOUR), "
                        + "expira_em = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE chave = ?", chave));

        CountDownLatch reservaIniciada = new CountDownLatch(1);
        CountDownLatch cartaoIniciado = new CountDownLatch(1);
        AtomicLong inicioPrazo = new AtomicLong();
        AtomicLong fimRequisicao = new AtomicLong();
        org.mockito.Mockito.doAnswer(invocacao -> {
            PrazoConcorrenciaIdempotente prazo = invocacao.getArgument(2);
            inicioPrazo.set(prazo.limiteNanos() - TimeUnit.SECONDS.toNanos(5));
            reservaIniciada.countDown();
            return invocacao.callRealMethod();
        }).when(idempotenciaPort).reservar(org.mockito.ArgumentMatchers.eq(chave),
                org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(invocacao -> {
            cartaoIniciado.countDown();
            return invocacao.callRealMethod();
        }).when(autorizarTransacaoUseCase).executar(org.mockito.ArgumentMatchers.eq(CARTAO),
                org.mockito.ArgumentMatchers.eq(SENHA), org.mockito.ArgumentMatchers.eq(new BigDecimal("1.00")),
                org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));

        try (Connection bloqueioReserva = dataSource.getConnection();
                Connection bloqueioCartao = dataSource.getConnection();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            bloqueioReserva.setAutoCommit(false);
            bloqueioCartao.setAutoCommit(false);
            try (var reservaStatement = bloqueioReserva.prepareStatement(
                    "SELECT chave FROM idempotencia WHERE chave = ? FOR UPDATE");
                    var cartaoStatement = bloqueioCartao.prepareStatement(
                            "SELECT numero_cartao FROM cartao WHERE numero_cartao = ? FOR UPDATE")) {
                reservaStatement.setString(1, chave);
                assertThat(reservaStatement.executeQuery().next()).isTrue();
                cartaoStatement.setString(1, CARTAO);
                assertThat(cartaoStatement.executeQuery().next()).isTrue();
            }

            long inicio = System.nanoTime();
            var requisicao = executor.submit(() -> {
                try {
                    return transacaoComChave(CARTAO, SENHA, "1.00", chave).andReturn();
                } finally {
                    fimRequisicao.set(System.nanoTime());
                }
            });
            assertThat(reservaIniciada.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> requisicao.get(2100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            bloqueioReserva.commit();
            liberarCartaoAposMillis.ifPresent(millis -> {
                try {
                    assertThat(cartaoIniciado.await(1, TimeUnit.SECONDS))
                            .as("Autorização deve alcançar o cartão ainda dentro do prazo")
                            .isTrue();
                    assertThatThrownBy(() -> requisicao.get(millis, TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                    bloqueioCartao.commit();
                } catch (Exception falha) {
                    throw new AssertionError(falha);
                }
            });

            var resultadoMvc = requisicao.get(4500, TimeUnit.MILLISECONDS);
            int resultado = resultadoMvc.getResponse().getStatus();
            long duracaoMillis = TimeUnit.NANOSECONDS.toMillis(fimRequisicao.get() - inicioPrazo.get());
            System.out.printf("T008 bloqueios: expirada=%s, liberarCartao=%s, prazo_ms=%d, http_ms=%d%n",
                    expirarReserva, liberarCartaoAposMillis.isPresent(), duracaoMillis,
                    TimeUnit.NANOSECONDS.toMillis(fimRequisicao.get() - inicio));
            assertThat(resultado).withFailMessage("Resposta: %s; exceção resolvida: %s",
                    resultadoMvc.getResponse().getContentAsString(), resultadoMvc.getResolvedException())
                    .isEqualTo(liberarCartaoAposMillis.map(ignorado -> 201).orElse(503));
            assertThat(duracaoMillis).isBetween(3500L, 5600L);
            assertThat(saldoAtual()).isEqualByComparingTo(
                    liberarCartaoAposMillis.map(ignorado -> "499.00").orElse("500.00"));
            assertThat(jdbcTemplate.queryForMap("SELECT estado, status_http, corpo_resposta FROM idempotencia "
                    + "WHERE chave = ?", chave))
                    .containsEntry("estado", liberarCartaoAposMillis.map(ignorado -> "CONCLUIDA").orElse("PENDENTE"))
                    .containsEntry("status_http", liberarCartaoAposMillis.map(ignorado -> 201).orElse(null))
                    .containsEntry("corpo_resposta", liberarCartaoAposMillis.map(ignorado -> "OK").orElse(null));
            liberarCartaoAposMillis.ifPresent(ignorado -> {
                assertThat(resultadoMvc.getResponse().getContentAsByteArray()).isEqualTo("OK".getBytes(StandardCharsets.UTF_8));
                org.mockito.Mockito.verify(autorizarTransacaoUseCase, org.mockito.Mockito.times(1))
                        .executar(org.mockito.ArgumentMatchers.eq(CARTAO), org.mockito.ArgumentMatchers.eq(SENHA),
                                org.mockito.ArgumentMatchers.eq(new BigDecimal("1.00")),
                                org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));
            });
        }
    }

    @Test
    void divergenciaDuranteExecucaoRetomadaAposExpiracaoSobBloqueioRetornaConflito() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        transacaoComChave(CARTAO, SENHA, "2.00", chave).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE idempotencia SET expira_em = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE chave = ?", chave);
        AtomicBoolean expirarReservaPublicada = new AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocacao -> {
            IdempotenciaPort.Reserva reserva = (IdempotenciaPort.Reserva) invocacao.callRealMethod();
            Optional.of(expirarReservaPublicada.getAndSet(false))
                    .filter(Boolean::booleanValue)
                    .ifPresent(ignorado -> jdbcTemplate.update("UPDATE idempotencia SET estado = 'CONCLUIDA', "
                            + "status_http = 201, corpo_resposta = 'OK', tipo_midia = 'text/plain', "
                            + "concluida_em = UTC_TIMESTAMP(6), expira_em = "
                            + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE chave = ?", chave));
            return reserva;
        }).when(idempotenciaPort).reservar(org.mockito.ArgumentMatchers.eq(chave),
                org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.any());

        CountDownLatch autorizacaoIniciada = new CountDownLatch(1);
        CountDownLatch liberarAutorizacao = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocacao -> {
            autorizacaoIniciada.countDown();
            liberarAutorizacao.await(8, TimeUnit.SECONDS);
            return invocacao.callRealMethod();
        }).when(autorizarTransacaoUseCase).executar(org.mockito.ArgumentMatchers.eq(CARTAO),
                org.mockito.ArgumentMatchers.eq(SENHA), org.mockito.ArgumentMatchers.eq(new BigDecimal("1.00")),
                org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var novaExecucao = executor.submit(() -> transacaoComChave(CARTAO, SENHA, "1.00", chave)
                    .andReturn().getResponse().getStatus());
            assertThat(autorizacaoIniciada.await(5, TimeUnit.SECONDS)).isTrue();
            var reservaAtual = jdbcTemplate.queryForMap(
                    "SELECT estado, geracao FROM idempotencia WHERE chave = ?", chave);
            assertThat(reservaAtual.get("estado")).isEqualTo("PENDENTE");
            assertThat(((Number) reservaAtual.get("geracao")).longValue()).isEqualTo(3L);

            transacaoComChave(CARTAO, SENHA, "3.00", chave).andExpect(status().isConflict())
                    .andExpect(content().string("CHAVE_IDEMPOTENCIA_REUTILIZADA"));
            liberarAutorizacao.countDown();
            assertThat(novaExecucao.get(5, TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertThat(saldoAtual()).isEqualByComparingTo("497.00");
    }

    @Test
    void conteudoDivergenteDuranteProcessamentoRetornaSemAguardarABaixa() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        CountDownLatch autorizacaoIniciada = new CountDownLatch(1);
        CountDownLatch liberarAutorizacao = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocacao -> {
            autorizacaoIniciada.countDown();
            liberarAutorizacao.await(5, TimeUnit.SECONDS);
            return invocacao.callRealMethod();
        }).when(autorizarTransacaoUseCase).executar(org.mockito.ArgumentMatchers.eq(CARTAO),
                org.mockito.ArgumentMatchers.eq(SENHA), org.mockito.ArgumentMatchers.eq(new BigDecimal("10.00")),
                org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var original = executor.submit(() -> transacaoComChave(CARTAO, SENHA, "10.00", chave)
                    .andReturn().getResponse().getStatus());
            assertThat(autorizacaoIniciada.await(5, TimeUnit.SECONDS)).isTrue();

            transacaoComChave(CARTAO, SENHA, "5.00", chave).andExpect(status().isConflict())
                    .andExpect(content().string("CHAVE_IDEMPOTENCIA_REUTILIZADA"));

            liberarAutorizacao.countDown();
            assertThat(original.get(5, TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertThat(saldoAtual()).isEqualByComparingTo("490.00");
    }

    @Test
    void mesmaOperacaoEmEsperaRetorna409ComRetryAfterSemDuplicarDebito() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        CountDownLatch autorizacaoIniciada = new CountDownLatch(1);
        CountDownLatch liberarAutorizacao = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocacao -> {
            autorizacaoIniciada.countDown();
            liberarAutorizacao.await(8, TimeUnit.SECONDS);
            return invocacao.callRealMethod();
        }).when(cartaoPort).debitarSaldo(CARTAO, new BigDecimal("10.00"));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var original = executor.submit(() -> transacaoComChave(CARTAO, SENHA, "10.00", chave)
                    .andReturn().getResponse().getStatus());
            assertThat(autorizacaoIniciada.await(5, TimeUnit.SECONDS)).isTrue();

            long inicio = System.nanoTime();
            transacaoComChave(CARTAO, SENHA, "10.00", chave).andExpect(status().isConflict())
                    .andExpect(content().string("OPERACAO_EM_PROCESSAMENTO"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("Retry-After", "1"));
            long duracaoMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
            assertThat(duracaoMillis).isBetween(4800L, 5600L);

            assertThat(saldoAtual()).isEqualByComparingTo("500.00");
            assertThat(jdbcTemplate.queryForMap("SELECT estado, status_http, corpo_resposta FROM idempotencia "
                    + "WHERE chave = ?", chave)).containsEntry("estado", "PENDENTE")
                    .containsEntry("status_http", null).containsEntry("corpo_resposta", null);

            liberarAutorizacao.countDown();
            assertThat(original.get(5, TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertThat(saldoAtual()).isEqualByComparingTo("490.00");
    }

    @Test
    void reproduzResultadoOriginalConcluidoNaParteFinalDaJanelaSemNovaAutorizacao() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        CountDownLatch autorizacaoIniciada = new CountDownLatch(1);
        CountDownLatch liberarAutorizacao = new CountDownLatch(1);
        CountDownLatch repeticaoIniciada = new CountDownLatch(1);
        AtomicLong inicioPrazo = new AtomicLong();
        AtomicLong fimRepeticao = new AtomicLong();
        org.mockito.Mockito.doAnswer(invocacao -> {
            autorizacaoIniciada.countDown();
            assertThat(liberarAutorizacao.await(8, TimeUnit.SECONDS)).isTrue();
            return invocacao.callRealMethod();
        }).when(autorizarTransacaoUseCase).executar(org.mockito.ArgumentMatchers.eq(CARTAO),
                org.mockito.ArgumentMatchers.eq(SENHA), org.mockito.ArgumentMatchers.eq(new BigDecimal("10.00")),
                org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var original = executor.submit(() -> transacaoComChave(CARTAO, SENHA, "10.00", chave).andReturn());
            assertThat(autorizacaoIniciada.await(3, TimeUnit.SECONDS)).isTrue();
            org.mockito.Mockito.doAnswer(invocacao -> {
                PrazoConcorrenciaIdempotente prazo = invocacao.getArgument(3);
                inicioPrazo.set(prazo.limiteNanos() - TimeUnit.SECONDS.toNanos(5));
                repeticaoIniciada.countDown();
                return invocacao.callRealMethod();
            }).when(idempotenciaPort).executar(org.mockito.ArgumentMatchers.eq(chave),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(byte[].class),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            long inicio = System.nanoTime();
            var repeticao = executor.submit(() -> {
                try {
                    return transacaoComChave(CARTAO, SENHA, "10.00", chave).andReturn();
                } finally {
                    fimRepeticao.set(System.nanoTime());
                }
            });
            assertThat(repeticaoIniciada.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> repeticao.get(4200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            liberarAutorizacao.countDown();
            assertThat(original.get(2, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            var resposta = repeticao.get(2, TimeUnit.SECONDS).getResponse();
            assertThat(resposta.getStatus()).isEqualTo(201);
            assertThat(resposta.getContentAsString()).isEqualTo("OK");
            long duracaoMillis = TimeUnit.NANOSECONDS.toMillis(fimRepeticao.get() - inicioPrazo.get());
            System.out.printf("T008 reprodução tardia: prazo_ms=%d, http_ms=%d%n", duracaoMillis,
                    TimeUnit.NANOSECONDS.toMillis(fimRepeticao.get() - inicio));
            assertThat(duracaoMillis).isBetween(4200L, 5600L);
        } finally {
            liberarAutorizacao.countDown();
        }
        assertThat(saldoAtual()).isEqualByComparingTo("490.00");
        assertThat(jdbcTemplate.queryForMap("SELECT estado, status_http, corpo_resposta FROM idempotencia "
                + "WHERE chave = ?", chave)).containsEntry("estado", "CONCLUIDA")
                .containsEntry("status_http", 201).containsEntry("corpo_resposta", "OK");
        org.mockito.Mockito.verify(autorizarTransacaoUseCase, org.mockito.Mockito.times(1))
                .executar(org.mockito.ArgumentMatchers.eq(CARTAO), org.mockito.ArgumentMatchers.eq(SENHA),
                        org.mockito.ArgumentMatchers.eq(new BigDecimal("10.00")), org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));
    }

    @Test
    void falhaTransitoriaGenericaDuranteExecucaoPreserva503() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new TransientDataAccessResourceException("falha transitória simulada"))
                .when(autorizarTransacaoUseCase).executar(org.mockito.ArgumentMatchers.eq(CARTAO),
                        org.mockito.ArgumentMatchers.eq(SENHA),
                        org.mockito.ArgumentMatchers.eq(new BigDecimal("1.00")),
                        org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));

        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("SERVICO_INDISPONIVEL"));

        org.mockito.Mockito.verify(autorizarTransacaoUseCase, org.mockito.Mockito.times(1))
                .executar(org.mockito.ArgumentMatchers.eq(CARTAO), org.mockito.ArgumentMatchers.eq(SENHA),
                        org.mockito.ArgumentMatchers.eq(new BigDecimal("1.00")),
                        org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));
        assertThat(saldoAtual()).isEqualByComparingTo("500.00");
        assertThat(jdbcTemplate.queryForMap("SELECT estado, status_http FROM idempotencia WHERE chave = ?", chave))
                .containsEntry("estado", "PENDENTE")
                .containsEntry("status_http", null);
    }

    @Test
    void respostaPerdidaAposCommitRecuperaPelaChaveSemNovoDebito() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        AtomicBoolean primeiraResposta = new AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocacao -> {
            Optional<ResultadoOperacaoIdempotente> resultado = (Optional<ResultadoOperacaoIdempotente>)
                    invocacao.callRealMethod();
            return Optional.of(primeiraResposta.getAndSet(false))
                    .filter(Boolean::booleanValue)
                    .<Optional<ResultadoOperacaoIdempotente>>map(ignorado -> falharRespostaAposCommitOpcional())
                    .orElse(resultado);
        }).when(idempotenciaPort).executar(org.mockito.ArgumentMatchers.eq(chave),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isCreated())
                .andExpect(content().string("OK"));
        assertThat(saldoAtual()).isEqualByComparingTo("499.00");
        assertThat(jdbcTemplate.queryForMap("SELECT estado, status_http, corpo_resposta "
                + "FROM idempotencia WHERE chave = ?", chave))
                .containsEntry("estado", "CONCLUIDA")
                .containsEntry("status_http", 201)
                .containsEntry("corpo_resposta", "OK");

        assertThat(saldoAtual()).isEqualByComparingTo("499.00");
        org.mockito.Mockito.verify(idempotenciaPort, org.mockito.Mockito.times(1))
                .executar(org.mockito.ArgumentMatchers.eq(chave), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(idempotenciaPort, org.mockito.Mockito.times(1)).consultar(chave);
    }

    @Test
    void commitIncertoSemConsultaDuravelPreserva503SemRepetirDebito() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        AtomicBoolean primeiraResposta = new AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocacao -> {
            Optional<ResultadoOperacaoIdempotente> resultado = (Optional<ResultadoOperacaoIdempotente>)
                    invocacao.callRealMethod();
            return Optional.of(primeiraResposta.getAndSet(false))
                    .filter(Boolean::booleanValue)
                    .<Optional<ResultadoOperacaoIdempotente>>map(ignorado -> falharRespostaAposCommitOpcional())
                    .orElse(resultado);
        }).when(idempotenciaPort).executar(org.mockito.ArgumentMatchers.eq(chave),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doThrow(new TransientDataAccessResourceException("consulta indisponível"))
                .when(idempotenciaPort).consultar(chave);

        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("SERVICO_INDISPONIVEL"));

        assertThat(saldoAtual()).isEqualByComparingTo("499.00");
        assertThat(jdbcTemplate.queryForMap("SELECT estado, status_http FROM idempotencia WHERE chave = ?", chave))
                .containsEntry("estado", "CONCLUIDA")
                .containsEntry("status_http", 201);
        org.mockito.Mockito.verify(idempotenciaPort, org.mockito.Mockito.times(1)).consultar(chave);
        org.mockito.Mockito.verify(idempotenciaPort, org.mockito.Mockito.times(1))
                .executar(org.mockito.ArgumentMatchers.eq(chave), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void respostaHttpPerdidaAposCommitERecuperadaPelaChave() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        MockMvc comRespostaPerdida = mockMvcComRespostaPerdida();
        MockHttpServletRequestBuilder requisicao = autenticada(post("/transacoes"))
                .header("Idempotency-Key", chave)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + CARTAO + "\",\"senhaCartao\":\"" + SENHA
                        + "\",\"valor\":1.00}");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> comRespostaPerdida.perform(requisicao)))
                .isNotNull();
        assertThat(saldoAtual()).isEqualByComparingTo("499.00");
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM idempotencia WHERE chave = ?", String.class, chave))
                .isEqualTo("CONCLUIDA");

        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isCreated())
                .andExpect(content().string("OK"));
        assertThat(saldoAtual()).isEqualByComparingTo("499.00");
    }

    @Test
    void reservaPendenteAntigaReconheceConteudoAposNovoContexto() throws Exception {
        criarCartao(CARTAO, SENHA);
        jdbcTemplate.update("UPDATE cartao SET saldo = 10.00 WHERE numero_cartao = ?", CARTAO);
        String chave = java.util.UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new IllegalStateException("falha antes da autorizacao"))
                .when(autorizarTransacaoUseCase).executar(org.mockito.ArgumentMatchers.eq(CARTAO),
                        org.mockito.ArgumentMatchers.eq(SENHA),
                        org.mockito.ArgumentMatchers.eq(new BigDecimal("10.00")),
                        org.mockito.ArgumentMatchers.any(PrazoConcorrenciaIdempotente.class));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> webApplicationContext
                .getBean(AutorizarTransacaoIdempotenteUseCase.class)
                .executar(chave, CARTAO, SENHA, new BigDecimal("10.00"))))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbcTemplate.update("UPDATE idempotencia SET criada_em = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 25 HOUR) "
                + "WHERE chave = ?", chave);

        try (ConfigurableApplicationContext contexto = novoContexto()) {
            var resultado = contexto.getBean(AutorizarTransacaoIdempotenteUseCase.class)
                    .executar(chave, CARTAO, SENHA, new BigDecimal("10.00"));
            assertThat(resultado.status()).isEqualTo(201);
            assertThat(resultado.corpo()).isEqualTo("OK");
        }

        assertThat(saldoAtual()).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM idempotencia WHERE chave = ?", String.class, chave))
                .isEqualTo("CONCLUIDA");
    }

    @Test
    void falhaDepoisDoDebitoFazRollbackEPermiteRetomarAReserva() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        org.mockito.Mockito.doAnswer(invocacao -> {
            invocacao.callRealMethod();
            throw new IllegalStateException("falha simulada entre debito e resultado");
        }).doCallRealMethod().when(cartaoPort).debitarSaldo(CARTAO, new BigDecimal("1.00"));

        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isInternalServerError());

        assertThat(saldoAtual()).isEqualByComparingTo("500.00");
        assertThat(jdbcTemplate.queryForMap("SELECT estado, status_http FROM idempotencia WHERE chave = ?", chave))
                .containsEntry("estado", "PENDENTE")
                .containsEntry("status_http", null);

        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isCreated())
                .andExpect(content().string("OK"));
        assertThat(saldoAtual()).isEqualByComparingTo("499.00");
    }

    @Test
    void resultadoConfirmadoPermaneceReproduzivelEmNovoContexto() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isCreated());

        try (ConfigurableApplicationContext contexto = novoContexto()) {
            var resultado = contexto.getBean(AutorizarTransacaoIdempotenteUseCase.class)
                    .executar(chave, CARTAO, SENHA, new BigDecimal("1.00"));
            assertThat(resultado.status()).isEqualTo(201);
            assertThat(resultado.corpo()).isEqualTo("OK");
        }

        assertThat(saldoAtual()).isEqualByComparingTo("499.00");
    }

    @Test
    void deadlockDeChaveNaoGeraResultadoConcluidoNemRetentativaAutomatica() throws Exception {
        criarCartao(CARTAO, SENHA);
        String chave = java.util.UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new org.springframework.dao.DeadlockLoserDataAccessException(
                "deadlock simulado", null)).doCallRealMethod()
                .when(cartaoPort).debitarSaldo(CARTAO, new BigDecimal("1.00"));

        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isServiceUnavailable());

        org.mockito.Mockito.verify(cartaoPort, org.mockito.Mockito.times(1))
                .debitarSaldo(CARTAO, new BigDecimal("1.00"));
        assertThat(saldoAtual()).isEqualByComparingTo("500.00");
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM idempotencia WHERE chave = ?", String.class, chave))
                .isEqualTo("PENDENTE");

        transacaoComChave(CARTAO, SENHA, "1.00", chave).andExpect(status().isCreated());
        assertThat(saldoAtual()).isEqualByComparingTo("499.00");
    }

    @Test
    void falhaTecnicaSemChaveNaoRepeteAutorizacaoAutomaticamente() throws Exception {
        criarCartao(CARTAO, SENHA);
        org.mockito.Mockito.doThrow(new IllegalStateException("falha tecnica simulada"))
                .when(autorizarTransacaoUseCase).executar(CARTAO, SENHA, new BigDecimal("1.00"));

        transacao(CARTAO, SENHA, "1.00").andExpect(status().isInternalServerError());

        org.mockito.Mockito.verify(autorizarTransacaoUseCase, org.mockito.Mockito.times(1))
                .executar(CARTAO, SENHA, new BigDecimal("1.00"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotencia", Integer.class)).isZero();
        assertThat(saldoAtual()).isEqualByComparingTo("500.00");
    }

    @Test
    void respostaIncertaSemChaveRetorna503SemRepetirDebitoAutomaticamente() throws Exception {
        criarCartao(CARTAO, SENHA);
        MockMvc comRespostaPerdida = mockMvcComRespostaPerdida();
        MockHttpServletRequestBuilder requisicao = autenticada(post("/transacoes"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + CARTAO + "\",\"senhaCartao\":\"" + SENHA
                        + "\",\"valor\":1.00}");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> comRespostaPerdida.perform(requisicao)))
                .isNotNull();

        org.mockito.Mockito.verify(autorizarTransacaoUseCase, org.mockito.Mockito.times(1))
                .executar(CARTAO, SENHA, new BigDecimal("1.00"));
        assertThat(saldoAtual()).isEqualByComparingTo("499.00");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotencia", Integer.class)).isZero();
    }

    @Test
    void doisContextosIndependentesSerializamDebitoNoMySql() throws Exception {
        criarCartao(CARTAO, SENHA);
        jdbcTemplate.update("UPDATE cartao SET saldo = 10.00 WHERE numero_cartao = ?", CARTAO);
        try (ConfigurableApplicationContext primeiro = novoContexto();
                ConfigurableApplicationContext segundo = novoContexto();
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            long primeiraConexao = connectionId(primeiro.getBean(DataSource.class));
            long segundaConexao = connectionId(segundo.getBean(DataSource.class));
            CountDownLatch prontas = new CountDownLatch(2);
            CountDownLatch iniciar = new CountDownLatch(1);
            var primeira = executor.submit(() -> autorizarConcorrente(primeiro, prontas, iniciar));
            var segunda = executor.submit(() -> autorizarConcorrente(segundo, prontas, iniciar));
            assertThat(prontas.await(5, TimeUnit.SECONDS)).isTrue();
            iniciar.countDown();

            assertThat(List.of(primeira.get(10, TimeUnit.SECONDS), segunda.get(10, TimeUnit.SECONDS)))
                    .extracting(ResultadoAutorizacao::motivoRecusa)
                    .containsExactlyInAnyOrder(Optional.empty(), Optional.of(MotivoRecusa.SALDO_INSUFICIENTE));
            assertThat(primeiraConexao).isNotEqualTo(segundaConexao);
            assertThat(saldoAtual()).isEqualByComparingTo("0.00");
        }
    }

    @Test
    void rejeitaTiposJsonValoresInvalidosEDevolve400Estruturado() throws Exception {
        List<String> cartoesInvalidos = List.of(
                "{\"numeroCartao\":1234567890123456,\"senha\":\"0042\"}",
                "{\"numeroCartao\":\"0000000000000001\",\"senha\":42}",
                "{\"numeroCartao\":\"0000000000000001\",\"senha\":\"42\"}",
                "{\"numeroCartao\":\"0000000000000001\"}");
        List<String> transacoesInvalidas = List.of(
                "{\"numeroCartao\":1234567890123456,\"senhaCartao\":\"0042\",\"valor\":10}",
                "{\"numeroCartao\":\"0000000000000001\",\"senhaCartao\":42,\"valor\":10}",
                "{\"numeroCartao\":\"0000000000000001\",\"senhaCartao\":\"42\",\"valor\":10}",
                "{\"numeroCartao\":\"0000000000000001\",\"senhaCartao\":\"0042\"}",
                "{\"numeroCartao\":\"0000000000000001\",\"senhaCartao\":\"0042\",\"valor\":\"10.00\"}",
                "{\"numeroCartao\":\"0000000000000001\",\"senhaCartao\":\"0042\",\"valor\":10.001}",
                "{\"numeroCartao\":\"0000000000000001\",\"senhaCartao\":\"0042\",\"valor\":1.00000000000000001}",
                "{\"numeroCartao\":\"0000000000000001\",\"senhaCartao\":\"0042\",\"valor\":0}",
                "{\"numeroCartao\":\"0000000000000001\",\"senhaCartao\":\"0042\",\"valor\":-1}");

        for (String corpo : cartoesInvalidos) {
            mockMvc.perform(autenticada(post("/cartoes").contentType(MediaType.APPLICATION_JSON).content(corpo)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.codigo").value("REQUISICAO_INVALIDA"))
                    .andExpect(jsonPath("$.mensagem").isNotEmpty());
        }

        criarCartao(CARTAO, SENHA);
        for (String corpo : transacoesInvalidas) {
            mockMvc.perform(autenticada(post("/transacoes").contentType(MediaType.APPLICATION_JSON).content(corpo)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.codigo").value("REQUISICAO_INVALIDA"))
                    .andExpect(jsonPath("$.mensagem").isNotEmpty());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cartao", Integer.class)).isEqualTo(1);
        assertThat(saldoAtual()).isEqualByComparingTo("500.00");
    }

    @Test
    void mapperDoSpringPreservaPrecisaoEEscalaDoNumeroJson() throws Exception {
        BigDecimal valor = jsonMapper.readTree("{\"valor\":1.00000000000000001}")
                .get("valor")
                .decimalValue();

        assertThat(valor).isEqualTo(new BigDecimal("1.00000000000000001"));
        assertThat(valor.scale()).isEqualTo(17);
    }

    @Test
    void exigeBasicSemCorpoNasTresRotasENaoExecutaOperacoes() throws Exception {
        mockMvc.perform(post("/cartoes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + CARTAO + "\",\"senha\":\"" + SENHA + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get("/cartoes/{numeroCartao}", CARTAO))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(post("/transacoes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + CARTAO + "\",\"senhaCartao\":\"" + SENHA
                        + "\",\"valor\":1.00}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cartao", Integer.class)).isZero();
    }

    @Test
    void credenciaisBasicIncorretasRetornam401SemCriarNemDebitar() throws Exception {
        criarCartao(CARTAO, SENHA);
        String credencialInvalida = "Basic " + Base64.getEncoder()
                .encodeToString("username:senha-incorreta".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/cartoes").header("Authorization", credencialInvalida)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"0000000000000002\",\"senha\":\"0042\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get("/cartoes/{numeroCartao}", CARTAO).header("Authorization", credencialInvalida))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(post("/transacoes").header("Authorization", credencialInvalida)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + CARTAO + "\",\"senhaCartao\":\"" + SENHA
                        + "\",\"valor\":1.00}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cartao", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cartao WHERE numero_cartao = ?", Integer.class,
                "0000000000000002")).isZero();
        assertThat(saldoAtual()).isEqualByComparingTo("500.00");
    }

    @Test
    void falhaTransitoriaPeloMvcRetorna503ComErroEstruturado() throws Exception {
        org.mockito.Mockito.doThrow(new TransientDataAccessResourceException("falha temporaria"))
                .when(cartaoPort).buscar(CARTAO);

        mockMvc.perform(autenticada(get("/cartoes/{numeroCartao}", CARTAO)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value("SERVICO_INDISPONIVEL"))
                .andExpect(jsonPath("$.mensagem").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("falha temporaria"))));
    }

    @Test
    void falhaInesperadaPeloMvcRetorna500ComErroEstruturado() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("detalhe inesperado"))
                .when(cartaoPort).buscar(CARTAO);

        mockMvc.perform(autenticada(get("/cartoes/{numeroCartao}", CARTAO)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value("ERRO_INTERNO"))
                .andExpect(jsonPath("$.mensagem").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("detalhe inesperado"))));
    }

    @Test
    void caminhosInvalidosEJsonMalformadoNaoExecutamOperacoes() throws Exception {
        mockMvc.perform(autenticada(get("/cartoes/{numeroCartao}", "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("REQUISICAO_INVALIDA"));
        mockMvc.perform(autenticada(post("/cartoes")).contentType(MediaType.APPLICATION_JSON)
                .content("{malformado"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("REQUISICAO_INVALIDA"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cartao", Integer.class)).isZero();
    }

    private MockHttpServletRequestBuilder autenticada(MockHttpServletRequestBuilder requisicao) {
        return requisicao.header("Authorization", CREDENCIAL);
    }

    private MockMvc mockMvcComRespostaPerdida() {
        Filter perda = (requisicao, resposta, cadeia) -> {
            cadeia.doFilter(requisicao, resposta);
            throw new org.springframework.dao.DataAccessResourceFailureException("resposta HTTP perdida após commit");
        };
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain, perda)
                .build();
    }

    private Optional<ResultadoOperacaoIdempotente> falharRespostaAposCommitOpcional() {
        throw new org.springframework.dao.DataAccessResourceFailureException("conexão perdida após o commit");
    }

    private void criarCartao(String numero, String senha) throws Exception {
        mockMvc.perform(autenticada(post("/cartoes")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + numero + "\",\"senha\":\"" + senha + "\"}"))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions transacao(String numero, String senha, String valor)
            throws Exception {
        String corpo = "{\"numeroCartao\":\"" + numero + "\",\"senhaCartao\":\"" + senha
                + "\",\"valor\":" + valor + "}";
        return mockMvc.perform(autenticada(post("/transacoes")).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private org.springframework.test.web.servlet.ResultActions transacaoComChave(String numero, String senha,
            String valor, String chave) throws Exception {
        String corpo = "{\"numeroCartao\":\"" + numero + "\",\"senhaCartao\":\"" + senha
                + "\",\"valor\":" + (valor.isEmpty() ? "1.00" : valor) + "}";
        return mockMvc.perform(autenticada(post("/transacoes")).header("Idempotency-Key", chave)
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private int transacaoConcorrente(String chave, CountDownLatch prontas, CountDownLatch iniciar) throws Exception {
        prontas.countDown();
        iniciar.await(5, TimeUnit.SECONDS);
        return transacaoComChave(CARTAO, SENHA, "10.00", chave).andReturn().getResponse().getStatus();
    }

    private int autorizarIdempotenteConcorrente(ConfigurableApplicationContext contexto, String chave,
            CountDownLatch prontas, CountDownLatch iniciar) throws Exception {
        prontas.countDown();
        iniciar.await(5, TimeUnit.SECONDS);
        return contexto.getBean(AutorizarTransacaoIdempotenteUseCase.class)
                .executar(chave, CARTAO, SENHA, new BigDecimal("10.00")).status();
    }

    private int criarConcorrente(String senha, CountDownLatch prontas, CountDownLatch iniciar) throws Exception {
        prontas.countDown();
        iniciar.await(5, TimeUnit.SECONDS);
        return mockMvc.perform(autenticada(post("/cartoes")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroCartao\":\"" + CARTAO + "\",\"senha\":\"" + senha + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private ResultadoAutorizacao autorizarConcorrente(ConfigurableApplicationContext contexto, CountDownLatch prontas,
            CountDownLatch iniciar) throws Exception {
        prontas.countDown();
        iniciar.await(5, TimeUnit.SECONDS);
        return contexto.getBean(AutorizarTransacaoUseCase.class).executar(CARTAO, SENHA,
                new BigDecimal("10.00"));
    }

    private ConfigurableApplicationContext novoContexto() {
        return new SpringApplicationBuilder(MiniAutorizadorApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--idempotencia.segredo-hmac=segredo-de-teste-compartilhado-com-32-bytes",
                        "--spring.jpa.database-platform=org.hibernate.community.dialect.MySQLLegacyDialect");
    }

    private BigDecimal saldoAtual() {
        return jdbcTemplate.queryForObject("SELECT saldo FROM cartao WHERE numero_cartao = ?", BigDecimal.class,
                CARTAO);
    }

    private long connectionId(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT CONNECTION_ID()")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
