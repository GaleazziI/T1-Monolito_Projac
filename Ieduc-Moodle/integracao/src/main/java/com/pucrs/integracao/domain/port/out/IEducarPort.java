package com.pucrs.integracao.domain.port.out;

import com.pucrs.integracao.domain.model.Matricula;
import com.pucrs.integracao.domain.model.Turma;
import com.pucrs.integracao.domain.model.VinculoProfessor;

import java.util.Collection;
import java.util.List;

/** Fonte dos dados (iEducar). */
public interface IEducarPort {

    List<Turma> listarTurmas();

    List<Matricula> listarMatriculas(int ano);

    List<VinculoProfessor> listarVinculosProfessores(int ano, Collection<Long> escolaIds);
}
