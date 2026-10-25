<?php
/**
 * Dados de demonstração para a integração i-Educar -> Moodle.
 *
 * Uso (dentro do container):  demo-seed base | demo-seed nova-turma
 *   base        instituição, escola, 2 cursos, 3 turmas, 8 alunos matriculados e 3 professores
 *   nova-turma  UMA turma nova ("Turma C") com 2 alunos e 1 professor (demonstração ao vivo)
 *
 * Usa as factories do próprio i-Educar (por isso a imagem mantém as dependências de desenvolvimento).
 * Os dados são determinísticos (nomes, e-mails e CPFs fixos) e o script é seguro para rodar mais de uma vez.
 */

use App\Models\Employee;
use App\Models\LegacyCourse;
use App\Models\LegacyGrade;
use App\Models\LegacyIndividual;
use App\Models\LegacyInstitution;
use App\Models\LegacySchool;
use App\Models\LegacySchoolClass;
use Database\Factories\EmployeeFactory;
use App\Models\LegacyDisciplineAcademicYear;
use Database\Factories\LegacyCourseFactory;
use Database\Factories\LegacyDisciplineAcademicYearFactory;
use Database\Factories\LegacyDisciplineFactory;
use Database\Factories\LegacySchoolClassTeacherDisciplineFactory;
use Database\Factories\LegacyGradeFactory;
use Database\Factories\LegacyIndividualFactory;
use Database\Factories\LegacyInstitutionFactory;
use Database\Factories\LegacyPeriodFactory;
use Database\Factories\LegacyRegistrationFactory;
use Database\Factories\LegacySchoolAcademicYearFactory;
use Database\Factories\LegacySchoolClassFactory;
use Database\Factories\LegacySchoolClassTeacherFactory;
use Database\Factories\LegacySchoolClassTypeFactory;
use Database\Factories\LegacySchoolCourseFactory;
use Database\Factories\LegacySchoolFactory;
use Database\Factories\LegacySchoolGradeFactory;
use Database\Factories\LegacyStudentFactory;
use Illuminate\Contracts\Console\Kernel;

require '/var/www/ieducar/vendor/autoload.php';
$app = require '/var/www/ieducar/bootstrap/app.php';
$app->make(Kernel::class)->bootstrap();

$mode = $argv[1] ?? 'base';
$year = (int) now()->year;

function out(string $m): void
{
    fwrite(STDOUT, "[demo-seed] $m\n");
}

/** Gera um CPF válido a partir de 9 dígitos-base (determinístico). */
function cpf(int $base): int
{
    $d = str_split(str_pad((string) $base, 9, '0', STR_PAD_LEFT));
    foreach ([10, 11] as $len) {
        $sum = 0;
        foreach ($d as $i => $n) {
            $sum += $n * ($len - $i);
        }
        $r = ($sum * 10) % 11;
        $d[] = $r === 10 ? 0 : $r;
    }

    return (int) implode('', $d);
}

/** Cria uma pessoa física com nome, e-mail e CPF definidos. */
function pessoa(string $nome, string $email, int $cpfBase, string $sexo, string $nascimento): LegacyIndividual
{
    $individual = LegacyIndividualFactory::new()->create([
        'sexo' => $sexo,
        'data_nasc' => $nascimento,
        'cpf' => cpf($cpfBase),
    ]);
    $individual->person->update(['nome' => $nome, 'email' => $email]);

    return $individual->refresh();
}

/** Estrutura base (instituição, escola, cursos, séries, ano letivo). Idempotente. */
function estruturaBase(int $year): array
{
    $institution = LegacyInstitution::query()->first()
        ?? LegacyInstitutionFactory::new()->create([
            'nm_instituicao' => 'Instituto Educacional Demonstração',
            'nm_responsavel' => 'Secretaria Municipal de Educação',
            'cidade' => 'Porto Alegre',
            'ref_sigla_uf' => 'RS',
            'bairro' => 'Centro',
            'logradouro' => 'Av. Ipiranga, 6681',
            'cep' => '90619900',
        ]);

    $school = LegacySchool::query()->where('sigla', 'EDC')->first()
        ?? LegacySchoolFactory::new()->withName('Escola de Tecnologia - Campus Central')->create([
            'ref_cod_instituicao' => $institution->getKey(),
            'sigla' => 'EDC',
        ]);

    $cursos = [];
    foreach ([
        ['Engenharia de Software', 'ES', 'Semestre'],
        ['Análise e Desenvolvimento de Sistemas', 'ADS', 'Semestre'],
    ] as [$nome, $sigla, $etapa]) {
        $course = LegacyCourse::query()->where('nm_curso', $nome)->first();
        if (!$course) {
            $course = LegacyCourseFactory::new()->withName($nome)->create([
                'sgl_curso' => $sigla,
                'ref_cod_instituicao' => $institution->getKey(),
                'qtd_etapas' => 8,
            ]);
            $grade = LegacyGradeFactory::new()->withEvaluationRule()->create([
                'ref_cod_curso' => $course->getKey(),
                'nm_serie' => '1º Semestre',
                'descricao' => '1º Semestre',
                'etapa_curso' => 1,
                'idade_inicial' => 17,
                'idade_final' => 99,
            ]);
            LegacySchoolCourseFactory::new()->create([
                'ref_cod_escola' => $school->getKey(),
                'ref_cod_curso' => $course->getKey(),
                'anos_letivos' => '{' . $year . '}',
            ]);
            LegacySchoolGradeFactory::new()->create([
                'ref_cod_escola' => $school->getKey(),
                'ref_cod_serie' => $grade->getKey(),
                'anos_letivos' => '{' . $year . '}',
            ]);
        }
        // cada série precisa de ao menos uma disciplina: a API legada de servidores só devolve
        // vínculos professor-turma que tenham disciplina (professor_turma_disciplina).
        $grade = LegacyGrade::query()->where('ref_cod_curso', $course->getKey())->first();
        if (!LegacyDisciplineAcademicYear::query()->where('ano_escolar_id', $grade->getKey())->exists()) {
            $disc = LegacyDisciplineFactory::new()->create([
                'name' => 'Fundamentos de ' . $nome, 'abbreviation' => 'FUND' . $sigla,
                'institution_id' => $institution->getKey(),
            ]);
            LegacyDisciplineAcademicYearFactory::new()->create([
                'componente_curricular_id' => $disc->getKey(),
                'ano_escolar_id' => $grade->getKey(),
                'anos_letivos' => '{' . $year . '}',
            ]);
        }
        $cursos[$sigla] = $course;
    }

    $schoolAcademicYear = App\Models\LegacySchoolAcademicYear::query()
        ->where('ref_cod_escola', $school->getKey())->where('ano', $year)->first();
    if (!$schoolAcademicYear) {
        LegacySchoolAcademicYearFactory::new()->withSchool($school)->create(['ano' => $year, 'andamento' => 1]);
    }

    return [$institution, $school, $cursos];
}

/** Cria a turma (se não existir) de um curso. */
function turma(LegacyInstitution $inst, LegacySchool $school, LegacyCourse $course, string $nome, string $sigla, int $year): LegacySchoolClass
{
    $existente = LegacySchoolClass::query()
        ->where('nm_turma', $nome)->where('ref_cod_curso', $course->getKey())->where('ano', $year)->first();
    if ($existente) {
        return $existente;
    }
    $grade = LegacyGrade::query()->where('ref_cod_curso', $course->getKey())->first();

    return LegacySchoolClassFactory::new()->morning()->create([
        'nm_turma' => $nome,
        'sgl_turma' => $sigla,
        'max_aluno' => 40,
        'ref_cod_instituicao' => $inst->getKey(),
        'ref_ref_cod_escola' => $school->getKey(),
        'ref_ref_cod_serie' => $grade->getKey(),
        'ref_cod_curso' => $course->getKey(),
        'ref_cod_turma_tipo' => LegacySchoolClassTypeFactory::new()->current()->getKey(),
        'turma_turno_id' => LegacyPeriodFactory::new()->morning()->getKey(),
        'ano' => $year,
    ]);
}

/** Cria o aluno e já o matricula e enturma na turma. */
function aluno(LegacySchoolClass $turma, string $nome, string $email, int $cpfBase, string $sexo, string $nasc, int $year): void
{
    $individual = pessoa($nome, $email, $cpfBase, $sexo, $nasc);
    $student = LegacyStudentFactory::new()->create(['ref_idpes' => $individual->getKey()]);
    LegacyRegistrationFactory::new()->withStudent($student)->withEnrollment($turma)->create(['ano' => $year]);
    out("aluno {$nome} matriculado em {$turma->nm_turma} (curso {$turma->course->nm_curso})");
}

/** Cria o servidor (professor) e o vincula à turma. */
function professor(LegacyInstitution $inst, LegacySchoolClass $turma, string $nome, string $email, int $cpfBase, string $nasc, int $year): void
{
    $individual = pessoa($nome, $email, $cpfBase, 'M', $nasc);
    $employee = EmployeeFactory::new()->withTeacherFunction()->create([
        'id' => $individual->getKey(),
        'institution_id' => $inst->getKey(),
    ]);
    $vinculo = LegacySchoolClassTeacherFactory::new()->create([
        'ano' => $year,
        'instituicao_id' => $inst->getKey(),
        'turma_id' => $turma->getKey(),
        'servidor_id' => $employee->getKey(),
        'turno_id' => $turma->turma_turno_id,
    ]);
    $componente = LegacyDisciplineAcademicYear::query()->where('ano_escolar_id', $turma->ref_ref_cod_serie)->first();
    LegacySchoolClassTeacherDisciplineFactory::new()->create([
        'professor_turma_id' => $vinculo->getKey(),
        'componente_curricular_id' => $componente->componente_curricular_id,
    ]);
    out("professor {$nome} vinculado a {$turma->nm_turma} (curso {$turma->course->nm_curso})");
}

DB::beginTransaction();
try {
    [$inst, $school, $cursos] = estruturaBase($year);
    $es = $cursos['ES'];
    $ads = $cursos['ADS'];

    if ($mode === 'base') {
        if (LegacySchoolClass::query()->where('nm_turma', 'Turma A')->where('ref_cod_curso', $es->getKey())->exists()) {
            out('dados base já existem; nada a fazer.');
        } else {
            $esA = turma($inst, $school, $es, 'Turma A', 'A', $year);
            $esB = turma($inst, $school, $es, 'Turma B', 'B', $year);
            $adsA = turma($inst, $school, $ads, 'Turma A', 'A', $year);

            aluno($esA, 'Ana Beatriz Souza', 'ana.souza@aluno.demo.edu.br', 100000001, 'F', '2006-03-14', $year);
            aluno($esA, 'Bruno Henrique Lima', 'bruno.lima@aluno.demo.edu.br', 100000002, 'M', '2005-11-02', $year);
            aluno($esA, 'Carla Mendes Ferreira', 'carla.ferreira@aluno.demo.edu.br', 100000003, 'F', '2006-07-21', $year);
            aluno($esB, 'Diego Almeida Costa', 'diego.costa@aluno.demo.edu.br', 100000004, 'M', '2005-05-09', $year);
            aluno($esB, 'Eduarda Pereira Rocha', 'eduarda.rocha@aluno.demo.edu.br', 100000005, 'F', '2006-01-30', $year);
            aluno($adsA, 'Felipe Santos Oliveira', 'felipe.oliveira@aluno.demo.edu.br', 100000006, 'M', '2004-09-17', $year);
            aluno($adsA, 'Gabriela Martins Duarte', 'gabriela.duarte@aluno.demo.edu.br', 100000007, 'F', '2005-12-05', $year);
            aluno($adsA, 'Henrique Barbosa Teixeira', 'henrique.teixeira@aluno.demo.edu.br', 100000008, 'M', '2004-04-26', $year);

            professor($inst, $esA, 'Prof. Marcos Vinícius Ribeiro', 'marcos.ribeiro@prof.demo.edu.br', 200000001, '1980-02-10', $year);
            professor($inst, $esB, 'Profa. Juliana Carvalho Nunes', 'juliana.nunes@prof.demo.edu.br', 200000002, '1985-08-23', $year);
            professor($inst, $adsA, 'Prof. Ricardo Alves Moreira', 'ricardo.moreira@prof.demo.edu.br', 200000003, '1978-06-15', $year);
        }
    } elseif ($mode === 'nova-turma') {
        if (LegacySchoolClass::query()->where('nm_turma', 'Turma C')->where('ref_cod_curso', $ads->getKey())->exists()) {
            out('a "Turma C" já foi criada; nada a fazer.');
        } else {
            $adsC = turma($inst, $school, $ads, 'Turma C', 'C', $year);
            aluno($adsC, 'Isabela Rodrigues Campos', 'isabela.campos@aluno.demo.edu.br', 100000009, 'F', '2006-02-11', $year);
            aluno($adsC, 'João Pedro Azevedo', 'joao.azevedo@aluno.demo.edu.br', 100000010, 'M', '2005-10-29', $year);
            professor($inst, $adsC, 'Profa. Patrícia Gomes Lopes', 'patricia.lopes@prof.demo.edu.br', 200000004, '1982-12-01', $year);
        }
    } else {
        fwrite(STDERR, "modo inválido: use 'base' ou 'nova-turma'\n");
        DB::rollBack();
        exit(2);
    }
    DB::commit();
    out('concluído.');
} catch (Throwable $e) {
    DB::rollBack();
    fwrite(STDERR, '[demo-seed] ERRO: ' . $e->getMessage() . "\n" . $e->getTraceAsString() . "\n");
    exit(1);
}
