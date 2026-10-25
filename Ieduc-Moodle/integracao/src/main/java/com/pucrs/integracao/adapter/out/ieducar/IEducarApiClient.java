package com.pucrs.integracao.adapter.out.ieducar;

import com.pucrs.integracao.config.IntegracaoProperties;
import com.pucrs.integracao.domain.exception.SistemaExternoException;
import com.pucrs.integracao.domain.exception.SistemaIndisponivelException;
import com.pucrs.integracao.domain.model.Aluno;
import com.pucrs.integracao.domain.model.Curso;
import com.pucrs.integracao.domain.model.Matricula;
import com.pucrs.integracao.domain.model.Professor;
import com.pucrs.integracao.domain.model.Turma;
import com.pucrs.integracao.domain.model.VinculoProfessor;
import com.pucrs.integracao.domain.port.out.IEducarPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adaptador do iEducar. Turmas e matrículas vêm da API REST (Bearer token Sanctum); professores vêm da API
 * legada ({@code /module/Api/Servidor}, access_key/secret_key), que não tem equivalente REST.
 * Formatos verificados em docs/ieducar-api.md.
 */
public class IEducarApiClient implements IEducarPort {

    private static final Logger log = LoggerFactory.getLogger(IEducarApiClient.class);
    private static final String SISTEMA = "iEducar";
    private static final int PAGINA = 100;
    /** Situações da matrícula que encerram a participação: transferido, reclassificado, abandono, falecido. */
    private static final Set<Integer> SITUACOES_ENCERRADAS = Set.of(4, 5, 6, 15);

    private final RestClient http;
    private final IntegracaoProperties.IEducar props;
    private final JsonMapper json = JsonMapper.builder().build();

    public IEducarApiClient(RestClient http, IntegracaoProperties.IEducar props) {
        this.http = http;
        this.props = props;
    }

    // ---------------------------------------------------------------------------------- turmas

    @Override
    public List<Turma> listarTurmas() {
        List<Turma> turmas = new ArrayList<>();
        for (int pagina = 1; ; pagina++) {
            JsonNode raiz = getRest("/api/school-class?only={only}&include={include}&order={order}&show={show}&page={page}",
                    Map.of("only", "cod_turma,nm_turma,sgl_turma,ano,ativo,visivel,ref_cod_curso,ref_ref_cod_escola,ref_ref_cod_serie",
                            "include", "course:cod_curso,nm_curso,ativo|school.person:idpes,nome",
                            "order", "cod_turma", "show", PAGINA, "page", pagina));
            for (JsonNode t : raiz.path("data")) {
                turmas.add(paraTurma(t));
            }
            if (!temProximaPagina(raiz)) {
                return turmas;
            }
        }
    }

    private Turma paraTurma(JsonNode t) {
        long cursoId = t.path("course").path("id").asLong(t.path("ref_cod_curso").asLong());
        String cursoNome = texto(t.path("course"), "name");
        Curso curso = new Curso(cursoId, cursoNome != null ? cursoNome : "Curso " + cursoId,
                t.path("course").path("ativo").asInt(1) == 1);
        long escolaId = t.path("school").path("id").asLong(t.path("ref_ref_cod_escola").asLong());
        String escolaNome = texto(t.path("school").path("person"), "name");
        return new Turma(t.path("cod_turma").asLong(), texto(t, "nm_turma"), t.path("ano").asInt(),
                t.path("ativo").asInt() == 1, escolaId, escolaNome, curso);
    }

    // ------------------------------------------------------------------------------ matrículas

    @Override
    public List<Matricula> listarMatriculas(int ano) {
        List<Matricula> matriculas = new ArrayList<>();
        for (int pagina = 1; ; pagina++) {
            JsonNode raiz = getRest("/api/registration?year_eq={ano}&show={show}&page={page}&include={include}",
                    Map.of("ano", ano, "show", PAGINA, "page", pagina,
                            "include", "student.individual:idpes,cpf|enrollments:ref_cod_matricula,ref_cod_turma,sequencial,ativo,data_enturmacao"));
            for (JsonNode r : raiz.path("data")) {
                Aluno aluno = new Aluno(r.path("student_id").asLong(), texto(r, "nome"), texto(r, "email"),
                        texto(r.path("student").path("individual"), "cpf"));
                boolean situacaoAtiva = !SITUACOES_ENCERRADAS.contains(r.path("aprovado").asInt());
                // O campo "ativo" da raiz NÃO é o da matrícula (docs/ieducar-api.md): vale enrollments[].ativo
                for (JsonNode e : r.path("enrollments")) {
                    matriculas.add(new Matricula(aluno, e.path("ref_cod_turma").asLong(),
                            situacaoAtiva && e.path("ativo").asInt() == 1));
                }
            }
            if (!temProximaPagina(raiz)) {
                return matriculas;
            }
        }
    }

    // ----------------------------------------------------------------------------- professores

    @Override
    public List<VinculoProfessor> listarVinculosProfessores(int ano, Collection<Long> escolaIds) {
        if (escolaIds.isEmpty()) {
            return List.of();
        }
        JsonNode raiz = getLegado(Map.of("resource", "servidores-disciplinas-turmas",
                "instituicao_id", props.instituicaoId(), "ano", ano,
                "escola", escolaIds.stream().map(String::valueOf).collect(Collectors.joining(","))));
        Map<Long, Professor> professores = new HashMap<>();
        List<VinculoProfessor> vinculos = new ArrayList<>();
        Set<Long> servidoresVistos = new LinkedHashSet<>();
        for (JsonNode v : raiz.path("vinculos")) {
            long servidorId = v.path("servidor_id").asLong();
            servidoresVistos.add(servidorId);
            Professor professor = professores.computeIfAbsent(servidorId, this::buscarProfessor);
            boolean ativo = v.path("deleted_at").isNull() || v.path("deleted_at").isMissingNode();
            vinculos.add(new VinculoProfessor(professor, v.path("turma_id").asLong(), ativo));
        }
        return vinculos;
    }

    private Professor buscarProfessor(long servidorId) {
        try {
            JsonNode r = getLegado(Map.of("resource", "dados-servidor", "servidor_id", servidorId)).path("result");
            return new Professor(servidorId, texto(r, "nome"), texto(r, "email"), texto(r, "cpf"));
        } catch (SistemaIndisponivelException e) {
            throw e;
        } catch (SistemaExternoException e) {
            log.warn("Não foi possível ler os dados do servidor {}: {}", servidorId, e.getMessage());
            return new Professor(servidorId, "Servidor " + servidorId, null, null);
        }
    }

    // -------------------------------------------------------------------------------- HTTP/JSON

    private JsonNode getRest(String template, Map<String, ?> variaveis) {
        try {
            String corpo = http.get().uri(template, variaveis)
                    .header("Authorization", "Bearer " + props.token())
                    .header("Accept", "application/json")
                    .retrieve().body(String.class);
            return json.readTree(corpo);
        } catch (RestClientException e) {
            throw new SistemaIndisponivelException(SISTEMA, e.getMessage(), e);
        } catch (JacksonException e) {
            throw new SistemaExternoException(SISTEMA, "Resposta inválida do iEducar: " + e.getOriginalMessage(), e);
        }
    }

    /** API legada: HTTP 200 mesmo em erro; o erro vem em {@code any_error_msg}. */
    private JsonNode getLegado(Map<String, ?> params) {
        try {
            Map<String, Object> todos = new HashMap<>(params);
            todos.put("access_key", props.accessKey());
            todos.put("secret_key", props.secretKey());
            StringBuilder uri = new StringBuilder("/module/Api/Servidor?oper=get");
            todos.keySet().forEach(k -> uri.append('&').append(k).append("={").append(k).append('}'));
            String corpo = http.get().uri(uri.toString(), todos)
                    .header("Accept", "application/json")
                    .retrieve().body(String.class);
            JsonNode raiz = json.readTree(corpo);
            if (raiz.path("any_error_msg").asBoolean(false)) {
                String msg = raiz.path("msgs").isEmpty() ? "erro desconhecido" : texto(raiz.path("msgs").get(0), "msg");
                throw new SistemaIndisponivelException(SISTEMA, "API legada recusou a chamada: " + msg);
            }
            return raiz;
        } catch (RestClientException e) {
            throw new SistemaIndisponivelException(SISTEMA, e.getMessage(), e);
        } catch (JacksonException e) {
            throw new SistemaExternoException(SISTEMA, "Resposta inválida do iEducar: " + e.getOriginalMessage(), e);
        }
    }

    private static boolean temProximaPagina(JsonNode raiz) {
        JsonNode proxima = raiz.path("links").path("next");
        if (!proxima.isMissingNode()) {
            return !proxima.isNull();
        }
        return raiz.path("meta").path("current_page").asInt(1) < raiz.path("meta").path("last_page").asInt(1);
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.path(campo);
        return valor.isMissingNode() || valor.isNull() ? null : valor.asString();
    }
}
