package br.com.miniautorizador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.miniautorizador.cartao.application.port.in.ConsultarSaldoUseCase;
import br.com.miniautorizador.cartao.application.port.out.CartaoPort;
import br.com.miniautorizador.cartao.application.port.out.SenhaCartaoPort;
import br.com.miniautorizador.transacao.application.port.in.AutorizarTransacaoUseCase;
import br.com.miniautorizador.transacao.domain.MotivoRecusa;
import br.com.miniautorizador.transacao.domain.ResultadoAutorizacao;
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
    private SenhaCartaoPort senhaCartaoPort;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoSpyBean
    private CartaoPort cartaoPort;

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
