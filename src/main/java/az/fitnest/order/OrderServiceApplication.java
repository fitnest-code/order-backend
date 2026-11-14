package az.fitnest.order;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableKafka
@EnableJpaRepositories(basePackages = {
        "az.fitnest.order.repository",
        "az.fitnest.order.pricingcampaign.repository"
})
@EnableRedisRepositories(basePackages = {})
@EnableScheduling
@org.springframework.scheduling.annotation.EnableAsync
public class OrderServiceApplication {

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Baku"));
    }

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
