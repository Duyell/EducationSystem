package duyell;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * @author duyell
 */
@SpringBootApplication
@ComponentScan(basePackages = {"utils", "duyell", "login", "interceptor","config"})
public class EduApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(EduApiApplication.class, args);
    }
}