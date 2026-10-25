package com.pucrs.integracao;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "integracao.sincronizacao.agendada=false")
class IntegracaoApplicationTests {

	@Test
	void contextLoads() {
	}

}
