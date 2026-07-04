package com.acervo.config;

import com.acervo.domain.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Adapter entre {@link User} (entidade JPA) e o contrato {@link UserDetails}
 * do Spring Security. Carregado pelo {@code AcervoUserDetailsService}.
 */
@Getter
public class AcervoUserDetails implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final boolean active;
    private final List<GrantedAuthority> authorities;

    public AcervoUserDetails(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.active = user.isActive();
        this.authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return active; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return active; }
}
