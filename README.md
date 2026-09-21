# cafeorbe-auction-service

Subasta completa. **Arquitectura:** capas con dominio rico (DDD táctico) + CQRS ligero. Puerto `8082`.

```text
api/             controladores REST, identidad desde cabeceras X-User-*, manejo de errores
application/     SubastaService y RegistrarPujaService (comandos) · ConsultasDeSubasta (consultas)
domain/          Subasta (agregado y máquina de estados), Puja, FichaLote, ReglasDePuja, MotivoRechazo
infrastructure/  repositorios JPA, cliente de wallet, outbox transaccional
```

## Endpoints (todos detrás del gateway, prefijo `/api/subastas`)

| Método y ruta | Historia | Notas |
|---|---|---|
| `POST /` | HU-08 | Solo Subastador. Queda `PROGRAMADA`. Fecha pasada → 400 `La fecha de inicio debe ser futura`. |
| `GET /?estado=programada,en_curso` | HU-04 | Ordenadas por fecha. Sin filtro devuelve todas. |
| `GET /mias` | HU-03 | Solo Subastador (403 a un Comprador). |
| `GET /{id}` | HU-05 | Detalle: ficha, reglas, precio actual, líder, próximas pujas. |
| `PUT /{id}/ficha` | HU-09 | 409 si la subasta ya no está `PROGRAMADA`. |
| `PUT /{id}/reglas` | HU-10 | Valores ≤ 0 → 400. Cada cambio sube `reglas.version`. |
| `POST /{id}/iniciar` | HU-12 | `EN_CURSO`, fija `horaFin`, publica `SubastaIniciada`. Sin reglas → 409. |
| `POST /{id}/pujas` | HU-13, HU-14 | Solo Comprador. 200 aceptada · 422 rechazada con `motivo` tipificado. |
| `GET /{id}/pujas` | HU-14 | Historial, de la más reciente a la más antigua. |

## Reglas de puja

Próxima puja mínima = **precio actual + incremento**, donde el precio actual arranca en el precio base. Orden de validación y motivo:
`SUBASTA_NO_EN_CURSO` / `SUBASTA_FINALIZADA` → `YA_ERES_LIDER` → `INCREMENTO_MINIMO` → `SALDO_NO_DISPONIBLE` → `SALDO_INSUFICIENTE`.
Los Orbes **no se reservan** al pujar (el cobro es al cierre, Sprint 2). Solo se persisten las pujas aceptadas.

## Garantías

- **Concurrencia:** las pujas sobre una misma subasta se serializan con bloqueo pesimista de la fila. El saldo se consulta a wallet *antes* del bloqueo.
- **Outbox transaccional:** el cambio de estado y su evento se guardan en la misma transacción; `OutboxScheduler` publica cada 200 ms (`SKIP LOCKED`, entrega al menos una vez).
- Si wallet no responde (timeout 2 s) la puja se rechaza con `SALDO_NO_DISPONIBLE` sin cambiar nada.

Pendiente del Sprint 2: temporizador y cierre automático (HU-17, HU-19), anti-sniping (HU-18) y resultados (HU-22). `ReglasDePuja` y `EstadoSubasta` ya tienen el lugar previsto.

## Ejecutar

```bash
mvn spring-boot:run      # requiere Postgres (auction_db), RabbitMQ y wallet-service
mvn test                 # H2, sin infraestructura (39 pruebas)
```
