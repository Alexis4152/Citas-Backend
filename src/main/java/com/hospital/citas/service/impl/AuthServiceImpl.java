package com.hospital.citas.service.impl;

import com.hospital.citas.tenant.TenantContext;
import com.hospital.citas.tenant.TenantLinks;
import java.util.Optional;

import com.hospital.citas.dto.request.ChangePasswordRequest;
import com.hospital.citas.dto.request.ForgotPasswordRequest;
import com.hospital.citas.dto.request.LoginRequest;
import com.hospital.citas.dto.request.RegisterRequest;
import com.hospital.citas.dto.request.ResetPasswordRequest;
import com.hospital.citas.dto.response.LoginResponse;
import com.hospital.citas.entity.PasswordResetToken;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.entity.Role;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.DuplicateResourceException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.UserMapper;
import com.hospital.citas.repository.PasswordResetTokenRepository;
import com.hospital.citas.repository.RoleRepository;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.security.JwtTokenProvider;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.AuthService;
import com.hospital.citas.service.EmailService;
import com.hospital.citas.service.PatientService;
import com.hospital.citas.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserMapper userMapper;
    private final PatientService patientService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final EmailService emailService;

    private final TenantLinks tenantLinks;
    private final com.hospital.citas.repository.PatientRepository patientRepository;
    private final com.hospital.citas.repository.EmailTokenRepository emailTokenRepository;
    private final com.hospital.citas.service.ArcoEmailService arcoEmailService;
    private final com.hospital.citas.service.ConsentService consentService;

    @Override
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        // Las cuentas de paciente son por hospital: solo se registra desde el link de uno.
        TenantContext.requireHospitalId();
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Ya existe una cuenta con ese correo");
        }
        // Registro público: el rol SIEMPRE es PATIENT, nunca se acepta del cliente.
        Role patientRole = roleRepository.findByName(RoleName.PATIENT)
                .orElseThrow(() -> new IllegalStateException("Rol PATIENT no configurado"));

        // Un correo = un paciente por hospital. Si ya hay un expediente con este correo (alguien
        // que agendó como invitado), la cuenta se crea apagada y se manda un enlace al correo:
        // solo quien de verdad controla ese correo puede ligar la cuenta a ese expediente.
        var existingPatient = patientRepository.findFirstByEmailIgnoreCaseAndIsActiveTrue(request.getEmail());
        if (existingPatient.isPresent() && existingPatient.get().getUser() != null) {
            throw new DuplicateResourceException("Ya existe una cuenta con ese correo");
        }
        boolean needsActivation = existingPatient.isPresent();

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(patientRole)
                .build();
        user.setIsActive(!needsActivation);
        user = userRepository.save(user);

        if (needsActivation) {
            String token = com.hospital.citas.util.SecureTokens.newToken();
            emailTokenRepository.save(com.hospital.citas.entity.EmailToken.builder()
                    .purpose(com.hospital.citas.entity.EmailToken.ACCOUNT_ACTIVATION)
                    .tokenHash(com.hospital.citas.util.SecureTokens.hash(token))
                    .user(user).patient(existingPatient.get())
                    .expiresAt(LocalDateTime.now().plusHours(48))
                    .build());
            consentService.recordAcceptance(existingPatient.get(), user, "REGISTER");
            String to = user.getEmail();
            String name = user.getFirstName();
            afterCommit(() -> arcoEmailService.sendAccountActivation(to, name, token));
            return LoginResponse.builder().pendingActivation(true).build();
        }

        // Todo paciente con cuenta propia obtiene automáticamente su fila en `patients`.
        Patient ownPatient = patientService.findOrCreatePatientForUser(user);
        consentService.recordAcceptance(ownPatient, user, "REGISTER");

        String token = jwtTokenProvider.generateToken(user);
        return LoginResponse.builder().token(token).user(userMapper.toResponse(user))
                .mustChangePassword(user.getMustChangePassword()).build();
    }

    /** Activa la cuenta creada con un correo que ya tenía expediente y la liga a ese expediente. */
    @Override
    @Transactional
    public LoginResponse activate(String token) {
        var record = emailTokenRepository.findByTokenHashAndPurpose(com.hospital.citas.util.SecureTokens.hash(token),
                        com.hospital.citas.entity.EmailToken.ACCOUNT_ACTIVATION)
                .orElseThrow(() -> new BusinessException("El enlace no es válido"));
        if (record.getUsedAt() != null) throw new BusinessException("Este enlace ya se usó: inicia sesión con tu correo y contraseña");
        if (record.getExpiresAt().isBefore(LocalDateTime.now())) throw new BusinessException("El enlace venció: vuelve a registrarte para recibir uno nuevo");
        User user = record.getUser();
        Patient patient = record.getPatient();
        if (patient != null && patient.getUser() != null && !patient.getUser().getId().equals(user.getId())) {
            throw new BusinessException("Ese expediente ya está ligado a otra cuenta; acude a recepción");
        }
        user.setIsActive(true);
        userRepository.save(user);
        if (patient != null && Boolean.TRUE.equals(patient.getIsActive())) {
            patient.setUser(user);
            patientRepository.save(patient);
        } else {
            patientService.findOrCreatePatientForUser(user);
        }
        record.setUsedAt(LocalDateTime.now());
        emailTokenRepository.save(record);
        return LoginResponse.builder().token(jwtTokenProvider.generateToken(user)).user(userMapper.toResponse(user))
                .mustChangePassword(user.getMustChangePassword()).build();
    }

    private static void afterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    // Fuerza bruta en login (hallazgo "Alto" de la auditoría): sin esto, una cuenta acepta
    // intentos de contraseña ilimitados. Al llegar al límite se bloquea temporalmente --
    // ver User#isAccountNonLocked, que es lo que Spring Security revisa antes de comparar
    // la contraseña (por eso una cuenta ya bloqueada nunca llega a exponer si la contraseña
    // enviada era o no correcta).
    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    private static final int ACCOUNT_LOCK_MINUTES = 15;

    // Sin @Transactional a propósito: si el método completo fuera una sola transacción, el
    // guardado del contador de intentos fallidos (registerFailedLoginAttempt) se revertiría
    // en cuanto la excepción de credenciales inválidas se relanza y sale del método -- cada
    // llamada a userRepository.save()/findByEmail() ya es transaccional por sí misma (Spring
    // Data), así que el contador debe quedar confirmado ANTES de lanzar el error, no envuelto
    // junto con él.
    @Override
    public LoginResponse login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (LockedException e) {
            String detail = findLoginUser(request.getEmail())
                    .map(User::getLockedUntil)
                    .map(until -> "Intenta de nuevo en " + Math.max(1, Duration.between(LocalDateTime.now(), until).toMinutes()) + " minuto(s).")
                    .orElse("Intenta de nuevo más tarde.");
            throw new BusinessException("Cuenta bloqueada temporalmente por múltiples intentos fallidos. " + detail);
        } catch (BadCredentialsException e) {
            registerFailedLoginAttempt(request.getEmail());
            throw e;
        }

        User user = (User) authentication.getPrincipal();
        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        String token = jwtTokenProvider.generateToken(user);
        return LoginResponse.builder().token(token).user(userMapper.toResponse(user))
                .mustChangePassword(user.getMustChangePassword()).build();
    }

    private void registerFailedLoginAttempt(String email) {
        findLoginUser(email).ifPresent(user -> {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= MAX_FAILED_LOGIN_ATTEMPTS) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(ACCOUNT_LOCK_MINUTES));
            }
            userRepository.save(user);
        });
    }

    /** Cuenta con ese correo en el hospital del link (filtrado por @TenantId) o, sin hospital
     * (login en la raíz del sitio, que el controller corre en ROOT), la del SUPER_ADMIN. */
    private Optional<User> findLoginUser(String email) {
        return TenantContext.hospitalIdOrNull() != null
                ? userRepository.findByEmail(email)
                : userRepository.findByEmailAndHospitalIdIsNull(email);
    }

    @Override
    public User getCurrentUser() {
        User user = SecurityUtils.getCurrentUserOrNull();
        if (user == null) {
            throw new ResourceNotFoundException("No hay sesión activa");
        }
        return user;
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        // Misma respuesta exista o no la cuenta (anti-enumeración): el controller siempre
        // regresa 200 sin importar lo que pase aquí adentro.
        findLoginUser(request.getEmail())
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .ifPresent(user -> {
                    PasswordResetToken resetToken = PasswordResetToken.builder()
                            .user(user)
                            .token(UUID.randomUUID())
                            .expiresAt(LocalDateTime.now().plusMinutes(30))
                            .build();
                    passwordResetTokenRepository.save(resetToken);
                    String resetUrl = tenantLinks.frontendBase() + "/restablecer-password/" + resetToken.getToken();
                    emailService.sendPasswordResetEmail(user, resetUrl);
                });
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new BusinessException("El enlace de recuperación no es válido o ya expiró"));
        if (!resetToken.isUsable()) {
            throw new BusinessException("El enlace de recuperación no es válido o ya expiró");
        }
        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);

        resetToken.setUsedAt(LocalDateTime.now());
        passwordResetTokenRepository.save(resetToken);

        refreshTokenService.revokeAllForUser(user.getId());
    }

    @Override
    @Transactional
    public User changePassword(ChangePasswordRequest request) {
        User user = getCurrentUser();
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("La contraseña actual no es correcta");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        user = userRepository.save(user);

        refreshTokenService.revokeAllForUser(user.getId());
        return user;
    }
}
