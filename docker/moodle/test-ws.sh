#!/bin/bash
# Smoke test dos Web Services do Moodle (usado para gerar docs/samples/moodle-*.json).
# uso: BASE=http://localhost:8081 TOKEN=... OUT=docs/samples ./docker/moodle/test-ws.sh
BASE=${BASE:-http://localhost:8081}; TOKEN=${TOKEN:-a1b2c3d4e5f60718293a4b5c6d7e8f90}; OUT=${OUT:-/tmp/moodle-samples}
mkdir -p "$OUT"
call(){ n=$1; shift; curl -s -X POST "$BASE/webservice/rest/server.php" -d "wstoken=${T:-$TOKEN}" -d moodlewsrestformat=json "$@" > "$OUT/moodle-$n.json"; echo "== $n: $(head -c 220 "$OUT/moodle-$n.json")"; }
j(){ python3 -c "import json,sys;d=json.load(open('$OUT/moodle-$1.json'));print($2)"; }
P='Mudar@2026!'
call site-info -d wsfunction=core_webservice_get_site_info
call cat-create -d wsfunction=core_course_create_categories -d 'categories[0][name]=Engenharia de Software' -d 'categories[0][idnumber]=IEDUCAR-CURSO-1' -d 'categories[0][parent]=0'
call cat-get -d wsfunction=core_course_get_categories -d 'criteria[0][key]=idnumber' -d 'criteria[0][value]=IEDUCAR-CURSO-1'
call cat-get-missing -d wsfunction=core_course_get_categories -d 'criteria[0][key]=idnumber' -d 'criteria[0][value]=NAO-EXISTE'
CAT=$(j cat-get "d[0]['id']")
call course-create -d wsfunction=core_course_create_courses -d 'courses[0][fullname]=Engenharia de Software — Turma A' -d 'courses[0][shortname]=TURMA-1' -d 'courses[0][idnumber]=IEDUCAR-TURMA-1' -d "courses[0][categoryid]=$CAT" -d 'courses[0][visible]=1'
CID=$(j course-create "d[0]['id']")
call course-get -d wsfunction=core_course_get_courses_by_field -d field=idnumber -d value=IEDUCAR-TURMA-1
call course-get-missing -d wsfunction=core_course_get_courses_by_field -d field=idnumber -d value=NAO-EXISTE
call course-update -d wsfunction=core_course_update_courses -d "courses[0][id]=$CID" -d 'courses[0][fullname]=Engenharia de Software — Turma A (renomeada)' -d 'courses[0][visible]=0'
call course-get-after-update -d wsfunction=core_course_get_courses_by_field -d field=idnumber -d value=IEDUCAR-TURMA-1
call course-dup-shortname -d wsfunction=core_course_create_courses -d 'courses[0][fullname]=Outro' -d 'courses[0][shortname]=TURMA-1' -d 'courses[0][idnumber]=IEDUCAR-TURMA-99' -d "courses[0][categoryid]=$CAT"
call course-dup-idnumber -d wsfunction=core_course_create_courses -d 'courses[0][fullname]=Outro' -d 'courses[0][shortname]=TURMA-99' -d 'courses[0][idnumber]=IEDUCAR-TURMA-1' -d "courses[0][categoryid]=$CAT"
call user-create -d wsfunction=core_user_create_users -d 'users[0][username]=aluno1' -d "users[0][password]=$P" -d 'users[0][firstname]=Maria' -d 'users[0][lastname]=Souza Lima' -d 'users[0][email]=maria.lima@exemplo.edu.br' -d 'users[0][idnumber]=IEDUCAR-ALUNO-1' -d 'users[0][auth]=manual' -d 'users[0][preferences][0][type]=auth_forcepasswordchange' -d 'users[0][preferences][0][value]=1'
call user-create-prof -d wsfunction=core_user_create_users -d 'users[0][username]=prof1' -d "users[0][password]=$P" -d 'users[0][firstname]=Carlos' -d 'users[0][lastname]=Pereira' -d 'users[0][email]=carlos.pereira@exemplo.edu.br' -d 'users[0][idnumber]=IEDUCAR-SERVIDOR-1' -d 'users[0][auth]=manual'
SID=$(j user-create "d[0]['id']"); PID=$(j user-create-prof "d[0]['id']")
call user-get -d wsfunction=core_user_get_users_by_field -d field=idnumber -d 'values[0]=IEDUCAR-ALUNO-1'
call user-get-missing -d wsfunction=core_user_get_users_by_field -d field=idnumber -d 'values[0]=NAO-EXISTE'
call user-update -d wsfunction=core_user_update_users -d "users[0][id]=$SID" -d 'users[0][lastname]=Souza Lima Santos' -d 'users[0][email]=maria.santos@exemplo.edu.br'
call user-dup-username -d wsfunction=core_user_create_users -d 'users[0][username]=aluno1' -d "users[0][password]=$P" -d 'users[0][firstname]=X' -d 'users[0][lastname]=Y' -d 'users[0][email]=x@exemplo.edu.br' -d 'users[0][idnumber]=IEDUCAR-ALUNO-77'
call user-dup-idnumber -d wsfunction=core_user_create_users -d 'users[0][username]=aluno77' -d "users[0][password]=$P" -d 'users[0][firstname]=X' -d 'users[0][lastname]=Y' -d 'users[0][email]=x77@exemplo.edu.br' -d 'users[0][idnumber]=IEDUCAR-ALUNO-1'
call user-dup-email -d wsfunction=core_user_create_users -d 'users[0][username]=aluno80' -d "users[0][password]=$P" -d 'users[0][firstname]=X' -d 'users[0][lastname]=Y' -d 'users[0][email]=maria.santos@exemplo.edu.br'
call user-bad-password -d wsfunction=core_user_create_users -d 'users[0][username]=aluno78' -d 'users[0][password]=abc' -d 'users[0][firstname]=X' -d 'users[0][lastname]=Y' -d 'users[0][email]=x78@exemplo.edu.br'
call user-upper-username -d wsfunction=core_user_create_users -d 'users[0][username]=Aluno79' -d "users[0][password]=$P" -d 'users[0][firstname]=X' -d 'users[0][lastname]=Y' -d 'users[0][email]=x79@exemplo.edu.br'
call enrol-student -d wsfunction=enrol_manual_enrol_users -d "enrolments[0][roleid]=5" -d "enrolments[0][userid]=$SID" -d "enrolments[0][courseid]=$CID"
call enrol-teacher -d wsfunction=enrol_manual_enrol_users -d "enrolments[0][roleid]=3" -d "enrolments[0][userid]=$PID" -d "enrolments[0][courseid]=$CID"
call enrolled-all -d wsfunction=core_enrol_get_enrolled_users -d courseid=$CID
call enrol-suspend -d wsfunction=enrol_manual_enrol_users -d "enrolments[0][roleid]=5" -d "enrolments[0][userid]=$SID" -d "enrolments[0][courseid]=$CID" -d "enrolments[0][suspend]=1"
call enrolled-active-after-suspend -d wsfunction=core_enrol_get_enrolled_users -d courseid=$CID -d 'options[0][name]=onlyactive' -d 'options[0][value]=1'
echo "   ativos após suspend: $(j enrolled-active-after-suspend "[u['username'] for u in d]")"
call enrol-reactivate -d wsfunction=enrol_manual_enrol_users -d "enrolments[0][roleid]=5" -d "enrolments[0][userid]=$SID" -d "enrolments[0][courseid]=$CID" -d "enrolments[0][suspend]=0"
call enrolled-active-after-reactivate -d wsfunction=core_enrol_get_enrolled_users -d courseid=$CID -d 'options[0][name]=onlyactive' -d 'options[0][value]=1'
echo "   ativos após reativar: $(j enrolled-active-after-reactivate "[u['username'] for u in d]")"
T=token-invalido call bad-token -d wsfunction=core_webservice_get_site_info
