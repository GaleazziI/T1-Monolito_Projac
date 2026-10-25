package com.pucrs.integracao.domain.port.out;

import com.pucrs.integracao.domain.model.CursoMoodle;
import com.pucrs.integracao.domain.model.NovoUsuarioMoodle;
import com.pucrs.integracao.domain.model.UsuarioMoodle;

import java.util.Optional;
import java.util.Set;

/** Destino dos dados (Moodle Web Services). Todas as buscas usam o idnumber como chave. */
public interface MoodlePort {

    Optional<Long> buscarCategoria(String idNumber);

    long criarCategoria(String nome, String idNumber);

    Optional<CursoMoodle> buscarCurso(String idNumber);

    long criarCurso(String nomeCompleto, String nomeCurto, String idNumber, long categoriaId, boolean visivel);

    void atualizarCurso(long cursoId, String nomeCompleto, boolean visivel);

    Optional<UsuarioMoodle> buscarUsuario(String idNumber);

    long criarUsuario(NovoUsuarioMoodle usuario);

    void atualizarUsuario(long usuarioId, String primeiroNome, String sobrenome, String email);

    /** Ids dos usuários com inscrição ATIVA (não suspensa) no curso. */
    Set<Long> listarInscritosAtivos(long cursoId);

    /** Inscreve (ou reativa) o usuário; {@code suspenso=true} suspende a inscrição existente. */
    void inscrever(long cursoId, long usuarioId, long papelId, boolean suspenso);
}
