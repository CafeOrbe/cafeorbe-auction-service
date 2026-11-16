package com.cafeorbe.auction;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** Respuestas del broker para el RabbitTemplate simulado: el publicador de la outbox espera su confirmación. */
public final class BrokerDePrueba {

    private BrokerDePrueba() {
    }

    /** El broker confirma (ack) todo lo que recibe. */
    public static void confirmaTodo(RabbitTemplate rabbit) {
        responde(rabbit, true, false);
    }

    /** El broker rechaza (nack) todo lo que recibe. */
    public static void rechazaTodo(RabbitTemplate rabbit) {
        responde(rabbit, false, false);
    }

    /** El broker confirma, pero devuelve el mensaje porque no había cola para su clave de enrutamiento. */
    public static void devuelveTodo(RabbitTemplate rabbit) {
        responde(rabbit, true, true);
    }

    /** El broker recibe, pero nunca confirma (se cayó antes de responder). */
    public static void nuncaConfirma(RabbitTemplate rabbit) {
        doAnswer(i -> null).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private static void responde(RabbitTemplate rabbit, boolean ack, boolean devuelto) {
        doAnswer(i -> {
            Message mensaje = i.getArgument(2);
            CorrelationData confirmacion = i.getArgument(3);
            if (devuelto) {
                confirmacion.setReturned(new ReturnedMessage(mensaje, 312, "NO_ROUTE", i.getArgument(0), i.getArgument(1)));
            }
            confirmacion.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack de prueba"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
