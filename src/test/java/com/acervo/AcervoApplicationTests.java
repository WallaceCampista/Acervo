package com.acervo;

import org.junit.jupiter.api.Test;

class AcervoApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
        // Garante que o ApplicationContext sobe com perfil `test` + Postgres
        // testcontainer + Hibernate criando o schema + SchemaBootstrap.
    }
}
