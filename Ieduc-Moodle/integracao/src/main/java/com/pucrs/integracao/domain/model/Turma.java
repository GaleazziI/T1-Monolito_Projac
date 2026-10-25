package com.pucrs.integracao.domain.model;

/** Turma do iEducar (vira um curso no Moodle). */
public record Turma(long id, String nome, int ano, boolean ativa, long escolaId, String escolaNome, Curso curso) {

    /** Nome do curso no Moodle, ex.: "Engenharia de Software — Turma A". */
    public String nomeNoMoodle() {
        return curso.nome() + " — " + nome;
    }
}
