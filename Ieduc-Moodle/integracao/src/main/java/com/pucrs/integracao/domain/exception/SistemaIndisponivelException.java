package com.pucrs.integracao.domain.exception;

/**
 * O sistema externo não respondeu (rede, timeout, 5xx, credencial recusada). A sincronização inteira é
 * abortada e tentada de novo na próxima execução agendada.
 */
public class SistemaIndisponivelException extends SistemaExternoException {

    public SistemaIndisponivelException(String sistema, String mensagem, Throwable causa) {
        super(sistema, sistema + " indisponível: " + mensagem, causa);
    }

    public SistemaIndisponivelException(String sistema, String mensagem) {
        super(sistema, sistema + " indisponível: " + mensagem);
    }
}
