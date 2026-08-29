package com.yashu.projectcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    @Test
    void verifiesModuleBoundariesAndCycles() {
        ApplicationModules.of(ProjectControlApplication.class).verify();
    }
}
