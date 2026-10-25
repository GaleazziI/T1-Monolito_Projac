package com.pucrs.integracao.application;

import com.pucrs.integracao.domain.exception.SincronizacaoEmAndamentoException;
import com.pucrs.integracao.domain.exception.SistemaIndisponivelException;
import com.pucrs.integracao.domain.model.Aluno;
import com.pucrs.integracao.domain.model.Curso;
import com.pucrs.integracao.domain.model.Matricula;
import com.pucrs.integracao.domain.model.OrigemSincronizacao;
import com.pucrs.integracao.domain.model.Professor;
import com.pucrs.integracao.domain.model.RelatorioSincronizacao;
import com.pucrs.integracao.domain.model.RelatorioSincronizacao.Status;
import com.pucrs.integracao.domain.model.Turma;
import com.pucrs.integracao.domain.model.VinculoProfessor;
import com.pucrs.integracao.domain.port.out.IEducarPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SincronizacaoServiceTest {

    static final Curso ES = new Curso(2, "Engenharia de Software", true);
    static final Curso ADS = new Curso(3, "Análise e Desenvolvimento de Sistemas", true);

    /** iEducar em memória: o teste muda as listas para simular alterações na origem. */
    static class FakeIEducar implements IEducarPort {
        List<Turma> turmas = new ArrayList<>();
        List<Matricula> matriculas = new ArrayList<>();
        List<VinculoProfessor> vinculos = new ArrayList<>();
        boolean fora;

        public List<Turma> listarTurmas() {
            if (fora) throw new SistemaIndisponivelException("iEducar", "timeout");
            return turmas;
        }
        public List<Matricula> listarMatriculas(int ano) { return matriculas; }
        public List<VinculoProfessor> listarVinculosProfessores(int ano, Collection<Long> escolas) { return vinculos; }
    }

    FakeIEducar ieducar;
    FakeMoodle moodle;
    SincronizacaoService service;

    Aluno ana = new Aluno(3, "Ana Beatriz Souza", "ana@aluno.edu.br", "1");
    Aluno bruno = new Aluno(4, "Bruno Henrique Lima", "bruno@aluno.edu.br", "2");
    Professor marcos = new Professor(13, "Prof. Marcos Vinícius Ribeiro", "marcos@prof.edu.br", "3");

    @BeforeEach
    void preparar() {
        ieducar = new FakeIEducar();
        moodle = new FakeMoodle();
        service = new SincronizacaoService(ieducar, moodle, new OpcoesSincronizacao(5, 3, "Mudar@2026!", "exemplo.edu.br"));
        ieducar.turmas.add(new Turma(3, "Turma A", 2026, true, 2, "Escola", ES));
        ieducar.turmas.add(new Turma(5, "Turma A", 2026, true, 2, "Escola", ADS));
        ieducar.matriculas.add(new Matricula(ana, 3, true));
        ieducar.matriculas.add(new Matricula(bruno, 3, true));
        ieducar.vinculos.add(new VinculoProfessor(marcos, 3, true));
    }

    @Test
    void turmaViraCursoComCategoriaEPessoasSaoInscritasComOPapelCerto() {
        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.MANUAL);

        assertThat(r.status()).isEqualTo(Status.SUCESSO);
        assertThat(r.cursosCriados()).isEqualTo(2);
        assertThat(r.usuariosCriados()).isEqualTo(3);
        assertThat(r.inscricoesCriadas()).isEqualTo(3);
        assertThat(moodle.categorias).containsOnlyKeys("IEDUCAR-CURSO-2", "IEDUCAR-CURSO-3");
        assertThat(moodle.cursos.get("IEDUCAR-TURMA-3").nomeCompleto()).isEqualTo("Engenharia de Software — Turma A");
        long curso = moodle.cursos.get("IEDUCAR-TURMA-3").id();
        long idAna = moodle.usuarios.get("IEDUCAR-ALUNO-3").id();
        long idMarcos = moodle.usuarios.get("IEDUCAR-SERVIDOR-13").id();
        assertThat(moodle.papeis.get(curso + ":" + idAna)).isEqualTo(5L);
        assertThat(moodle.papeis.get(curso + ":" + idMarcos)).isEqualTo(3L);
        assertThat(moodle.usernamePorIdNumber).containsEntry("IEDUCAR-ALUNO-3", "aluno3")
                .containsEntry("IEDUCAR-SERVIDOR-13", "prof13");
        assertThat(moodle.usuarios.get("IEDUCAR-ALUNO-3").sobrenome()).isEqualTo("Beatriz Souza");
        assertThat(service.ultimoRelatorio()).contains(r);
    }

    @Test
    void repetirASincronizacaoNaoDuplicaNada() {
        service.sincronizar(OrigemSincronizacao.MANUAL);
        int criacoes = moodle.chamadasCriar;

        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.AGENDADA);

        assertThat(moodle.chamadasCriar).isEqualTo(criacoes);
        assertThat(r.cursosCriados() + r.usuariosCriados() + r.inscricoesCriadas() + r.cursosAtualizados()
                + r.usuariosAtualizados() + r.inscricoesSuspensas()).isZero();
        assertThat(r.status()).isEqualTo(Status.SUCESSO);
    }

    @Test
    void novaTurmaEAlunoNovoApareceNaProximaExecucao() {
        service.sincronizar(OrigemSincronizacao.MANUAL);
        ieducar.turmas.add(new Turma(7, "Turma C", 2026, true, 2, "Escola", ES));
        ieducar.matriculas.add(new Matricula(new Aluno(20, "Davi Costa", null, null), 7, true));

        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.AGENDADA);

        assertThat(r.cursosCriados()).isEqualTo(1);
        assertThat(r.usuariosCriados()).isEqualTo(1);
        assertThat(r.inscricoesCriadas()).isEqualTo(1);
        assertThat(moodle.usuarios.get("IEDUCAR-ALUNO-20").email()).isEqualTo("aluno20@exemplo.edu.br");
    }

    @Test
    void matriculaCancelada_suspendeAInscricaoSemApagarOUsuario() {
        service.sincronizar(OrigemSincronizacao.MANUAL);
        ieducar.matriculas.set(1, new Matricula(bruno, 3, false));

        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.AGENDADA);

        long curso = moodle.cursos.get("IEDUCAR-TURMA-3").id();
        long idBruno = moodle.usuarios.get("IEDUCAR-ALUNO-4").id();
        assertThat(r.inscricoesSuspensas()).isEqualTo(1);
        assertThat(moodle.inscricoes.get(curso).get(idBruno)).isTrue();
        assertThat(moodle.usuarios).containsKey("IEDUCAR-ALUNO-4");

        // e se a matrícula voltar, a inscrição é reativada
        ieducar.matriculas.set(1, new Matricula(bruno, 3, true));
        assertThat(service.sincronizar(OrigemSincronizacao.AGENDADA).inscricoesCriadas()).isEqualTo(1);
        assertThat(moodle.inscricoes.get(curso).get(idBruno)).isFalse();
    }

    @Test
    void vinculoDeProfessorRemovido_suspendeOProfessor() {
        service.sincronizar(OrigemSincronizacao.MANUAL);
        ieducar.vinculos.set(0, new VinculoProfessor(marcos, 3, false));

        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.AGENDADA);

        assertThat(r.inscricoesSuspensas()).isEqualTo(1);
    }

    @Test
    void turmaInativa_ocultaOCursoENaoCriaTurmaInativaNova() {
        service.sincronizar(OrigemSincronizacao.MANUAL);
        Turma desativada = new Turma(3, "Turma A", 2026, false, 2, "Escola", ES);
        ieducar.turmas.set(0, desativada);
        ieducar.turmas.add(new Turma(9, "Turma Z", 2026, false, 2, "Escola", ES));

        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.AGENDADA);

        assertThat(r.cursosOcultados()).isEqualTo(1);
        assertThat(moodle.cursos.get("IEDUCAR-TURMA-3").visivel()).isFalse();
        assertThat(moodle.cursos).doesNotContainKey("IEDUCAR-TURMA-9");
    }

    @Test
    void turmaRenomeada_atualizaONomeDoCurso() {
        service.sincronizar(OrigemSincronizacao.MANUAL);
        ieducar.turmas.set(0, new Turma(3, "Turma Alfa", 2026, true, 2, "Escola", ES));

        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.AGENDADA);

        assertThat(r.cursosAtualizados()).isEqualTo(1);
        assertThat(moodle.cursos.get("IEDUCAR-TURMA-3").nomeCompleto()).isEqualTo("Engenharia de Software — Turma Alfa");
    }

    @Test
    void emailJaUsadoNoMoodle_usaEmailGeradoEContinua() {
        moodle.emailsUsados.add("ana@aluno.edu.br");

        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.MANUAL);

        assertThat(r.status()).isEqualTo(Status.SUCESSO);
        assertThat(moodle.usuarios.get("IEDUCAR-ALUNO-3").email()).isEqualTo("aluno3@exemplo.edu.br");
    }

    @Test
    void erroEmUmItemNaoImpedeOsDemais() {
        // dois alunos com o mesmo e-mail gerado causam conflito no 2º; os outros itens seguem
        moodle.emailsUsados.add("aluno4@exemplo.edu.br");
        ieducar.matriculas.set(1, new Matricula(new Aluno(4, "Sem Email", null, null), 3, true));

        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.MANUAL);

        assertThat(r.status()).isEqualTo(Status.PARCIAL);
        assertThat(r.erros()).hasSize(1);
        assertThat(r.usuariosCriados()).isEqualTo(2); // Ana e Marcos
    }

    @Test
    void ieducarForaDoAr_registraFalhaENadaEAlteradoNoMoodle() {
        ieducar.fora = true;

        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.AGENDADA);

        assertThat(r.status()).isEqualTo(Status.FALHA);
        assertThat(r.erros()).singleElement().asString().contains("iEducar indisponível");
        assertThat(moodle.cursos).isEmpty();

        ieducar.fora = false; // volta: a próxima execução se recupera sozinha
        assertThat(service.sincronizar(OrigemSincronizacao.AGENDADA).status()).isEqualTo(Status.SUCESSO);
    }

    @Test
    void moodleForaDoAr_registraFalha() {
        moodle.fora = true;

        RelatorioSincronizacao r = service.sincronizar(OrigemSincronizacao.AGENDADA);

        assertThat(r.status()).isEqualTo(Status.FALHA);
        assertThat(r.erros().get(0)).contains("Moodle indisponível");
    }

    @Test
    void duasSincronizacoesSimultaneas_aSegundaEhRecusada() throws Exception {
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        ieducar = new FakeIEducar() {
            @Override public List<Turma> listarTurmas() {
                dentro.countDown();
                try { liberar.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                return super.listarTurmas();
            }
        };
        service = new SincronizacaoService(ieducar, moodle, new OpcoesSincronizacao(5, 3, "x", "exemplo.edu.br"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RelatorioSincronizacao> primeira = pool.submit(() -> service.sincronizar(OrigemSincronizacao.AGENDADA));
            assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.sincronizar(OrigemSincronizacao.MANUAL))
                    .isInstanceOf(SincronizacaoEmAndamentoException.class);

            liberar.countDown();
            assertThat(primeira.get(5, TimeUnit.SECONDS).status()).isEqualTo(Status.SUCESSO);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dividirNome() {
        assertThat(SincronizacaoService.dividirNome("  Maria   da Silva ")).containsExactly("Maria", "da Silva");
        assertThat(SincronizacaoService.dividirNome("Madonna")).containsExactly("Madonna", "-");
        assertThat(SincronizacaoService.dividirNome(null)).containsExactly("(sem nome)", "-");
    }
}
