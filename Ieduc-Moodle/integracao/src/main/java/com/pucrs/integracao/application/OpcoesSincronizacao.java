package com.pucrs.integracao.application;

/** Parâmetros das regras de sincronização. */
public record OpcoesSincronizacao(long papelAlunoId, long papelProfessorId, String senhaInicial, String dominioEmail) {
}
