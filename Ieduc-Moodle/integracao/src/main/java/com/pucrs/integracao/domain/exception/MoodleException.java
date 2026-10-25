package com.pucrs.integracao.domain.exception;

/** O Moodle respondeu, mas recusou a operação (JSON com "exception"; o HTTP é sempre 200). */
public class MoodleException extends SistemaExternoException {

    private final String errorCode;

    public MoodleException(String errorCode, String mensagem) {
        super("Moodle", "Moodle recusou a operação [" + errorCode + "]: " + mensagem);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
