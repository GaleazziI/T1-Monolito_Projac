# T1 – Monolito: integração iEducar ⇄ Moodle

Aplicação monolítica em **Java (Spring Boot)** que sincroniza dados do **iEducar** (gestão escolar) com o **Moodle** (ambiente virtual de aprendizagem). Todo o ambiente sobe com **Docker Compose**.

| O que acontece no iEducar | O que a integração faz no Moodle |
|---|---|
| Existe uma **turma** (ex.: "Engenharia de Software — Turma A") | Cria o **curso** correspondente (dentro da categoria do curso do iEducar) |
| Um **aluno** é matriculado na turma | Cria o usuário (se não existir) e o **inscreve** como estudante |
| Um **professor** é vinculado à turma | Cria o usuário (se não existir) e o **inscreve** como professor |
| Turma inativa / matrícula cancelada / vínculo removido | **Oculta** o curso / **suspende** a inscrição (nada é apagado) |

A integração é unidirecional (iEducar → Moodle) e **idempotente**: pode rodar quantas vezes quiser sem duplicar nada.

## Arquitetura

```
 iEducar (nginx+PHP-FPM, PostgreSQL, Redis)  <──REST /api + API legada──  integracao (Spring Boot)  ──Web Services REST──>  Moodle (Apache+PHP, PostgreSQL)
```

Diagramas em [`docs/diagramas/`](docs/diagramas) (arquitetura e fluxo). A aplicação segue arquitetura hexagonal (`domain` / `application` / `adapter`) em `Ieduc-Moodle/integracao`.

## Pré-requisitos

- Docker com Docker Compose v2 (Docker Desktop no Windows/macOS)
- ~8 GB de RAM livres para o Docker e ~15 GB de disco
- Acesso à internet na primeira execução (baixa imagens e dependências)

## Como executar

```bash
# 1) (opcional) personalizar portas, tokens e senhas
cp .env.example .env

# 2) construir e subir tudo
docker compose up -d --build
```

Tempos aproximados: o **build** do iEducar leva alguns minutos (baixa as dependências PHP); o **primeiro boot** leva ~2 min no iEducar e ~5 min no Moodle (instalação). O serviço `integracao` só inicia quando os dois estiverem saudáveis. Acompanhe com:

```bash
docker compose ps          # todos devem ficar "healthy"
docker compose logs -f integracao
```

| Sistema | URL | Acesso |
|---|---|---|
| iEducar | http://localhost:8080 | `admin` / `123456789` |
| Moodle | http://localhost:8081 | `admin` / `Admin@12345` |
| Integração (painel e API) | http://localhost:8090 | — |

### Criar os dados de exemplo no iEducar

O iEducar vem vazio. O comando abaixo cria 1 escola, 2 cursos, 3 turmas, 8 alunos matriculados e 3 professores (nomes fixos, em português):

```bash
docker compose exec ieducar demo-seed base
```

### Sincronizar

- **Automático:** a cada 2 minutos (`SINCRONIZACAO_INTERVALO=PT2M`).
- **Manual:** abra http://localhost:8090 e clique em **Sincronizar agora**, ou:

```bash
curl -X POST http://localhost:8090/api/sincronizacao     # executa e devolve o relatório
curl http://localhost:8090/api/sincronizacao/ultima      # último relatório
```

Depois confira no Moodle (http://localhost:8081 → *Administração do site → Cursos → Gerenciar cursos e categorias*): uma categoria por curso do iEducar, um curso por turma, com alunos e professores inscritos (em *Participantes*).

### Roteiro da demonstração

Veja [`docs/ROTEIRO_DEMONSTRACAO.md`](docs/ROTEIRO_DEMONSTRACAO.md). Em resumo: mostrar os dados no iEducar → sincronizar → mostrar o resultado no Moodle → criar uma turma nova (`docker compose exec ieducar demo-seed nova-turma`) → sincronizar de novo → ver o novo curso aparecer.

### Parar e limpar

```bash
docker compose down        # para os contêineres (mantém os dados)
docker compose down -v     # para e APAGA todos os dados (volta ao zero)
```

## Testes

Os testes automatizados (33) usam as respostas reais capturadas do iEducar e do Moodle (`Ieduc-Moodle/integracao/src/test/resources/samples`) e não precisam do Docker:

```bash
cd Ieduc-Moodle/integracao
./mvnw test        # Windows: mvnw.cmd test   (requer JDK 17+)
```

## Configuração

Todas as variáveis têm padrão no `docker-compose.yml`; veja [`.env.example`](.env.example). As principais:

| Variável | Padrão | Para que serve |
|---|---|---|
| `IEDUCAR_API_TOKEN` | `ieducar-integracao-token-…` | Token Bearer da API REST do iEducar (registrado para o `admin`) |
| `IEDUCAR_API_ACCESS_KEY` / `_SECRET_KEY` | `ieducar-access-key` / `ieducar-secret-key` | Chaves da API legada (professores) |
| `MOODLE_WS_TOKEN` | `a1b2c3d4e5f60718293a4b5c6d7e8f90` | Token dos Web Services do Moodle (registrado para o `admin`) |
| `MOODLE_SENHA_INICIAL` | `Mudar@2026!` | Senha dos usuários criados (o Moodle exige trocar no 1º acesso) |
| `SINCRONIZACAO_INTERVALO` | `PT2M` | Intervalo entre sincronizações automáticas |
| `IEDUCAR_PORT` / `MOODLE_PORT` / `INTEGRACAO_PORT` | `8080` / `8081` / `8090` | Portas no seu computador |

## Regras de sincronização (resumo)

- Chave de cada objeto no Moodle = campo `idnumber`: `IEDUCAR-CURSO-<id>`, `IEDUCAR-TURMA-<id>`, `IEDUCAR-ALUNO-<id>`, `IEDUCAR-SERVIDOR-<id>`. Usuários: `aluno<id>` e `prof<id>`.
- Nome do curso no Moodle: `"<curso> — <turma>"`; nome curto `TURMA-<id>`. Papéis: estudante (id 5) e professor com edição (id 3).
- Turma renomeada → curso renomeado. Turma inativa → curso oculto. Matrícula cancelada/transferida/abandono/falecido ou vínculo de professor removido → inscrição suspensa (reativa se voltar).
- Sem e-mail válido no iEducar, o usuário recebe `<usuário>@exemplo.edu.br`.
- Indisponibilidade do iEducar ou do Moodle: a execução termina como `FALHA` e é tentada de novo no ciclo seguinte. Erro em um item é registrado e os demais seguem (`PARCIAL`).

APIs usadas, com respostas reais: [`docs/ieducar-api.md`](docs/ieducar-api.md) e [`docs/moodle-api.md`](docs/moodle-api.md).

## Estrutura do repositório

```
docker-compose.yml            # iEducar + Moodle + integração
.env.example
Ieduc-Moodle/integracao/      # aplicação Spring Boot (código, testes, Dockerfile)
docker/ieducar/               # Dockerfile, entrypoint e dados de demonstração do iEducar
docker/moodle/                # script que configura os Web Services do Moodle
docs/                         # APIs, diagramas e roteiro da demonstração
relatorio/                    # relatório em LaTeX (abnTeX2): fontes e PDF
```

## Solução de problemas

- **Build do iEducar falha com `Could not authenticate against github.com`** (limite da API do GitHub): crie/edite `.env` com `COMPOSER_INSTALL_FLAGS=--prefer-source` e rode `docker compose up -d --build` de novo (a imagem fica maior).
- **Primeira subida demora**: normal. Veja `docker compose ps` e `docker compose logs -f moodle ieducar`.
- **Porta em uso**: mude `IEDUCAR_PORT`, `MOODLE_PORT` ou `INTEGRACAO_PORT` no `.env`.
- **Sincronização com status `FALHA`**: veja a mensagem no painel; geralmente um dos sistemas ainda está iniciando.
- **Moodle em inglês**: o pacote de idioma é baixado na primeira inicialização; sem internet ele permanece em inglês (não afeta a integração).

## Limitações

Não é tempo real (ciclo de 2 min ou disparo manual); fluxo só iEducar → Moodle; professores só são lidos se tiverem ao menos uma disciplina na turma (limitação da API legada); a imagem `bitnamilegacy/moodle` não recebe atualizações de segurança; tokens e senhas padrão são só para demonstração. Detalhes no relatório.
