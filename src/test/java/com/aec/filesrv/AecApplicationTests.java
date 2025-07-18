package com.aec.filesrv;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.aec.FileSrv.FileServiceApplication;

// Le decimos explícitamente a Spring qué clase arrancar
@SpringBootTest(classes = FileServiceApplication.class)
class AecApplicationTests {

    @Test
    void contextLoads() {
        // Si la aplicación arranca sin errores, este test pasa.
    }
}

