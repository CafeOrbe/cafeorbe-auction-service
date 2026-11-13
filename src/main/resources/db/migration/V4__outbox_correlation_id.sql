-- OBS-01: cada evento guarda el correlationId de la petición que lo originó, para seguir una puja de punta a punta.
alter table outbox add column correlation_id varchar(64);
