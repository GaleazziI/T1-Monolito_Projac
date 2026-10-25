package com.pucrs.integracao.config;

import com.pucrs.integracao.application.OpcoesSincronizacao;
import com.pucrs.integracao.adapter.out.ieducar.IEducarApiClient;
import com.pucrs.integracao.adapter.out.moodle.MoodleWsClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class IntegracaoConfig {

    @Bean
    IEducarApiClient iEducarApiClient(IntegracaoProperties props) {
        var p = props.ieducar();
        return new IEducarApiClient(restClient(p.baseUrl(), p.timeout()), p);
    }

    @Bean
    MoodleWsClient moodleWsClient(IntegracaoProperties props) {
        var p = props.moodle();
        return new MoodleWsClient(restClient(p.baseUrl(), p.timeout()), p.token());
    }

    @Bean
    OpcoesSincronizacao opcoesSincronizacao(IntegracaoProperties props) {
        var m = props.moodle();
        return new OpcoesSincronizacao(m.papelAlunoId(), m.papelProfessorId(), m.senhaInicial(), m.dominioEmail());
    }

    private static RestClient restClient(String baseUrl, Duration timeout) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
