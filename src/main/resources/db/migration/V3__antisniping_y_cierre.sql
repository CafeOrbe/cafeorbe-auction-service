-- HU-18: la regla anti-sniping queda fijada en la subasta al iniciarla; "extensiones" cuenta las ya aplicadas.
alter table subasta add column ventana_antisniping_seg integer;
alter table subasta add column max_extensiones integer;
alter table subasta add column extensiones integer not null default 0;

-- HU-19: momento en que el servidor cerró la subasta.
alter table subasta add column cerrada_en timestamp with time zone;

-- El cierre automático busca las subastas En curso cuya hora de fin ya pasó.
create index ix_subasta_estado_hora_fin on subasta (estado, hora_fin);
