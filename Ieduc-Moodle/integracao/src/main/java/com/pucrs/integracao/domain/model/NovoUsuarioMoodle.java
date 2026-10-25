package com.pucrs.integracao.domain.model;

public record NovoUsuarioMoodle(String username, String senha, String primeiroNome, String sobrenome,
                                String email, String idNumber) {
}
