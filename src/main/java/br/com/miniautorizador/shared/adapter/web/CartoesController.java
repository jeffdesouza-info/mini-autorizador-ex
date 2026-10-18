package br.com.miniautorizador.shared.adapter.web;

import br.com.miniautorizador.cartao.application.port.in.ConsultarSaldoUseCase;
import br.com.miniautorizador.cartao.application.port.in.CriarCartaoUseCase;
import br.com.miniautorizador.transacao.application.port.in.AutorizarTransacaoUseCase;
import tools.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CartoesController {

    private final CriarCartaoUseCase criarCartaoUseCase;
    private final ConsultarSaldoUseCase consultarSaldoUseCase;
    private final AutorizarTransacaoUseCase autorizarTransacaoUseCase;
    private final ApiRequestParser requestParser;
    private final ApiResponseTranslator responseTranslator;

    public CartoesController(CriarCartaoUseCase criarCartaoUseCase, ConsultarSaldoUseCase consultarSaldoUseCase,
            AutorizarTransacaoUseCase autorizarTransacaoUseCase, ApiRequestParser requestParser,
            ApiResponseTranslator responseTranslator) {
        this.criarCartaoUseCase = criarCartaoUseCase;
        this.consultarSaldoUseCase = consultarSaldoUseCase;
        this.autorizarTransacaoUseCase = autorizarTransacaoUseCase;
        this.requestParser = requestParser;
        this.responseTranslator = responseTranslator;
    }

    @PostMapping("/cartoes")
    public ResponseEntity<ApiResponseTranslator.RespostaCartao> criar(@RequestBody JsonNode corpo) {
        ApiRequestParser.DadosCartao entrada = requestParser.cartao(corpo);
        var resultado = criarCartaoUseCase.executar(entrada.numeroCartao(), entrada.senha());
        return responseTranslator.cartao(resultado, entrada.numeroCartao(), entrada.senha());
    }

    @GetMapping("/cartoes/{numeroCartao}")
    public ResponseEntity<String> consultar(@PathVariable String numeroCartao) {
        return responseTranslator.saldo(consultarSaldoUseCase.executar(requestParser.numero(numeroCartao)));
    }

    @PostMapping("/transacoes")
    public ResponseEntity<String> autorizar(@RequestBody JsonNode corpo) {
        ApiRequestParser.DadosTransacao entrada = requestParser.transacao(corpo);
        return responseTranslator.transacao(
                autorizarTransacaoUseCase.executar(entrada.numeroCartao(), entrada.senhaCartao(), entrada.valor()));
    }
}
