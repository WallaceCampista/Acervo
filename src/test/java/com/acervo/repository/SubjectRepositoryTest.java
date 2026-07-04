package com.acervo.repository;

import com.acervo.AbstractIntegrationTest;
import com.acervo.domain.Subject;
import com.acervo.domain.User;
import com.acervo.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class SubjectRepositoryTest extends AbstractIntegrationTest {

    @Autowired SubjectRepository subjects;
    @Autowired UserService userService;

    @BeforeEach
    void clean() {
        subjects.deleteAll();
    }

    private User owner(String email) {
        return userService.signup(email, "senha123", "Owner", "X", User.Role.ALUNO);
    }

    @Test
    @DisplayName("salva e recupera por id")
    void saveAndFind() {
        User u = owner("calc@acervo.dev");
        Subject s = subjects.save(Subject.builder()
                .name("Cálculo I").color("#d8a657").owner(u).build());

        assertThat(s.getId()).isNotNull();
        assertThat(subjects.findById(s.getId())).isPresent()
                .get().extracting(Subject::getName).isEqualTo("Cálculo I");
    }

    @Test
    @DisplayName("existsByOwner_IdAndNameIgnoreCase reconhece diferença só de caixa")
    void existsByOwnerIdAndName() {
        User u = owner("ed@acervo.dev");
        subjects.save(Subject.builder()
                .name("Estrutura de Dados").color("#7fae8f").owner(u).build());

        assertThat(subjects.existsByOwner_IdAndNameIgnoreCase(u.getId(),
                "estrutura de dados")).isTrue();
        assertThat(subjects.existsByOwner_IdAndNameIgnoreCase(u.getId(),
                "ESTRUTURA DE DADOS")).isTrue();
        assertThat(subjects.existsByOwner_IdAndNameIgnoreCase(u.getId(),
                "Cálculo II")).isFalse();
    }

    @Test
    @DisplayName("uniqueness é por (owner, nome) — usuários diferentes podem ter matérias com mesmo nome")
    void perOwnerUniqueness() {
        User a = owner("a@acervo.dev");
        User b = owner("b@acervo.dev");
        subjects.save(Subject.builder().name("História").color("#c98b6b").owner(a).build());
        subjects.save(Subject.builder().name("História").color("#7fae8f").owner(b).build());
        subjects.flush();

        // Mesmo dono + mesmo nome → violação
        Subject dup = Subject.builder().name("História").color("#7fae8f").owner(a).build();
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> { subjects.save(dup); subjects.flush(); });
    }

    @Test
    @DisplayName("findByOwner_IdOrderByNameAsc só devolve matérias do dono")
    void findByOwner() {
        User a = owner("aa@acervo.dev");
        User b = owner("bb@acervo.dev");
        subjects.save(Subject.builder().name("Mat A").color("#d8a657").owner(a).build());
        subjects.save(Subject.builder().name("Bio A").color("#7fae8f").owner(a).build());
        subjects.save(Subject.builder().name("Pop B").color("#c98b6b").owner(b).build());

        var listA = subjects.findByOwner_IdOrderByNameAsc(a.getId());
        assertThat(listA).hasSize(2);
        assertThat(listA.get(0).getName()).isEqualTo("Bio A");
        assertThat(listA.get(1).getName()).isEqualTo("Mat A");
    }
}
