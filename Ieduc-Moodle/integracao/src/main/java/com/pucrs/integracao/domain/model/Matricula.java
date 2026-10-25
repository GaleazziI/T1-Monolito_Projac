package com.pucrs.integracao.domain.model;

/** Enturmação de um aluno em uma turma. {@code ativa=false} indica cancelamento/saída. */
public record Matricula(Aluno aluno, long turmaId, boolean ativa) {
}
