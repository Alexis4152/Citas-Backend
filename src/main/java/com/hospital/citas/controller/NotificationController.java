package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.response.NotificationResponse;
import com.hospital.citas.entity.User;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Notificaciones en app del usuario autenticado (hoy solo se generan para DOCTOR/ADMIN, ver
 * NotificationServiceImpl, pero el endpoint es genérico por si algún día aplica a otro rol).
 * El panel las consulta por polling -- ver usePolling en el frontend.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<List<NotificationResponse>> list() {
        return ApiResponse.ok(notificationService.listRecent(currentUser()));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(notificationService.countUnread(currentUser()));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markAsRead(@PathVariable Long id) {
        notificationService.markAsRead(id, currentUser());
        return ApiResponse.ok(null);
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> markAllAsRead() {
        notificationService.markAllAsRead(currentUser());
        return ApiResponse.ok(null);
    }

    private User currentUser() {
        return SecurityUtils.getCurrentUserOrNull();
    }
}
