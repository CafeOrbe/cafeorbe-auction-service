package com.cafeorbe.auction.api;

import com.cafeorbe.contracts.Cabeceras;
import com.cafeorbe.contracts.Rol;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Construye {@link UsuarioActual} desde las cabeceras X-User-*. El nombre viaja codificado como URL (UTF-8). */
@Component
public class UsuarioActualResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parametro) {
        return UsuarioActual.class.equals(parametro.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parametro, ModelAndViewContainer contenedor,
                                  NativeWebRequest peticion, WebDataBinderFactory fabrica) {
        try {
            UUID id = UUID.fromString(peticion.getHeader(Cabeceras.USUARIO_ID));
            String nombre = URLDecoder.decode(peticion.getHeader(Cabeceras.USUARIO_NOMBRE), StandardCharsets.UTF_8);
            Rol rol = Rol.valueOf(peticion.getHeader(Cabeceras.USUARIO_ROL));
            return new UsuarioActual(id, nombre, rol);
        } catch (RuntimeException e) {
            throw new SesionRequeridaException();
        }
    }
}
