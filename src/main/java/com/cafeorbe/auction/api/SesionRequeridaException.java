package com.cafeorbe.auction.api;

public class SesionRequeridaException extends RuntimeException {

    public SesionRequeridaException() {
        super("Sesión requerida");
    }
}
