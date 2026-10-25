package com.pucrs.integracao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class IntegracaoApplication {

	public static void main(String[] args) {
		SpringApplication.run(IntegracaoApplication.class, args);
	}

}
