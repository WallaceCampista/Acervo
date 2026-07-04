package com.acervo.service;

import com.acervo.domain.User;
import com.acervo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public Optional<User> findByEmail(String email) {
        return users.findByEmailIgnoreCase(email);
    }

    public Optional<User> findById(UUID id) {
        return users.findById(id);
    }

    public User get(UUID id) {
        return users.findById(id).orElseThrow();
    }

    @Transactional
    public User signup(String email, String rawPassword, String firstName,
                       String lastName, User.Role role) {
        String normalized = normalizeEmail(email);
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new EmailAlreadyInUseException(normalized);
        }
        User u = User.builder()
                .email(normalized)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .firstName(firstName == null ? "" : firstName.trim())
                .lastName(lastName == null ? "" : lastName.trim())
                .role(role == null ? User.Role.ALUNO : role)
                .build();
        return users.save(u);
    }

    @Transactional
    public User updateProfile(UUID userId, String firstName, String lastName,
                              String contact) {
        User u = get(userId);
        u.setFirstName(firstName == null ? "" : firstName.trim());
        u.setLastName(lastName == null ? "" : lastName.trim());
        u.setContact(contact == null ? "" : contact.trim());
        u.setUpdatedAt(OffsetDateTime.now());
        return users.save(u);
    }

    /**
     * Troca a senha. Retorna true se a senha atual conferiu; false caso
     * contrário (sem mudar nada). O caller decide se invalida sessões.
     */
    @Transactional
    public boolean changePassword(UUID userId, String currentPassword,
                                  String newPassword) {
        User u = get(userId);
        if (!passwordEncoder.matches(currentPassword, u.getPasswordHash())) {
            return false;
        }
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        u.setUpdatedAt(OffsetDateTime.now());
        users.save(u);
        return true;
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    public List<User> listAll() {
        return users.findAll(org.springframework.data.domain.Sort.by("createdAt").ascending());
    }

    @Transactional
    public User adminUpdate(UUID userId, String firstName, String lastName,
                            String contact, User.Role role) {
        User u = get(userId);
        u.setFirstName(firstName == null ? "" : firstName.trim());
        u.setLastName(lastName == null ? "" : lastName.trim());
        u.setContact(contact == null ? "" : contact.trim());
        if (role != null) u.setRole(role);
        u.setUpdatedAt(OffsetDateTime.now());
        return users.save(u);
    }

    @Transactional
    public User setActive(UUID userId, boolean active) {
        User u = get(userId);
        u.setActive(active);
        u.setUpdatedAt(OffsetDateTime.now());
        return users.save(u);
    }

    @Transactional
    public User resetPassword(UUID userId, String newPassword) {
        User u = get(userId);
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        u.setUpdatedAt(OffsetDateTime.now());
        return users.save(u);
    }

    @Transactional
    public void delete(UUID userId) {
        users.deleteById(userId);
    }

    public static class EmailAlreadyInUseException extends RuntimeException {
        public EmailAlreadyInUseException(String email) {
            super("Email já cadastrado: " + email);
        }
    }
}
