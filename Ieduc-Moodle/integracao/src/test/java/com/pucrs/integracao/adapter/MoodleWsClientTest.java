package com.pucrs.integracao.adapter;

import com.pucrs.integracao.adapter.out.moodle.MoodleWsClient;
import com.pucrs.integracao.domain.exception.MoodleException;
import com.pucrs.integracao.domain.exception.SistemaIndisponivelException;
import com.pucrs.integracao.domain.model.CursoMoodle;
import com.pucrs.integracao.domain.model.NovoUsuarioMoodle;
import com.pucrs.integracao.domain.model.UsuarioMoodle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Usa respostas REAIS capturadas do Moodle (src/test/resources/samples). */
class MoodleWsClientTest {

    MockRestServiceServer server;
    MoodleWsClient client;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://moodle:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MoodleWsClient(builder.build(), "tok123");
    }

    static String amostra(String nome) throws IOException {
        return new ClassPathResource("samples/" + nome).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void buscaCursoPorIdNumber() throws IOException {
        server.expect(requestTo("http://moodle:8080/webservice/rest/server.php"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("wstoken=tok123")))
                .andExpect(content().string(containsString("wsfunction=core_course_get_courses_by_field")))
                .andExpect(content().string(containsString("field=idnumber")))
                .andRespond(withSuccess(amostra("moodle-course-get.json"), MediaType.APPLICATION_JSON));

        Optional<CursoMoodle> curso = client.buscarCurso("IEDUCAR-TURMA-1");

        assertThat(curso).contains(new CursoMoodle(2, "Engenharia de Software — Turma A", true));
    }

    @Test
    void cursoInexistenteDevolveVazio() {
        server.expect(requestTo(containsString("server.php")))
                .andRespond(withSuccess("{\"courses\":[],\"warnings\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.buscarCurso("x")).isEmpty();
    }

    @Test
    void criaCursoEnviandoOsCamposAninhados() throws IOException {
        server.expect(requestTo(containsString("server.php")))
                .andExpect(content().string(containsString("wsfunction=core_course_create_courses")))
                .andExpect(content().string(containsString("courses%5B0%5D%5Bidnumber%5D=IEDUCAR-TURMA-1")))
                .andExpect(content().string(containsString("courses%5B0%5D%5Bshortname%5D=TURMA-1")))
                .andRespond(withSuccess(amostra("moodle-course-create.json"), MediaType.APPLICATION_JSON));

        assertThat(client.criarCurso("Engenharia de Software — Turma A", "TURMA-1", "IEDUCAR-TURMA-1", 2, true)).isEqualTo(2);
    }

    @Test
    void buscaCategoriaEUsuario() throws IOException {
        server.expect(requestTo(containsString("server.php")))
                .andRespond(withSuccess(amostra("moodle-cat-get.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("server.php")))
                .andRespond(withSuccess(amostra("moodle-user-get.json"), MediaType.APPLICATION_JSON));

        assertThat(client.buscarCategoria("IEDUCAR-CURSO-1")).contains(2L);
        assertThat(client.buscarUsuario("IEDUCAR-ALUNO-1"))
                .contains(new UsuarioMoodle(3, "Maria", "Souza Lima", "maria.lima@exemplo.edu.br"));
    }

    @Test
    void usuarioInexistenteDevolveVazio() {
        server.expect(requestTo(containsString("server.php"))).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(client.buscarUsuario("x")).isEmpty();
    }

    @Test
    void criaUsuarioPedindoTrocaDeSenha() {
        server.expect(requestTo(containsString("server.php")))
                .andExpect(content().string(containsString("wsfunction=core_user_create_users")))
                .andExpect(content().string(containsString("auth_forcepasswordchange")))
                .andExpect(content().string(containsString("users%5B0%5D%5Busername%5D=aluno3")))
                .andRespond(withSuccess("[{\"id\":7,\"username\":\"aluno3\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.criarUsuario(new NovoUsuarioMoodle("aluno3", "Mudar@2026!", "Ana", "Souza", "a@b.com", "IEDUCAR-ALUNO-3")))
                .isEqualTo(7);
    }

    @Test
    void erroDoMoodleComHttp200ViraMoodleException() throws IOException {
        server.expect(requestTo(containsString("server.php")))
                .andRespond(withSuccess(amostra("moodle-user-dup-username.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.criarUsuario(new NovoUsuarioMoodle("aluno1", "x", "A", "B", "a@b.com", "i")))
                .isInstanceOfSatisfying(MoodleException.class, e -> assertThat(e.getErrorCode()).isEqualTo("invalidparameter"));
    }

    @Test
    void tokenInvalidoEhTratadoComoIndisponibilidade() throws IOException {
        server.expect(requestTo(containsString("server.php")))
                .andRespond(withSuccess(amostra("moodle-bad-token.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.buscarCategoria("x")).isInstanceOf(SistemaIndisponivelException.class)
                .hasMessageContaining("invalidtoken");
    }

    @Test
    void http500ViraIndisponibilidade() {
        server.expect(requestTo(containsString("server.php"))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> client.buscarCategoria("x")).isInstanceOf(SistemaIndisponivelException.class);
    }

    @Test
    void inscricaoComSuspensaoEResultadoNull() {
        server.expect(requestTo(containsString("server.php")))
                .andExpect(content().string(containsString("wsfunction=enrol_manual_enrol_users")))
                .andExpect(content().string(containsString("enrolments%5B0%5D%5Bsuspend%5D=1")))
                .andExpect(content().string(containsString("enrolments%5B0%5D%5Broleid%5D=5")))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        client.inscrever(2, 3, 5, true);
        server.verify();
    }

    @Test
    void listaInscritosAtivos() {
        server.expect(requestTo(containsString("server.php")))
                .andExpect(content().string(containsString("onlyactive")))
                .andRespond(withSuccess("[{\"id\":3},{\"id\":4}]", MediaType.APPLICATION_JSON));

        assertThat(client.listarInscritosAtivos(2)).containsExactlyInAnyOrder(3L, 4L);
    }
}
