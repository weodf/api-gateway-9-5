package org.imooc.backend.http.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.imooc")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
