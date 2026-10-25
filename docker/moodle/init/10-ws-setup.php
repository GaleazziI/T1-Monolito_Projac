<?php
// Configura os Web Services do Moodle para a integração com o iEducar (idempotente).
define('CLI_SCRIPT', true);
require('/bitnami/moodle/config.php');
require_once($CFG->libdir . '/externallib.php');
require_once($CFG->dirroot . '/webservice/lib.php');

$token = getenv('MOODLE_WS_TOKEN') ?: 'a1b2c3d4e5f60718293a4b5c6d7e8f90';
$functions = [
    'core_webservice_get_site_info', 'core_course_get_categories', 'core_course_create_categories',
    'core_course_get_courses_by_field', 'core_course_create_courses', 'core_course_update_courses',
    'core_user_get_users_by_field', 'core_user_create_users', 'core_user_update_users',
    'enrol_manual_enrol_users', 'enrol_manual_unenrol_users', 'core_enrol_get_enrolled_users',
    'core_role_assign_roles',
];

// 1) Habilita web services + protocolo REST.
set_config('enablewebservices', 1);
set_config('webserviceprotocols', 'rest');
// Usuários criados pela API não precisam de confirmação por e-mail; senha padrão da política do Moodle.
set_config('registerauth', '');
set_config('passwordpolicy', 1);
// Plugin de inscrição manual habilitado.
$enabled = array_filter(explode(',', (string) get_config('core', 'enrol_plugins_enabled')));
if (!in_array('manual', $enabled)) { $enabled[] = 'manual'; set_config('enrol_plugins_enabled', implode(',', $enabled)); }
// Permite criar cursos com idnumber e usuários com preferências.
set_config('allowuserblockhiding', 1);

// 2) Serviço externo.
$wsm = new webservice();
$service = $DB->get_record('external_services', ['shortname' => 'ieducar_integracao']);
if (!$service) {
    $obj = (object) ['name' => 'iEducar Integração', 'shortname' => 'ieducar_integracao', 'enabled' => 1,
        'restrictedusers' => 1, 'downloadfiles' => 0, 'uploadfiles' => 0, 'component' => null,
        'timecreated' => time(), 'timemodified' => time()];
    $obj->id = $wsm->add_external_service($obj);
    $service = $DB->get_record('external_services', ['id' => $obj->id]);
}
foreach ($functions as $fn) {
    if (!$DB->record_exists('external_functions', ['name' => $fn])) { mtrace("Função inexistente: $fn"); continue; }
    if (!$wsm->service_function_exists($fn, $service->id)) { $wsm->add_external_function_to_service($fn, $service->id); }
}

// 3) Admin autorizado + token determinístico.
$admin = get_admin();
if (!$DB->record_exists('external_services_users', ['externalserviceid' => $service->id, 'userid' => $admin->id])) {
    $wsm->add_ws_authorised_user((object) ['externalserviceid' => $service->id, 'userid' => $admin->id]);
}
$rec = $DB->get_record('external_tokens', ['token' => $token]);
$data = (object) ['token' => $token, 'tokentype' => EXTERNAL_TOKEN_PERMANENT, 'userid' => $admin->id,
    'externalserviceid' => $service->id, 'contextid' => context_system::instance()->id,
    'creatorid' => $admin->id, 'timecreated' => time(), 'validuntil' => 0, 'name' => 'iEducar Integração'];
if ($rec) { $data->id = $rec->id; $DB->update_record('external_tokens', $data); }
else { $data->privatetoken = random_string(64); $DB->insert_record('external_tokens', $data); }
// Sem SMTP no ambiente: evita que a inscrição retorne "Message was not sent." (a inscrição era feita, mas a API acusava erro).
$cfgfile = $CFG->dirroot . '/config.php';
$conf = file_get_contents($cfgfile);
if (strpos($conf, 'noemailever') === false) {
    $conf = preg_replace('/^require_once\(/m', "\$CFG->noemailever = true;\nrequire_once(", $conf, 1);
    file_put_contents($cfgfile, $conf);
}

// Pacote de idioma pt_BR (best effort: precisa de saída HTTPS para download.moodle.org).
try {
    if (!get_string_manager()->translation_exists('pt_br')) {
        require_once($CFG->libdir . '/componentlib.class.php');
        $controller = new \tool_langimport\controller();
        $controller->install_languagepacks('pt_br');
    }
    if (get_string_manager()->translation_exists('pt_br')) { set_config('lang', 'pt_br'); }
} catch (\Throwable $e) { mtrace('Pacote pt_br não instalado: ' . $e->getMessage()); }
purge_all_caches();
mtrace('Web services do Moodle configurados.');
// O hook roda como root: devolve a propriedade do moodledata ao usuário do Apache (daemon).
exec('chown -R daemon:daemon /bitnami/moodledata 2>&1');
