package com.pucrs.integracao.adapter.out.moodle;

import com.pucrs.integracao.domain.exception.MoodleException;
import com.pucrs.integracao.domain.exception.SistemaExternoException;
import com.pucrs.integracao.domain.exception.SistemaIndisponivelException;
import com.pucrs.integracao.domain.model.CursoMoodle;
import com.pucrs.integracao.domain.model.NovoUsuarioMoodle;
import com.pucrs.integracao.domain.model.UsuarioMoodle;
import com.pucrs.integracao.domain.port.out.MoodlePort;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Adaptador do Moodle via Web Services REST ({@code POST /webservice/rest/server.php}).
 * O Moodle responde HTTP 200 mesmo em erro: o erro é um objeto JSON com a chave {@code exception}.
 * Formatos verificados em docs/moodle-api.md.
 */
public class MoodleWsClient implements MoodlePort {

    private static final String SISTEMA = "Moodle";
    private static final String ENDPOINT = "/webservice/rest/server.php";

    private final RestClient http;
    private final String token;
    private final JsonMapper json = JsonMapper.builder().build();

    public MoodleWsClient(RestClient http, String token) {
        this.http = http;
        this.token = token;
    }

    @Override
    public Optional<Long> buscarCategoria(String idNumber) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("criteria[0][key]", "idnumber");
        p.add("criteria[0][value]", idNumber);
        JsonNode r = chamar("core_course_get_categories", p);
        return r.isArray() && !r.isEmpty() ? Optional.of(r.get(0).path("id").asLong()) : Optional.empty();
    }

    @Override
    public long criarCategoria(String nome, String idNumber) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("categories[0][name]", nome);
        p.add("categories[0][idnumber]", idNumber);
        p.add("categories[0][parent]", "0");
        return chamar("core_course_create_categories", p).get(0).path("id").asLong();
    }

    @Override
    public Optional<CursoMoodle> buscarCurso(String idNumber) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("field", "idnumber");
        p.add("value", idNumber);
        JsonNode cursos = chamar("core_course_get_courses_by_field", p).path("courses");
        if (!cursos.isArray() || cursos.isEmpty()) {
            return Optional.empty();
        }
        JsonNode c = cursos.get(0);
        return Optional.of(new CursoMoodle(c.path("id").asLong(), c.path("fullname").asString(),
                c.path("visible").asInt(1) == 1));
    }

    @Override
    public long criarCurso(String nomeCompleto, String nomeCurto, String idNumber, long categoriaId, boolean visivel) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("courses[0][fullname]", nomeCompleto);
        p.add("courses[0][shortname]", nomeCurto);
        p.add("courses[0][idnumber]", idNumber);
        p.add("courses[0][categoryid]", String.valueOf(categoriaId));
        p.add("courses[0][visible]", visivel ? "1" : "0");
        return chamar("core_course_create_courses", p).get(0).path("id").asLong();
    }

    @Override
    public void atualizarCurso(long cursoId, String nomeCompleto, boolean visivel) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("courses[0][id]", String.valueOf(cursoId));
        p.add("courses[0][fullname]", nomeCompleto);
        p.add("courses[0][visible]", visivel ? "1" : "0");
        chamar("core_course_update_courses", p);
    }

    @Override
    public Optional<UsuarioMoodle> buscarUsuario(String idNumber) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("field", "idnumber");
        p.add("values[0]", idNumber);
        JsonNode r = chamar("core_user_get_users_by_field", p);
        if (!r.isArray() || r.isEmpty()) {
            return Optional.empty();
        }
        JsonNode u = r.get(0);
        return Optional.of(new UsuarioMoodle(u.path("id").asLong(), u.path("firstname").asString(),
                u.path("lastname").asString(), u.path("email").asString()));
    }

    @Override
    public long criarUsuario(NovoUsuarioMoodle u) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("users[0][username]", u.username());
        p.add("users[0][password]", u.senha());
        p.add("users[0][firstname]", u.primeiroNome());
        p.add("users[0][lastname]", u.sobrenome());
        p.add("users[0][email]", u.email());
        p.add("users[0][idnumber]", u.idNumber());
        p.add("users[0][auth]", "manual");
        p.add("users[0][preferences][0][type]", "auth_forcepasswordchange");
        p.add("users[0][preferences][0][value]", "1");
        return chamar("core_user_create_users", p).get(0).path("id").asLong();
    }

    @Override
    public void atualizarUsuario(long usuarioId, String primeiroNome, String sobrenome, String email) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("users[0][id]", String.valueOf(usuarioId));
        p.add("users[0][firstname]", primeiroNome);
        p.add("users[0][lastname]", sobrenome);
        if (email != null) {
            p.add("users[0][email]", email);
        }
        chamar("core_user_update_users", p);
    }

    @Override
    public Set<Long> listarInscritosAtivos(long cursoId) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("courseid", String.valueOf(cursoId));
        p.add("options[0][name]", "onlyactive");
        p.add("options[0][value]", "1");
        Set<Long> ids = new HashSet<>();
        for (JsonNode u : chamar("core_enrol_get_enrolled_users", p)) {
            ids.add(u.path("id").asLong());
        }
        return ids;
    }

    @Override
    public void inscrever(long cursoId, long usuarioId, long papelId, boolean suspenso) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("enrolments[0][roleid]", String.valueOf(papelId));
        p.add("enrolments[0][userid]", String.valueOf(usuarioId));
        p.add("enrolments[0][courseid]", String.valueOf(cursoId));
        p.add("enrolments[0][suspend]", suspenso ? "1" : "0");
        chamar("enrol_manual_enrol_users", p);
    }

    // ------------------------------------------------------------------------------------------

    private JsonNode chamar(String funcao, MultiValueMap<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("wstoken", token);
        form.add("wsfunction", funcao);
        form.add("moodlewsrestformat", "json");
        form.addAll(params);
        try {
            String corpo = http.post().uri(ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(String.class);
            JsonNode resposta = json.readTree(corpo == null || corpo.isBlank() ? "null" : corpo);
            if (resposta.isObject() && resposta.has("exception")) {
                String codigo = resposta.path("errorcode").asString();
                if ("invalidtoken".equals(codigo) || "accessexception".equals(codigo)) {
                    throw new SistemaIndisponivelException(SISTEMA,
                            "token recusado (" + codigo + "): " + resposta.path("message").asString());
                }
                throw new MoodleException(codigo, resposta.path("message").asString());
            }
            return resposta;
        } catch (RestClientException e) {
            throw new SistemaIndisponivelException(SISTEMA, e.getMessage(), e);
        } catch (JacksonException e) {
            throw new SistemaExternoException(SISTEMA, "Resposta inválida do Moodle: " + e.getOriginalMessage(), e);
        }
    }
}
