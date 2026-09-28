package com.hospital.citas.security;

import com.hospital.citas.entity.User;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Login (lo llama el AuthenticationManager). El mismo correo puede existir en varios
     * hospitales, así que se busca en el hospital del link desde el que se entra (la consulta ya
     * sale filtrada por {@code @TenantId}). Sin hospital (login en la raíz del sitio) solo puede
     * entrar el SUPER_ADMIN.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        if (TenantContext.hospitalIdOrNull() != null) {
            return userRepository.findByEmail(email)
                    .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + email));
        }
        return TenantContext.callAs(TenantContext.ROOT, () -> userRepository.findByEmailAndHospitalIdIsNull(email))
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + email));
    }

    /** Usuario del JWT en cada request. Se busca en ROOT: todavía no se sabe su hospital. */
    public User loadActiveUserById(long userId) {
        return TenantContext.callAs(TenantContext.ROOT, () -> userRepository.findById(userId))
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .orElse(null);
    }
}
