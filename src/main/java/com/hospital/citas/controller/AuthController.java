package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.ChangePasswordRequest;
import com.hospital.citas.dto.request.ForgotPasswordRequest;
import com.hospital.citas.dto.request.LoginRequest;
import com.hospital.citas.dto.request.RegisterRequest;
import com.hospital.citas.dto.request.ResetPasswordRequest;
import com.hospital.citas.dto.response.LoginResponse;
import com.hospital.citas.entity.User;
import com.hospital.citas.mapper.UserMapper;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.security.JwtTokenProvider;
import com.hospital.citas.service.AuthService;
import com.hospital.citas.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "refresh_token";

    private final AuthService authService;
    private final UserMapper userMapper;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.refresh.expiration}")
    private long refreshExpirationMs;

    @Value("${app.cookie.secure}")
    private boolean cookieSecure;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginResponse>> register(@Valid @RequestBody RegisterRequest request,
                                                                 HttpServletResponse response) {
        LoginResponse loginResponse = authService.register(request);
        issueRefreshCookie(loginResponse.getUser().getEmail(), response);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(loginResponse, "Cuenta creada correctamente"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                              HttpServletResponse response) {
        LoginResponse loginResponse = authService.login(request);
        issueRefreshCookie(loginResponse.getUser().getEmail(), response);
        return ResponseEntity.ok(ApiResponse.ok(loginResponse));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<?>> me() {
        return ResponseEntity.ok(ApiResponse.ok(userMapper.toResponse(authService.getCurrentUser())));
    }

    /** Público: usa la cookie httpOnly para rotar el refresh token y emitir un access JWT
     * nuevo, sin pedir credenciales de nuevo (patrón 01). */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = readCookie(request);
        RefreshTokenService.RotatedToken rotated = refreshTokenService.rotate(rawToken);
        setRefreshCookie(response, rotated.newRawToken());

        String accessToken = jwtTokenProvider.generateToken(rotated.user());
        LoginResponse loginResponse = LoginResponse.builder()
                .token(accessToken)
                .user(userMapper.toResponse(rotated.user()))
                .mustChangePassword(rotated.user().getMustChangePassword())
                .build();
        return ResponseEntity.ok(ApiResponse.ok(loginResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
        refreshTokenService.revoke(readCookie(request));
        clearRefreshCookie(response);
        return ResponseEntity.ok(ApiResponse.ok(null, "Sesión cerrada"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponse.ok(null,
                "Si el correo existe, te enviamos instrucciones para recuperar tu contraseña"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok(null, "Contraseña actualizada correctamente"));
    }

    /** Autenticado: sirve tanto para el cambio forzado de primer login como para uno
     * voluntario. Revoca todos los refresh tokens existentes y emite uno nuevo de inmediato
     * para no cortar la sesión actual. */
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<LoginResponse>> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                                        HttpServletResponse response) {
        User user = authService.changePassword(request);
        setRefreshCookie(response, refreshTokenService.issue(user));
        String accessToken = jwtTokenProvider.generateToken(user);
        LoginResponse loginResponse = LoginResponse.builder()
                .token(accessToken)
                .user(userMapper.toResponse(user))
                .mustChangePassword(false)
                .build();
        return ResponseEntity.ok(ApiResponse.ok(loginResponse, "Contraseña actualizada correctamente"));
    }

    // ── Cookie helpers ───────────────────────────────────────────

    private void issueRefreshCookie(String userEmail, HttpServletResponse response) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalStateException("Usuario recién autenticado no encontrado: " + userEmail));
        setRefreshCookie(response, refreshTokenService.issue(user));
    }

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(refreshExpirationMs / 1000)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (REFRESH_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
