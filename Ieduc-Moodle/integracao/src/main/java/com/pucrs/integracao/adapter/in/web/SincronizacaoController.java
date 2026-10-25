package com.pucrs.integracao.adapter.in.web;

import com.pucrs.integracao.domain.exception.SincronizacaoEmAndamentoException;
import com.pucrs.integracao.domain.model.OrigemSincronizacao;
import com.pucrs.integracao.domain.model.RelatorioSincronizacao;
import com.pucrs.integracao.domain.port.in.SincronizacaoUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sincronizacao")
public class SincronizacaoController {

    private final SincronizacaoUseCase useCase;

    public SincronizacaoController(SincronizacaoUseCase useCase) {
        this.useCase = useCase;
    }

    /** Sincronização manual: executa na hora e devolve o relatório. */
    @PostMapping
    public RelatorioSincronizacao sincronizar() {
        return useCase.sincronizar(OrigemSincronizacao.MANUAL);
    }

    @GetMapping("/ultima")
    public ResponseEntity<RelatorioSincronizacao> ultima() {
        return useCase.ultimoRelatorio().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @ExceptionHandler(SincronizacaoEmAndamentoException.class)
    ResponseEntity<Map<String, String>> emAndamento(SincronizacaoEmAndamentoException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("erro", e.getMessage()));
    }
}
