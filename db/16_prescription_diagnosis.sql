-- Diagnóstico opcional en la receta: texto libre que el doctor escribe antes de las
-- indicaciones; se imprime en el PDF entre el tipo de sangre y las INDICACIONES.
ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS diagnosis TEXT;
