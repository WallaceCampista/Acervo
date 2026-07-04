package com.acervo.config;

import com.acervo.domain.User;
import com.acervo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AcervoUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User u = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("Conta não encontrada: " + email));
        return new AcervoUserDetails(u);
    }
}
