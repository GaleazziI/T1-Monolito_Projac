<?php
// Garante um token pessoal (Laravel Sanctum) determinístico para o usuário `admin` do i-Educar.
// O token em texto puro vem da variável IEDUCAR_API_TOKEN; no banco fica apenas o SHA-256 (como o Sanctum faz).
// Idempotente: pode rodar a cada inicialização do container.

use Illuminate\Contracts\Console\Kernel;
use Illuminate\Support\Facades\DB;

require '/var/www/ieducar/vendor/autoload.php';
$app = require '/var/www/ieducar/bootstrap/app.php';
$app->make(Kernel::class)->bootstrap();

$plain = getenv('IEDUCAR_API_TOKEN');
if (!$plain) {
    fwrite(STDOUT, "[ieducar] IEDUCAR_API_TOKEN não definido; token da API não será criado.\n");
    exit(0);
}

$user = App\User::query()->whereHas('employee', fn ($q) => $q->where('matricula', 'admin'))->first();
if (!$user) {
    fwrite(STDERR, "[ieducar] ERRO: usuário admin não encontrado; não foi possível criar o token.\n");
    exit(1);
}

$hash = hash('sha256', $plain);
$now = now();

DB::table('personal_access_tokens')
    ->where('name', 'integracao-moodle')->orWhere('token', $hash)->delete();
DB::table('personal_access_tokens')->insert([
    'tokenable_type' => App\User::class,
    'tokenable_id' => $user->getKey(),
    'name' => 'integracao-moodle',
    'token' => $hash,
    'abilities' => json_encode(['*']),
    'created_at' => $now,
    'updated_at' => $now,
]);

fwrite(STDOUT, "[ieducar] token da API (integracao-moodle) configurado para o usuário admin.\n");
