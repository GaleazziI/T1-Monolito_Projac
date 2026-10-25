package com.pucrs.integracao.domain.model;

import java.time.Instant;
import java.util.List;

/** Resultado de uma execução da sincronização. */
public record RelatorioSincronizacao(
        OrigemSincronizacao origem,
        Status status,
        Instant inicio,
        Instant fim,
        int turmasLidas,
        int cursosCriados,
        int cursosAtualizados,
        int cursosOcultados,
        int usuariosCriados,
        int usuariosAtualizados,
        int inscricoesCriadas,
        int inscricoesSuspensas,
        List<String> erros) {

    public enum Status {
        /** Tudo sincronizado sem erros. */
        SUCESSO,
        /** Terminou, mas alguns itens falharam (ver {@code erros}). */
        PARCIAL,
        /** Não foi possível concluir (ex.: iEducar ou Moodle indisponível). Será tentado de novo. */
        FALHA
    }
}
