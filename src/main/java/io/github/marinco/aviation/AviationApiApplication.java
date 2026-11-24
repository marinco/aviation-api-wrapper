package io.github.marinco.aviation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AviationApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AviationApiApplication.class, args);
    }
}
