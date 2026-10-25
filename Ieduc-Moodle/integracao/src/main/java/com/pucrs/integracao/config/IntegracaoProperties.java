package com.pucrs.integracao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "integracao")
public record IntegracaoProperties(IEducar ieducar, Moodle moodle) {

    public record IEducar(String baseUrl, String token, String accessKey, String secretKey,
                          int instituicaoId, Duration timeout) {
    }

    public record Moodle(String baseUrl, String token, long papelAlunoId, long papelProfessorId,
                         String senhaInicial, String dominioEmail, Duration timeout) {
    }
}
