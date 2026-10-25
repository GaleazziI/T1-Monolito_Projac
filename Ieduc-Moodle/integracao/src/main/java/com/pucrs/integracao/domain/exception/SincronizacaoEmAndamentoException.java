package com.pucrs.integracao.domain.exception;

public class SincronizacaoEmAndamentoException extends RuntimeException {

    public SincronizacaoEmAndamentoException() {
        super("Já existe uma sincronização em andamento.");
    }
}
