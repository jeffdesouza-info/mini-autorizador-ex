package br.com.miniautorizador.cartao.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ValidacaoCartao {

    private static final Pattern NUMERO_CARTAO = Pattern.compile("[0-9]{16}");
    private static final Pattern SENHA = Pattern.compile("[0-9]{4}");

    private ValidacaoCartao() {
    }

    public static boolean numeroCartaoValido(String numeroCartao) {
        return Objects.nonNull(numeroCartao) && NUMERO_CARTAO.matcher(numeroCartao).matches();
    }

    public static boolean senhaValida(String senha) {
        return Objects.nonNull(senha) && SENHA.matcher(senha).matches();
    }

    public static String exigirNumeroCartao(String numeroCartao) {
        return Optional.ofNullable(numeroCartao)
                .filter(ValidacaoCartao::numeroCartaoValido)
                .orElseThrow(() -> new IllegalArgumentException("Número de cartão inválido"));
    }

    public static String exigirSenha(String senha) {
        return Optional.ofNullable(senha)
                .filter(ValidacaoCartao::senhaValida)
                .orElseThrow(() -> new IllegalArgumentException("Senha de cartão inválida"));
    }

    public static String exigirHashSenha(String senhaHash) {
        return Optional.ofNullable(senhaHash)
                .filter(hash -> !hash.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Hash da senha inválido"));
    }
}
