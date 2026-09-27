package tr.com.innova.akis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AkisApplication {

    public static void main(String[] args) {
        SpringApplication.run(AkisApplication.class, args);
    }
}
