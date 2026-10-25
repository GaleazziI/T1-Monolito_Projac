package com.pucrs.integracao.domain.model;

/** Vínculo de um professor com uma turma. {@code ativo=false} indica vínculo removido. */
public record VinculoProfessor(Professor professor, long turmaId, boolean ativo) {
}
