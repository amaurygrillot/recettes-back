package ilenreste.unpeu.recettesback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class RecettesBackApplication {

    private RecettesBackApplication() {
    }

    static void main(String[] args) {
        SpringApplication.run(RecettesBackApplication.class, args);
    }

}
