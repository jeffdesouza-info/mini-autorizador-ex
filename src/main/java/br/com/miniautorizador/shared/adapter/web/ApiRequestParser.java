package br.com.miniautorizador.shared.adapter.web;

import br.com.miniautorizador.cartao.domain.ValidacaoCartao;
import br.com.miniautorizador.shared.domain.RegrasMonetarias;
import java.math.BigDecimal;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
class ApiRequestParser {

    DadosCartao cartao(JsonNode corpo) {
        JsonNode objeto = objeto(corpo);
        return new DadosCartao(numero(texto(objeto, "numeroCartao")), senha(texto(objeto, "senha")));
    }

    DadosTransacao transacao(JsonNode corpo) {
        JsonNode objeto = objeto(corpo);
        return new DadosTransacao(numero(texto(objeto, "numeroCartao")), senha(texto(objeto, "senhaCartao")),
                valor(objeto));
    }

    String numero(String valor) {
        return Optional.of(valor)
                .filter(ValidacaoCartao::numeroCartaoValido)
                .orElseThrow(InvalidRequestException::new);
    }

    private JsonNode objeto(JsonNode corpo) {
        return Optional.ofNullable(corpo)
                .filter(JsonNode::isObject)
                .orElseThrow(InvalidRequestException::new);
    }

    private String texto(JsonNode objeto, String campo) {
        return Optional.ofNullable(objeto.get(campo))
                .filter(JsonNode::isString)
                .map(JsonNode::stringValue)
                .filter(valor -> !valor.isBlank())
                .orElseThrow(InvalidRequestException::new);
    }

    private String senha(String valor) {
        return Optional.of(valor)
                .filter(ValidacaoCartao::senhaValida)
                .orElseThrow(InvalidRequestException::new);
    }

    private BigDecimal valor(JsonNode objeto) {
        BigDecimal decimal = Optional.ofNullable(objeto.get("valor"))
                .filter(JsonNode::isNumber)
                .map(JsonNode::decimalValue)
                .filter(RegrasMonetarias::debitoValido)
                .orElseThrow(InvalidRequestException::new);
        return RegrasMonetarias.debito(decimal);
    }

    record DadosCartao(String numeroCartao, String senha) {
        @Override
        public String toString() {
            return "DadosCartao[numeroCartao=" + numeroCartao + ", senha=[PROTEGIDA]]";
        }
    }

    record DadosTransacao(String numeroCartao, String senhaCartao, BigDecimal valor) {
        @Override
        public String toString() {
            return "DadosTransacao[numeroCartao=" + numeroCartao + ", senhaCartao=[PROTEGIDA], valor="
                    + valor.toPlainString() + "]";
        }
    }
}
