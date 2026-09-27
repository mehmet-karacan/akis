package tr.com.innova.akis.security;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import tr.com.innova.akis.AkisApplication;

public final class BootstrapAdminCommand {

    private BootstrapAdminCommand() {
    }

    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(AkisApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run(args)) {
            long userId = context.getBean(BootstrapAdminService.class)
                    .runInteractive(System.console());
            System.out.println("Sistem yöneticisi hazırlandı (kullanıcı id: " + userId + ").");
        }
    }
}
