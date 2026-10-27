# i-Educar (Portabilis) em uma única imagem: nginx + php-fpm.
# Uso didático/demonstração — não recomendado para produção.
# (Reconstruído a partir do histórico da imagem integracao/ieducar:2.12.)
FROM composer:2 AS composer

FROM php:8.5-fpm-alpine
LABEL org.opencontainers.image.title="i-Educar (demo de integração com Moodle)" \
      org.opencontainers.image.source="https://github.com/portabilis/i-educar"

ARG IEDUCAR_REF=2.12
# Em redes lentas/instáveis use --prefer-source; o padrão (dist/zip) gera imagem bem menor.
ARG COMPOSER_INSTALL_FLAGS=

ENV COMPOSER_ALLOW_SUPERUSER=1 \
    COMPOSER_PROCESS_TIMEOUT=900 \
    COMPOSER_DISABLE_XDEBUG_WARN=1 \
    COMPOSER_MEMORY_LIMIT=-1 \
    COMPOSER_NO_SECURITY_BLOCKING=1

COPY --from=composer /usr/bin/composer /usr/local/bin/composer

RUN apk add --no-cache \
        bash curl git unzip nginx su-exec tzdata \
        libc-dev libpng-dev libzip-dev linux-headers \
        postgresql-client postgresql-dev \
    && apk add --no-cache --virtual .phpize_deps $PHPIZE_DEPS \
    && docker-php-ext-install bcmath gd pcntl pdo pdo_pgsql pgsql sockets zip ftp \
    && apk del .phpize_deps

RUN git clone --depth 1 --branch "${IEDUCAR_REF}" https://github.com/portabilis/i-educar.git /var/www/ieducar \
    && rm -rf /var/www/ieducar/.git
WORKDIR /var/www/ieducar

# composer install COM dev (o demo-seed usa factories/faker) + plugins do i-Educar.
# O Composer 2.10 recusa pacotes com alertas de seguranca publicados depois do composer.lock do
# i-Educar (ex.: phpunit, usado so em testes). Para esta demonstracao desligamos esse bloqueio.
RUN composer config --global policy.advisories.block false \
    && composer install --no-interaction ${COMPOSER_INSTALL_FLAGS} \
    && composer plug-and-play --no-interaction \
    && rm -rf /root/.composer /root/.cache/composer

RUN mkdir -p /etc/nginx/snippets /run/nginx \
    && cp docker/nginx/snippets/snippets.conf /etc/nginx/snippets/snippets.conf

COPY nginx.conf /etc/nginx/nginx.conf
COPY php-fpm.conf /usr/local/etc/php-fpm.d/zz-ieducar.conf
COPY php.ini /usr/local/etc/php/conf.d/zz-ieducar.ini
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY bootstrap-token.php /usr/local/bin/bootstrap-token.php
COPY demo-seed.php /usr/local/share/ieducar/demo-seed.php
COPY demo-seed /usr/local/bin/demo-seed
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/demo-seed \
    && chown -R www-data:www-data storage bootstrap/cache

EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --start-period=10m --retries=10 \
    CMD curl -fsS http://127.0.0.1/api/version >/dev/null || exit 1
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
