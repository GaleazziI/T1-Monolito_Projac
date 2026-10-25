package com.pucrs.integracao.application;

import com.pucrs.integracao.domain.exception.MoodleException;
import com.pucrs.integracao.domain.exception.SincronizacaoEmAndamentoException;
import com.pucrs.integracao.domain.exception.SistemaIndisponivelException;
import com.pucrs.integracao.domain.model.CursoMoodle;
import com.pucrs.integracao.domain.model.Matricula;
import com.pucrs.integracao.domain.model.NovoUsuarioMoodle;
import com.pucrs.integracao.domain.model.OrigemSincronizacao;
import com.pucrs.integracao.domain.model.RelatorioSincronizacao;
import com.pucrs.integracao.domain.model.RelatorioSincronizacao.Status;
import com.pucrs.integracao.domain.model.Turma;
import com.pucrs.integracao.domain.model.UsuarioMoodle;
import com.pucrs.integracao.domain.model.VinculoProfessor;
import com.pucrs.integracao.domain.port.in.SincronizacaoUseCase;
import com.pucrs.integracao.domain.port.out.IEducarPort;
import com.pucrs.integracao.domain.port.out.MoodlePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Regras da sincronização iEducar → Moodle (unidirecional, idempotente):
 * <ol>
 *   <li>cada <b>curso</b> do iEducar vira uma <b>categoria</b> no Moodle;</li>
 *   <li>cada <b>turma</b> vira um <b>curso</b> dentro da categoria do seu curso;</li>
 *   <li>cada <b>aluno</b> com enturmação ativa vira usuário inscrito como estudante;</li>
 *   <li>cada <b>professor</b> vinculado à turma vira usuário inscrito como professor.</li>
 * </ol>
 * A identidade dos objetos no Moodle é o {@code idnumber} ({@code IEDUCAR-TURMA-<id>} etc.), então repetir a
 * sincronização nunca duplica nada. Remoções no iEducar nunca apagam dados no Moodle: turma inativa oculta o
 * curso; matrícula cancelada ou vínculo removido suspende a inscrição.
 */
@Service
public class SincronizacaoService implements SincronizacaoUseCase {

    static final String PREFIXO_CURSO = "IEDUCAR-CURSO-";
    static final String PREFIXO_TURMA = "IEDUCAR-TURMA-";
    static final String PREFIXO_ALUNO = "IEDUCAR-ALUNO-";
    static final String PREFIXO_SERVIDOR = "IEDUCAR-SERVIDOR-";

    private static final Logger log = LoggerFactory.getLogger(SincronizacaoService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final IEducarPort ieducar;
    private final MoodlePort moodle;
    private final OpcoesSincronizacao opcoes;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<RelatorioSincronizacao> ultimo = new AtomicReference<>();

    public SincronizacaoService(IEducarPort ieducar, MoodlePort moodle, OpcoesSincronizacao opcoes) {
        this.ieducar = ieducar;
        this.moodle = moodle;
        this.opcoes = opcoes;
    }

    @Override
    public Optional<RelatorioSincronizacao> ultimoRelatorio() {
        return Optional.ofNullable(ultimo.get());
    }

    @Override
    public RelatorioSincronizacao sincronizar(OrigemSincronizacao origem) {
        if (!lock.tryLock()) {
            throw new SincronizacaoEmAndamentoException();
        }
        try {
            Execucao ex = new Execucao(origem);
            log.info("Sincronização {} iniciada", origem);
            try {
                executar(ex);
            } catch (SistemaIndisponivelException e) {
                log.warn("Sincronização abortada: {}", e.getMessage());
                ex.falhaGeral = true;
                ex.erros.add(e.getMessage() + " (nova tentativa na próxima execução)");
            } catch (RuntimeException e) {
                log.error("Erro inesperado na sincronização", e);
                ex.falhaGeral = true;
                ex.erros.add("Erro inesperado: " + e.getMessage());
            }
            RelatorioSincronizacao relatorio = ex.concluir();
            ultimo.set(relatorio);
            log.info("Sincronização {} concluída: {}", origem, relatorio);
            return relatorio;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------------

    private void executar(Execucao ex) {
        List<Turma> turmas = ieducar.listarTurmas();
        ex.turmasLidas = turmas.size();

        // 1) turmas -> cursos (e cursos -> categorias)
        Map<Long, Long> cursoMoodlePorTurma = new HashMap<>();
        Map<Long, Long> categoriaPorCurso = new HashMap<>();
        for (Turma turma : turmas) {
            try {
                Long cursoMoodleId = sincronizarCurso(turma, categoriaPorCurso, ex);
                if (cursoMoodleId != null && turma.ativa()) {
                    cursoMoodlePorTurma.put(turma.id(), cursoMoodleId);
                }
            } catch (SistemaIndisponivelException e) {
                throw e;
            } catch (RuntimeException e) {
                ex.erro("Turma " + turma.id() + " (" + turma.nome() + ")", e);
            }
        }

        // 2) participantes (alunos e professores) de cada turma ativa
        Map<Long, Map<String, Participante>> participantes = new LinkedHashMap<>();
        Map<Integer, Set<Long>> escolasPorAno = new HashMap<>();
        for (Turma turma : turmas) {
            if (cursoMoodlePorTurma.containsKey(turma.id())) {
                escolasPorAno.computeIfAbsent(turma.ano(), a -> new TreeSet<>()).add(turma.escolaId());
            }
        }
        for (var entrada : escolasPorAno.entrySet()) {
            int ano = entrada.getKey();
            for (Matricula m : ieducar.listarMatriculas(ano)) {
                if (cursoMoodlePorTurma.containsKey(m.turmaId())) {
                    adicionar(participantes, m.turmaId(), new Participante(false, m.aluno().id(), m.aluno().nome(),
                            m.aluno().email(), m.ativa()));
                }
            }
            for (VinculoProfessor v : ieducar.listarVinculosProfessores(ano, entrada.getValue())) {
                if (cursoMoodlePorTurma.containsKey(v.turmaId())) {
                    adicionar(participantes, v.turmaId(), new Participante(true, v.professor().id(),
                            v.professor().nome(), v.professor().email(), v.ativo()));
                }
            }
        }

        // 3) usuários + inscrições
        Map<String, UsuarioMoodle> usuarios = new HashMap<>();
        for (var entrada : participantes.entrySet()) {
            long turmaId = entrada.getKey();
            long cursoMoodleId = cursoMoodlePorTurma.get(turmaId);
            try {
                Set<Long> ativos = moodle.listarInscritosAtivos(cursoMoodleId);
                for (Participante p : entrada.getValue().values()) {
                    try {
                        sincronizarParticipante(p, cursoMoodleId, ativos, usuarios, ex);
                    } catch (SistemaIndisponivelException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        ex.erro("Turma " + turmaId + " / " + p.rotulo(), e);
                    }
                }
            } catch (SistemaIndisponivelException e) {
                throw e;
            } catch (RuntimeException e) {
                ex.erro("Turma " + turmaId + " (inscrições)", e);
            }
        }
    }

    private Long sincronizarCurso(Turma turma, Map<Long, Long> categoriaPorCurso, Execucao ex) {
        String idNumber = PREFIXO_TURMA + turma.id();
        String nome = turma.nomeNoMoodle();
        Optional<CursoMoodle> existente = moodle.buscarCurso(idNumber);

        if (existente.isPresent()) {
            CursoMoodle curso = existente.get();
            boolean mudouNome = !nome.equals(curso.nomeCompleto());
            boolean mudouVisibilidade = curso.visivel() != turma.ativa();
            if (mudouNome || mudouVisibilidade) {
                moodle.atualizarCurso(curso.id(), nome, turma.ativa());
                if (mudouVisibilidade && !turma.ativa()) {
                    ex.cursosOcultados++;
                } else {
                    ex.cursosAtualizados++;
                }
            }
            return curso.id();
        }
        if (!turma.ativa()) {
            return null; // turma inativa que nunca foi para o Moodle: nada a fazer
        }
        long categoriaId = garantirCategoria(turma, categoriaPorCurso);
        long id = moodle.criarCurso(nome, "TURMA-" + turma.id(), idNumber, categoriaId, true);
        ex.cursosCriados++;
        return id;
    }

    private long garantirCategoria(Turma turma, Map<Long, Long> cache) {
        Long cached = cache.get(turma.curso().id());
        if (cached != null) {
            return cached;
        }
        String idNumber = PREFIXO_CURSO + turma.curso().id();
        long id = moodle.buscarCategoria(idNumber)
                .orElseGet(() -> moodle.criarCategoria(turma.curso().nome(), idNumber));
        cache.put(turma.curso().id(), id);
        return id;
    }

    private void sincronizarParticipante(Participante p, long cursoMoodleId, Set<Long> ativos,
                                         Map<String, UsuarioMoodle> usuarios, Execucao ex) {
        String idNumber = (p.professor() ? PREFIXO_SERVIDOR : PREFIXO_ALUNO) + p.id();
        UsuarioMoodle usuario = usuarios.get(idNumber);
        if (usuario == null) {
            usuario = moodle.buscarUsuario(idNumber).orElse(null);
            if (usuario != null) {
                usuario = atualizarSeNecessario(usuario, p, ex);
            } else if (p.ativa()) {
                usuario = criarUsuario(p, idNumber);
                ex.usuariosCriados++;
            } else {
                return; // saiu do iEducar antes de existir no Moodle: nada a fazer
            }
            usuarios.put(idNumber, usuario);
        }

        long papel = p.professor() ? opcoes.papelProfessorId() : opcoes.papelAlunoId();
        boolean inscritoAtivo = ativos.contains(usuario.id());
        if (p.ativa() && !inscritoAtivo) {
            moodle.inscrever(cursoMoodleId, usuario.id(), papel, false);
            ex.inscricoesCriadas++;
        } else if (!p.ativa() && inscritoAtivo) {
            moodle.inscrever(cursoMoodleId, usuario.id(), papel, true);
            ex.inscricoesSuspensas++;
        }
    }

    private UsuarioMoodle criarUsuario(Participante p, String idNumber) {
        String username = (p.professor() ? "prof" : "aluno") + p.id();
        String[] nome = dividirNome(p.nome());
        String emailReal = emailValido(p.email()) ? p.email().trim() : null;
        String emailPadrao = username + "@" + opcoes.dominioEmail();
        try {
            long id = moodle.criarUsuario(new NovoUsuarioMoodle(username, opcoes.senhaInicial(), nome[0], nome[1],
                    emailReal != null ? emailReal : emailPadrao, idNumber));
            return new UsuarioMoodle(id, nome[0], nome[1], emailReal != null ? emailReal : emailPadrao);
        } catch (MoodleException e) {
            if (emailReal == null) {
                throw e;
            }
            // e-mail já usado por outra conta no Moodle: cria com e-mail gerado em vez de falhar
            log.warn("Moodle recusou o e-mail {} para {}; usando {}", emailReal, username, emailPadrao);
            long id = moodle.criarUsuario(new NovoUsuarioMoodle(username, opcoes.senhaInicial(), nome[0], nome[1],
                    emailPadrao, idNumber));
            return new UsuarioMoodle(id, nome[0], nome[1], emailPadrao);
        }
    }

    private UsuarioMoodle atualizarSeNecessario(UsuarioMoodle atual, Participante p, Execucao ex) {
        String[] nome = dividirNome(p.nome());
        boolean mudouNome = !nome[0].equals(atual.primeiroNome()) || !nome[1].equals(atual.sobrenome());
        String emailNovo = emailValido(p.email()) ? p.email().trim() : null;
        boolean mudouEmail = emailNovo != null && !emailNovo.equalsIgnoreCase(atual.email());
        if (!mudouNome && !mudouEmail) {
            return atual;
        }
        try {
            moodle.atualizarUsuario(atual.id(), nome[0], nome[1], mudouEmail ? emailNovo : null);
            ex.usuariosAtualizados++;
            return new UsuarioMoodle(atual.id(), nome[0], nome[1], mudouEmail ? emailNovo : atual.email());
        } catch (MoodleException e) {
            ex.erro((p.professor() ? "Professor " : "Aluno ") + p.id() + " (atualização)", e);
            return atual;
        }
    }

    private static void adicionar(Map<Long, Map<String, Participante>> mapa, long turmaId, Participante novo) {
        // se houver mais de uma enturmação do mesmo aluno/professor na turma, vale a ativa
        mapa.computeIfAbsent(turmaId, t -> new LinkedHashMap<>())
                .merge(novo.chave(), novo, (a, b) -> a.ativa() ? a : b);
    }

    static String[] dividirNome(String nomeCompleto) {
        String nome = nomeCompleto == null ? "" : nomeCompleto.trim().replaceAll("\\s+", " ");
        if (nome.isEmpty()) {
            return new String[]{"(sem nome)", "-"};
        }
        int espaco = nome.indexOf(' ');
        return espaco < 0 ? new String[]{nome, "-"} : new String[]{nome.substring(0, espaco), nome.substring(espaco + 1)};
    }

    private static boolean emailValido(String email) {
        return email != null && EMAIL.matcher(email.trim()).matches();
    }

    private record Participante(boolean professor, long id, String nome, String email, boolean ativa) {
        String chave() {
            return (professor ? "P" : "A") + id;
        }

        String rotulo() {
            return (professor ? "Professor " : "Aluno ") + id + " (" + nome + ")";
        }
    }

    /** Contadores mutáveis de uma execução. */
    private static final class Execucao {
        final OrigemSincronizacao origem;
        final Instant inicio = Instant.now();
        final List<String> erros = new ArrayList<>();
        boolean falhaGeral;
        int turmasLidas, cursosCriados, cursosAtualizados, cursosOcultados;
        int usuariosCriados, usuariosAtualizados, inscricoesCriadas, inscricoesSuspensas;

        Execucao(OrigemSincronizacao origem) {
            this.origem = origem;
        }

        void erro(String contexto, RuntimeException e) {
            log.warn("{}: {}", contexto, e.getMessage());
            erros.add(contexto + ": " + e.getMessage());
        }

        RelatorioSincronizacao concluir() {
            Status status = falhaGeral ? Status.FALHA : erros.isEmpty() ? Status.SUCESSO : Status.PARCIAL;
            return new RelatorioSincronizacao(origem, status, inicio, Instant.now(), turmasLidas, cursosCriados,
                    cursosAtualizados, cursosOcultados, usuariosCriados, usuariosAtualizados, inscricoesCriadas,
                    inscricoesSuspensas, List.copyOf(new LinkedHashSet<>(erros)));
        }
    }
}
