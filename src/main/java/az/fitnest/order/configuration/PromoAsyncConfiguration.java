package az.fitnest.order.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded pool for promo audience uploads (NFR-6). The default
 * SimpleAsyncTaskExecutor spawns an unbounded thread per task; a few
 * concurrent 200k-row uploads would exhaust memory/threads.
 */
@Configuration
public class PromoAsyncConfiguration {

    @Bean(name = "promoUploadExecutor")
    public Executor promoUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("promo-upload-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
