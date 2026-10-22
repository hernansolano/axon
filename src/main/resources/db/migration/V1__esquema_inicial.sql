-- Esquema inicial de AXON. Las siete tablas de docs/ESPEC.md §2.
-- Los enums son texto con CHECK: agregar un valor es una migración, no un tipo de Postgres.

CREATE TABLE paciente (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cedula      VARCHAR(20)  NOT NULL UNIQUE,
    nombre      VARCHAR(120) NOT NULL,
    hash_clave  VARCHAR(100) NOT NULL,
    creado_en   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE profesional (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idoneidad   VARCHAR(20)  NOT NULL UNIQUE,
    nombre      VARCHAR(120) NOT NULL,
    institucion VARCHAR(160) NOT NULL,
    hash_clave  VARCHAR(100) NOT NULL,
    creado_en   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE registro (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    paciente_id        BIGINT       NOT NULL REFERENCES paciente (id),
    categoria          VARCHAR(20)  NOT NULL
        CHECK (categoria IN ('DIAGNOSTICOS', 'MEDICAMENTOS', 'LABORATORIOS', 'VACUNAS', 'OTROS')),
    titulo             VARCHAR(160) NOT NULL,
    detalle            VARCHAR(400),
    fecha_documento    DATE         NOT NULL,
    institucion_origen VARCHAR(160),
    creado_en          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX registro_paciente_fecha ON registro (paciente_id, fecha_documento DESC);

-- procedencia no es columna: es derivada de la existencia de un documento (ESPEC §2).
CREATE TABLE documento (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    registro_id     BIGINT       NOT NULL UNIQUE REFERENCES registro (id) ON DELETE CASCADE,
    nombre_original VARCHAR(200) NOT NULL,
    tipo_mime       VARCHAR(80)  NOT NULL,
    ruta_almacen    UUID         NOT NULL UNIQUE,
    tamano_bytes    BIGINT       NOT NULL CHECK (tamano_bytes >= 0),
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE permiso (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    paciente_id    BIGINT      NOT NULL REFERENCES paciente (id),
    codigo         VARCHAR(7)  NOT NULL UNIQUE,
    alcance        TEXT[]      NOT NULL CHECK (cardinality(alcance) > 0),
    proposito      VARCHAR(80) NOT NULL,
    creado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    vence_en       TIMESTAMPTZ NOT NULL,
    -- VENCIDO no se guarda: se calcula comparando vence_en con el momento actual.
    estado         VARCHAR(10) NOT NULL DEFAULT 'ACTIVO' CHECK (estado IN ('ACTIVO', 'REVOCADO')),
    profesional_id BIGINT REFERENCES profesional (id),
    canjeado_en    TIMESTAMPTZ
);

CREATE TABLE emergencia_item (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    paciente_id BIGINT NOT NULL REFERENCES paciente (id),
    registro_id BIGINT NOT NULL REFERENCES registro (id) ON DELETE CASCADE,
    UNIQUE (paciente_id, registro_id)
);

-- Solo inserción: no hay endpoint de actualización ni de borrado, y borrar un registro
-- no toca esta tabla. Por eso registro_id no aparece aquí.
CREATE TABLE bitacora (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    paciente_id    BIGINT      NOT NULL REFERENCES paciente (id),
    tipo_evento    VARCHAR(30) NOT NULL CHECK (tipo_evento IN (
        'PERMISO_CREADO', 'PERMISO_CANJEADO', 'CONSULTA', 'CONSULTA_DENEGADA',
        'PERMISO_REVOCADO', 'EMERGENCIA', 'REGISTRO_ELIMINADO', 'SOLICITUD_AMPLIACION')),
    profesional_id BIGINT REFERENCES profesional (id),
    permiso_id     BIGINT REFERENCES permiso (id),
    categorias     TEXT[],
    detalle        VARCHAR(400),
    creado_en      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX bitacora_paciente_fecha ON bitacora (paciente_id, creado_en DESC);

-- RS-07: la base misma rechaza cambios y borrados sobre la bitácora.
CREATE FUNCTION bitacora_solo_insercion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'La bitácora es de solo inserción';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bitacora_sin_cambios
    BEFORE UPDATE OR DELETE ON bitacora
    FOR EACH ROW EXECUTE FUNCTION bitacora_solo_insercion();
