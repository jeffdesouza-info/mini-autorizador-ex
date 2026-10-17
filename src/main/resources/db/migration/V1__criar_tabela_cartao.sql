CREATE TABLE cartao (
    numero_cartao CHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    senha_hash VARCHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    saldo DECIMAL(19, 2) NOT NULL,
    CONSTRAINT pk_cartao PRIMARY KEY (numero_cartao)
) ENGINE = InnoDB;
