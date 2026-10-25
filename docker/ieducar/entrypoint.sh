#!/bin/bash
# Entrypoint do container i-Educar: espera o banco, gera o .env, instala (1ª execução),
# garante o token da API e inicia php-fpm + nginx.
set -euo pipefail
cd /var/www/ieducar

log() { echo "[ieducar] $*"; }

export DB_HOST="${DB_HOST:-ieducar-db}"
export DB_PORT="${DB_PORT:-5432}"
export DB_DATABASE="${DB_DATABASE:-ieducar}"
export DB_USERNAME="${DB_USERNAME:-ieducar}"
export DB_PASSWORD="${DB_PASSWORD:-ieducar}"
export REDIS_HOST="${REDIS_HOST:-ieducar-redis}"
export REDIS_PORT="${REDIS_PORT:-6379}"
export PGPASSWORD="$DB_PASSWORD"

# 1. Espera o PostgreSQL ------------------------------------------------------
log "aguardando PostgreSQL em ${DB_HOST}:${DB_PORT}..."
until pg_isready -q -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$DB_DATABASE"; do sleep 2; done

# 2. APP_KEY persistente (fica no volume de storage) --------------------------
KEYFILE=storage/app/.app_key
mkdir -p storage/app
if [ -z "${APP_KEY:-}" ]; then
    if [ -s "$KEYFILE" ]; then
        APP_KEY="$(cat "$KEYFILE")"
    else
        APP_KEY="base64:$(head -c 32 /dev/urandom | base64)"
        echo -n "$APP_KEY" > "$KEYFILE"
    fi
fi

# 3. Gera o .env a partir do .env.example + variáveis de ambiente --------------
[ -f .env.example ] || { log "ERRO: .env.example não encontrado"; exit 1; }
cp .env.example .env
setenv() { # setenv CHAVE VALOR  (substitui a linha existente ou acrescenta)
    local k="$1" v="$2"
    if grep -q "^${k}=" .env; then
        sed -i "s|^${k}=.*|${k}=${v//|/\\|}|" .env
    else
        echo "${k}=${v}" >> .env
    fi
}
setenv APP_ENV        "${APP_ENV:-production}"
setenv APP_KEY        "$APP_KEY"
setenv APP_DEBUG      "${APP_DEBUG:-false}"
setenv APP_URL        "${APP_URL:-http://localhost}"
setenv APP_TIMEZONE   "${APP_TIMEZONE:-America/Sao_Paulo}"
setenv ASSETS_SECURE  "false"
setenv API_ACCESS_KEY "${API_ACCESS_KEY:-ieducar-access-key}"
setenv API_SECRET_KEY "${API_SECRET_KEY:-ieducar-secret-key}"
setenv DB_CONNECTION  "pgsql"
setenv DB_HOST        "$DB_HOST"
setenv DB_PORT        "$DB_PORT"
setenv DB_DATABASE    "$DB_DATABASE"
setenv DB_USERNAME    "$DB_USERNAME"
setenv DB_PASSWORD    "$DB_PASSWORD"
setenv REDIS_HOST     "$REDIS_HOST"
setenv REDIS_PORT     "$REDIS_PORT"
setenv CACHE_STORE    "redis"
setenv REDIS_CLIENT   "predis"
setenv QUEUE_CONNECTION "sync"
setenv SESSION_DRIVER "file"
setenv LOG_CHANNEL    "stderr"
chown www-data:www-data .env
mkdir -p storage/framework/{cache,sessions,views} storage/logs bootstrap/cache
chown -R www-data:www-data storage bootstrap/cache
chmod -R ug+rwX storage bootstrap/cache

artisan() { su-exec www-data php artisan "$@"; }

# 4. Instalação (idempotente e segura contra reinício no meio do boot) --------
log "aplicando migrations (a primeira execução cria toda a estrutura e pode levar alguns minutos)..."
artisan migrate --force --ansi
# Os seeders só rodam se ainda não foram aplicados (países vazio) — não depende de ser a "primeira execução"
SEEDED=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$DB_DATABASE" -tAc "select count(*) from public.countries")
if [ "${SEEDED:-0}" = "0" ]; then
    log "carregando dados iniciais (seeders)..."
    artisan db:seed --force --ansi
fi
artisan storage:link --ansi 2>/dev/null || true
artisan config:clear >/dev/null 2>&1 || true

# 5. Token fixo (Sanctum) para a API REST, usado pelo serviço de integração ----
su-exec www-data php /usr/local/bin/bootstrap-token.php

# 6. Sobe php-fpm e nginx -----------------------------------------------------
log "iniciando php-fpm e nginx"
php-fpm -D
exec nginx -g 'daemon off;'
