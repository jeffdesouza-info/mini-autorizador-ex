CREATE TABLE idempotencia (
    rota VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chave CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    geracao BIGINT UNSIGNED NOT NULL DEFAULT 1,
    impressao_digital BINARY(32) NOT NULL,
    estado ENUM('PENDENTE', 'CONCLUIDA') NOT NULL,
    status_http SMALLINT UNSIGNED NULL,
    corpo_resposta TEXT NULL,
    tipo_midia VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    criada_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    concluida_em DATETIME(6) NULL,
    expira_em DATETIME(6) NULL,
    CONSTRAINT pk_idempotencia PRIMARY KEY (rota, chave),
    INDEX ix_idempotencia_estado_expira (estado, expira_em)
) ENGINE = InnoDB;
