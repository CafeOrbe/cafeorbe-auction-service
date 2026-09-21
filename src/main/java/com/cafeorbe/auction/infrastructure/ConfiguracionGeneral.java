package com.cafeorbe.auction.infrastructure;

import com.cafeorbe.contracts.Eventos;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ConfiguracionGeneral {

    @Bean
    TopicExchange eventosExchange() {
        return new TopicExchange(Eventos.EXCHANGE, true, false);
    }

    /** Reloj inyectable para poder fijar la hora en las pruebas. */
    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }
}
