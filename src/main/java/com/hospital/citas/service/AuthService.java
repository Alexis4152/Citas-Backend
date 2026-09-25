package com.hospital.citas.service;

import com.hospital.citas.dto.request.ChangePasswordRequest;
import com.hospital.citas.dto.request.ForgotPasswordRequest;
import com.hospital.citas.dto.request.LoginRequest;
import com.hospital.citas.dto.request.RegisterRequest;
import com.hospital.citas.dto.request.ResetPasswordRequest;
import com.hospital.citas.dto.response.LoginResponse;
import com.hospital.citas.entity.User;

public interface AuthService {
    LoginResponse register(RegisterRequest request);
    LoginResponse login(LoginRequest request);
    User getCurrentUser();

    /** Siempre "silenciosa" (misma respuesta exista o no el correo, anti-enumeración). */
    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    /** Cambio voluntario o forzado (primer login con contraseña temporal). Revoca todos los
     * refresh tokens existentes del usuario -- el llamador (AuthController) se encarga de
     * emitir uno nuevo para no cortar la sesión actual. */
    User changePassword(ChangePasswordRequest request);
}
