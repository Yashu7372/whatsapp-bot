package com.yashu.projectcontrol.access;

import java.util.List;

final class LocalDemoAccountCatalog {

    static final String PASSWORD = "Project123!";

    static final List<AccountSpec> ACCOUNTS = List.of(
            new AccountSpec("local:admin", "admin@local.demo", "Project Admin", "admin"),
            new AccountSpec("local:site", "site@local.demo", "Aisha Khan · Site Team", "site"),
            new AccountSpec("local:qce", "qce@local.demo", "Ravi Menon · QCE", "qce"),
            new AccountSpec("local:qcdc", "qcdc@local.demo", "Sara Ali · QC/DC", "qcdc"),
            new AccountSpec("local:inspector", "inspector@local.demo", "Daniel Lee · Consultant Inspector", "inspector"),
            new AccountSpec("local:re", "re@local.demo", "Omar Rahman · Consultant RE", "re"),
            new AccountSpec("local:viewer", "viewer@local.demo", "Maya Joseph · Scoped Viewer", "viewer")
    );

    private LocalDemoAccountCatalog() {}

    record AccountSpec(String subject, String email, String displayName, String key) {}
}
