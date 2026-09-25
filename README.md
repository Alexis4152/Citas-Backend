# Hospital Citas — Backend

API REST para un sistema de agendado de citas médicas (hospital con múltiples doctores, especialidades y sedes, y un pool compartido de recepcionistas), construida replicando la arquitectura, el modelo de seguridad y las convenciones de código del proyecto de referencia **libreria-backend** (mismo stack Java/Spring Boot/JWT/BCrypt/PostgreSQL).

## Tecnologías

- **Java 17**, **Spring Boot 3.2.5**, Maven
- Spring Web, Spring Data JPA (Hibernate), Spring Security
- **PostgreSQL** (runtime), **H2** en memoria (solo pruebas)
- JWT: `io.jsonwebtoken` (jjwt) 0.11.5, `BCryptPasswordEncoder`
- Lombok
- JUnit 5 + `TestRestTemplate` (pruebas de integración contra la API real)

## Arquitectura

```
controller/  →  service/ (interfaz) + service/impl/  →  repository/  →  entity/
dto/ (ApiResponse, PageResponse) , dto/request/ , dto/response/   (nunca se devuelven entities directamente)
mapper/                         (entity ↔ DTO, manual, sin MapStruct)
security/                       (JWT: filtro, provider, UserDetailsService)
exception/                      (jerarquía propia + GlobalExceptionHandler)
enums/, util/, config/
```

Todas las entidades administrables (`Branch`, `Specialty`, `Doctor`, `DoctorSchedule`, `DoctorScheduleException`, `Patient`, `Appointment`, `User`) extienden `AuditableEntity` (`@MappedSuperclass`): `isActive`, `createdBy/At`, `updatedBy/At`, `deletedBy/At`. Nunca se hace `DELETE` físico — todo es borrado lógico (`is_active = false`).

## Modelo de dominio (resumen)

- **User**: cuenta con login (ADMIN, RECEPTIONIST, DOCTOR o PATIENT). Un `Patient` agendado por teléfono/recepción **nunca** genera una fila `User` — solo existe como `Patient` sin cuenta.
- **Doctor**: perfil ligado 1-a-1 a un `User` con rol DOCTOR, con especialidad, sedes asignadas (`doctor_branches`) y horario semanal (`doctor_schedules`) + excepciones puntuales (`doctor_schedule_exceptions`).
- **Appointment**: doctor + sede + paciente + fecha/hora + estado (`SCHEDULED`, `CANCELLED`, `COMPLETED`, `NO_SHOW`).

### Regla de disponibilidad de un slot

Un horario (doctor + fecha + hora) está **ocupado** si existe una cita con `status IN ('SCHEDULED','COMPLETED','NO_SHOW')`, o `status='CANCELLED' AND slot_released=false`. Está **disponible** en cualquier otro caso (sin fila, o `CANCELLED` con `slot_released=true`).

### Regla de cancelación / liberación de espacio

Al cancelar una cita (paciente logueado, invitado vía link sin login, recepción o doctor), se calcula si faltaban **≥24 horas** para la cita: si sí, queda `releaseEligible=true` y el doctor puede liberar manualmente el espacio (`PATCH /api/doctor/appointments/{id}/release-slot`), lo que pone `slotReleased=true` y vuelve a hacer reservable ese horario. Si faltaban **menos de 24 horas**, no se ofrece esa opción y el espacio queda bloqueado. `releaseEligible` se calcula y guarda **una sola vez**, en el momento de la cancelación (no se recalcula después, porque la fecha de la cita ya pudo haber pasado).

### Seguridad de concurrencia en el agendado

Antes de insertar una cita se toma un bloqueo pesimista (`SELECT ... FOR UPDATE` vía `@Lock(PESSIMISTIC_WRITE)`) sobre cualquier fila ocupante existente del slot. Como segunda línea de defensa (para el caso de la primera reserva de un slot totalmente libre, donde no hay fila previa que bloquear), existe un índice único parcial en Postgres:

```sql
CREATE UNIQUE INDEX ux_appointments_slot
  ON appointments(doctor_id, appointment_date, start_time) WHERE status <> 'CANCELLED';
```

Su violación se traduce a `SlotUnavailableException` → HTTP 409.

## Prerrequisitos

- JDK 17
- Maven 3.9+
- PostgreSQL 15+ corriendo localmente

## Configuración de PostgreSQL

```sql
CREATE DATABASE hospital_citas;
```

Luego ejecuta, en orden, los scripts de `db/`:

```bash
psql -U postgres -d hospital_citas -f db/01_schema.sql
psql -U postgres -d hospital_citas -f db/02_seed.sql
# Scripts incrementales, en orden numérico (03 a 19): autenticación, notificaciones, info médica,
# recetas, recordatorios, etc. El último (17_functional_gaps.sql) agrega la restricción de
# exclusión de traslapes de citas -- requiere la extensión btree_gist (en PostgreSQL 13+ la
# puede crear el dueño de la BD sin ser superusuario).
for f in db/0[3-9]_*.sql db/1[0-9]_*.sql; do psql -U postgres -d hospital_citas -f "$f"; done
```

`01_schema.sql` crea tablas, PKs, FKs, constraints, el índice único parcial de citas e índices de consulta. `02_seed.sql` inserta los 4 roles, la configuración del hospital, 2 sedes, 4 especialidades, un usuario ADMIN, un RECEPTIONIST, 3 DOCTOR (con perfil, sede asignada y horario semanal Lun-Vie 09:00-14:00) y un PATIENT de prueba.

## Variables de entorno

La app funciona con valores por defecto de desarrollo sin configurar nada, pero en producción **siempre** debes sobreescribir estas variables (nunca commitear secretos reales):

| Variable | Default (dev) | Descripción |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/hospital_citas` | Cadena de conexión |
| `DB_USERNAME` | `postgres` | Usuario de BD |
| `DB_PASSWORD` | `admin` | Password de BD |
| `SERVER_PORT` | `8082` | Puerto HTTP |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5175,http://localhost:3000` | Orígenes permitidos (frontend) |
| `APP_JWT_SECRET` | clave de desarrollo | Secreto de firma JWT — **cambiar en producción** |
| `APP_JWT_EXPIRATION` | `86400000` (24h) | Expiración del token en ms |
| `APP_NOSHOW_GRACE_MINUTES` | `15` | Margen tras el fin del horario antes de marcar "no asistió" automáticamente (las citas con llegada registrada nunca se marcan) |
| `APP_MAX_ACTIVE_PER_PATIENT` | `5` | Máximo de citas programadas vigentes por paciente (agendado por invitado/cuenta; recepción no tiene tope) |
| `APP_MAX_ACTIVE_PER_PHONE` | `10` | Máximo de citas programadas vigentes por teléfono en el agendado de invitado |
| `APP_SELF_RESCHEDULE_MIN_HOURS` | `24` | Horas mínimas de anticipación para que el paciente reprograme por su cuenta |
| `APP_UPLOADS_DIR` | `uploads` | Carpeta de archivos subidos (fotos de doctores, logo) |
| `APP_PUBLIC_URL` | (vacío) | URL pública del backend, para convertir rutas `/uploads/**` en absolutas |
| `APP_TIMEZONE` | `America/Mexico_City` | Zona horaria de la JVM (fechas/horas de citas, `created_at`, validación de horarios ya pasados). Cambiar si el hospital está en otra zona del país, ej. `America/Tijuana`, `America/Hermosillo` |

## Cobros y corte de caja (OpenPay)

- **Precio:** cada doctor define el precio de su consulta (menú *Cobros* del doctor, o el admin en el formulario del doctor). Cada cita guarda el precio que tenía al agendarse.
- **Pago anticipado (paso 5 del agendado):** el paciente elige pagar en recepción o por anticipado con tarjeta o SPEI (OpenPay). La tarjeta se tokeniza en el navegador (el backend nunca recibe los datos de la tarjeta). El pago se hace *después* de agendar: si se declina, la cita sigue y se paga en recepción.
- **Cancelación de una cita pagada:** con al menos 1 hora de anticipación se reembolsa automático (tarjeta); con menos, o si fue SPEI, queda en *Cobros → Reembolsos por decidir* del admin (`app.payments.auto-refund-min-minutes`, 60 por defecto).
- **Módulo Cobrar (recepción/admin):** encuentra la cita escaneando el QR del comprobante (cámara o lector USB), escribiendo el folio (#123) o buscando por nombre/teléfono, y cobra ahí mismo; sin búsqueda muestra la cola de *por cobrar*. Se puede cobrar una cita atendida o una programada de HOY (al cobrar se registra la llegada).
- **Cobro en recepción:** al marcar una cita como atendida se abre el cobro (efectivo con cambio, tarjeta en terminal u OpenPay). Exige tener un **corte de caja** abierto; cada cobro entra al corte de quien lo registra. El corte muestra efectivo/tarjeta/OpenPay, el efectivo que debe haber en caja, las citas por doctor y, al cerrar, la diferencia contra lo contado.
- **Variables:** `OPENPAY_MERCHANT_ID`, `OPENPAY_PRIVATE_KEY`, `OPENPAY_PUBLIC_KEY`, `OPENPAY_BASE_URL` (producción: `https://api.openpay.mx/v1`), `OPENPAY_PRODUCTION`, `OPENPAY_WEBHOOK_USER`, `OPENPAY_WEBHOOK_PASSWORD`. Por defecto usa el **sandbox** con llaves de prueba: en producción **sobreescribe todas**.
- **Webhook (SPEI):** en el panel de OpenPay registra `https://<tu-backend>/api/webhooks/openpay` con el usuario/contraseña de arriba; así una transferencia acreditada marca la cita como pagada. Sin webhook, recepción puede usar *Verificar pago* en el cobro.
- Tarjetas de prueba del sandbox: `4111 1111 1111 1111` (aprobada), `4222 2222 2222 2220` (declinada); cualquier CVV y vencimiento futuro.

## Configuración de correo (SMTP)

Igual que en el proyecto de referencia, las credenciales SMTP **no** son una variable de entorno: viven en la tabla `email_config` (fila única) y se administran desde `/api/admin/email-config`. `EmailServiceImpl` arma el cliente SMTP a partir de esa tabla en cada envío, así que un cambio de credenciales aplica de inmediato, sin reiniciar el backend. Por default queda `enabled = false`. Un fallo de envío de correo **nunca** revierte el agendado/cancelación de una cita — solo se registra en el log.

El `GET` de `/api/admin/email-config` nunca devuelve la contraseña, solo `passwordConfigured: true/false`; dejar el campo de contraseña en blanco al hacer `PUT` conserva la que ya estaba guardada.

## Ejecución

```bash
mvn spring-boot:run
```

La API queda en `http://localhost:8082`. Los archivos subidos se sirven en `http://localhost:8082/uploads/**`.

## Pruebas

```bash
mvn test
```

Pruebas de integración (JUnit 5 + `TestRestTemplate`, H2 en memoria) que cubren: registro (rol siempre PATIENT, con fila `Patient` creada automáticamente), login válido/inválido, 403 por rol en `/reception/**`, `/doctor/**` y `/admin/**` para roles sin permiso, disponibilidad de horarios (exclusión por excepción de agenda total/parcial, exclusión por cita activa, re-inclusión tras `slotReleased=true`), agendado como invitado y como paciente autenticado de punta a punta, rechazo fuera de horario, doble-reserva (secuencial y concurrente) rechazada con 409, y la regla de cancelación ≥24h/<24h incluyendo el endpoint de liberación manual de espacio.

## Usuarios de prueba (sembrados por `02_seed.sql`)

| Rol | Email | Password |
|---|---|---|
| ADMIN | `admin@hospital-demo.com` | `Admin123!` |
| RECEPTIONIST | `recepcion@hospital-demo.com` | `Recepcion123!` |
| DOCTOR (Pediatría) | `doctor.pediatria@hospital-demo.com` | `Doctor123!` |
| DOCTOR (Ginecología) | `doctor.gineco@hospital-demo.com` | `Doctor123!` |
| DOCTOR (Cardiología) | `doctor.cardiologia@hospital-demo.com` | `Doctor123!` |
| PATIENT | `paciente@demo.com` | `Paciente123!` |

## Endpoints principales

```
/api/auth/**                     público   (register — rol SIEMPRE forzado a PATIENT, login, me)
GET /api/public/**                público   (branches, specialties, doctors, doctor availability)
POST /api/appointments/guest      público   (agendado de invitado: solo nombre+teléfono obligatorios)
/api/appointments/cancel/**       público   (cancelación vía {token} en la URL, sin login)
/api/appointments/**              autenticado (PATIENT logueado: agenda para sí mismo, lista propias, cancela propias)
/api/reception/**                 RECEPTIONIST o ADMIN (búsqueda/gestión de citas de cualquier doctor, pacientes)
/api/doctor/**                    DOCTOR o ADMIN (perfil, horarios/excepciones, agenda propia, cancelar, liberar espacio)
/api/admin/**                     solo ADMIN (branches, specialties, doctors, receptionists, hospital-config, email-config, dashboard)
/uploads/**                       público
```

### Catálogo público

- `GET /api/public/branches`, `GET /api/public/specialties`
- `GET /api/public/doctors?specialtyId=&branchId=&page=&size=`
- `GET /api/public/doctors/{id}`
- `GET /api/public/doctors/{id}/availability?date=YYYY-MM-DD&days=N`

### Citas

- `POST /api/appointments/guest` — agendado sin cuenta (nombre + apellido + teléfono obligatorios, correo opcional).
- `POST /api/appointments/cancel/{token}` — cancelación sin login vía el token único de la cita.
- `POST /api/appointments` / `GET /api/appointments` / `POST /api/appointments/{id}/cancel` — paciente autenticado.

### Recepción

- `GET /api/reception/appointments` (filtros: doctor, sede, rango de fechas, estado, nombre/teléfono del paciente)
- `POST /api/reception/appointments`, `POST /api/reception/appointments/{id}/cancel`, `PATCH /api/reception/appointments/{id}/reschedule`
- `GET /api/reception/patients`, `POST /api/reception/patients`

### Doctor

- `GET/PUT /api/doctor/me`
- `GET/POST /api/doctor/schedule`, `PUT/DELETE /api/doctor/schedule/{id}`
- `GET/POST /api/doctor/schedule-exceptions`, `DELETE /api/doctor/schedule-exceptions/{id}`
- `GET /api/doctor/appointments?from=&to=`
- `POST /api/doctor/appointments/{id}/cancel`
- `PATCH /api/doctor/appointments/{id}/release-slot`

### Admin

- `AdminBranchController`, `AdminSpecialtyController` — CRUD + baja lógica
- `AdminDoctorController` — `POST /api/admin/doctors` crea de un solo golpe el `User` (rol DOCTOR), el perfil `Doctor`, sus sedes y su horario semanal
- `AdminReceptionistController` — `POST /api/admin/receptionists`
- `AdminHospitalConfigController`, `AdminEmailConfigController`, `AdminDashboardController`

## Envío de correos

`EmailServiceImpl` sigue la misma arquitectura que el proyecto de referencia: arma un `JavaMailSenderImpl` nuevo a partir de la fila `email_config` en cada envío, y nunca deja que un fallo de SMTP tumbe la transacción de agendado/cancelación (solo registra en el log). Se notifica:

- Al paciente (si tiene correo capturado): confirmación de la cita, con recomendaciones (llegar 15 min antes, traer identificación oficial, cancelar con ≥24h de anticipación).
- Al doctor y a todos los recepcionistas activos: aviso de nueva cita y de cancelación.
