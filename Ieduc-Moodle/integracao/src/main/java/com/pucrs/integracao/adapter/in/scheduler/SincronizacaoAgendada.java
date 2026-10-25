package com.pucrs.integracao.adapter.in.scheduler;

import com.pucrs.integracao.domain.exception.SincronizacaoEmAndamentoException;
import com.pucrs.integracao.domain.model.OrigemSincronizacao;
import com.pucrs.integracao.domain.port.in.SincronizacaoUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** faz o disparo da sincronização periodicamente. Como a APi do iEducar não notifica quando é realizada alguma alteração, usamos polling. */
@Component
@ConditionalOnProperty(name = "integracao.sincronizacao.agendada", havingValue = "true", matchIfMissing = true)
public class SincronizacaoAgendada {

    private static final Logger log = LoggerFactory.getLogger(SincronizacaoAgendada.class);

    private final SincronizacaoUseCase useCase;

    public SincronizacaoAgendada(SincronizacaoUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(initialDelayString = "${integracao.sincronizacao.atraso-inicial:PT30S}",
            fixedDelayString = "${integracao.sincronizacao.intervalo:PT2M}")
    public void executar() {
        try {
            useCase.sincronizar(OrigemSincronizacao.AGENDADA);
        } catch (SincronizacaoEmAndamentoException e) {
            log.info("Sincronização agendada ignorada: {}", e.getMessage());
        }
    }
}
