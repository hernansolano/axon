-- RS-01 (DEC-14): intentos fallidos de inicio de sesión. Tabla propia porque un intento fallido
-- puede no corresponder a ninguna cuenta, y bitacora exige un paciente.
-- Nunca se guarda la clave tecleada.

CREATE TABLE intento_fallido (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo_cuenta   VARCHAR(12)  NOT NULL CHECK (tipo_cuenta IN ('PACIENTE', 'PROFESIONAL')),
    identificador VARCHAR(20)  NOT NULL,
    origen        VARCHAR(45),
    motivo        VARCHAR(60)  NOT NULL,
    creado_en     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX intento_fallido_cuenta_fecha ON intento_fallido (tipo_cuenta, identificador, creado_en DESC);

CREATE FUNCTION intento_fallido_solo_insercion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Los intentos fallidos son de solo inserción';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER intento_fallido_sin_cambios
    BEFORE UPDATE OR DELETE ON intento_fallido
    FOR EACH ROW EXECUTE FUNCTION intento_fallido_solo_insercion();
