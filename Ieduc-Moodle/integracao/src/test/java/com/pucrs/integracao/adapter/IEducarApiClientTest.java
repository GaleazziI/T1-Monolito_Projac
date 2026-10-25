package com.pucrs.integracao.adapter;

import com.pucrs.integracao.adapter.out.ieducar.IEducarApiClient;
import com.pucrs.integracao.config.IntegracaoProperties;
import com.pucrs.integracao.domain.exception.SistemaIndisponivelException;
import com.pucrs.integracao.domain.model.Matricula;
import com.pucrs.integracao.domain.model.Turma;
import com.pucrs.integracao.domain.model.VinculoProfessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Lê as respostas REAIS capturadas do iEducar (src/test/resources/samples) e confere o mapeamento. */
class IEducarApiClientTest {

    MockRestServiceServer server;
    IEducarApiClient client;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ieducar");
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new IntegracaoProperties.IEducar("http://ieducar", "tok", "chave", "segredo", 1, Duration.ofSeconds(5));
        client = new IEducarApiClient(builder.build(), props);
    }

    static String amostra(String nome) throws IOException {
        return new ClassPathResource("samples/" + nome).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void listaTurmasComCursoEEscola() throws IOException {
        server.expect(requestTo(containsString("/api/school-class?")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess(amostra("ieducar-school-class.json"), MediaType.APPLICATION_JSON));

        List<Turma> turmas = client.listarTurmas();

        assertThat(turmas).hasSize(4);
        Turma primeira = turmas.get(0);
        assertThat(primeira.id()).isEqualTo(3);
        assertThat(primeira.nome()).isEqualTo("Turma A");
        assertThat(primeira.ano()).isEqualTo(2026);
        assertThat(primeira.ativa()).isTrue();
        assertThat(primeira.curso().nome()).isEqualTo("Engenharia de Software");
        assertThat(primeira.escolaId()).isEqualTo(2);
        assertThat(primeira.escolaNome()).isEqualTo("Escola de Tecnologia - Campus Central");
        assertThat(primeira.nomeNoMoodle()).isEqualTo("Engenharia de Software — Turma A");
        server.verify();
    }

    @Test
    void listaMatriculasPorEnturmacao() throws IOException {
        server.expect(requestTo(containsString("/api/registration?year_eq=2026")))
                .andRespond(withSuccess(amostra("ieducar-registration-ano.json"), MediaType.APPLICATION_JSON));

        List<Matricula> matriculas = client.listarMatriculas(2026);

        assertThat(matriculas).hasSize(10).allMatch(Matricula::ativa);
        assertThat(matriculas.get(0).aluno().id()).isEqualTo(3);
        assertThat(matriculas.get(0).aluno().nome()).isEqualTo("Ana Beatriz Souza");
        assertThat(matriculas.get(0).aluno().email()).isEqualTo("ana.souza@aluno.demo.edu.br");
        assertThat(matriculas.get(0).aluno().cpf()).isEqualTo("100.000.001-08");
        assertThat(matriculas.get(0).turmaId()).isEqualTo(3);
    }

    @Test
    void enturmacaoInativaViraMatriculaInativa_mesmoComAtivoNaRaiz() throws IOException {
        server.expect(requestTo(containsString("/api/registration")))
                .andRespond(withSuccess(amostra("ieducar-cancelamento-registration-ano.json"), MediaType.APPLICATION_JSON));

        List<Matricula> matriculas = client.listarMatriculas(2026);

        assertThat(matriculas.stream().filter(m -> !m.ativa()).map(m -> m.aluno().id())).containsExactly(4L, 5L);
    }

    @Test
    void listaVinculosDeProfessoresEBuscaOsDadosDeCadaServidor() throws IOException {
        server.expect(requestTo(containsString("/module/Api/Servidor?oper=get")))
                .andExpect(requestTo(containsString("resource=servidores-disciplinas-turmas")))
                .andExpect(requestTo(containsString("access_key=chave")))
                .andRespond(withSuccess(amostra("ieducar-legacy-vinculos-professor-turma.json"), MediaType.APPLICATION_JSON));
        // um "dados-servidor" por servidor distinto (13, 15, 17, 21)
        for (int i = 0; i < 4; i++) {
            server.expect(requestTo(containsString("resource=dados-servidor")))
                    .andRespond(withSuccess(amostra("ieducar-legacy-dados-servidor.json"), MediaType.APPLICATION_JSON));
        }

        List<VinculoProfessor> vinculos = client.listarVinculosProfessores(2026, List.of(2L));

        assertThat(vinculos).hasSize(4).allMatch(VinculoProfessor::ativo);
        assertThat(vinculos.get(0).turmaId()).isEqualTo(3);
        assertThat(vinculos.get(0).professor().email()).isEqualTo("marcos.ribeiro@prof.demo.edu.br");
        server.verify();
    }

    @Test
    void vinculoComDeletedAtEhInativo() throws IOException {
        server.expect(requestTo(containsString("servidores-disciplinas-turmas")))
                .andRespond(withSuccess(amostra("ieducar-cancelamento-legacy-vinculos.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("dados-servidor"))).andRespond(withSuccess(amostra("ieducar-legacy-dados-servidor.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("dados-servidor"))).andRespond(withSuccess(amostra("ieducar-legacy-dados-servidor.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("dados-servidor"))).andRespond(withSuccess(amostra("ieducar-legacy-dados-servidor.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("dados-servidor"))).andRespond(withSuccess(amostra("ieducar-legacy-dados-servidor.json"), MediaType.APPLICATION_JSON));

        List<VinculoProfessor> vinculos = client.listarVinculosProfessores(2026, List.of(2L));

        assertThat(vinculos.stream().filter(v -> !v.ativo()).map(VinculoProfessor::turmaId)).containsExactlyInAnyOrder(6L, 4L);
    }

    @Test
    void chaveLegadaInvalida_apesarDoHttp200_viraIndisponibilidade() throws IOException {
        server.expect(requestTo(containsString("servidores-disciplinas-turmas")))
                .andRespond(withSuccess(amostra("ieducar-legacy-chave-invalida.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.listarVinculosProfessores(2026, List.of(2L)))
                .isInstanceOf(SistemaIndisponivelException.class)
                .hasMessageContaining("Chave de acesso inválida");
    }

    @Test
    void http401ViraIndisponibilidade() {
        server.expect(requestTo(containsString("/api/school-class")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Unauthenticated.\"}"));

        assertThatThrownBy(() -> client.listarTurmas()).isInstanceOf(SistemaIndisponivelException.class);
    }

    @Test
    void percorreTodasAsPaginas() {
        String p1 = "{\"data\":[{\"cod_turma\":1,\"nm_turma\":\"A\",\"ano\":2026,\"ativo\":1,\"ref_cod_curso\":1,\"course\":{\"id\":1,\"name\":\"C\"}}],\"links\":{\"next\":\"http://x?page=2\"}}";
        String p2 = "{\"data\":[{\"cod_turma\":2,\"nm_turma\":\"B\",\"ano\":2026,\"ativo\":1,\"ref_cod_curso\":1,\"course\":{\"id\":1,\"name\":\"C\"}}],\"links\":{\"next\":null}}";
        server.expect(requestTo(containsString("page=1"))).andRespond(withSuccess(p1, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("page=2"))).andRespond(withSuccess(p2, MediaType.APPLICATION_JSON));

        assertThat(client.listarTurmas()).extracting(Turma::id).containsExactly(1L, 2L);
        server.verify();
    }
}
