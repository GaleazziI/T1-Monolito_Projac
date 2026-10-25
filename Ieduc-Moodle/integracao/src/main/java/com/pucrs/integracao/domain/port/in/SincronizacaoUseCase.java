package com.pucrs.integracao.domain.port.in;

import com.pucrs.integracao.domain.model.OrigemSincronizacao;
import com.pucrs.integracao.domain.model.RelatorioSincronizacao;

import java.util.Optional;

public interface SincronizacaoUseCase {

    /** Executa uma sincronização completa iEducar → Moodle. */
    RelatorioSincronizacao sincronizar(OrigemSincronizacao origem);

    /** Relatório da última execução, se houver. */
    Optional<RelatorioSincronizacao> ultimoRelatorio();
}
