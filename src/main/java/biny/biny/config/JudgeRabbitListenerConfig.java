package biny.biny.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 判题队列使用手动 ACK，避免异常导致消息无限重试刷屏。
 */
@Configuration
public class JudgeRabbitListenerConfig {

    @Bean("judgeRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory judgeRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setAutoStartup(true);
        // 兜底：即使监听方法异常退出，也不要 requeue 造成死循环刷屏
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
